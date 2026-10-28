package com.freemax.app

import android.app.Application
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MessageRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FreeMAXApp : Application() {
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var localCache: com.freemax.app.core.storage.LocalCache
    @Inject lateinit var secureStorage: com.freemax.app.core.storage.SecureStorage

    override fun onCreate() {
        super.onCreate()
        // Лог кадров WebSocket — только в debug-сборке (в release payload'ы
        // не попадают в logcat).
        com.freemax.api.transport.MaxWebSocket.debug = BuildConfig.DEBUG
        // Force eager init so replayCache is captured
        chatRepository.hashCode()
        messageRepository.hashCode()
        // Auto-connect if already logged in (covers cold start at CHATS)
        if (authRepository.isLoggedIn()) {
            authRepository.connect()
        }
        // Автоочистка кэша по времени (настройка в Settings)
        val autoClearDays = try { secureStorage.getAutoClearDays() } catch (_: Exception) { 0 }
        if (autoClearDays > 0) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { localCache.autoClearIfNeeded(autoClearDays) } catch (_: Exception) {}
            }
        }
        // NOTE: no Gecko preinit here. GeckoRuntime.create() in Application
        // starts the full engine (child :tab processes) and the process was
        // SIGKILLed ~12s after every cold start. Gecko (if used) must be
        // created lazily on the login screen only.
    }
}
