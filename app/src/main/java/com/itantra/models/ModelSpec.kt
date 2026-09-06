package com.itantra.models

/**
 * Identifies one model file inside a language pack.
 *
 * A pack is described declaratively (see `assets/models/manifest.json`) so that new
 * exports can be published without touching Kotlin: the Model Manager resolves,
 * downloads and verifies whatever the manifest names.
 */
enum class ModelRole {
    /** IndicConformer acoustic model (ONNX). */
    STT_ACOUSTIC,

    /** Token/vocabulary list backing the STT decoder. */
    STT_TOKENS,

    /** FastPitch acoustic model (ONNX). */
    TTS_ACOUSTIC,

    /** HiFi-GAN vocoder (ONNX). */
    TTS_VOCODER,

    /** Symbol table backing TTS text normalisation. */
    TTS_TOKENS,

    /** IndicTrans2 encoder (ONNX). */
    MT_ENCODER,

    /** IndicTrans2 decoder (ONNX). */
    MT_DECODER,

    /** BPE vocabulary for the translation source side. */
    MT_BPE_SRC,

    /** BPE vocabulary for the translation target side. */
    MT_BPE_TGT,

    /** Language-tag ids and vocabulary sizes for one translation direction. */
    MT_META
}

/**
 * One downloadable/bundled file.
 *
 * @param sha256 lowercase hex digest. Empty means "not yet published" — [ModelManager]
 *   refuses to install such a file rather than trusting an unverified download
 *   (Rules §19).
 */
data class ModelSpec(
    val role: ModelRole,
    val fileName: String,
    val url: String = "",
    val mirrorUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L
) {
    val isPublished: Boolean get() = url.isNotBlank() && sha256.isNotBlank()
}

/**
 * The full set of files one language needs to run STT and TTS offline.
 */
data class LanguageModelSpec(
    val lang: String,
    val displayName: String,
    val nativeName: String,
    val bundled: Boolean,
    val models: List<ModelSpec>,
    /**
     * Files for translating *into* this language, kept out of [models] on purpose.
     *
     * Translation is optional and roughly as large again as the voice pack, so counting
     * it towards pack completeness would report Hindi as "not ready" for want of a
     * feature the voice loop deliberately does not depend on.
     */
    val translation: List<ModelSpec> = emptyList()
) {
    fun of(role: ModelRole): ModelSpec? = models.firstOrNull { it.role == role }

    val sttSpecs: List<ModelSpec> get() = models.filter { it.role == ModelRole.STT_ACOUSTIC || it.role == ModelRole.STT_TOKENS }
    val ttsSpecs: List<ModelSpec> get() = models.filter {
        it.role == ModelRole.TTS_ACOUSTIC || it.role == ModelRole.TTS_VOCODER || it.role == ModelRole.TTS_TOKENS
    }

    val totalBytes: Long get() = models.sumOf { it.sizeBytes }

    /** Size of the optional translation download, separate from [totalBytes]. */
    val translationBytes: Long get() = translation.sumOf { it.sizeBytes }
}
