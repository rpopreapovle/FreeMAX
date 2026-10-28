package com.freemax.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.freemax.api.MaxClient
import com.freemax.api.model.Attachment
import com.freemax.api.model.Message
import com.freemax.api.protocol.MsgPack
import com.freemax.api.protocol.Opcodes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Локально скачанное вложение, готовое к открытию/сохранению. */
data class LocalAttachment(
    val file: File,
    val mime: String,
    val name: String
)

/** Стабильный ключ вложения для индикатора загрузки в конкретной строке. */
fun attachmentKey(messageId: Long, att: Attachment): String =
    "$messageId:${att.type}:${att.id}:${att.fileId ?: att.url ?: att.photoUrl ?: att.videoUrl ?: ""}"

/**
 * Скачивание вложений из ленты и открытие их через FileProvider/MediaStore.
 *
 * Ссылки берутся так же, как в web-клиенте (проверено на живом сервере):
 *  - PHOTO/AUDIO обычно уже приходят с готовым url из push/history;
 *  - FILE — опкод 88 (fileId + chatId + messageId + itemType); fileId сервер
 *    принимает только числом — строка даёт proto.payload ("Expected number");
 *  - VIDEO — опкод 83 (videoId + token + chatId + postId + messageId), ответ
 *    содержит MP4_1080/MP4_720/... (плюс EXTERNAL — страницу плеера, не файл);
 *  - AUDIO без url — опкод 301 (audioId + chatId + messageId + token), ответ
 *    содержит m4a/mp3/opus.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val client: MaxClient,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MediaRepo"
        private const val FP_SUFFIX = ".fileprovider"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Стабильный ключ вложения для индикатора загрузки в конкретной строке. */
    fun key(messageId: Long, att: Attachment): String = attachmentKey(messageId, att)

    /** Скачивает вложение в cache/downloads. Бросает IOException при ошибке. */
    suspend fun fetch(
        message: Message,
        att: Attachment,
        onProgress: (Int) -> Unit = {}
    ): LocalAttachment = withContext(Dispatchers.IO) {
        val url = resolveUrl(message, att)
            ?: throw IOException("сервер не отдал ссылку на файл")
        val name = safeName(att.name ?: nameFromUrl(url) ?: defaultName(att))
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val out = File(dir, name)
        android.util.Log.d(TAG, "GET $url -> ${out.absolutePath}")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FreeMAX/0.1")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("пустой ответ")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        copied += read
                        if (total > 0) {
                            val pct = ((copied * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        if (out.length() == 0L) throw IOException("файл пустой")
        LocalAttachment(out, mimeFor(name, att), name)
    }

    /** Открывает скачанный файл во внешнем приложении через chooser. */
    fun open(local: LocalAttachment) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + FP_SUFFIX,
            local.file
        )
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, local.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val chooser = Intent.createChooser(view, "Открыть")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "open failed", e)
            throw IOException("нет приложения для ${local.mime}")
        }
    }

    /** Кладёт скачанный файл в системные «Загрузки» (MediaStore, без разрешений). */
    suspend fun saveToDownloads(local: LocalAttachment): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, local.name)
            put(MediaStore.Downloads.MIME_TYPE, local.mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore отказал")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                local.file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("не удалось открыть поток записи")
            values.clear()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        uri
    }

    /** Прямая ссылка, если сервер уже прислал её вместе с сообщением. */
    private fun directUrl(att: Attachment): String? = sequenceOf(
        att.url,
        att.videoUrl,
        att.photoUrl,
        att.audioUrl
    ).firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }

    private suspend fun resolveUrl(message: Message, att: Attachment): String? {
        directUrl(att)?.let { return it }
        val chatId = message.chatId
        val msgId = message.id
        try {
            when {
                att.isFile -> {
                    // web.max.ru: cmd(88, {fileId, chatId, messageId, itemType}).
                    // fileId — только число: строку сервер отвергает (proto.payload).
                    val fileId: Any = att.fileId?.toLongOrNull()?.takeIf { it != 0L }
                        ?: att.id.takeIf { it != 0L }
                        ?: att.fileId
                        ?: 0L
                    val payload = mutableMapOf<String, Any?>(
                        "fileId" to fileId,
                        "chatId" to MsgPack.BigInt(chatId),
                        "messageId" to MsgPack.BigInt(msgId),
                        "itemType" to "REGULAR"
                    )
                    pickUrl(client.request(Opcodes.FILE_DOWNLOAD, payload, 20000)?.payload)?.let { return it }
                    // Фолбэк: повтор с токеном вложения (сервер принимает и такой запрос).
                    att.extra?.takeIf { it.isNotBlank() }?.let { token ->
                        payload["token"] = token
                        pickUrl(client.request(Opcodes.FILE_DOWNLOAD, payload, 20000)?.payload)?.let { return it }
                    }
                }
                att.isVideo -> {
                    // web.max.ru: cmd(83, {videoId, token, chatId, postId, messageId})
                    // -> MP4_720/MP4_1080/...; постId для обычных чатов — 0.
                    val payload = mutableMapOf<String, Any?>(
                        "videoId" to att.id,
                        "chatId" to MsgPack.BigInt(chatId),
                        "postId" to MsgPack.BigInt(0),
                        "messageId" to MsgPack.BigInt(msgId)
                    )
                    att.extra?.takeIf { it.isNotBlank() }?.let { payload["token"] = it }
                    pickUrl(client.request(Opcodes.VIDEO_UPLOAD, payload, 20000)?.payload)?.let { return it }
                }
                att.isAudio -> {
                    // web.max.ru: cmd(301, {audioId, chatId, messageId, token}) -> m4a/mp3/opus.
                    val payload = mutableMapOf<String, Any?>(
                        "audioId" to att.id,
                        "chatId" to MsgPack.BigInt(chatId),
                        "messageId" to MsgPack.BigInt(msgId)
                    )
                    att.extra?.takeIf { it.isNotBlank() }?.let { payload["token"] = it }
                    pickUrl(client.request(Opcodes.AUDIO_UPLOAD, payload, 20000)?.payload)?.let { return it }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "resolveUrl failed", e)
        }
        return null
    }

    /** Ищет первую http-ссылку в ответе сервера (url/MP4_720/m4a/...). */
    private fun pickUrl(payload: Map<String, Any?>?): String? {
        if (payload == null) return null
        // Видео: ключи MP4_1080/MP4_720/... — берём максимальное качество.
        payload.keys.filter { it.startsWith("MP4_") }
            .sortedByDescending { it.removePrefix("MP4_").toIntOrNull() ?: 0 }
            .forEach { key ->
                val v = payload[key]
                if (v is String && v.startsWith("http")) return v
            }
        val preferred = listOf(
            "url", "downloadUrl", "download_url", "m4a", "mp3", "opus", "href", "src", "link"
        )
        for (key in preferred) {
            val v = payload[key]
            if (v is String && v.startsWith("http")) return v
        }
        return findUrlDeep(payload, 0)
    }

    private fun findUrlDeep(value: Any?, depth: Int): String? {
        if (depth > 4) return null
        when (value) {
            is String -> if (value.startsWith("http")) return value
            is Map<*, *> -> {
                for ((k, v) in value) {
                    // EXTERNAL — ссылка на страницу плеера, её качать не нужно.
                    if (k is String && k.equals("external", ignoreCase = true)) continue
                    findUrlDeep(v, depth + 1)?.let { return it }
                }
            }
            is List<*> -> {
                for (v in value) findUrlDeep(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun defaultName(att: Attachment): String {
        val base = when {
            att.isFile -> "file"
            att.isVideo -> "video"
            att.isAudio -> "audio"
            att.isPhoto -> "photo"
            else -> "attachment"
        }
        val ext = when {
            att.isVideo -> ".mp4"
            att.isAudio -> ".ogg"
            att.isPhoto -> ".jpg"
            else -> ""
        }
        return "$base" + "_${att.id}$ext"
    }

    private fun nameFromUrl(url: String): String? = try {
        val path = Uri.parse(url).lastPathSegment
        path?.takeIf { it.isNotBlank() && it.contains('.') && it.length < 120 }
    } catch (_: Exception) {
        null
    }

    private fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        val name = cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
        return if (name.length > 120) name.take(100) + "_" + name.takeLast(15) else name
    }

    private fun mimeFor(name: String, att: Attachment): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xls") -> "application/vnd.ms-excel"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            att.isVideo -> "video/mp4"
            att.isAudio -> "audio/ogg"
            att.isPhoto -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
