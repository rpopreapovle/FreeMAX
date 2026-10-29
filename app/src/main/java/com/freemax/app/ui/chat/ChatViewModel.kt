package com.freemax.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freemax.api.model.Attachment
import com.freemax.api.model.Chat
import com.freemax.api.model.Message
import com.freemax.api.MaxClient
import com.freemax.app.core.storage.OutboxItem
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MediaRepository
import com.freemax.app.data.repository.MessageRepository
import com.freemax.app.data.repository.attachmentKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Снимок потоков, которые нужны экрану чата (одним combine). */
private data class MessagesFeed(
    val messages: List<Message>,
    val loadingChats: Set<Long>,
    val chats: List<Chat>,
    val outbox: List<OutboxItem>,
    val online: Boolean
)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val chatId: Long = 0,
    val chatTitle: String = "",
    val chatIconUrl: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val viewerId: Long = 0,
    val senderNames: Map<Long, String> = emptyMap(),
    /** Аватары авторов для групповых сообщений (id -> url). */
    val senderAvatars: Map<Long, String> = emptyMap(),
    val isGroup: Boolean = false,
    val replyTo: Message? = null,
    val pendingAttaches: List<com.freemax.app.data.repository.PendingAttach> = emptyList(),
    val isUploading: Boolean = false,
    val uploadProgress: Float? = null,
    val typingText: String? = null,
    val headerTarget: HeaderTarget? = null,
    /** Подпись в шапке: «онлайн» / «был(а) недавно» / «печатает…». */
    val chatStatusText: String? = null,
    /** Сохранённый черновик чата, подставляется в поле ввода. */
    val draft: String = "",
    /** Первое непрочитанное сообщение — перед ним рисуем разделитель. */
    val firstUnreadId: Long? = null,
    /** Ошибка загрузки (нет сети и нет кэша). */
    val loadError: String? = null,
    /** Избранное (Saved Messages): шапка/аватар показывают закладку. */
    val isSavedMessages: Boolean = false,
    /** Одноразовое сообщение для снэкбара (скачивание файла и т.п.). */
    val toast: String? = null,
    /** Растёт при каждой новой toast-строке — триггер для SnackbarHost. */
    val toastId: Long = 0L,
    /** Ключ вложения, которое сейчас скачивается (индикатор в пузыре). */
    val busyAttachmentKey: String? = null,
    /** Прогресс текущего скачивания 0..1 (для линейного прогресс-бара). */
    val attachmentProgress: Float? = null
)

sealed interface HeaderTarget {
    data class Contact(val contactId: Long) : HeaderTarget
    data class Group(val chatId: Long) : HeaderTarget
    data object Self : HeaderTarget
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val maxClient: MaxClient,
    private val secureStorage: SecureStorage,
    private val localCache: com.freemax.app.core.storage.LocalCache,
    private val uploadRepository: com.freemax.app.data.repository.UploadRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private fun normalizeId(id: Long): Long {
        // Only DIALOG-style ids 0xD2XXXXXXXX map to low32 signed, rest keep as is.
        if (id > 0xFFFFFFFFL) {
            val high = (id ushr 32) and 0xFF
            if (high == 0xD2L) {
                val low = id and 0xFFFFFFFFL
                return if (low and 0x80000000L != 0L) low or -0x100000000L else low
            }
        }
        return id
    }
    private val rawChatId: Long = savedStateHandle.get<Long>("chatId") ?: 0L
    private val chatId: Long = normalizeId(rawChatId)

    private val pendingMessages = mutableListOf<Message>()
    private var dividerArmed = false

    private val _uiState = MutableStateFlow(ChatUiState(chatId = chatId, viewerId = normalizeId(secureStorage.getUserId())))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    // Self contactId from profile (for isFromMe in DIALOGs)
    private var selfContactId: Long = normalizeId(secureStorage.getUserId())

    // --- Черновики/«печатает»/реакции: объявлены ДО init, потому что init
    // запускает коллекторы, которые читают эти поля синхронно (StateFlow
    // эмитит текущее значение сразу при подписке).
    private var draftJob: kotlinx.coroutines.Job? = null
    private var lastTypingSentAt = 0L
    private var lastPresenceRequestAt = 0L
    private var lastReactionsRequestAt = 0L
    private val reactionsRequestedFor = mutableSetOf<Long>()

