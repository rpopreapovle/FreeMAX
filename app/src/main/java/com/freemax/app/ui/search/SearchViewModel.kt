package com.freemax.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.SearchEntry
import com.freemax.app.data.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<SearchEntry> = emptyList(),
    val isSearching: Boolean = false,
    val history: List<String> = emptyList(),
    val chatResults: List<SearchEntry.MessageEntry> = emptyList(),
    val isChatSearching: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var debounce: Job? = null
    private var chatDebounce: Job? = null

    init {
        viewModelScope.launch {
            combine(
                searchRepository.results,
                searchRepository.isSearching,
                searchRepository.history,
                searchRepository.chatResults,
                searchRepository.isChatSearching
            ) { results, searching, history, chatResults, chatSearching ->
                SearchUiState(
                    query = _uiState.value.query,
                    results = results,
                    isSearching = searching,
                    history = history,
                    chatResults = chatResults,
                    isChatSearching = chatSearching
                )
            }.collect { _uiState.value = it }
        }
        // Show existing chats immediately
        searchRepository.search("")
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        debounce?.cancel()
        if (query.trim().length < 2) {
            searchRepository.search(query)
            return
        }
        debounce = viewModelScope.launch {
            delay(400)
            searchRepository.search(query)
        }
    }

    /** Запомнить запрос в локальной истории (при открытии результата/сабмите). */
    fun rememberQuery(query: String) {
        searchRepository.rememberQuery(query)
    }

    fun clearHistory() {
        searchRepository.clearHistory()
    }

    /** Поиск сообщений внутри чата (opcode 73). */
    fun onChatQueryChange(chatId: Long, query: String) {
        _uiState.update { it.copy(query = query) }
        chatDebounce?.cancel()
        chatDebounce = viewModelScope.launch {
            delay(400)
            searchRepository.searchInChat(chatId, query)
        }
    }

    fun findDialogWith(contactId: Long) = searchRepository.findDialogWith(contactId)

    /** Вступить в чат/канал по ссылке-приглашению (opcode 57). */
    fun joinByLink(link: String) {
        chatRepository.joinChat(link)
    }

    override fun onCleared() {
        searchRepository.clear()
        searchRepository.clearChatSearch()
    }
}
