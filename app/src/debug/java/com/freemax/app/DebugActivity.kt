package com.freemax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.freemax.api.MaxClient
import com.freemax.app.core.notify.Notifier
import com.freemax.app.core.storage.LocalCache
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MessageRepository
import com.freemax.app.data.repository.UploadRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug-only точка запуска adb-хуков.
 *
 * Важно: activity объявлена в debug-манифесте как `exported=false`, поэтому
 * обычные приложения на устройстве не могут её запустить. adb shell может
 * (у shell есть право запускать неэкспортированные компоненты debuggable-
 * приложения), но для этого нужно явно передать `freemax_test_enable=true`.
 *
 * В release этого класса и activity нет вообще.
 */
@AndroidEntryPoint
class DebugActivity : ComponentActivity() {
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var maxClient: MaxClient
    @Inject lateinit var uploadRepository: UploadRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var localCache: LocalCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra("freemax_test_enable", false)) {
            finish()
            return
        }
        DebugHooks.installed().forEach { hook ->
            hook.handle(
                applicationContext,
                intent,
                messageRepository,
                chatRepository,
                authRepository,
                maxClient,
                notifier,
                secureStorage,
                localCache,
                uploadRepository
            )
        }
        finish()
    }
}
