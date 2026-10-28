package com.freemax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import com.freemax.api.MaxClient
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MessageRepository
import com.freemax.app.data.repository.UploadRepository
import com.freemax.app.ui.navigation.NavGraph
import com.freemax.app.ui.theme.FreeMAXTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var maxClient: MaxClient
    @Inject lateinit var uploadRepository: UploadRepository
    @Inject lateinit var notifier: com.freemax.app.core.notify.Notifier
    @Inject lateinit var localCache: com.freemax.app.core.storage.LocalCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Канал уведомлений + разрешение POST_NOTIFICATIONS (Android 13+)
        notifier.ensureChannel()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            } catch (_: Exception) {}
        }
        // Отслеживание «приложение на переднем плане» для уведомлений
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                notifier.appInForeground = true
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                notifier.appInForeground = false
            }
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityCreated(activity: android.app.Activity, b: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, b: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // DEBUG-only тестовые хуки (src/debug, см. DebugNightHook): активируются
        // только явными extras из adb. В release реализаций нет, код хука в APK
        // не попадает (см. DebugHooks).
        if (BuildConfig.DEBUG) {
            DebugHooks.installed().forEach { hook ->
                hook.handle(
                    applicationContext, intent, messageRepository, chatRepository,
                    authRepository, maxClient, notifier, secureStorage, localCache,
                    uploadRepository
                )
            }
        }
        enableEdgeToEdge()
        setContent {
            val themeMode by secureStorage.themeFlow.collectAsState()
            val darkTheme = when (themeMode) {
                SecureStorage.THEME_LIGHT -> false
                SecureStorage.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            FreeMAXTheme(darkTheme = darkTheme) {
                // Блокировка приложения (PIN): показываем экран разблокировки,
                // пока пользователь не введёт корректный PIN.
                val lockEnabled by secureStorage.appLockFlow.collectAsState()
                var unlocked by remember { mutableStateOf(false) }
                if (lockEnabled && !unlocked) {
                    AppLockScreen(
                        check = { pin -> runCatching { secureStorage.checkAppLockPin(pin) }.getOrDefault(false) },
                        onUnlocked = { unlocked = true }
                    )
                } else {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }
}
