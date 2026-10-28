package com.freemax.app.data.repository

import com.freemax.app.BuildConfig
import com.freemax.app.core.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional speech-to-text under voice messages.
 * Endpoint and key are build-time values; public builds ship with both empty,
 * so the feature is unavailable and no audio leaves the device.
 */
@Singleton
class SttRepository @Inject constructor(
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "SttRepo"
        private val ENDPOINT: String get() = BuildConfig.STT_ENDPOINT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _transcripts = MutableStateFlow<Map<Long, String>>(emptyMap())
    val transcripts: StateFlow<Map<Long, String>> = _transcripts.asStateFlow()

    private val _pending = MutableStateFlow<Set<Long>>(emptySet())
    val pending: StateFlow<Set<Long>> = _pending.asStateFlow()

    private val inFlight = mutableSetOf<Long>()
    private val failed = mutableSetOf<Long>()

    // После всех полей: ensure()/коллекторы трогают _pending/inFlight/failed.
    init {
        // Hard cache: restore transcripts from storage so they survive restart
        try {
            val cached = secureStorage.getTranscripts()
            if (cached.isNotEmpty()) {
                _transcripts.value = cached
                android.util.Log.d(TAG, "Restored ${cached.size} transcripts")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "restore failed", e)
        }
    }

    fun isEnabled(): Boolean = secureStorage.isSttEnabled() && isAvailable()

    /** False in builds without an API key: the feature quietly doesn't exist. */
    fun isAvailable(): Boolean =
        BuildConfig.STT_API_KEY.isNotBlank() && BuildConfig.STT_ENDPOINT.isNotBlank()

    fun transcriptFor(audioKey: Long): String? = _transcripts.value[audioKey]

    fun ensure(audioKey: Long, audioUrl: String?) {
        if (!isAvailable() || !secureStorage.isSttEnabled()) return
        if (audioKey == 0L || audioUrl.isNullOrBlank()) return
        if (_transcripts.value.containsKey(audioKey) || failed.contains(audioKey)) return
        synchronized(inFlight) {
            if (!inFlight.add(audioKey)) return
        }
        _pending.update { it + audioKey }
        scope.launch {
            try {
                val text = transcribe(audioUrl)
                if (!text.isNullOrBlank()) {
                    _transcripts.update { it + (audioKey to text) }
                    try {
                        secureStorage.saveTranscript(audioKey, text)
                    } catch (_: Exception) {
                    }
                } else {
                    synchronized(failed) { failed.add(audioKey) }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "transcribe failed", e)
                synchronized(failed) { failed.add(audioKey) }
            } finally {
                synchronized(inFlight) { inFlight.remove(audioKey) }
                _pending.update { it - audioKey }
            }
        }
    }

    private fun transcribe(audioUrl: String): String? {
        val key = BuildConfig.STT_API_KEY
        if (key.isBlank()) {
            android.util.Log.d(TAG, "STT unavailable in this build")
            return null
        }
        // 1. Download audio bytes
        val audioBytes = http.newCall(Request.Builder().url(audioUrl).get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.e(TAG, "audio download code=${resp.code}")
                    return null
                }
                resp.body?.bytes()
            } ?: return null
        android.util.Log.d(TAG, "downloaded ${audioBytes.size} bytes for STT")
        // 2. base64 body as plain text
        val b64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val req = Request.Builder()
            .url(ENDPOINT)
            .header("Content-Type", "text/plain")
            .header("X-API-Key", key)
            .post(b64.toRequestBody("text/plain".toMediaTypeOrNull()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            android.util.Log.d(TAG, "STT resp code=${resp.code} body=${body?.take(300)}")
            if (!resp.isSuccessful || body.isNullOrBlank()) return null
            return try {
                JSONObject(body).optString("text", "").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
