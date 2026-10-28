package com.freemax.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: String = SecureStorage.THEME_SYSTEM,
    val phone: String? = null,
    val userId: Long = 0,
    val cacheCleared: Boolean = false,
    val sttEnabled: Boolean = true,
    val sttAvailable: Boolean = false,
    val autoClearDays: Int = 0,
    val appLockEnabled: Boolean = false,
    val cacheSizeText: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
    private val localCache: com.freemax.app.core.storage.LocalCache,
    chatRepository: com.freemax.app.data.repository.ChatRepository
) : ViewModel() {

    val selfProfile = chatRepository.selfProfile

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            themeMode = secureStorage.getThemeMode(),
            phone = secureStorage.getAuthPhone(),
            userId = secureStorage.getUserId(),
            sttEnabled = secureStorage.isSttEnabled(),
            sttAvailable = com.freemax.app.BuildConfig.STT_API_KEY.isNotBlank() &&
                com.freemax.app.BuildConfig.STT_ENDPOINT.isNotBlank(),
            autoClearDays = secureStorage.getAutoClearDays(),
            appLockEnabled = secureStorage.isAppLockEnabled()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshCacheSize()
    }

    private fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = localCache.messagesCacheSize()
            val text = when {
                bytes > 1024 * 1024 -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
                bytes > 1024 -> "${bytes / 1024} КБ"
                else -> "$bytes Б"
            }
            _uiState.update { it.copy(cacheSizeText = text) }
        }
    }

    fun setSttEnabled(enabled: Boolean) {
        secureStorage.setSttEnabled(enabled)
        _uiState.update { it.copy(sttEnabled = enabled) }
    }

    fun setThemeMode(mode: String) {
        secureStorage.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setAutoClearDays(days: Int) {
        secureStorage.setAutoClearDays(days)
        _uiState.update { it.copy(autoClearDays = days) }
    }

    /** Только кэш изображений Coil. */
    fun clearCoilDiskCache(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.imageLoader.diskCache?.clear()
                context.imageLoader.memoryCache?.clear()
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(cacheCleared = true) }
            }
        }
    }

    /** Полная очистка локального кэша (сообщения, чаты, черновики) + Coil. */
    fun clearFullCache(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                localCache.clearCurrentAccount()
                context.imageLoader.diskCache?.clear()
                context.imageLoader.memoryCache?.clear()
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(cacheCleared = true) }
            }
            refreshCacheSize()
        }
    }

    // --- Блокировка приложения (PIN) ---

    fun setAppLockPin(pin: String) {
        secureStorage.setAppLockPin(pin)
        _uiState.update { it.copy(appLockEnabled = true) }
    }

    fun disableAppLock() {
        secureStorage.disableAppLock()
        _uiState.update { it.copy(appLockEnabled = false) }
    }

    fun checkPin(pin: String): Boolean = try {
        secureStorage.checkAppLockPin(pin)
    } catch (_: Exception) {
        false
    }

    fun logout() {
        authRepository.logout()
    }
}
