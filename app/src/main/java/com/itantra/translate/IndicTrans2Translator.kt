package com.itantra.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * AI4Bharat IndicTrans2 (distilled 200M) running on ONNX Runtime, with **greedy** decoding.
 *
 * Graph contract, read off the exported models rather than taken from a README:
 *
 *   encoder  input_ids [B,S] int64, attention_mask [B,S] int64
 *            -> last_hidden_state [B,S,512] float32
 *   decoder  input_ids [B,T] int64, encoder_attention_mask [B,S] int64,
 *            encoder_hidden_states [B,S,512] float32
 *            -> logits [B,T,V] float32  (+ 72 KV-cache tensors this class ignores)
 *
 * Source sequence is `[<src_tag>, <tgt_tag>, ...pieces, </s>]`, matching IndicTrans2's own
 * tokeniser. The decoder is primed with `decoder_start_token_id` (2, which is also `</s>`).
 *
 * **Greedy, not beam.** Beam search would score somewhat better, but it is materially more
 * code to get right and a subtly wrong beam is worse than an honest greedy pass. This uses
 * the cacheless `decoder_model.onnx`, so each step re-runs the whole prefix: decoding is
 * O(n^2) in output length. That is the first thing to optimise (switch to
 * `decoder_with_past_model.onnx`) if latency proves unacceptable on real hardware — it is a
 * speed problem, not a correctness one.
 */
