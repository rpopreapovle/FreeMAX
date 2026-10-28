package com.freemax.app.ui.settings

import androidx.lifecycle.ViewModel
import com.freemax.app.core.storage.MaxAccount
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<MaxAccount> = emptyList(),
    val activeId: Long = -1L,
    val error: String? = null,
    val info: String? = null
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(load())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    private fun load() = AccountsUiState(
        accounts = secureStorage.getAccounts(),
        activeId = secureStorage.getActiveAccountId()
    )

    private fun refresh() {
        _uiState.update { load().copy(error = it.error, info = it.info) }
    }

    fun switchAccount(viewerId: Long) {
        authRepository.switchAccount(viewerId)
        refresh()
        _uiState.update { it.copy(info = "Активный аккаунт переключён") }
    }

    /**
     * Добавляет аккаунт. Принимает либо JSON из браузера
     * ({"token":"...","viewerId":123}), либо токен + id, введённые вручную.
     */
    fun addAccount(rawJson: String, token: String, viewerId: Long): Boolean {
        val parsed = parseAuth(rawJson, token, viewerId)
        if (parsed == null) {
            _uiState.update { it.copy(error = "Не понял токен: нужен JSON {\"token\":\"...\",\"viewerId\":123} или токен + ID") }
            return false
        }
        if (!authRepository.addAccount(parsed.second, parsed.first)) {
            _uiState.update { it.copy(error = "Пустой токен или неверный ID") }
            return false
        }
        authRepository.switchAccount(parsed.first)
        refresh()
        _uiState.update { it.copy(error = null, info = "Аккаунт добавлен: ${parsed.first}") }
        return true
    }

    fun updateToken(viewerId: Long, token: String) {
        if (token.isBlank()) {
            _uiState.update { it.copy(error = "Токен пустой") }
            return
        }
        authRepository.updateToken(viewerId, token)
        refresh()
        _uiState.update { it.copy(error = null, info = "Токен обновлён, если аккаунт активный — переподключаемся") }
    }

    fun rename(viewerId: Long, name: String) {
        authRepository.renameAccount(viewerId, name.trim())
        refresh()
    }

    fun remove(viewerId: Long) {
        authRepository.removeAccount(viewerId)
        refresh()
        _uiState.update { it.copy(info = "Аккаунт удалён") }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(error = null, info = null) }
    }

    /** Разбор вставленного из браузера значения localStorage "__oneme_auth". */
    private fun parseAuth(rawJson: String, token: String, viewerId: Long): Pair<Long, String>? {
        val json = rawJson.trim()
        if (json.startsWith("{")) {
            try {
                val obj = org.json.JSONObject(json)
                val t = obj.optString("token", "").ifBlank { obj.optString("auth", "") }
                val id = when (val v = obj.opt("viewerId") ?: obj.opt("uid")) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (t.isNotBlank() && id > 0L) return id to t
            } catch (_: Exception) {
                // не JSON — попробуем как «токен:viewerId»
            }
        }
        // «token viewerId» одной строкой
        val parts = json.split(Regex("[\\s,;:]+")).filter { it.isNotBlank() }
        if (parts.size == 2) {
            val id = parts[1].toLongOrNull()
            if (id != null && id > 0L && parts[0].length > 20) return id to parts[0]
        }
        if (token.isNotBlank() && viewerId > 0L) return viewerId to token.trim()
        if (json.isNotBlank() && viewerId > 0L) return viewerId to json
        return null
    }
}