    init {
        restorePending()
        _uiState.update { it.copy(draft = localCache.loadDraft(chatId)) }
        loadMessages()
        observeMessages()
        observeResponses()
        observeProfile()
        observeTyping()
        observePresenceAndReactions()
        // Single retry when auth arrives late (the repository itself waits for
        // auth inside loadMessages; no repeat hammer — it caused request storms
        // when several chats were opened and their shared list kept clearing).
        viewModelScope.launch {
            authRepository.authState.collect { authed ->
                if (authed && _uiState.value.messages.isEmpty()
                    && !messageRepository.isChatLoading(chatId)
                    && !messageRepository.isChatLoaded(chatId)
                ) {
                    kotlinx.coroutines.delay(800)
                    if (_uiState.value.messages.isEmpty()
                        && !messageRepository.isChatLoading(chatId)
                        && !messageRepository.isChatLoaded(chatId)
                    ) loadMessages()
                }
            }
        }
    }

    /** Восстанавливаем неотправленные из очереди — они пережили рестарт/офлайн. */
    private fun restorePending() {
        try {
            val queued = messageRepository.outboxMessagesFor(chatId)
                .map { it.copy(userId = _uiState.value.viewerId) }
            if (queued.isNotEmpty()) {
                synchronized(pendingMessages) { pendingMessages.addAll(queued) }
                android.util.Log.d("ChatVM", "Restored ${queued.size} pending messages for chat=$chatId")
            }
        } catch (_: Exception) {}
    }

    private fun observeProfile() {
        viewModelScope.launch {
            // Profile comes via AUTH_SYNC, extract self contactId
            authRepository.observeFrames().collect { frame ->
                if (frame.opcode == com.freemax.api.protocol.Opcodes.AUTH_SYNC) {
                    val profile = frame.payload?.get("profile") as? Map<*, *>
                    val contact = profile?.get("contact") as? Map<*, *>
                    val cid = contact?.get("id")
                    selfContactId = normalizeId(when (cid) {
                        is Number -> cid.toLong()
                        is ByteArray -> {
                            var r = 0L; for (b in cid) r = (r shl 8) or (b.toInt() and 0xFF).toLong()
                            // Handle D2 prefix
                            if (cid.isNotEmpty() && cid[0].toInt() and 0xFF == 0xD2 && cid.size == 5) {
                                var rr = 0L; for (i in 1 until 5) rr = (rr shl 8) or (cid[i].toInt() and 0xFF).toLong()
                                if (rr and 0x80000000L != 0L) rr or -0x100000000L else rr
                            } else r
                        }
                        else -> 0L
                    })
                    if (selfContactId != 0L) {
                        _uiState.update { it.copy(viewerId = selfContactId) }
                    }
                }
            }
        }
    }

    fun loadMessages() {
        dividerArmed = true
        _uiState.update { it.copy(isLoading = true, firstUnreadId = null) }
        // Clear the list badge immediately when opening the chat
        try { chatRepository.markChatRead(chatId) } catch (_: Exception) {}
        viewModelScope.launch {
            try { maxClient.subscribeChat(chatId) } catch (_: Exception) {}
        }
        // Synchronous: the repository sets its per-chat loading flag immediately,
        // so concurrent collectors/retries can't fire a duplicate request.
        messageRepository.loadMessages(chatId)
    }

    fun loadMore() {
        val loading = messageRepository.isChatLoading(chatId)
        val exhausted = messageRepository.isExhausted(chatId)
        val oldest = _uiState.value.messages.filter { it.time > 0L }.minOfOrNull { it.time }
        android.util.Log.d("ChatVM", "loadMore chat=$chatId loading=$loading exhausted=$exhausted oldest=$oldest shown=${_uiState.value.messages.size}")
        if (loading || exhausted) return
        if (oldest == null) return
        messageRepository.loadMore(chatId, oldest)
    }

    fun setReply(message: Message?) {
        _uiState.update { it.copy(replyTo = message) }
    }

    fun addAttaches(uris: List<android.net.Uri>, voiceDurationMs: Long = 0L) {
        if (uris.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val resolved = uris.mapNotNull { uri ->
                uploadRepository.resolve(uri)?.let { rf ->
                    val kind = when {
                        rf.mime.startsWith("image/") -> com.freemax.app.data.repository.AttachKind.PHOTO
                        rf.mime.startsWith("video/") -> com.freemax.app.data.repository.AttachKind.VIDEO
                        rf.mime.startsWith("audio/") -> com.freemax.app.data.repository.AttachKind.AUDIO
                        else -> com.freemax.app.data.repository.AttachKind.FILE
                    }
                    // Resolve bytes now (with photo downscale) so sending is instant later
                    com.freemax.app.data.repository.PendingAttach(
                        uri, kind, rf.mime, rf.name, rf.bytes,
                        durationMs = if (kind == com.freemax.app.data.repository.AttachKind.AUDIO) voiceDurationMs else 0L
                    )
                }
            }
            if (resolved.isNotEmpty()) {
                _uiState.update { it.copy(pendingAttaches = it.pendingAttaches + resolved) }
            }
        }
    }

