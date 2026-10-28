package com.freemax.app.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.freemax.api.MaxClient
import com.freemax.api.protocol.BinaryCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AttachKind { PHOTO, VIDEO, AUDIO, FILE }

data class PendingAttach(
    val uri: Uri,
    val kind: AttachKind,
    val mime: String?,
    val name: String?,
    val bytes: ByteArray? = null,
    /** Для голосовых: длительность записи (мс), измеренная рекордером. */
    val durationMs: Long = 0L
) {
    // ByteArray breaks data-class equals; compare by uri only where it matters.
    override fun equals(other: Any?): Boolean =
        other is PendingAttach && other.uri == uri && other.kind == kind
    override fun hashCode(): Int = 31 * uri.hashCode() + kind.hashCode()
}

@Singleton
class UploadRepository @Inject constructor(
    private val client: MaxClient,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UploadRepo"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    data class ResolvedFile(
        val bytes: ByteArray,
        val mime: String,
        val name: String
    )

    fun resolve(uri: Uri, fallbackMime: String? = null): ResolvedFile? {
        return try {
            val cr = context.contentResolver
            var name = "file"
            var mime = fallbackMime
            cr.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
            }
            if (mime == null) mime = cr.getType(uri)
            if (mime == null) {
                mime = when {
                    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                    name.endsWith(".png", true) -> "image/png"
                    name.endsWith(".webp", true) -> "image/webp"
                    name.endsWith(".mp4", true) -> "video/mp4"
                    name.endsWith(".mp3", true) -> "audio/mpeg"
                    name.endsWith(".ogg", true) || name.endsWith(".oga", true) -> "audio/ogg"
                    name.endsWith(".m4a", true) -> "audio/mp4"
                    name.endsWith(".pdf", true) -> "application/pdf"
                    else -> "application/octet-stream"
                }
            }
            var bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            // file:// и некоторые провайдеры не отдают имя через query — берём из пути
            if (name == "file") {
                uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { name = it }
            }
            // MIME мог не определиться до имени файла — повторяем по расширению
            if (mime == null || mime == "application/octet-stream") {
                mime = when {
                    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                    name.endsWith(".png", true) -> "image/png"
                    name.endsWith(".webp", true) -> "image/webp"
                    name.endsWith(".gif", true) -> "image/gif"
                    name.endsWith(".heic", true) -> "image/heic"
                    name.endsWith(".mp4", true) -> "video/mp4"
                    name.endsWith(".mov", true) -> "video/quicktime"
                    name.endsWith(".3gp", true) -> "video/3gpp"
                    name.endsWith(".mkv", true) -> "video/x-matroska"
                    name.endsWith(".webm", true) -> "video/webm"
                    name.endsWith(".mp3", true) -> "audio/mpeg"
                    name.endsWith(".ogg", true) || name.endsWith(".oga", true) || name.endsWith(".opus", true) -> "audio/ogg"
                    name.endsWith(".m4a", true) -> "audio/mp4"
                    name.endsWith(".aac", true) -> "audio/aac"
                    name.endsWith(".wav", true) -> "audio/wav"
                    name.endsWith(".pdf", true) -> "application/pdf"
                    else -> mime ?: "application/octet-stream"
                }
            }
            // Downscale huge photos like the web client does (max 1920px, JPEG 85)
            if ((mime.startsWith("image/")) && bytes.size > 800 * 1024) {
                downscalePhoto(bytes)?.let { bytes = it }
            }
            ResolvedFile(bytes, mime, name)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolve failed", e)
            null
        }
    }

    private fun downscalePhoto(bytes: ByteArray, maxDim: Int = 1920): ByteArray? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || (w <= maxDim && h <= maxDim)) return null
            var sample = 1
            while ((w / sample) > maxDim || (h / sample) > maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            if (!bmp.isRecycled) bmp.recycle()
            val small = out.toByteArray()
            android.util.Log.d(TAG, "downscaled photo ${bytes.size} -> ${small.size}")
            small.takeIf { it.size < bytes.size }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "downscale failed", e)
            null
        }
    }

    fun videoMeta(uri: Uri): Triple<Int, Int, Long> {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            try { r.release() } catch (_: Exception) {}
            Triple(w, h, d)
        } catch (e: Exception) {
            Triple(0, 0, 0L)
        }
    }

    fun audioDurationMs(uri: Uri): Long {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            try { r.release() } catch (_: Exception) {}
            d
        } catch (e: Exception) {
            0L
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun slotInfo(frame: com.freemax.api.protocol.WsFrame?): Map<String, Any?>? {
        if (frame == null || frame.cmd != BinaryCodec.CMD_RESPONSE) return null
        val info = frame.payload?.get("info") as? List<*> ?: return null
        return info.firstOrNull() as? Map<String, Any?>
    }

    /**
     * Ждём push NOTIF_ATTACH (opcode 136): сервер подтверждает, что файл
     * обработан и готов к прикреплению. Без этого SEND_MESSAGE отвечает
     * «attachment.not.ready» / «Вложение в процессе обработки».
     */
    private suspend fun awaitAttachAck(id: Any?, timeoutMs: Long = 20000): Boolean {
        if (id == null) return false
        val idStr = id.toString()
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                client.frames.first { f ->
                    f.opcode == com.freemax.api.protocol.Opcodes.NOTIF_ATTACH &&
                        f.payload?.values?.any { v -> v?.toString() == idStr } == true
                }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Upload like the web client ($oe): POST to the slot URL.
     * Photos go as multipart/form-data field "file", everything else as raw bytes
     * with Content-Type / Content-Disposition / Content-Range headers.
     * Handles 416 resume once via X-Last-Known-Byte.
     */
    private class CountingBody(
        private val delegate: okhttp3.RequestBody,
        private val onProgress: (sent: Long, total: Long) -> Unit
    ) : okhttp3.RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = try {
            delegate.contentLength()
        } catch (_: Exception) {
            -1L
        }
        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength()
            var sent = 0L
            val forwarding = object : okio.ForwardingSink(sink) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    onProgress(sent, total)
                }
            }
            val buffered = forwarding.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    private fun httpUpload(
        url: String,
        bytes: ByteArray,
        mime: String,
        name: String,
        multipart: Boolean,
        disposition: Boolean = true,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): JSONObject? {
        return try {
            android.util.Log.d(TAG, "POST ${bytes.size} bytes ($mime, multipart=$multipart) to ${url.take(80)}...")
            val rawBody = if (multipart) {
                okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                    .build()
            } else {
                bytes.toRequestBody(mime.toMediaTypeOrNull())
            }
            val body = CountingBody(rawBody, onProgress)
            val builder = Request.Builder().url(url).post(body)
            if (!multipart) {
                // Голосовые/видео CDN ждут application/octet-stream + Content-Range
                builder.header("Content-Type", "application/octet-stream")
                if (disposition) {
                    builder.header("Content-Disposition", "attachment; filename=${java.net.URLEncoder.encode(name, "UTF-8")}")
                }
                builder.header("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
            }
            http.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string()
                android.util.Log.d(TAG, "POST resp code=${resp.code} body=${respBody?.take(500)}")
                if (resp.code == 415 && !multipart) {
                    // Some endpoints take multipart instead — retry same slot once
                    android.util.Log.d(TAG, "Retrying as multipart")
                    return httpUpload(url, bytes, mime, name, multipart = true)
                }
                if (resp.code == 416) {
                    // Resume: server tells last known byte via X-Last-Known-Byte
                    val known = resp.header("X-Last-Known-Byte")?.toLongOrNull()
                    if (known != null && known > 0 && known < bytes.size && !multipart) {
                        android.util.Log.d(TAG, "Resuming upload from $known")
                        val rest = bytes.copyOfRange(known.toInt(), bytes.size)
                        val retry = Request.Builder().url(url)
                            .post(rest.toRequestBody(mime.toMediaTypeOrNull()))
                            .header("Content-Type", "application/octet-stream")
                            .header("Content-Range", "bytes $known-${bytes.size - 1}/${bytes.size}")
                            .build()
                        http.newCall(retry).execute().use { resp2 ->
                            val body2 = resp2.body?.string()
                            android.util.Log.d(TAG, "Resume resp code=${resp2.code} body=${body2?.take(500)}")
                            if (!resp2.isSuccessful || body2.isNullOrBlank()) return null
                            return try {
                                JSONObject(body2)
                            } catch (_: Exception) {
                                JSONObject()
                            }
                        }
                    }
                    return null
                }
                if (!resp.isSuccessful) return null
                if (respBody.isNullOrBlank()) return JSONObject()
                val parsed = try {
                    JSONObject(respBody)
                } catch (_: Exception) {
                    JSONObject()
                }
                // Сторона CDN может ответить 200 с error_code — это провал загрузки
                if (parsed.has("error_code") || parsed.has("error")) {
                    android.util.Log.e(TAG, "Upload rejected by CDN: ${respBody.take(200)}")
                    return null
                }
                parsed
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "POST upload failed", e)
            null
        }
    }

    /**
     * Uploads one attachment and returns the wire map for SEND_MESSAGE attaches.
     * Returns null on any failure.
     */
    /** Avatar upload (own profile or group): slot 80 + multipart, returns photoToken. */
    suspend fun uploadAvatar(
        file: ResolvedFile,
        forProfile: Boolean,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): String? {
        return try {
            val slotPayload = if (forProfile) mapOf("count" to 1, "profile" to true)
            else mapOf("count" to 1)
            val slot = client.request(
                com.freemax.api.protocol.Opcodes.PHOTO_UPLOAD_SLOT,
                slotPayload
            )
            val url = slot?.payload?.get("url")?.toString()
            if (url == null) {
                android.util.Log.e(TAG, "avatar slot failed: ${slot?.payload}")
                return null
            }
            val json = httpUpload(url, file.bytes, file.mime, file.name, multipart = true, onProgress = onProgress)
                ?: return null
            val photos = json.optJSONObject("photos") ?: return null
            val firstKey = photos.keys().asSequence().firstOrNull() ?: return null
            photos.optJSONObject(firstKey)?.optString("token")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "avatar upload failed", e)
            null
        }
    }

    suspend fun upload(
        kind: AttachKind,
        file: ResolvedFile,
        localCid: Long,
        durationMs: Long = 0L,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): Map<String, Any?>? {
        return try {
            android.util.Log.d(TAG, "upload kind=$kind name=${file.name} size=${file.bytes.size} mime=${file.mime}")
            when (kind) {
                AttachKind.PHOTO -> {
                    val slot = client.request(
                        com.freemax.api.protocol.Opcodes.PHOTO_UPLOAD_SLOT,
                        mapOf("count" to 1)
                    )
                    android.util.Log.d(TAG, "photo slot keys=${slot?.payload?.keys}")
                    val url = slot?.payload?.get("url")?.toString()
                    if (url == null) {
                        android.util.Log.e(TAG, "photo slot failed: ${slot?.payload}")
                        return null
                    }
                    // Chat photos: multipart form field "file" to iu.oneme.ru/uploadImage
                    val json = httpUpload(url, file.bytes, file.mime, file.name, multipart = true, onProgress = onProgress)
                    if (json == null) {
                        android.util.Log.e(TAG, "photo PUT failed")
                        return null
                    }
                    // {"photos": {"<id>": {"token": ...}}}
                    val photos = json.optJSONObject("photos")
                    if (photos == null) {
                        android.util.Log.e(TAG, "photo PUT resp has no photos: $json")
                        return null
                    }
                    val firstKey = photos.keys().asSequence().firstOrNull() ?: return null
                    val token = photos.optJSONObject(firstKey)?.optString("token")?.takeIf { it.isNotBlank() }
                    if (token == null) {
                        android.util.Log.e(TAG, "photo PUT resp has no token: $json")
                        return null
                    }
                    android.util.Log.d(TAG, "photo uploaded, token len=${token.length}")
                    mapOf("_type" to "PHOTO", "photoToken" to token)
                }
                AttachKind.VIDEO -> {
                    val slot = client.request(
                        com.freemax.api.protocol.Opcodes.GET_UPLOAD_SLOT,
                        mapOf("count" to 1)
                    )
                    val info = slotInfo(slot)
                    if (info == null) {
                        android.util.Log.e(TAG, "video slot failed")
                        return null
                    }
                    val url = info["url"]?.toString()
                    if (url == null) {
                        android.util.Log.e(TAG, "video slot has no url")
                        return null
                    }
                    val videoId = (info["videoId"] as? Number)?.toLong() ?: 0L
                    val token = info["token"]?.toString()
                    if (httpUpload(url, file.bytes, file.mime, file.name, multipart = false, disposition = false, onProgress = onProgress) == null) {
                        android.util.Log.e(TAG, "video PUT failed")
                        return null
                    }
                    // Ждём NOTIF_ATTACH, иначе сервер отвергнет вложение
                    if (videoId != 0L && !awaitAttachAck(videoId, 5000)) {
                        android.util.Log.w(TAG, "video attach ack timeout, sending anyway")
                    }
                    val m = mutableMapOf<String, Any?>(
                        "_type" to "VIDEO",
                        "videoId" to videoId
                    )
                    if (token != null) m["token"] = token
                    m
                }
                AttachKind.FILE -> {
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    val slot = client.request(
                        com.freemax.api.protocol.Opcodes.FILE_UPLOAD_SLOT,
                        mapOf(
                            "name" to file.name,
                            "size" to file.bytes.size.toLong(),
                            "ext" to ext,
                            "count" to 1
                        )
                    )
                    val info = slotInfo(slot)
                    if (info == null) {
                        android.util.Log.e(TAG, "file slot failed")
                        return null
                    }
                    val url = info["url"]?.toString()
                    if (url == null) {
                        android.util.Log.e(TAG, "file slot has no url")
                        return null
                    }
                    // fileId может быть строкой (UUID) или числом — сохраняем как есть
                    val fileId = info["fileId"]
                    val token = info["token"]?.toString()
                    // Как в web-клиенте: файлы заливаются multipart'ом
                    if (httpUpload(url, file.bytes, file.mime, file.name, multipart = true, onProgress = onProgress) == null &&
                        httpUpload(url, file.bytes, file.mime, file.name, multipart = false, onProgress = onProgress) == null
                    ) {
                        android.util.Log.e(TAG, "file PUT failed")
                        return null
                    }
                    if (fileId != null && !awaitAttachAck(fileId, 6000)) {
                        android.util.Log.w(TAG, "file attach ack timeout, sending anyway")
                    }
                    val m = mutableMapOf<String, Any?>(
                        "_type" to "FILE",
                        "name" to file.name,
                        "size" to file.bytes.size
                    )
                    if (fileId != null) m["fileId"] = fileId
                    if (token != null) m["token"] = token
                    m
                }
                AttachKind.AUDIO -> {
                    val slot = client.request(
                        com.freemax.api.protocol.Opcodes.GET_UPLOAD_SLOT,
                        mapOf("count" to 1, "type" to 2, "uploaderType" to 1)
                    )
                    val info = slotInfo(slot)
                    if (info == null) {
                        android.util.Log.e(TAG, "audio slot failed")
                        return null
                    }
                    val url = info["url"]?.toString()
                    if (url == null) {
                        android.util.Log.e(TAG, "audio slot has no url")
                        return null
                    }
                    val audioId = (info["videoId"] as? Number)?.toLong()
                        ?: (info["audioId"] as? Number)?.toLong() ?: 0L
                    val token = info["token"]?.toString()
                    var uploaded = httpUpload(
                        url, file.bytes, file.mime, file.name,
                        multipart = false, disposition = false, onProgress = onProgress
                    )
                    if (uploaded == null) {
                        // Часть CDN принимает только multipart — повторяем как видео
                        android.util.Log.d(TAG, "audio raw rejected, retrying multipart")
                        uploaded = httpUpload(url, file.bytes, file.mime, file.name, multipart = true, onProgress = onProgress)
                    }
                    if (uploaded == null) {
                        android.util.Log.e(TAG, "audio PUT failed")
                        return null
                    }
                    if (audioId != 0L && !awaitAttachAck(audioId, 3000)) {
                        android.util.Log.w(TAG, "audio attach ack timeout, sending anyway")
                    }
                    val m = mutableMapOf<String, Any?>(
                        "_type" to "AUDIO",
                        "audioId" to audioId,
                        "duration" to durationMs,
                        // wave — 80 сырых байт (bin), не список int: иначе Invalid attachment
                        "wave" to ByteArray(80)
                    )
                    if (token != null) m["token"] = token
                    m
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "upload failed", e)
            null
        }
    }
}
