package com.freemax.app.data.repository

import com.freemax.api.MaxClient
import com.freemax.api.protocol.ConnectionState
import com.freemax.api.protocol.WsFrame
import com.freemax.app.core.storage.MaxAccount
import com.freemax.app.core.storage.SecureStorage
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val client: MaxClient,
    private val secureStorage: SecureStorage,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository
) {
    val state: StateFlow<ConnectionState> = client.state
    val authState: StateFlow<Boolean> = client.authState

    fun isAuthed(): Boolean = client.isAuthed()

    fun isLoggedIn(): Boolean = secureStorage.isLoggedIn()

    // ------------------------------------------------------------------
    // Мультиаккаунт
    // ------------------------------------------------------------------

    fun accounts(): List<MaxAccount> = secureStorage.getAccounts()

    fun activeAccountId(): Long = secureStorage.getActiveAccountId()

    /** Переключиться на другой сохранённый аккаунт: переподключаемся с его токеном. */
    fun switchAccount(viewerId: Long) {
        val account = secureStorage.getAccounts().firstOrNull { it.viewerId == viewerId } ?: return
        if (secureStorage.getActiveAccountId() == viewerId && client.isConnected()) return
        secureStorage.setActiveAccountId(viewerId)
        chatRepository.clear()
        messageRepository.clear()
        client.disconnect()
        client.connectWithToken(account.token, account.viewerId)
        connectIfNeeded()
    }

    /** Добавить/обновить аккаунт и сделать его активным. */
    fun addAccount(token: String, viewerId: Long, name: String = "", phone: String = ""): Boolean {
        if (token.isBlank() || viewerId <= 0L) return false
        secureStorage.upsertAccount(MaxAccount(viewerId = viewerId, token = token, name = name, phone = phone))
        if (secureStorage.getAccounts().size == 1) {
            secureStorage.setActiveAccountId(viewerId)
        }
        return true
    }

    /** Изменить токен аккаунта; если он активный — переподключиться. */
    fun updateToken(viewerId: Long, token: String) {
        if (token.isBlank()) return
        secureStorage.updateAccountToken(viewerId, token)
        if (secureStorage.getActiveAccountId() == viewerId) {
            chatRepository.clear()
            messageRepository.clear()
            client.disconnect()
            client.connectWithToken(token, viewerId)
        }
    }

    fun renameAccount(viewerId: Long, name: String) {
        secureStorage.updateAccountName(viewerId, name)
    }

    /** Удалить аккаунт; если он был активным — переключиться на следующий. */
    fun removeAccount(viewerId: Long) {
        val wasActive = secureStorage.getActiveAccountId() == viewerId
        secureStorage.removeAccount(viewerId)
        val next = secureStorage.getActiveAccount()
        if (next != null) {
            if (wasActive) switchAccount(next.viewerId)
        } else if (wasActive) {
            logout()
        }
    }

    private fun connectIfNeeded() {
        if (client.isConnected()) return
        val account = secureStorage.getActiveAccount()
        if (account != null) client.connectWithToken(account.token, account.viewerId) else client.connect()
    }

    fun connect() {
        if (!client.isConnected()) {
            val token = secureStorage.getToken()
            val userId = secureStorage.getUserId()
            if (token != null && userId > 0) {
                client.connectWithToken(token, userId)
            } else {
                client.connect()
            }
        }
    }

    fun disconnect() {
        client.disconnect()
    }

    /**
     * Login with saved token from WebView.
     * Sends LOGIN with savedAuth — same as web client reconnect.
     */
    fun loginWithToken(): Int {
        val token = secureStorage.getToken() ?: return -1
        val userId = secureStorage.getUserId()
        if (userId <= 0) return -1
        return client.loginWithToken(token, userId)
    }

    /**
     * Save auth token from WebView login.
     */
    fun saveWebViewToken(token: String, viewerId: Long) {
        secureStorage.saveToken(token)
        secureStorage.saveUserId(viewerId)
    }

    fun requestCode(phone: String): Int {
        secureStorage.saveAuthPhone(phone)
        return client.requestCode(phone)
    }

    fun verifyCode(code: String): Int {
        val trackId = secureStorage.getTrackId() ?: return -1
        return client.verifyCode(trackId, code)
    }

    fun register(firstName: String, lastName: String): Int {
        val trackId = secureStorage.getTrackId() ?: return -1
        return client.register(trackId, firstName, lastName)
    }

    fun loginPassword(password: String): Int {
        val trackId = secureStorage.getTrackId() ?: return -1
        return client.loginPassword(trackId, password)
    }

    fun saveSession(token: String, sessionKey: String, userId: Long) {
        secureStorage.saveToken(token)
        secureStorage.saveSessionKey(sessionKey)
        secureStorage.saveUserId(userId)
    }

    fun saveTrackId(trackId: String) {
        secureStorage.saveTrackId(trackId)
    }

    fun logout() {
        secureStorage.clearAll()
        client.disconnect()
        try {
            chatRepository.clear()
        } catch (_: Exception) {}
        try {
            messageRepository.clear()
        } catch (_: Exception) {}
    }

    fun observeFrames(): Flow<WsFrame> = client.observeFrames()
}
