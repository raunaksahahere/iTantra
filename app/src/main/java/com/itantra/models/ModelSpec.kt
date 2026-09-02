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
    TTS_TOKENS
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
    val models: List<ModelSpec>
) {
    fun of(role: ModelRole): ModelSpec? = models.firstOrNull { it.role == role }

    val sttSpecs: List<ModelSpec> get() = models.filter { it.role == ModelRole.STT_ACOUSTIC || it.role == ModelRole.STT_TOKENS }
    val ttsSpecs: List<ModelSpec> get() = models.filter {
        it.role == ModelRole.TTS_ACOUSTIC || it.role == ModelRole.TTS_VOCODER || it.role == ModelRole.TTS_TOKENS
    }

    val totalBytes: Long get() = models.sumOf { it.sizeBytes }
}
