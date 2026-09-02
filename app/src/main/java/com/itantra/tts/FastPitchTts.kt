package com.itantra.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.File
import java.nio.LongBuffer

/**
 * AI4Bharat Indic-TTS (MIT): FastPitch predicts a mel spectrogram, HiFi-GAN V1 turns it
 * into audio. Both non-autoregressive, which is what keeps RTF low (PRD latency metric).
 *
 * As with STT, input/output names differ between export scripts, so both sessions are
 * introspected at load time rather than hardcoded.
 */
class FastPitchTts private constructor(
    override val lang: String,
    private val env: OrtEnvironment,
    private val fastpitch: OrtSession,
    private val hifigan: OrtSession,
    private val symbolToId: Map<String, Long>,
    override val sampleRate: Int
) : TtsEngine {

    companion object {
        private const val TAG = "FastPitchTts"

        /** Indic-TTS FastPitch/HiFi-GAN V1 are trained at 22.05 kHz. */
        private const val DEFAULT_SAMPLE_RATE = 22_050

        private val TEXT_INPUT_NAMES = listOf("text", "tokens", "input", "text_padded", "x", "input_ids")
        private val MEL_INPUT_NAMES = listOf("spec", "mel", "input", "x", "mels", "spectrogram")

        fun load(lang: String, fastpitchFile: File, hifiganFile: File, tokensFile: File): FastPitchTts? = try {
            val symbols = tokensFile.readLines()
                .map { it.trimEnd('\n', '\r') }
                .filter { it.isNotEmpty() }
            if (symbols.isEmpty()) throw IllegalStateException("Empty token file: ${tokensFile.name}")

            // A "<symbol> <id>" file pins ids explicitly; a bare list is positional.
            val map = HashMap<String, Long>(symbols.size * 2)
            var positional = true
            for (line in symbols) {
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size == 2 && parts[1].toLongOrNull() != null) {
                    map[parts[0]] = parts[1].toLong()
                    positional = false
                }
            }
            if (positional) {
                map.clear()
                symbols.forEachIndexed { index, symbol -> map[symbol] = index.toLong() }
            }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val fp = env.createSession(fastpitchFile.absolutePath, opts)
            val hg = env.createSession(hifiganFile.absolutePath, opts)
            Log.i(
                TAG,
                "Loaded TTS '$lang': fastpitch=${fastpitchFile.name} inputs=${fp.inputNames}, " +
                    "hifigan=${hifiganFile.name} inputs=${hg.inputNames}, symbols=${map.size}"
            )
            FastPitchTts(lang, env, fp, hg, map, DEFAULT_SAMPLE_RATE)
        } catch (e: Throwable) {
            Log.e(TAG, "TTS_LOAD_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    override fun synthesize(text: String): FloatArray? {
        val clean = text.trim()
        if (clean.isEmpty()) return null

        val ids = tokenize(clean)
        if (ids.isEmpty()) {
            Log.w(TAG, "No known symbols in \"$clean\" for '$lang'")
            return null
        }

        return try {
            val started = System.currentTimeMillis()

            val mel = runFastPitch(ids) ?: return null
            val audio = runHifiGan(mel) ?: return null

            val elapsed = System.currentTimeMillis() - started
            val seconds = audio.size.toFloat() / sampleRate
            Log.i(
                TAG,
                "TTS[$lang] ${ids.size} tokens -> ${"%.2f".format(seconds)}s audio in ${elapsed}ms " +
                    "(RTF ${"%.2f".format(elapsed / 1000f / seconds)})"
            )
            audio
        } catch (e: Throwable) {
            Log.e(TAG, "TTS_INFERENCE_FAILED[$lang]: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /** Longest-match symbol lookup, so multi-character symbols win over single ones. */
    private fun tokenize(text: String): LongArray {
        val ids = ArrayList<Long>(text.length + 2)
        var i = 0
        val maxSymbol = symbolToId.keys.maxOfOrNull { it.length } ?: 1

        while (i < text.length) {
            var matched = false
            var len = minOf(maxSymbol, text.length - i)
            while (len >= 1) {
                val candidate = text.substring(i, i + len)
                val id = symbolToId[candidate]
                if (id != null) {
                    ids.add(id)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                // Unknown character: skip it rather than emitting a wrong phoneme.
                Log.d(TAG, "Unmapped character '${text[i]}' in '$lang'")
                i++
            }
        }
        return ids.toLongArray()
    }

    private fun runFastPitch(ids: LongArray): Array<FloatArray>? {
        val inputName = TEXT_INPUT_NAMES.firstOrNull { it in fastpitch.inputNames }
            ?: fastpitch.inputNames.first()

        val feeds = HashMap<String, OnnxTensor>()
        feeds[inputName] = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())
        )
        // Some exports also want an explicit length alongside the tokens.
        for (name in fastpitch.inputNames) {
            if (name == inputName || name in feeds) continue
            val info = fastpitch.inputInfo[name]?.info as? TensorInfo ?: continue
            if (info.type == ai.onnxruntime.OnnxJavaType.INT64 && info.shape.size <= 1) {
                feeds[name] = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)
                )
            }
        }

        return try {
            fastpitch.run(feeds).use { out -> asMel(out.get(0).value) }
        } finally {
            feeds.values.forEach { runCatching { it.close() } }
        }
    }

    private fun runHifiGan(mel: Array<FloatArray>): FloatArray? {
        val inputName = MEL_INPUT_NAMES.firstOrNull { it in hifigan.inputNames }
            ?: hifigan.inputNames.first()

        val bins = mel.size
        val frames = mel[0].size
        val flat = FloatArray(bins * frames)
        for (m in 0 until bins) mel[m].copyInto(flat, m * frames)

        val tensor = OnnxTensor.createTensor(
            env,
            java.nio.FloatBuffer.wrap(flat),
            longArrayOf(1, bins.toLong(), frames.toLong())
        )
        return tensor.use {
            hifigan.run(mapOf(inputName to it)).use { out -> asAudio(out.get(0).value) }
        }
    }

    /** Normalises FastPitch output into `[mels][frames]`. */
    @Suppress("UNCHECKED_CAST")
    private fun asMel(value: Any?): Array<FloatArray>? = when (value) {
        is Array<*> -> when (val first = value.firstOrNull()) {
            is Array<*> -> if (first.firstOrNull() is FloatArray) value[0] as Array<FloatArray> else null
            is FloatArray -> value as Array<FloatArray>
            else -> null
        }
        else -> null
    }

    /** Flattens HiFi-GAN output — [B, T], [B, 1, T] and [T] all occur. */
    @Suppress("UNCHECKED_CAST")
    private fun asAudio(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> when (val first = value.firstOrNull()) {
            is FloatArray -> first
            is Array<*> -> (first.firstOrNull() as? FloatArray)
            else -> null
        }
        else -> null
    }

    override fun close() {
        runCatching { fastpitch.close() }
        runCatching { hifigan.close() }
        Log.i(TAG, "Unloaded TTS '$lang'")
    }
}
