package com.itantra.models

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Reads `assets/models/manifest.json` — the declarative description of every language
 * pack. Keeping this out of Kotlin means newly exported models can be published by
 * editing one JSON file.
 */
object ModelCatalog {

    private const val TAG = "ModelCatalog"
    private const val ASSET = "models/manifest.json"

    @Volatile
    private var cached: List<LanguageModelSpec>? = null

    fun languages(context: Context): List<LanguageModelSpec> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.e(TAG, "MANIFEST_LOAD_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                emptyList()
            }
            cached = parsed
            Log.i(TAG, "Loaded ${parsed.size} language specs from manifest")
            return parsed
        }
    }

    fun byLang(context: Context, lang: String): LanguageModelSpec? =
        languages(context).firstOrNull { it.lang == lang }

    private fun parse(json: String): List<LanguageModelSpec> {
        val arr = JSONObject(json).getJSONArray("languages")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val models = o.getJSONArray("models")
            val specs = (0 until models.length()).mapNotNull { j ->
                val m = models.getJSONObject(j)
                val role = runCatching { ModelRole.valueOf(m.getString("role")) }.getOrNull()
                if (role == null) {
                    Log.w(TAG, "Skipping model with unknown role: ${m.optString("role")}")
                    return@mapNotNull null
                }
                ModelSpec(
                    role = role,
                    fileName = m.getString("fileName"),
                    url = m.optString("url", ""),
                    mirrorUrl = m.optString("mirrorUrl", ""),
                    sha256 = m.optString("sha256", "").lowercase(),
                    sizeBytes = m.optLong("sizeBytes", 0L)
                )
            }
            LanguageModelSpec(
                lang = o.getString("lang"),
                displayName = o.getString("displayName"),
                nativeName = o.getString("nativeName"),
                bundled = o.optBoolean("bundled", false),
                models = specs
            )
        }
    }
}
