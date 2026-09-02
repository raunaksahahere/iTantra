package com.itantra.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads, verifies and installs language packs.
 *
 * This is the ONLY component in iTantra permitted to touch the network, and only
 * for one-time provisioning (Rules §1). Nothing in the speak → STT → mesh → TTS → hear
 * loop calls into it.
 *
 * Every file is SHA-256 verified before it is moved into place; a mismatch deletes the
 * download and fails the install (Rules §19).
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val BUFFER = 64 * 1024
    }

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val lang: String, val fileName: String, val fraction: Float) : Progress
        data class Verifying(val lang: String, val fileName: String) : Progress
        data class Done(val lang: String) : Progress
        data class Failed(val lang: String, val reason: String) : Progress
    }

    private val store = ModelStore(context)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Installs every missing file for [lang].
     *
     * @return true when the pack is fully present afterwards.
     */
    suspend fun install(lang: String): Boolean = withContext(Dispatchers.IO) {
        val spec = ModelCatalog.byLang(context, lang)
        if (spec == null) {
            fail(lang, "Unknown language '$lang'")
            return@withContext false
        }

        val missing = store.missing(spec)
        if (missing.isEmpty()) {
            Log.i(TAG, "Pack '$lang' already complete")
            _progress.value = Progress.Done(lang)
            return@withContext true
        }

        val unpublished = missing.filterNot { it.isPublished }
        if (unpublished.isNotEmpty()) {
            // Refusing here is deliberate: installing a file we cannot checksum would
            // silently break the offline-integrity guarantee.
            fail(
                lang,
                "No verified download published for: ${unpublished.joinToString { it.fileName }}. " +
                    "Export the model, then set url + sha256 in assets/models/manifest.json, " +
                    "or sideload into ${store.sideloadRoot?.absolutePath}/$lang/"
            )
            return@withContext false
        }

        val dir = store.installDir(lang).apply { mkdirs() }

        for (model in missing) {
            currentCoroutineContext().ensureActive()
            val ok = downloadAndVerify(lang, model, dir)
            if (!ok) return@withContext false
        }

        val complete = store.missing(spec).isEmpty()
        if (complete) {
            Log.i(TAG, "Pack '$lang' installed (${store.installedBytes(lang)} bytes)")
            _progress.value = Progress.Done(lang)
        } else {
            fail(lang, "Install finished but files are still missing")
        }
        complete
    }

    private suspend fun downloadAndVerify(lang: String, model: ModelSpec, dir: File): Boolean {
        val target = File(dir, model.fileName)
        val tmp = File(dir, "${model.fileName}.part")

        val sources = listOfNotNull(
            model.url.takeIf { it.isNotBlank() },
            model.mirrorUrl.takeIf { it.isNotBlank() }
        )

        for ((index, url) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                _progress.value = Progress.Downloading(lang, model.fileName, 0f)
                val digest = fetch(url, tmp, model.sizeBytes) { fraction ->
                    _progress.value = Progress.Downloading(lang, model.fileName, fraction)
                }

                _progress.value = Progress.Verifying(lang, model.fileName)
                if (!digest.equals(model.sha256, ignoreCase = true)) {
                    tmp.delete()
                    Log.e(TAG, "CHECKSUM_MISMATCH ${model.fileName}: expected ${model.sha256}, got $digest")
                    // A corrupt CDN copy is worth retrying against the mirror; a wrong
                    // digest in the manifest is not, but we cannot tell them apart here.
                    if (index < sources.lastIndex) continue
                    fail(lang, "Checksum mismatch for ${model.fileName}")
                    return false
                }

                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                Log.i(TAG, "Installed ${model.fileName} (${target.length()} bytes)")
                return true
            } catch (e: Exception) {
                tmp.delete()
                Log.e(TAG, "DOWNLOAD_FAILED ${model.fileName} from $url: ${e.javaClass.simpleName}: ${e.message}", e)
                if (index < sources.lastIndex) continue
                fail(lang, "Download failed for ${model.fileName}: ${e.message}")
                return false
            }
        }
        fail(lang, "No source URL for ${model.fileName}")
        return false
    }

    /** Streams [url] into [dest], returning the lowercase SHA-256 of what was written. */
    private suspend fun fetch(
        url: String,
        dest: File,
        expectedBytes: Long,
        onProgress: (Float) -> Unit
    ): String {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
            val body = it.body ?: throw IllegalStateException("Empty body")
            val total = body.contentLength().takeIf { len -> len > 0 } ?: expectedBytes

            val md = MessageDigest.getInstance("SHA-256")
            var written = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        written += n
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            return md.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }

    /** Removes an installed pack to reclaim space. */
    fun uninstall(lang: String): Boolean {
        val ok = store.delete(lang)
        Log.i(TAG, "Uninstall '$lang': $ok")
        return ok
    }

    fun status(lang: String): PackStatus {
        val spec = ModelCatalog.byLang(context, lang) ?: return PackStatus.UNKNOWN
        val missing = store.missing(spec)
        return when {
            missing.isEmpty() -> PackStatus.INSTALLED
            missing.size < spec.models.size -> PackStatus.PARTIAL
            spec.models.none { it.isPublished } -> PackStatus.UNPUBLISHED
            else -> PackStatus.NOT_INSTALLED
        }
    }

    enum class PackStatus { INSTALLED, PARTIAL, NOT_INSTALLED, UNPUBLISHED, UNKNOWN }

    private fun fail(lang: String, reason: String) {
        Log.e(TAG, "INSTALL_FAILED[$lang]: $reason")
        _progress.value = Progress.Failed(lang, reason)
    }
}
