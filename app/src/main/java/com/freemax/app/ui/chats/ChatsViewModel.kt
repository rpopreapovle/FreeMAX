package com.freemax.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.api.model.Chat
import com.freemax.api.protocol.ConnectionState
import com.freemax.app.core.network.NetworkMonitor
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatsUiState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selfAvatarUrl: String? = null,
    val selfName: String = "",
    /** Активный аккаунт: инициалы для шапки. */
    val accountInitials: String = "",
    val accountName: String = "",
    /** Сумма непрочитанных по всем чатам. */
    val unreadTotal: Int = 0,
    /** WebSocket активен (с задержкой, чтобы не мигать при реконнекте). */
    val online: Boolean = false,
    /** Есть ли на устройстве реальная сеть. */
    val networkAvailable: Boolean = true
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val maxClient: com.freemax.api.MaxClient,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatsUiState())
    val uiState: StateFlow<ChatsUiState> = _uiState.asStateFlow()

    // Короткие провалы сокета при реконнекте не должны выглядеть как офлайн.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val stableConnectionActive = maxClient.state
        .map { it.isActive() }
        .distinctUntilChanged()
        .transformLatest { active ->
            if (!active) delay(2500)
            emit(active)
        }

    init {
        observeChats()
        observeResponses()
        // Retry when AUTHENTICATED and chats still empty (covers cold start).
        // Socket Connected alone is not enough — server kills unauthed requests.
        viewModelScope.launch {
            authRepository.authState.collect { authed ->
                if (authed && chatRepository.chats.value.isEmpty()) {
                    kotlinx.coroutines.delay(800)
                    if (chatRepository.chats.value.isEmpty()) loadChats()
                }
            }
        }
        // Periodic retry up to 5 times (100% guarantee)
        viewModelScope.launch {
            repeat(6) { attempt ->
                kotlinx.coroutines.delay(2000L + attempt * 1000L)
                if (chatRepository.chats.value.isEmpty() && authRepository.isAuthed()) {
                    loadChats()
                }
            }
        }
        // После смены Wi-Fi/мобильной сети старый DNS/сокет может остаться
        // мёртвым. При появлении сети принудительно пересоздаём соединение.
        viewModelScope.launch {
            networkMonitor.available.collect { available ->
                if (!available) return@collect
                val state = maxClient.state.value
                if (state is ConnectionState.Disconnected ||
                    state is ConnectionState.Error ||
                    state is ConnectionState.Reconnecting
                ) {
                    delay(800)
                    maxClient.reconnectNow()
                }
            }
        }
    }

    fun reconnect() {
        try { maxClient.reconnectNow() } catch (_: Exception) {}
    }

    fun loadChats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            chatRepository.loadChats()
        }
    }

    fun loadMoreChats() {
        chatRepository.loadMoreChats()
    }

    fun openChat(chatId: Long) {
        chatRepository.openChat(chatId)
    }

    fun togglePin(chatId: Long) = chatRepository.togglePin(chatId)
    fun isPinned(chatId: Long): Boolean = chatRepository.isPinned(chatId)
    fun toggleMute(chatId: Long): Boolean = chatRepository.toggleMute(chatId)
    fun isMuted(chatId: Long): Boolean = chatRepository.isMuted(chatId)

    fun deleteChat(chatId: Long) = chatRepository.deleteChat(chatId, forAll = false)

    fun logout() {
        chatRepository.clear()
        authRepository.logout()
    }

    private fun observeChats() {
        viewModelScope.launch {
            combine(
                chatRepository.chats,
                chatRepository.isLoading,
                chatRepository.selfProfile,
                stableConnectionActive,
                networkMonitor.available
            ) { chats, isLoading, self, connectionActive, networkAvailable ->
                val account = try { authRepository.accounts().firstOrNull { it.viewerId == authRepository.activeAccountId() } } catch (_: Exception) { null }
                val displayName = account?.name?.takeIf { it.isNotBlank() }
                    ?: account?.phone?.takeIf { it.isNotBlank() }
                    ?: self?.name.orEmpty()
                val initials = displayName.trim().split(" ").filter { it.isNotBlank() }
                    .take(2).joinToString("") { it.first().uppercase() }
                ChatsUiState(
                    chats = chats,
                    isLoading = isLoading,
                    selfAvatarUrl = self?.photoUrl,
                    selfName = self?.name.orEmpty(),
                    accountInitials = initials.ifBlank { "MAX" },
                    accountName = displayName,
                    unreadTotal = chats.sumOf { it.newMessages },
                    online = connectionActive,
                    networkAvailable = networkAvailable
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private fun observeResponses() {
        viewModelScope.launch {
            authRepository.observeFrames().collect { frame ->
                chatRepository.handleFrame(frame)
            }
        }
    }
}