    fun removeAttach(uri: android.net.Uri) {
        _uiState.update { it.copy(pendingAttaches = it.pendingAttaches.filter { a -> a.uri != uri }) }
    }

    // --- Реакции ---

    fun toggleReaction(message: Message, emoji: String) {
        if (message.id == 0L || emoji.isBlank()) return
        messageRepository.toggleReaction(chatId, message.id, emoji)
    }

    // --- Редактирование ---

    fun editMessage(message: Message, newText: String) {
        if (message.id == 0L || newText.isBlank() || newText == message.text) return
        messageRepository.editMessage(chatId, message.id, newText)
    }

    // --- Черновики и «печатает» ---

    /** Текст меняется: сохраняем черновик (с дебаунсом) и шлём индикатор набора. */
    fun onDraftChange(text: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(600)
            try { localCache.saveDraft(chatId, text) } catch (_: Exception) {}
        }
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastTypingSentAt > 4000) {
            lastTypingSentAt = now
            try { maxClient.sendTyping(chatId) } catch (_: Exception) {}
        }
    }

    fun clearDraft() {
        draftJob?.cancel()
        _uiState.update { it.copy(draft = "") }
        try { localCache.saveDraft(chatId, "") } catch (_: Exception) {}
    }

    /** Присутствие собеседника + фоновая догрузка реакций для видимых сообщений. */
    private fun observePresenceAndReactions() {
        viewModelScope.launch {
            messageRepository.messages.collect { msgs ->
                val ids = msgs
                    .filter { normalizeId(it.chatId) == normalizeId(chatId) && it.id != 0L && it.reactions.isEmpty() }
                    .map { it.id }
                val fresh = ids.filter { it !in reactionsRequestedFor }
                val now = System.currentTimeMillis()
                if (fresh.isNotEmpty() && now - lastReactionsRequestAt > 4000) {
                    lastReactionsRequestAt = now
                    val batch = fresh.take(50)
                    reactionsRequestedFor.addAll(batch)
                    messageRepository.loadReactionsFor(chatId, batch)
                }
            }
        }
        viewModelScope.launch {
            combine(
                chatRepository.online,
                chatRepository.presence,
                chatRepository.chats
            ) { online, presence, chats -> Triple(online, presence, chats) }
                .collect { (online, presence, chats) ->
                    val normChatId = normalizeId(chatId)
                    val chat = chats.find { normalizeId(it.id) == normChatId || normalizeId(it.cid) == normChatId }
                    val peer = chat?.takeIf { it.type == 1 }?.let { chatRepository.dialogPeerId(it) }
                    if (peer != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastPresenceRequestAt > 30_000) {
                            lastPresenceRequestAt = now
                            try { chatRepository.requestPresence(listOf(peer)) } catch (_: Exception) {}
                        }
                        val text = when {
                            peer in online -> "онлайн"
                            (presence[peer] ?: 0L) > 0L -> "был(а) в сети ${formatSeen(presence[peer]!!)}"
                            else -> null
                        }
                        _uiState.update { it.copy(chatStatusText = text) }
                    }
                }
        }
    }

    private fun formatSeen(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 0L -> "только что"
            diff < 60_000L -> "только что"
            diff < 3_600_000L -> "${diff / 60_000L} мин назад"
            diff < 24 * 3_600_000L ->
                java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date(ts))
            else ->
                java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(ts))
        }
    }

    fun sendMessage(text: String) {
        val attaches = _uiState.value.pendingAttaches
        val replyTo = _uiState.value.replyTo
        if (text.isBlank() && attaches.isEmpty()) return
        val cid = System.currentTimeMillis()
        // Local preview attachments so the bubble renders instantly (real urls come after reload)
        val localPreview = attaches.map { p ->
            when (p.kind) {
                com.freemax.app.data.repository.AttachKind.PHOTO ->
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_PHOTO,
                        photoUrl = p.uri.toString(), url = p.uri.toString()
                    )
                com.freemax.app.data.repository.AttachKind.VIDEO ->
                    com.freemax.api.model.Attachment(type = com.freemax.api.model.Attachment.TYPE_VIDEO)
                com.freemax.app.data.repository.AttachKind.AUDIO ->
                    com.freemax.api.model.Attachment(type = com.freemax.api.model.Attachment.TYPE_AUDIO)
                com.freemax.app.data.repository.AttachKind.FILE ->
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_FILE, name = p.name
                    )
            }
        }
        // Optimistic UI: показываем сразу с «часиками»; серверное эхо заменит
        // состояние на ✓, read-mark — на ✓✓, офлайн — на «не отправлено».
        val tracking = Message(
            id = cid,
            mid = 0L,
            chatId = chatId,
            userId = _uiState.value.viewerId,
            text = text,
            time = cid,
            cid = cid,
            replyTo = replyTo?.id ?: 0L,
            state = Message.STATE_UNKNOWN,
            attachments = localPreview,
            isForward = replyTo != null,
            forwardType = if (replyTo != null) "REPLY" else null,
            quoteText = replyTo?.text?.takeIf { it.isNotBlank() }
        )
        pendingMessages.add(tracking)
        _uiState.update {
            it.copy(messages = it.messages + tracking, replyTo = null, pendingAttaches = emptyList(), draft = "")
        }
        clearDraft()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val wireAttaches = mutableListOf<Map<String, Any?>>()
                if (attaches.isNotEmpty()) {
                    _uiState.update { it.copy(isUploading = true, uploadProgress = 0f) }
                    var lastShown = 0f
                    // Bytes were already resolved (and photos downscaled) at pick time
                    val totalBytes = attaches.sumOf { (it.bytes?.size ?: 0).toLong() }.coerceAtLeast(1L)
                    var sentBase = 0L
                    for (p in attaches) {
                        val bytes = p.bytes
                            ?: uploadRepository.resolve(p.uri, p.mime)?.bytes
                        if (bytes == null) {
                            android.util.Log.e("ChatVM", "resolve failed for ${p.uri}")
                            continue
                        }
                        val mime = p.mime ?: "application/octet-stream"
                        val rf = com.freemax.app.data.repository.UploadRepository.ResolvedFile(
                            bytes, mime, p.name ?: "file"
                        )
                        var durationMs = 0L
                        if (p.kind == com.freemax.app.data.repository.AttachKind.AUDIO) {
                            durationMs = p.durationMs.takeIf { it > 0L }
                                ?: uploadRepository.audioDurationMs(p.uri)
                        }
                        val fileTotal = bytes.size.toLong()
                        val wire = uploadRepository.upload(p.kind, rf, cid, durationMs) { sent, _ ->
                            val frac = ((sentBase + sent).toFloat() / totalBytes).coerceIn(0f, 1f)
                            if (frac - lastShown > 0.03f || frac >= 1f) {
                                lastShown = frac
                                _uiState.update { it.copy(uploadProgress = frac) }
                            }
                        }
                        android.util.Log.d("ChatVM", "upload result keys=${wire?.keys}")
                        if (wire != null) {
                            wireAttaches.add(wire)
                            sentBase += fileTotal
                            _uiState.update { it.copy(uploadProgress = (sentBase.toFloat() / totalBytes).coerceIn(0f, 1f)) }
                        }
                    }
                    _uiState.update { it.copy(isUploading = false, uploadProgress = null) }
                }
                // If uploads failed and no text — restore pending so nothing is lost silently
                if (attaches.isNotEmpty() && wireAttaches.isEmpty() && text.isBlank()) {
                    pendingMessages.removeAll { it.cid == cid }
                    _uiState.update {
                        it.copy(
                            messages = it.messages.filter { m -> m.cid != cid },
                            pendingAttaches = attaches
                        )
                    }
                    return@launch
                }
                messageRepository.sendMessage(chatId, text, replyToId = replyTo?.id?.takeIf { id -> id != 0L }, attaches = wireAttaches, cid = cid)
                // Safety net: if server echo never matches, reload so no eternal hourglass
                kotlinx.coroutines.delay(6000)
                val stillPending = synchronized(pendingMessages) {
                    pendingMessages.any { it.cid == cid && (it.state == Message.STATE_UNKNOWN || it.state == Message.STATE_NOT_SENT) }
                }
                if (stillPending && maxClient.isConnected()) {
                    messageRepository.loadMessages(chatId)
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isUploading = false) }
            }
        }
    }

    /** Тап по «не отправлено»: повтор с тем же cid. */
    fun retryMessage(message: Message) {
        if (message.cid == 0L || message.text.isBlank()) return
        pendingMessages.replaceAll {
            if (it.cid == message.cid) it.copy(state = Message.STATE_UNKNOWN) else it
        }
        _uiState.update { st ->
            st.copy(messages = st.messages.map { m ->
                if (m.cid == message.cid && m.state == Message.STATE_NOT_SENT) {
                    m.copy(state = Message.STATE_UNKNOWN)
                } else m
            })
        }
        messageRepository.retryPending(message.cid)
    }

    private fun handleSentMessage(payload: Map<String, Any?>) {
        // Сервер эхо приходит как {chatId, message:{id,cid,text}, ...} — id/cid
        // лежат внутри message, поэтому смотрим и наружу, и внутрь.
        val inner = payload["message"] as? Map<String, Any?>
        fun pick(vararg keys: String): Any? {
            for (k in keys) {
                payload[k]?.let { return it }
                inner?.get(k)?.let { return it }
            }
            return null
        }
        val msgId = (pick("messageId", "mid", "id") as? Number)?.toLong() ?: 0L
        val cid = (pick("cid", "clientMsgId", "tempId") as? Number)?.toLong() ?: 0L
        val text = pick("text")?.toString()
        if (msgId == 0L && cid == 0L) return
        fun markSent(m: Message): Message = m.copy(
            mid = if (msgId != 0L) msgId else m.mid,
            id = if (msgId != 0L) msgId else m.id,
            state = Message.STATE_SENT
        )
        var updated = false
        for (i in pendingMessages.indices) {
            val p = pendingMessages[i]
            if ((cid != 0L && (p.cid == cid || p.id == cid)) || (msgId != 0L && text != null && p.text == text)) {
                pendingMessages[i] = markSent(p)
                updated = true
                break
            }
        }
        if (!updated) {
            val idx = pendingMessages.indexOfLast { it.state == Message.STATE_NOT_SENT || it.state == Message.STATE_UNKNOWN }
            if (idx != -1 && msgId != 0L) {
                pendingMessages[idx] = markSent(pendingMessages[idx])
                updated = true
            }
        }
        if (updated) {
            // Trigger UI refresh via _uiState
            _uiState.update { it.copy(messages = it.messages.map { m ->
                if (m.state != Message.STATE_SENT && m.state != Message.STATE_READ &&
                    ((cid != 0L && (m.cid == cid || m.id == cid)) || (msgId != 0L && text != null && m.text == text))
                ) markSent(m) else m
            }) }
        }
    }

    private fun markReadIfNeeded(messages: List<Message>) {
        if (messages.isEmpty()) return
        // Отмечаем прочитанным последнее входящее сообщение и на сервере:
        // opcode MARK_READ (50) сбрасывает серверный счётчик непрочитанных,
        // поэтому бейдж не возвращается после перерисовки списка.
        try {
            val viewer = normalizeId(_uiState.value.viewerId)
            val lastIncoming = messages.lastOrNull {
                it.id != 0L && normalizeId(it.userId) != viewer
            }
            if (lastIncoming != null) {
                messageRepository.markRead(chatId, lastIncoming.id)
            }
            messageRepository.markAllLocallyRead(chatId)
            chatRepository.markChatRead(chatId)
        } catch (_: Exception) {}
    }

    private fun observeMessages() {
        viewModelScope.launch {
            combine(
                messageRepository.messages,
                messageRepository.loadingChatsFlow,
                chatRepository.chats,
                messageRepository.outboxItems,
                maxClient.state
            ) { msgs, loadingChats, chats, outbox, conn ->
                MessagesFeed(msgs, loadingChats, chats, outbox, conn.isActive())
            }
                .collect { feed ->
                    val messages = feed.messages
                    val loadingChats = feed.loadingChats
                    val chats = feed.chats
                    val outbox = feed.outbox
                    val online = feed.online
                    val queuedCids = outbox.map { it.cid }.toSet()
                    val normChatId = normalizeId(chatId)
                    // This screen shows ONLY its own chat. The repository flow is
                    // shared, so a stale emission from another chat is dropped here.
                    val ownMessages = (messages ?: emptyList()).filter { normalizeId(it.chatId) == normChatId }
                    val mineLoading = normChatId in loadingChats
                    // Loader semantics: full-screen spinner only while OUR first
                    // page is in flight and nothing (not even cache) is shown.
                    // A background refresh with cached messages never flashes.
                    val showLoader = mineLoading && ownMessages.isEmpty() && pendingMessages.isEmpty()
                    val showMore = mineLoading && ownMessages.isNotEmpty()
                    val chat = chats.find { normalizeId(it.id) == normChatId || normalizeId(it.cid) == normChatId }
                    val isSavedMessages = chat?.isSavedMessages == true || normChatId == 0L
                    val title = chat?.title?.takeIf { it.isNotBlank() }
                        ?: if (isSavedMessages) "Избранное" else ""
                    val isGroup = chat?.type == 2 || chat?.type == 3
                    val safeMessagesForNames = ownMessages
                    // Resolve unknown sender names (e.g. reply authors) via LOAD_CONTACTS
                    if (isGroup) {
                        val unknown = safeMessagesForNames.map { normalizeId(it.userId) }
                            .filter { it != 0L && it != normalizeId(_uiState.value.viewerId) }
                        try { chatRepository.ensureContacts(unknown) } catch (_: Exception) {}
                    }
                    val senderNames = linkedMapOf<Long, String>()
                    val senderAvatars = linkedMapOf<Long, String>()
                    if (isGroup) {
                        val normViewer = normalizeId(_uiState.value.viewerId)
                        safeMessagesForNames.forEach { m ->
                            val normUid = normalizeId(m.userId)
                            if (normUid != 0L && normUid != normViewer) {
                                val contact = chatRepository.getContact(normUid)
                                    ?: chatRepository.getContact(m.userId)
                                val name = contact?.name?.takeIf { it.isNotBlank() }
                                val avatar = contact?.photoUrl?.takeIf { it.isNotBlank() }
                                if (name != null) {
                                    // Ключи и в «сыром», и в нормализованном виде:
                                    // сообщения приходят с разными форматами id.
                                    senderNames[m.userId] = name
                                    senderNames[normUid] = name
                                }
                                if (avatar != null) {
                                    senderAvatars[m.userId] = avatar
                                    senderAvatars[normUid] = avatar
                                }
                            }
                        }
                    }
                    // Merge pending messages that are not yet in server history (match by cid/id/text)
                    val safeMessages = ownMessages
                    val serverCids = safeMessages.map { it.cid }.toSet() + safeMessages.map { it.id }.toSet()
                    val serverTexts = safeMessages.map { it.text }.toSet()
                    val withPending = if (pendingMessages.isNotEmpty()) {
                        val stillPending = synchronized(pendingMessages) {
                            pendingMessages.filter { p ->
                                p.cid !in serverCids && p.id !in serverCids && p.text !in serverTexts
                            }
                        }
                        // Состояние берём из очереди: часики пока онлайн, «не отправлено»
                        // офлайн или после исчерпания попыток; эхо выставляет SENT/READ.
                        val toKeep = stillPending.map { p ->
                            val st = messageRepository.pendingState(p.cid)
                            when {
                                st != null -> p.copy(state = st)
                                p.cid in queuedCids ->
                                    p.copy(state = if (online) Message.STATE_UNKNOWN else Message.STATE_NOT_SENT)
                                else -> p
                            }
                        }
                        if (toKeep.isNotEmpty()) (safeMessages + toKeep).sortedBy { it.time } else safeMessages
                    } else safeMessages
                    // No fake "last message" from chat.description: while loading the
                    // screen shows only the spinner; an empty chat shows "Нет сообщений".
                    val displayMessages = withPending
                    // Clean pending that are now in server history (by cid/id/text)
                    if (safeMessages.isNotEmpty()) {
                        synchronized(pendingMessages) {
                            pendingMessages.removeAll { p -> p.cid in serverCids || p.id in serverCids || p.text in serverTexts }
                        }
                    }
                    // Auto-mark read if last message is from other
                    if (withPending.isNotEmpty()) {
                        // Запоминаем первое непрочитанное ДО пометки прочитанным
                        if (dividerArmed) {
                            val normSelf = normalizeId(_uiState.value.viewerId)
                            val firstUnread = withPending
                                .filter {
                                    it.id != 0L && normalizeId(it.userId) != normSelf &&
                                        it.state < Message.STATE_READ
                                }
                                .minByOrNull { it.time }
                            _uiState.update { it.copy(firstUnreadId = firstUnread?.id) }
                            dividerArmed = false
                        }
                        markReadIfNeeded(withPending)
                    }
                    // Header tap target: dialog peer / self / group
                    val normSelf = normalizeId(_uiState.value.viewerId)
                    val headerTarget: HeaderTarget? = when {
                        chat == null -> null
                        chat.type == 1 -> {
                            val peer = chatRepository.dialogPeerId(chat)
                            when {
                                peer == null && chat.isSavedMessages -> HeaderTarget.Self
                                peer == null -> null
                                peer == normSelf -> HeaderTarget.Self
                                else -> HeaderTarget.Contact(peer)
                            }
                        }
                        chat.type == 2 || chat.type == 3 -> HeaderTarget.Group(chat.id)
                        else -> null
                    }
                    if (headerTarget is HeaderTarget.Contact) {
                        try { chatRepository.ensureContacts(listOf(headerTarget.contactId)) } catch (_: Exception) {}
                    }
                    _uiState.update {
                        it.copy(messages = displayMessages, chatTitle = title, chatIconUrl = chat?.iconUrl, isLoading = showLoader, isLoadingMore = showMore, senderNames = senderNames, senderAvatars = senderAvatars, isGroup = isGroup, headerTarget = headerTarget, isSavedMessages = isSavedMessages)
                    }
                }
        }
    }

    private fun observeTyping() {
        viewModelScope.launch {
            combine(
                messageRepository.typing,
                chatRepository.chats
            ) { typing, chats -> typing to chats }
                .collect { (typing, chats) ->
                    val normChatId = normalizeId(chatId)
                    val users = typing[normChatId]
                        ?.keys
                        ?.filter { it != 0L && it != normalizeId(_uiState.value.viewerId) }
                        ?.distinct()
                        ?: emptyList()
                    // Resolve unknown names too (same cache as senders)
                    if (users.isNotEmpty()) {
                        try { chatRepository.ensureContacts(users) } catch (_: Exception) {}
                    }
                    val chat = chats.find { normalizeId(it.id) == normChatId || normalizeId(it.cid) == normChatId }
                    val isGroup = chat?.type == 2 || chat?.type == 3
                    val text = when {
                        users.isEmpty() -> null
                        !isGroup -> {
                            // Dialog: the typer can only be the interlocutor
                            val title = chat?.title?.takeIf { it.isNotBlank() && it != "Диалог" }
                            if (title != null) "$title печатает…" else "Печатает…"
                        }
                        users.size == 1 -> {
                            val name = chatRepository.getContactName(users[0])
                            if (name != null) "$name печатает…" else "Печатает…"
                        }
                        else -> {
                            val names = users.take(2).mapNotNull { chatRepository.getContactName(it) }
                            if (names.size == users.take(2).size && names.isNotEmpty()) {
                                names.joinToString(" и ") + " печатают…"
                            } else {
                                "Печатают…"
                            }
                        }
                    }
                    _uiState.update { it.copy(typingText = text) }
                }
        }
    }

    fun forwardMessage(msg: Message, targetChatId: Long) {
        if (msg.id == 0L) return
        val cid = System.currentTimeMillis()
        val tracking = Message(
            id = cid,
            mid = 0L,
            chatId = normalizeId(targetChatId),
            userId = _uiState.value.viewerId,
            text = "",
            time = cid,
            cid = cid,
            state = Message.STATE_NOT_SENT,
            attachments = msg.attachments,
            isForward = true,
            forwardType = "FORWARD",
            quoteText = msg.text.takeIf { it.isNotBlank() }
        )
        // Track for echo matching; shown once it lands in the target chat
        pendingMessages.add(tracking)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                messageRepository.forwardMessage(targetChatId, chatId, msg.id)
                kotlinx.coroutines.delay(6000)
                val stillPending = synchronized(pendingMessages) {
                    pendingMessages.any { it.cid == cid && it.state == Message.STATE_NOT_SENT }
                }
                if (stillPending && maxClient.isConnected()) {
                    messageRepository.loadMessages(normalizeId(targetChatId))
                }
            } catch (_: Exception) {}
        }
    }

    fun deleteMessage(msg: Message, forAll: Boolean) {
        if (msg.id == 0L) return
        // Drop optimistic copies immediately
        pendingMessages.removeAll { it.cid == msg.cid || it.id == msg.id }
        _uiState.update { st ->
            st.copy(messages = st.messages.filterNot { it.id == msg.id || (msg.cid != 0L && it.cid == msg.cid) })
        }
        messageRepository.deleteMessages(chatId, listOf(msg.id), forAll && normalizeId(msg.userId) == normalizeId(_uiState.value.viewerId))
    }

    // --- Вложения: скачать и открыть / сохранить в «Загрузки» ---

    private fun attachmentLabel(att: Attachment): String = att.name?.takeIf { it.isNotBlank() }
        ?: when {
            att.isVideo -> "видео"
            att.isAudio -> "аудио"
            att.isPhoto -> "фото"
            else -> "файл"
        }

    private fun showToast(text: String) {
        _uiState.update { it.copy(toast = text, toastId = it.toastId + 1) }
    }

    /** Тап по вложению: скачать в кэш и открыть во внешнем приложении. */
    fun openAttachment(message: Message, att: Attachment) {
        if (_uiState.value.busyAttachmentKey != null) return
        val key = attachmentKey(message.id, att)
        val label = attachmentLabel(att)
        _uiState.update { it.copy(busyAttachmentKey = key, attachmentProgress = 0f) }
        showToast("Скачиваю «$label»…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloaded = mediaRepository.fetch(message, att) { pct ->
                    _uiState.update { it.copy(attachmentProgress = pct / 100f) }
                }
                _uiState.update { it.copy(busyAttachmentKey = null, attachmentProgress = null) }
                try {
                    mediaRepository.open(downloaded)
                    showToast("Открываю «${downloaded.name}»")
                } catch (openError: Exception) {
                    // Нет приложения для ZIP/редкого формата — не теряем файл:
                    // сразу кладём его в системные «Загрузки».
                    mediaRepository.saveToDownloads(downloaded)
                    showToast("Нечем открыть — сохранено в Загрузки: ${downloaded.name}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatVM", "open attachment failed", e)
                _uiState.update { it.copy(busyAttachmentKey = null, attachmentProgress = null) }
                showToast("Не удалось открыть: ${e.message ?: "ошибка"}")
            }
        }
    }

    /** Долгий тап по вложению: скачать и положить в системные «Загрузки». */
    fun saveAttachment(message: Message, att: Attachment) {
        if (_uiState.value.busyAttachmentKey != null) return
        val key = attachmentKey(message.id, att)
        val label = attachmentLabel(att)
        _uiState.update { it.copy(busyAttachmentKey = key, attachmentProgress = 0f) }
        showToast("Скачиваю «$label»…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val local = mediaRepository.fetch(message, att) { pct ->
                    _uiState.update { it.copy(attachmentProgress = pct / 100f) }
                }
                mediaRepository.saveToDownloads(local)
                _uiState.update { it.copy(busyAttachmentKey = null, attachmentProgress = null) }
                showToast("Сохранено в Загрузки: ${local.name}")
            } catch (e: Exception) {
                android.util.Log.e("ChatVM", "save attachment failed", e)
                _uiState.update { it.copy(busyAttachmentKey = null, attachmentProgress = null) }
                showToast("Не удалось сохранить: ${e.message ?: "ошибка"}")
            }
        }
    }

    /** Snackbar показал текст — сбрасываем, чтобы не повторялся. */
    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    private fun observeResponses() {        // NOTE: frames are already consumed by MessageRepository's own collector.
        // Forwarding them here too handled every frame TWICE: the second pass no
        // longer found its load seq and misattributed messages to garbage chats.
        // This observer only maintains ViewModel-local pending states.
        viewModelScope.launch {
            authRepository.observeFrames().collect { frame ->
                when (frame.opcode) {
                    com.freemax.api.protocol.Opcodes.SEND_MESSAGE -> {
                        handleSentMessage(frame.payload ?: emptyMap())
                    }
                    com.freemax.api.protocol.Opcodes.NOTIF_MARK, com.freemax.api.protocol.Opcodes.MARK_READ, com.freemax.api.protocol.Opcodes.MARK_MESSAGES_READ -> {
                        val markId = frame.payload?.let {
                            (it["mark"] as? Number)?.toLong()
                                ?: (it["messageId"] as? Number)?.toLong()
                                ?: (it["mid"] as? Number)?.toLong() ?: 0L
                        } ?: 0L
                        if (markId != 0L) {
                            pendingMessages.replaceAll { if (it.id <= markId || it.cid <= markId) it.copy(state = Message.STATE_READ) else it }
                            // Also update displayed messages to READ
                            _uiState.update { st ->
                                st.copy(messages = st.messages.map { if (it.id <= markId) it.copy(state = Message.STATE_READ) else it })
                            }
                        }
                    }
                    else -> { /* consumed by MessageRepository */ }
                }
            }
        }
    }
}