class IndicTrans2Translator private constructor(
    override val sourceLang: String,
    override val targetLang: String,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val srcTokenizer: SpmBpeTokenizer,
    private val tgtTokenizer: SpmBpeTokenizer,
    private val srcTagId: Int,
    private val tgtTagId: Int,
    private val maxSourceTokens: Int
) : TranslationEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    override fun translate(text: String): String? {
        if (text.isBlank()) return null
        return try {
            runCatching { translateInternal(text) }
                .onFailure { Log.e(TAG, "translate failed: ${it.javaClass.simpleName}: ${it.message}", it) }
                .getOrNull()
        } catch (e: Throwable) {
            Log.e(TAG, "translate threw", e)
            null
        }
    }

    private fun translateInternal(text: String): String? {
        val started = System.currentTimeMillis()

        val pieces = srcTokenizer.encode(text)
        // [src_tag, tgt_tag, ...pieces, </s>], truncated to what the graph was trained for.
        val body = if (pieces.size > maxSourceTokens - 3) {
            Log.w(TAG, "source truncated from ${pieces.size} to ${maxSourceTokens - 3} tokens")
            pieces.copyOfRange(0, maxSourceTokens - 3)
        } else {
            pieces
        }
        val srcIds = LongArray(body.size + 3)
        srcIds[0] = srcTagId.toLong()
        srcIds[1] = tgtTagId.toLong()
        for (i in body.indices) srcIds[i + 2] = body[i].toLong()
        srcIds[srcIds.size - 1] = SpmBpeTokenizer.EOS.toLong()

        val shape = longArrayOf(1, srcIds.size.toLong())
        val mask = LongArray(srcIds.size) { 1L }

        OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                val encoded = encoder.run(
                    mapOf("input_ids" to idTensor, "attention_mask" to maskTensor),
                    setOf("last_hidden_state")
                )
                encoded.use { encOut ->
                    val hidden = encOut.get(0) as OnnxTensor
                    val out = greedyDecode(hidden, maskTensor)
                    val result = tgtTokenizer.decode(out)
                    Log.i(
                        TAG,
                        "translated $sourceLang->$targetLang: ${srcIds.size} src tokens -> " +
                            "${out.size} out tokens in ${System.currentTimeMillis() - started}ms"
                    )
                    return result.ifBlank { null }
                }
            }
        }
    }

    /** Argmax at each step until the end token or [MAX_NEW_TOKENS]. */
    private fun greedyDecode(hidden: OnnxTensor, encoderMask: OnnxTensor): List<Int> {
        val generated = ArrayList<Int>(MAX_NEW_TOKENS)
        // Primed with decoder_start_token_id; it is stripped before detokenising.
        var prefix = longArrayOf(DECODER_START.toLong())

        for (step in 0 until MAX_NEW_TOKENS) {
            val next = OnnxTensor.createTensor(
                env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong())
            ).use { decIn ->
                decoder.run(
                    mapOf(
                        "input_ids" to decIn,
                        "encoder_attention_mask" to encoderMask,
                        "encoder_hidden_states" to hidden
                    ),
                    // Only the logits are requested; the 72 cache tensors are not used by
                    // this cacheless loop and materialising them would be pure cost.
                    setOf("logits")
                ).use { out -> argmaxLastStep(out.get(0).value) }
            } ?: break

            if (next == SpmBpeTokenizer.EOS) break
            generated.add(next)
            prefix = prefix.copyOf(prefix.size + 1).also { it[it.size - 1] = next.toLong() }
        }
        return generated
    }

    /** logits arrive as [1][T][V]; only the last position matters for greedy decoding. */
    private fun argmaxLastStep(value: Any?): Int? {
        @Suppress("UNCHECKED_CAST")
        val batch = value as? Array<Array<FloatArray>> ?: run {
            Log.e(TAG, "unexpected logits layout: ${value?.javaClass}")
            return null
        }
        val steps = batch.firstOrNull() ?: return null
        val last = steps.lastOrNull() ?: return null
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in last.indices) {
            if (last[i] > bestVal) {
                bestVal = last[i]
                bestIdx = i
            }
        }
        return bestIdx
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }

    companion object {
        private const val TAG = "IndicTrans2"
        private const val DECODER_START = 2
        private const val MAX_NEW_TOKENS = 128

        /** FLORES tags IndicTrans2 uses to name languages. */
        private val FLORES_TAG = mapOf("hi" to "hin_Deva", "en" to "eng_Latn")

        fun fileNames(sourceLang: String, targetLang: String): List<String> {
            val d = direction(sourceLang, targetLang) ?: return emptyList()
            return listOf(
                "mt-$d-encoder.int8.onnx",
                "mt-$d-decoder.int8.onnx",
                "mt-$d-bpe-src.tsv",
                "mt-$d-bpe-tgt.tsv",
                "mt-$d-meta.json"
            )
        }

        /** Directory name for a pair, or null when no model exists for it. */
        fun direction(sourceLang: String, targetLang: String): String? = when {
            sourceLang == "hi" && targetLang == "en" -> "hi-en"
            sourceLang == "en" && targetLang == "hi" -> "en-hi"
            else -> null
        }

        /**
         * Opens the pair for [sourceLang] -> [targetLang], or returns why it cannot.
         *
         * [resolve] maps a file name to where it actually landed, so this class does not
         * need to know about install directories or sideload paths.
         */
        fun open(
            sourceLang: String,
            targetLang: String,
            resolve: (String) -> File?
        ): Result<IndicTrans2Translator> {
            val dir = direction(sourceLang, targetLang)
                ?: return Result.failure(
                    IllegalArgumentException("no model for $sourceLang->$targetLang")
                )

            val names = fileNames(sourceLang, targetLang)
            val files = names.map { it to resolve(it) }
            val missing = files.filter { it.second?.isFile != true }.map { it.first }
            if (missing.isNotEmpty()) {
                return Result.failure(java.io.FileNotFoundException(missing.joinToString()))
            }
            val byName = files.associate { it.first to it.second!! }

            return runCatching {
                val meta = JSONObject(byName["mt-$dir-meta.json"]!!.readText())
                val tags = meta.getJSONObject("langTagIds")
                val srcTag = FLORES_TAG[sourceLang] ?: error("unmapped source $sourceLang")
                val tgtTag = FLORES_TAG[targetLang] ?: error("unmapped target $targetLang")

                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }

                IndicTrans2Translator(
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    encoder = env.createSession(
                        byName["mt-$dir-encoder.int8.onnx"]!!.absolutePath, opts
                    ),
                    decoder = env.createSession(
                        byName["mt-$dir-decoder.int8.onnx"]!!.absolutePath, opts
                    ),
                    srcTokenizer = SpmBpeTokenizer.load(
                        byName["mt-$dir-bpe-src.tsv"]!!, meta.getInt("srcVocabSize")
                    ),
                    tgtTokenizer = SpmBpeTokenizer.load(
                        byName["mt-$dir-bpe-tgt.tsv"]!!, meta.getInt("tgtVocabSize")
                    ),
                    srcTagId = tags.getInt(srcTag),
                    tgtTagId = tags.getInt(tgtTag),
                    maxSourceTokens = meta.optInt("maxSourceTokens", 256)
                )
            }
        }
    }
}
