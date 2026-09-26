package com.itantra.translate

import android.content.Context
import android.util.Log
import com.itantra.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the received-text → translated-text step that sits between the mesh and TTS.
 *
 * The architecture this serves: a sender transmits text **in its own language**, tagged
 * with that language code, and never translates. Every receiver independently translates
 * into whatever language *it* has selected. One broadcast therefore reaches speakers of
 * different languages at once, each hearing their own — which is the point of a
 * multilingual mesh, and is only possible because translation happens on receive.
 *
 * A direct consequence: a phone only ever needs the direction *into* its own language, so
 * a Hindi handset carries en→hi and not both. Model files live in the target language's
 * pack directory for exactly that reason.
 *
 * Translating never throws. A failure surfaces as [Outcome.Failed] and callers fall back
 * to showing the original text *marked as untranslated* — presenting an untranslated
 * distress message as though it had been translated is the dangerous failure here.
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"

        /** Pairs with a published model today. Everything else is honestly unsupported. */
        val SUPPORTED_PAIRS = setOf("hi->en", "en->hi")

        fun isSupported(source: String, target: String) =
            "$source->$target" in SUPPORTED_PAIRS
    }

    sealed interface State {
        data object Idle : State
        data class Loading(val direction: String) : State
        data class Translating(val direction: String) : State
        data class Unavailable(val reason: TranslationUnavailable) : State
    }

    /**
     * Result of asking for a translation.
     *
     * [NotNeeded] and [Failed] are deliberately distinct: both end up displaying the
     * original string, but only one of them is a problem, and the UI must be able to tell
     * the reader which case they are looking at.
     */
    sealed interface Outcome {
        /** Source language already matches the reader's language. */
        data class NotNeeded(val text: String) : Outcome
        data class Translated(val text: String, val original: String, val millis: Long) : Outcome
        data class Failed(val original: String, val reason: TranslationUnavailable) : Outcome
    }

    private val store = ModelStore(context)
    private val loadLock = Mutex()

    /** Held while an engine is translating, so [release] cannot close it mid-run. */
    private val useLock = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TranslationEngine? = null

    /** True when every file for this direction is already on disk. */
    fun isAvailable(source: String, target: String): Boolean {
        if (!isSupported(source, target)) return false
        return missingFiles(source, target).isEmpty()
    }

    fun missingFiles(source: String, target: String): List<String> =
        IndicTrans2Translator.fileNames(source, target)
            .filter { store.resolve(target, it) == null }

    /**
     * Translates [text] from [source] into [target].
     *
     * Same-language input short-circuits before any model is touched, which is both the
     * common case on a single-language mesh and the reason a phone never needs a model
     * for its own language.
     */
    suspend fun translate(text: String, source: String, target: String): Outcome {
        if (source == target) return Outcome.NotNeeded(text)
        if (text.isBlank()) return Outcome.NotNeeded(text)

        if (!isSupported(source, target)) {
            Log.i(TAG, "no model for $source->$target; passing text through untranslated")
            return Outcome.Failed(text, TranslationUnavailable.UnsupportedPair(source, target))
        }

        val missing = missingFiles(source, target)
        if (missing.isNotEmpty()) {
            val direction = "$source->$target"
            Log.w(TAG, "TRANSLATION_UNAVAILABLE[$direction]: missing ${missing.joinToString()}")
            val reason = TranslationUnavailable.ModelMissing(direction, missing)
            _state.value = State.Unavailable(reason)
            return Outcome.Failed(text, reason)
        }

        val started = System.currentTimeMillis()
        val translated = useLock.withLock {
            val engine = engineFor(source, target)
                ?: return Outcome.Failed(
                    text,
                    TranslationUnavailable.LoadFailed("$source->$target", "engine unavailable")
                )
            _state.value = State.Translating("$source->$target")
            withContext(Dispatchers.Default) { engine.translate(text) }
        }
        val elapsed = System.currentTimeMillis() - started
        _state.value = State.Idle

        return if (translated.isNullOrBlank()) {
            Log.e(TAG, "TRANSLATION_FAILED[$source->$target] after ${elapsed}ms")
            Outcome.Failed(
                text,
                TranslationUnavailable.LoadFailed("$source->$target", "inference returned nothing")
            )
        } else {
            Outcome.Translated(translated, text, elapsed)
        }
    }

    /**
     * One direction is resident at a time, mirroring TtsManager's single-voice rule — two
     * loaded IndicTrans2 pairs would be roughly 450 MB of mapped model on a phone that
     * also holds STT and TTS.
     */
    private suspend fun engineFor(source: String, target: String): TranslationEngine? =
        loadLock.withLock {
            engine?.let {
                if (it.sourceLang == source && it.targetLang == target) return@withLock it
                Log.i(TAG, "Switching MT ${it.sourceLang}->${it.targetLang} to $source->$target")
                runCatching { it.close() }
                engine = null
            }

            _state.value = State.Loading("$source->$target")
            val opened = withContext(Dispatchers.IO) {
                IndicTrans2Translator.open(source, target) { name -> store.resolve(target, name) }
            }
            _state.value = State.Idle

            opened.fold(
                onSuccess = {
                    Log.i(TAG, "Loaded MT $source->$target")
                    engine = it
                    it
                },
                onFailure = {
                    Log.e(TAG, "MT load failed for $source->$target: ${it.message}", it)
                    _state.value = State.Unavailable(
                        TranslationUnavailable.LoadFailed(
                            "$source->$target", it.message ?: it.javaClass.simpleName
                        )
                    )
                    null
                }
            )
        }

    /** Frees the loaded direction once any translation in flight has finished. */
    fun release() {
        scope.launch {
            useLock.withLock {
                loadLock.withLock {
                    engine?.let { runCatching { it.close() } }
                    engine = null
                }
            }
            _state.value = State.Idle
        }
    }
}
