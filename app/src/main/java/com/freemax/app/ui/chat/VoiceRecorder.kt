package com.freemax.app.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Запись голосового сообщения. Пишем сразу в OGG/Opus (как официальный клиент),
 * если устройство не умеет — падаем на MPEG_4/AAC. Файл живёт в cacheDir и
 * удаляется после отправки/отмены.
 */
class VoiceRecorder(private val context: Context) {
    companion object {
        private const val TAG = "VoiceRecorder"
    }

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    var startedAt: Long = 0L
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        // OGG/Opus — предпочтительный формат для голосовых MAX
        val ogg = File(dir, "voice_${System.currentTimeMillis()}.ogg")
        if (tryStart(ogg, MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS)) {
            file = ogg
            startedAt = System.currentTimeMillis()
            return true
        }
        val m4a = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        if (tryStart(m4a, MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC)) {
            file = m4a
            startedAt = System.currentTimeMillis()
            return true
        }
        Log.e(TAG, "Не удалось начать запись")
        return false
    }

    private fun tryStart(f: File, format: Int, encoder: Int): Boolean {
        return try {
            val r = MediaRecorder(context)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(format)
            r.setAudioEncoder(encoder)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (e: Exception) {
            Log.e(TAG, "tryStart failed ($format/$encoder)", e)
            runCatching { recorder?.release() }
            recorder = null
            runCatching { f.delete() }
            false
        }
    }

    /** Максимальная амплитуда с последнего вызова — для индикатора. */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    /** Останавливает запись и возвращает (uri, длительность мс) или null. */
    fun stop(): Pair<Uri, Long>? {
        val r = recorder ?: return null
        val f = file
        val elapsed = elapsedMs()
        recorder = null
        startedAt = 0L
        return try {
            r.stop()
            r.release()
            if (f == null || !f.exists() || f.length() == 0L) {
                f?.delete()
                null
            } else {
                Uri.fromFile(f) to elapsed
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            runCatching { r.release() }
            f?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder
        recorder = null
        startedAt = 0L
        runCatching { r?.stop() }
        runCatching { r?.release() }
        file?.delete()
        file = null
    }

    fun deleteFile(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
        }
    }
}
