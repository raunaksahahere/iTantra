package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.models.ModelCatalog
import com.itantra.models.ModelRole
import com.itantra.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the text → FastPitch + HiFi-GAN → speaker path.
 *
 * One voice is resident at a time; switching language unloads the previous one
 * (Rules §9). Speaking never throws — a missing model surfaces as [State.Unavailable]
 * so a failed read-aloud cannot take down the message list.
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    sealed interface State {
        data object Idle : State
        data class Synthesizing(val lang: String) : State
        data class Speaking(val lang: String, val alert: Boolean) : State
        data class Unavailable(val reason: TtsUnavailable) : State
    }

    private val store = ModelStore(context)
    private val output = AudioOutput(context)
    private val loadLock = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TtsEngine? = null

    /** True when [lang] has every TTS file it needs on disk. */
    fun isAvailable(lang: String): Boolean {
        val spec = ModelCatalog.byLang(context, lang) ?: return false
        return store.hasTts(spec)
    }

    fun missingFiles(lang: String): List<String> {
        val spec = ModelCatalog.byLang(context, lang) ?: return emptyList()
        return spec.ttsSpecs.filterNot { store.isPresent(lang, it) }.map { it.fileName }
    }

    private suspend fun engineFor(lang: String): TtsEngine? = loadLock.withLock {
        engine?.let { if (it.lang == lang) return@withLock it }

        engine?.let {
            Log.i(TAG, "Switching TTS ${it.lang} -> $lang; unloading previous")
            runCatching { it.close() }
            engine = null
        }

        val spec = ModelCatalog.byLang(context, lang)
        if (spec == null) {
            Log.e(TAG, "TTS_UNAVAILABLE[$lang]: not in manifest")
            return@withLock null
        }

        val acoustic = spec.of(ModelRole.TTS_ACOUSTIC)?.let { store.resolve(lang, it) }
        val vocoder = spec.of(ModelRole.TTS_VOCODER)?.let { store.resolve(lang, it) }
        val tokens = spec.of(ModelRole.TTS_TOKENS)?.let { store.resolve(lang, it) }

        if (acoustic == null || vocoder == null || tokens == null) {
            val missing = missingFiles(lang)
            Log.e(TAG, "TTS_UNAVAILABLE[$lang]: missing ${missing.joinToString()}")
            _state.value = State.Unavailable(TtsUnavailable.ModelMissing(lang, missing))
            return@withLock null
        }

        val loaded = withContext(Dispatchers.IO) {
            FastPitchTts.load(lang, acoustic, vocoder, tokens)
        }
        if (loaded == null) {
            _state.value = State.Unavailable(TtsUnavailable.LoadFailed(lang, "ONNX session could not be created"))
        }
        engine = loaded
        loaded
    }

    /**
     * Synthesises and plays [text] in [lang], suspending until playback ends.
     *
     * @param alert plays at max volume, non-interruptible.
     * @return false when the voice is unavailable or synthesis failed.
     */
    suspend fun speak(text: String, lang: String, alert: Boolean = false): Boolean {
        if (text.isBlank()) return false

        return try {
            _state.value = State.Synthesizing(lang)
            val active = engineFor(lang) ?: return false

            val pcm = withContext(Dispatchers.Default) { active.synthesize(text) }
            if (pcm == null || pcm.isEmpty()) {
                Log.e(TAG, "TTS_SYNTHESIS_EMPTY[$lang] for \"$text\"")
                return false
            }

            _state.value = State.Speaking(lang, alert)
            output.play(pcm, active.sampleRate, alert)
        } catch (e: Throwable) {
            Log.e(TAG, "SPEAK_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        } finally {
            _state.value = State.Idle
        }
    }

    /** Stops normal playback; an alert in flight continues. */
    fun stop() = output.stop()

    fun release() {
        output.stop()
        runCatching { engine?.close() }
        engine = null
    }
}
