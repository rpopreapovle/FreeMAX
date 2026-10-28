package com.freemax.app.data.repository

import android.util.Log
import com.freemax.api.MaxClient
import com.freemax.api.model.Chat
import com.freemax.api.protocol.Opcodes
import com.freemax.api.protocol.WsFrame
import com.freemax.app.core.storage.SecureStorage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SearchEntry {
    data class ChannelEntry(val chat: Chat, val description: String = "") : SearchEntry
    data class PersonEntry(val contact: ContactInfo) : SearchEntry
    data class LocalChatEntry(val chat: Chat) : SearchEntry
    /** Найденное сообщение (глобальный поиск или поиск по чату). */
    data class MessageEntry(
        val chat: Chat?,
        val chatId: Long,
        val chatTitle: String,
        val messageId: Long,
        val text: String,
        val time: Long,
        val senderName: String?
    ) : SearchEntry
}

@Singleton
class SearchRepository @Inject constructor(
    private val client: MaxClient,
    private val chatRepository: ChatRepository,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "SearchRepo"
    }

    private val _results = MutableStateFlow<List<SearchEntry>>(emptyList())
    val results: StateFlow<List<SearchEntry>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /** История запросов (локально). */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var lastQuery = ""

    fun search(query: String) {
        lastQuery = query.trim()
        if (lastQuery.length < 2) {
            _results.value = localMatches(lastQuery)
            _isSearching.value = false
            return
        }
        // Instant local matches first
        _results.value = localMatches(lastQuery)
        _isSearching.value = true
        if (isPhoneQuery(lastQuery)) {
            // По номеру телефона сервер ищет отдельным запросом
            client.lookupPhone(normalizePhone(lastQuery))
        } else {
            client.searchAll(lastQuery, 40)
        }
    }

    /** Запрос попадёт в локальную историю. */
    fun rememberQuery(query: String) {
        secureStorage.addSearchHistory(query)
        _history.value = secureStorage.getSearchHistory()
    }

    fun clearHistory() {
        secureStorage.clearSearchHistory()
        _history.value = emptyList()
    }

    /**
     * Поиск сообщений внутри одного чата (opcode 73).
     * Результаты приходят отдельным списком в [chatResults].
     */
    private val _chatResults = MutableStateFlow<List<SearchEntry.MessageEntry>>(emptyList())
    val chatResults: StateFlow<List<SearchEntry.MessageEntry>> = _chatResults.asStateFlow()

    private val _isChatSearching = MutableStateFlow(false)
    val isChatSearching: StateFlow<Boolean> = _isChatSearching.asStateFlow()

    private var chatSearchId: Long = 0L
    private var lastChatQuery = ""

    fun searchInChat(chatId: Long, query: String) {
        val q = query.trim()
        chatSearchId = chatId
        lastChatQuery = q
        if (q.length < 2) {
            _chatResults.value = emptyList()
            _isChatSearching.value = false
            return
        }
        _isChatSearching.value = true
        client.searchMessages(chatId, q, 40)
    }

    fun clearChatSearch() {
        chatSearchId = 0L
        lastChatQuery = ""
        _chatResults.value = emptyList()
        _isChatSearching.value = false
    }

    /** Похоже на номер телефона: только цифры/разделители, минимум 10 цифр. */
    private fun isPhoneQuery(q: String): Boolean {
        val digits = q.count { it.isDigit() }
        return digits >= 10 && q.none { it.isLetter() }
    }

    /** 8XXXXXXXXXX → 7XXXXXXXXXX (формат, который ждёт сервер). */
    private fun normalizePhone(q: String): String {
        var digits = q.filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1)
        }
        return digits
    }

    fun clear() {
        lastQuery = ""
        _results.value = emptyList()
        _isSearching.value = false
    }

    private fun localMatches(query: String): List<SearchEntry> {
        val chats = chatRepository.chats.value
        if (query.isBlank()) return chats.map { SearchEntry.LocalChatEntry(it) }
        val q = query.lowercase()
        return chats.filter { it.title.lowercase().contains(q) }
            .map { SearchEntry.LocalChatEntry(it) }
    }

    private fun chatTitleOf(chatId: Long, rawChat: Map<String, Any?>?): String {
        val chat = chatRepository.chats.value.firstOrNull {
            it.id == chatId || it.cid == chatId
        }
        if (chat != null && chat.title.isNotBlank() && chat.title != "Диалог") return chat.title
        rawChat?.let {
            chatRepository.parseChat(it)?.let { parsed ->
                if (parsed.title.isNotBlank()) return parsed.title
            }
        }
        return chat?.title ?: "Чат"
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleFrame(frame: WsFrame) {
        // Поиск по сообщениям одного чата (73)
        if (frame.opcode == Opcodes.SEARCH_MESSAGES) {
            _isChatSearching.value = false
            val payload = frame.payload ?: return
            if (payload.containsKey("error")) {
                Log.d(TAG, "chat search error: ${payload["message"]}")
                return
            }
            val result = payload["result"] as? List<*> ?: return
            val entries = result.mapNotNull { item ->
                val m = (item as? Map<*, *>)?.get("message") as? Map<*, *> ?: return@mapNotNull null
                val id = chatRepository.asLongPublic(m["id"] ?: m["mid"])
                if (id == 0L) return@mapNotNull null
                val sender = chatRepository.asLongPublic(m["sender"] ?: m["userId"])
                SearchEntry.MessageEntry(
                    chat = null,
                    chatId = chatSearchId,
                    chatTitle = chatTitleOf(chatSearchId, null),
                    messageId = id,
                    text = m["text"]?.toString().orEmpty(),
                    time = chatRepository.asLongPublic(m["time"]),
                    senderName = chatRepository.getContactName(sender)
                )
            }
            _chatResults.value = entries
            Log.d(TAG, "Chat search '$lastChatQuery': ${entries.size} messages")
            return
        }
        // Ответ на поиск по номеру телефона
        if (frame.opcode == Opcodes.LOOKUP_PHONE) {
            try {
                val payload = frame.payload
                val contactMap = payload?.get("contact") as? Map<String, Any?>
                if (contactMap == null) {
                    Log.d(TAG, "lookup phone: не найдено (${payload?.get("error")})")
                    _isSearching.value = false
                    return
                }
                chatRepository.contactInfoOf(contactMap)?.let { (_, info) ->
                    val locals = localMatches(lastQuery)
                    _results.value = locals.filterNot {
                        (it as? SearchEntry.PersonEntry)?.contact?.id == info.id
                    } + SearchEntry.PersonEntry(info)
                    Log.d(TAG, "lookup phone: найден ${info.name} (${info.id})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse phone lookup", e)
            }
            _isSearching.value = false
            return
        }
        if (frame.opcode != Opcodes.SEARCH_ALL) return
        val payload = frame.payload ?: return
        try {
            if (payload.containsKey("error")) {
                Log.d(TAG, "search error: ${payload["message"]}")
                _isSearching.value = false
                return
            }
            val result = payload["result"] as? List<Map<String, Any?>> ?: emptyList()
            val entries = mutableListOf<SearchEntry>()
            result.forEach { entry ->
                val chatMap = entry["chat"] as? Map<String, Any?>
                if (chatMap != null) {
                    val chat = chatRepository.parseChat(chatMap)
                    if (chat != null) {
                        entries.add(
                            SearchEntry.ChannelEntry(
                                chat,
                                chatMap["description"]?.toString().orEmpty()
                            )
                        )
                    }
                }
                val contactMap = entry["contact"] as? Map<String, Any?>
                if (contactMap != null) {
                    chatRepository.contactInfoOf(contactMap)?.let { (_, info) ->
                        entries.add(SearchEntry.PersonEntry(info))
                    }
                }
                // Глобальный поиск может вернуть сообщения внутри чата
                val msgMap = entry["message"] as? Map<String, Any?>
                if (msgMap != null) {
                    val rawChat = entry["chat"] as? Map<String, Any?>
                    val cid = chatRepository.asLongPublic(rawChat?.get("id"))
                    val mid = chatRepository.asLongPublic(msgMap["id"] ?: msgMap["mid"])
                    if (mid != 0L) {
                        val sender = chatRepository.asLongPublic(msgMap["sender"] ?: msgMap["userId"])
                        entries.add(
                            SearchEntry.MessageEntry(
                                chat = null,
                                chatId = cid,
                                chatTitle = if (cid != 0L) chatTitleOf(cid, rawChat) else "Сообщение",
                                messageId = mid,
                                text = msgMap["text"]?.toString().orEmpty(),
                                time = chatRepository.asLongPublic(msgMap["time"]),
                                senderName = chatRepository.getContactName(sender)
                            )
                        )
                    }
                }
            }
            // Local matches on top, server results after (dedupe by chat id)
            val localIds = entries.mapNotNull { (it as? SearchEntry.ChannelEntry)?.chat?.id }.toSet()
            val combined = localMatches(lastQuery).filter {
                (it as? SearchEntry.LocalChatEntry)?.chat?.id !in localIds
            } + entries
            _results.value = combined
            _isSearching.value = false
            Log.d(TAG, "Search '$lastQuery': ${entries.size} server entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse search", e)
            _isSearching.value = false
        }
    }

    /** Existing DIALOG with this contact, if any (to open directly). */
    fun findDialogWith(contactId: Long): Chat? {
        return chatRepository.chats.value.firstOrNull { chat ->
            chat.type == 1 && chatRepository.dialogPeerId(chat) == contactId
        }
    }

    // Инициализация — в конце класса: handleFrame() трогает _chatResults/
    // _isChatSearching (объявлены выше), а коллектор стартует сразу.
    init {
        _history.value = secureStorage.getSearchHistory()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.observeFrames().collect { handleFrame(it) }
        }
    }
}
