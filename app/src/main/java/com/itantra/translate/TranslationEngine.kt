package com.itantra.translate

import java.io.Closeable

/**
 * Single inference interface for machine translation, so the model behind it can be
 * swapped or requantised without touching callers (Rules §8) — the same shape as
 * [com.itantra.tts.TtsEngine] and [com.itantra.stt.SttEngine].
 *
 * An engine translates in exactly one direction. That is not a simplification: under the
 * broadcast architecture a phone only ever translates *into* its own selected language,
 * so it needs one direction, not two.
 */
interface TranslationEngine : Closeable {

    /** Source language code, e.g. "hi". */
    val sourceLang: String

    /** Target language code, e.g. "en". */
    val targetLang: String

    /**
     * Translates [text], or returns null when inference failed.
     *
     * Returning null rather than the original text is deliberate: callers must be able to
     * tell a translation apart from an untranslated passthrough, because presenting
     * untranslated text as translated is the dangerous failure in a distress message.
     */
    fun translate(text: String): String?
}

sealed interface TranslationUnavailable {
    data class ModelMissing(val direction: String, val files: List<String>) : TranslationUnavailable
    data class LoadFailed(val direction: String, val reason: String) : TranslationUnavailable
    /** No model published for this pair — expected for the eight languages beyond hi/en. */
    data class UnsupportedPair(val sourceLang: String, val targetLang: String) : TranslationUnavailable
}
