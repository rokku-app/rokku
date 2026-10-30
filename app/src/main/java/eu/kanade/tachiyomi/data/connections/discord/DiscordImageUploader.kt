package eu.kanade.tachiyomi.data.connections.discord

import android.content.Context
import android.net.Uri
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DiscordImageUploader"
private const val MAX_COVER_BYTES = 10 * 1024 * 1024
private const val UGUU_TTL_MS = 2 * 60 * 60 * 1000L + 30 * 60 * 1000L // 2 h 30 min

private enum class UploadHost(
    val label: String,
    val url: String,
    val fileField: String,
    val extraFields: Map<String, String>,
    val ttlMs: Long?,
) {
    UGUU(
        label = "uguu.se",
        url = "https://uguu.se/upload.php?output=text",
        fileField = "files[]",
        extraFields = emptyMap(),
        ttlMs = UGUU_TTL_MS,
    ),
    CATBOX(
        label = "catbox.moe",
        url = "https://catbox.moe/user/api.php",
        fileField = "fileToUpload",
        extraFields = mapOf("reqtype" to "fileupload"),
        ttlMs = null,
    ),
}

internal object DiscordImageUploader {

    private data class CachedUpload(val url: String, val expiresAt: Long?)

    private val uploadCache = ConcurrentHashMap<String, CachedUpload>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val networkHelper: NetworkHelper by injectLazy()

    /**
     * Uploads a local content://, file:// URI or absolute file path
     * and returns its public URL.
     */
    suspend fun resolveImageUrl(context: Context, localUri: String): String? {
        if (localUri.startsWith("content://") || localUri.startsWith("file://")) {
            return resolve(localUri) {
                context.contentResolver
                    .openInputStream(Uri.parse(localUri))
                    ?.use { readCapped(it) }
            }
        }
        return resolveImageUrl(context, File(localUri))
    }

    /**
     * Uploads a local file and returns its public URL.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun resolveImageUrl(context: Context, file: File): String? =
        resolve("${file.absolutePath}:${file.lastModified()}") {
            if (file.length() > MAX_COVER_BYTES) null else file.readBytes()
        }

    /**
     * Common flow: cache lookup -> read -> validate -> upload -> cache.
     * Concurrent calls for the same key share a single upload.
     */
    private suspend fun resolve(cacheKey: String, read: () -> ByteArray?): String? =
        withContext(Dispatchers.IO) {
            cached(cacheKey)?.let { return@withContext it }

            locks.computeIfAbsent(cacheKey) { Mutex() }.withLock {
                // Another coroutine may have finished the upload while we waited.
                cached(cacheKey)?.let { return@withLock it }

                val bytes = try {
                    read()
                } catch (e: Exception) {
                    Logger.w(TAG) { "Failed to read local cover: ${e.message}" }
                    null
                }

                if (bytes == null || bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) {
                    Logger.w(TAG) { "Cover rejected: unreadable, empty or larger than 10 MB" }
                    return@withLock null
                }

                for (host in UploadHost.entries) {
                    val url = upload(host, bytes) ?: continue
                    val expiresAt = host.ttlMs?.let { System.currentTimeMillis() + it }
                    uploadCache[cacheKey] = CachedUpload(url, expiresAt)
                    return@withLock url
                }
                null
            }
        }

    private fun cached(key: String): String? {
        val entry = uploadCache[key] ?: return null
        val expired = entry.expiresAt?.let { System.currentTimeMillis() >= it } ?: false
        if (expired) {
            uploadCache.remove(key, entry)
            return null
        }
        return entry.url
    }

    /** Reads at most MAX_COVER_BYTES; returns null if the stream is larger. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_COVER_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun upload(host: UploadHost, bytes: ByteArray): String? = try {
        val extension = extensionFor(bytes)
        val mediaType = "image/$extension".toMediaTypeOrNull()

        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            host.extraFields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart(host.fileField, "cover.$extension", bytes.toRequestBody(mediaType))
        }.build()

        networkHelper.client
            .newCall(Request.Builder().url(host.url).post(body).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    Logger.w(TAG) { "${host.label} upload failed: HTTP ${response.code}" }
                    return@use null
                }
                response.body
                    ?.string()
                    ?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
    } catch (e: Exception) {
        Logger.w(TAG) { "${host.label} upload failed: ${e.message}" }
        null
    }

    private fun extensionFor(bytes: ByteArray): String = when {
        // WebP
        bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"

        // PNG
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png"

        // JPEG
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpeg"

        else -> "png"
    }
}
