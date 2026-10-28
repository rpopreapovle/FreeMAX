package com.freemax.app.data.repository

import android.util.Log
import com.freemax.api.MaxClient
import com.freemax.api.model.Message
import com.freemax.api.protocol.BinaryCodec
import com.freemax.api.protocol.Opcodes
import com.freemax.api.protocol.WsFrame
import com.freemax.app.core.storage.Outbox
import com.freemax.app.core.storage.OutboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val client: MaxClient,
    private val secureStorage: com.freemax.app.core.storage.SecureStorage,
    private val localCache: com.freemax.app.core.storage.LocalCache,
    private val outbox: Outbox,
    private val chatRepository: ChatRepository,
    private val notifier: com.freemax.app.core.notify.Notifier
) {
    // Last known read marks per chat, split by unit (ids vs timestamps) so they
    // never get mixed. Declared before init: init restores persisted marks into them.
    private val lastReadIdMark = mutableMapOf<Long, Long>()
    private val lastReadTsMark = mutableMapOf<Long, Long>()

    companion object {
        private const val TAG = "MsgRepo"

        /**
         * Сентинел «чат не открыт». 0 — валидный id чата «Избранное»
         * (Saved Messages), поэтому обычный 0 нельзя использовать как «ничего».
         */
        const val NO_CHAT = Long.MIN_VALUE
    }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Typing indicators: chatId -> (userId -> expiresAtMs). Pruned periodically.
    private val _typing = MutableStateFlow<Map<Long, Map<Long, Long>>>(emptyMap())
    val typing: StateFlow<Map<Long, Map<Long, Long>>> = _typing.asStateFlow()

    // Очередь исходящих (outbox): UI показывает такие сообщения как «отправляется/не отправлено».
    private val _outboxItems = MutableStateFlow<List<OutboxItem>>(emptyList())
    val outboxItems: StateFlow<List<OutboxItem>> = _outboxItems.asStateFlow()

    // cid, отправленные в сокет и ожидающие эхо SEND_MESSAGE.
    private val inflight = mutableSetOf<Long>()
    private val _inflightCids = MutableStateFlow<Set<Long>>(emptySet())
    val inflightCids: StateFlow<Set<Long>> = _inflightCids.asStateFlow()

    private fun refreshOutbox() {
        _outboxItems.value = outbox.items()
    }

    private fun setInflight(cid: Long, on: Boolean) {
        synchronized(inflight) { if (on) inflight.add(cid) else inflight.remove(cid) }
        _inflightCids.value = synchronized(inflight) { inflight.toSet() }
    }

    private fun isInflight(cid: Long): Boolean = synchronized(inflight) { cid in inflight }

    /** Настоящий AUTH_SYNC-ответ (профиль есть) — сессия авторизована заново. */
    private fun isAuthSyncFrame(frame: WsFrame): Boolean =
        frame.opcode == Opcodes.AUTH_SYNC &&
            frame.cmd == BinaryCodec.CMD_RESPONSE &&
            frame.payload?.containsKey("profile") == true

    /**
     * Новая авторизованная сессия: переотправляем очередь (тот же cid — сервер
     * дедуплицирует) и догружаем открытый чат. Инкрементальный список чатов и
     * пропущенные сообщения приходят в самом ответе AUTH_SYNC (ChatRepository/
     * MessageRepository обрабатывают его), отдельный SYNC_SECTION для чатов
     * сервер не принимает ("No enum constant: CHATS").
     */
    private fun onAuthSync() {
        Log.d(TAG, "AUTH_SYNC: flush outbox (${_outboxItems.value.size}) + sync")
        synchronized(inflight) { inflight.clear() }
        _inflightCids.value = emptySet()
        flushOutbox()
        val chat = currentChatId
        if (chat != NO_CHAT) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                delay(500)
                if (client.isAuthed()) loadMessages(chat, 40)
            }
        }
    }

    private var currentChatId: Long = NO_CHAT
    private var wasActive = false
    private var typingCleanerStarted = false

    // Per-chat cache: opening a chat shows its own history instantly and a
    // late response for another chat can never leak into the open screen.
    private val chatCache = mutableMapOf<Long, List<Message>>()
    private val loadingChats = mutableSetOf<Long>()
    private val _loadingChats = MutableStateFlow<Set<Long>>(emptySet())
    val loadingChatsFlow: StateFlow<Set<Long>> = _loadingChats.asStateFlow()
    private val loadedChats = mutableSetOf<Long>()
    private val exhaustedChats = mutableSetOf<Long>()
    // LOAD_MESSAGES seq -> (chatId, beforeTs, count). beforeTs==0 means initial load.
    private val loadSeqChat = mutableMapOf<Int, Triple<Long, Long, Int>>()

    private fun setChatLoading(chat: Long, loading: Boolean) {
        synchronized(loadingChats) {
            if (loading) loadingChats.add(chat) else loadingChats.remove(chat)
            _loadingChats.value = loadingChats.toSet()
            _isLoading.value = loadingChats.isNotEmpty()
        }
    }

    fun isChatLoading(chatId: Long): Boolean =
        synchronized(loadingChats) { normalizeId(chatId) in loadingChats }

    fun isChatLoaded(chatId: Long): Boolean =
        synchronized(loadedChats) { normalizeId(chatId) in loadedChats }

    fun isExhausted(chatId: Long): Boolean =
        synchronized(exhaustedChats) { normalizeId(chatId) in exhaustedChats }

    private fun markChatLoaded(chat: Long) {
        synchronized(loadedChats) { loadedChats.add(chat) }
        setChatLoading(chat, false)
    }

    private fun msgKey(m: Message): String =
        if (m.id != 0L) "id:${m.id}" else "c:${m.cid}:${m.time}:${m.text.hashCode()}"

    /** Сохраняет сообщения чата в локальный кэш (в фоне). */
    private fun persistChat(chat: Long) {
        val snapshot = cachedOrEmpty(chat)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                localCache.saveMessages(chat, snapshot)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to cache messages", e)
            }
        }
    }

    /**
     * Union-merge into the chat cache. Returns how many messages were added or
     * changed: an edit arrives as a NOTIF_MESSAGE push with the same id and a
     * new text, so same-id messages with different content must replace the
     * cached copy instead of being dropped as duplicates.
     */
    private fun mergeIntoCache(chat: Long, msgs: List<Message>): Int {
        synchronized(chatCache) {
            val old = chatCache[chat] ?: emptyList()
            val byKey = old.associateByTo(LinkedHashMap()) { msgKey(it) }
            var changed = 0
            for (msg in msgs) {
                val key = msgKey(msg)
                val prev = byKey[key]
                if (prev == null) {
                    byKey[key] = applyPendingReactions(chat, msg)
                    changed++
                } else if (prev.text != msg.text || prev.attachments != msg.attachments ||
                    prev.isForward != msg.isForward || prev.reactions != msg.reactions ||
                    (msg.edited && !prev.edited)
                ) {
                    // edited message — update in place, keep the read state
                    byKey[key] = msg.copy(
                        state = if (msg.state < prev.state) prev.state else msg.state,
                        edited = prev.edited || msg.edited || prev.text != msg.text,
                        reactions = if (msg.reactions.isEmpty()) prev.reactions else msg.reactions
                    )
                    changed++
                }
            }
            if (changed == 0) return 0
            chatCache[chat] = byKey.values.sortedBy { it.time }
            persistChat(chat)
            return changed
        }
    }

    private fun cachedOrEmpty(chat: Long): List<Message> =
        synchronized(chatCache) { chatCache[chat] ?: emptyList() }

    /** Mirror the visible list back into the open chat's cache entry. */
    private fun syncVisibleToCache() {
        val cur = currentChatId
        if (cur == NO_CHAT) return
        synchronized(chatCache) { chatCache[cur] = _messages.value }
        persistChat(cur)
    }

    fun loadMessages(chatId: Long, count: Int = 40) {
        val norm = normalizeId(chatId)
        currentChatId = norm
        synchronized(exhaustedChats) { exhaustedChats.remove(norm) }
        var cached = cachedOrEmpty(norm)
        if (cached.isEmpty()) {
            // офлайн-режим: показываем то, что сохранено на диске
            val fromDisk = localCache.loadMessages(norm)
            if (fromDisk.isNotEmpty()) {
                mergeIntoCache(norm, fromDisk)
                cached = cachedOrEmpty(norm)
            }
        }
        if (cached.isNotEmpty()) {
            // Instant: show our own cache, refresh silently — no loader flicker.
            _messages.value = applyStoredMarks(cached)
        } else {
            // Nothing cached: loader only, never a чужую историю or stale message.
            _messages.value = emptyList()
        }
        setChatLoading(norm, true)
        android.util.Log.d(TAG, "loadMessages chatId=$chatId normalized=$norm count=$count cached=${cached.size}")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < 25 && !client.isAuthed()) {
                delay(400)
                attempts++
            }
            if (!client.isAuthed()) {
                setChatLoading(norm, false)
                return@launch
            }
            try {
                val seq = client.loadMessages(norm, count)
                synchronized(loadSeqChat) {
                    loadSeqChat[seq] = Triple(norm, 0L, count)
                    if (loadSeqChat.size > 50) loadSeqChat.clear()
                }
            } catch (_: Exception) {
                setChatLoading(norm, false)
            }
        }
    }

    /** Pagination upward: page of messages older than [beforeTime] (server `from` is a timestamp). */
    fun loadMore(chatId: Long, beforeTime: Long, count: Int = 40) {
        val norm = normalizeId(chatId)
        if (beforeTime <= 0L || isChatLoading(norm) || isExhausted(norm)) return
        setChatLoading(norm, true)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < 25 && !client.isAuthed()) {
                delay(400)
                attempts++
            }
            if (!client.isAuthed()) {
                setChatLoading(norm, false)
                return@launch
            }
            try {
                val seq = client.loadMessages(norm, count, beforeTime)
                synchronized(loadSeqChat) {
                    loadSeqChat[seq] = Triple(norm, beforeTime, count)
                    if (loadSeqChat.size > 50) loadSeqChat.clear()
                }
            } catch (_: Exception) {
                setChatLoading(norm, false)
            }
        }
    }

    /**
     * Отправка исходящего. Возвращает cid. Текстовые сообщения всегда попадают
     * в durable-очередь (переживают офлайн/рестарт) и удаляются из неё только по
     * эху сервера с тем же cid. Вложения не персистим — их байты живут в сессии,
     * но держим запись для повтора, если сервер ещё не обработал файл.
     */
    private data class AttachResend(
        val chatId: Long,
        val text: String,
        val cid: Long,
        val replyToId: Long?,
        val attaches: List<Map<String, Any?>>,
        var attempts: Int = 0
    )

    private val attachResend = mutableMapOf<Long, AttachResend>()
    private val failedCids = mutableSetOf<Long>()

    fun sendMessage(
        chatId: Long,
        text: String,
        replyToId: Long? = null,
        attaches: List<Map<String, Any?>> = emptyList(),
        cid: Long = 0L
    ): Long {
        val normChat = normalizeId(chatId)
        val localCid = if (cid != 0L) cid else System.currentTimeMillis()
        if (attaches.isEmpty() && text.isNotBlank()) {
            outbox.upsert(
                OutboxItem(
                    chatId = normChat,
                    cid = localCid,
                    text = text,
                    replyTo = replyToId ?: 0L,
                    createdAt = System.currentTimeMillis(),
                    attempts = 0
                )
            )
            refreshOutbox()
            flushOutbox()
        } else if (client.isAuthed()) {
            setInflight(localCid, true)
            if (attaches.isNotEmpty()) {
                synchronized(attachResend) {
                    attachResend[localCid] = AttachResend(normChat, text, localCid, replyToId, attaches)
                    if (attachResend.size > 20) {
                        val oldest = attachResend.keys.minOrNull()
                        if (oldest != null) attachResend.remove(oldest)
                    }
                }
            }
            try {
                client.sendMessage(normChat, text, postId = localCid, replyToId = replyToId, attaches = attaches)
            } catch (e: Exception) {
                setInflight(localCid, false)
                Log.e(TAG, "sendMessage failed", e)
            }
        } else if (attaches.isNotEmpty()) {
            // Офлайн: вложение не отправить, но показываем «не отправлено» и
            // держим байты для ручного повтора (тап по сообщению).
            synchronized(attachResend) {
                attachResend[localCid] = AttachResend(normChat, text, localCid, replyToId, attaches)
            }
            synchronized(failedCids) { failedCids.add(localCid) }
        }
        return localCid
    }

    /** Сервер вернул ошибку на SEND_MESSAGE: файл ещё обрабатывается или отказ. */
    private fun handleSendError(payload: Map<String, Any?>) {
        val err = (payload["error"]?.toString() ?: "") + " " + (payload["message"]?.toString() ?: "")
        Log.d(TAG, "SEND error: $err")
        val entry = synchronized(attachResend) {
            attachResend.values.minByOrNull { it.attempts }
        } ?: run {
            // Ошибка при отправке текста из очереди — попытки уже учтены outbox'ом
            return
        }
        val isAttachmentPending = err.contains("attachment", ignoreCase = true)
        if (isAttachmentPending && entry.attempts < 6) {
            entry.attempts++
            val delayMs = 1500L * entry.attempts
            Log.d(TAG, "Attach not ready, retry #${entry.attempts} in ${delayMs}ms (cid=${entry.cid})")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(delayMs)
                try {
                    client.sendMessage(
                        entry.chatId, entry.text, postId = entry.cid,
                        replyToId = entry.replyToId, attaches = entry.attaches
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "attach retry failed", e)
                }
            }
        } else {
            Log.e(TAG, "Attach send failed permanently (cid=${entry.cid}): $err")
            synchronized(attachResend) { attachResend.remove(entry.cid) }
            synchronized(failedCids) { failedCids.add(entry.cid) }
            setInflight(entry.cid, false)
        }
    }

    /**
     * Автоотправка очереди: вызывается при подключении/AUTH_SYNC и после
     * изменения очереди. Пропускает то, что уже в полёте (ждёт эхо) и то, что
     * исчерпало попытки (ждёт ручного повтора).
     */
    fun flushOutbox() {
        if (!client.isAuthed()) return
        for (item in outbox.items()) {
            if (item.attempts >= Outbox.MAX_ATTEMPTS) continue
            if (isInflight(item.cid)) continue
            if (!client.isConnected()) return
            setInflight(item.cid, true)
            outbox.bumpAttempts(item.cid)
            refreshOutbox()
            try {
                client.sendMessage(
                    item.chatId, item.text, postId = item.cid,
                    replyToId = item.replyTo.takeIf { it != 0L }, attaches = emptyList()
                )
                Log.d(TAG, "Outbox: sent cid=${item.cid} attempt=${item.attempts + 1}")
            } catch (e: Exception) {
                setInflight(item.cid, false)
                Log.e(TAG, "Outbox: send failed cid=${item.cid}", e)
            }
        }
    }

    /** Ручной повтор: сбрасываем счётчик попыток и отправляем заново с тем же cid. */
    fun retryPending(cid: Long) {
        val attach = synchronized(attachResend) { attachResend[cid] }
        synchronized(failedCids) { failedCids.remove(cid) }
        if (attach != null || _outboxItems.value.any { it.cid == cid }) {
            outbox.resetAttempts(cid)
        }
        setInflight(cid, false)
        refreshOutbox()
        if (attach != null) {
            val item = synchronized(attachResend) {
                attachResend.remove(cid)?.copy(attempts = 0)
            }
            if (item != null) {
                setInflight(cid, true)
                synchronized(attachResend) { attachResend[cid] = item }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        client.sendMessage(
                            item.chatId, item.text, postId = item.cid,
                            replyToId = item.replyToId, attaches = item.attaches
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "retry attach failed", e)
                    }
                }
            }
        }
        flushOutbox()
    }

    /**
     * Состояние исходящего для UI: UNKNOWN — отправляется (часики),
     * NOT_SENT — не отправлено/ждёт повтора, null — уже не в очереди.
     */
    fun pendingState(cid: Long): Int? {
        if (cid in failedCids) return Message.STATE_NOT_SENT
        val item = _outboxItems.value.firstOrNull { it.cid == cid }
        if (item != null) {
            return if (client.isAuthed() && item.attempts < Outbox.MAX_ATTEMPTS)
                Message.STATE_UNKNOWN else Message.STATE_NOT_SENT
        }
        return if (isInflight(cid)) Message.STATE_UNKNOWN else null
    }

    /** Очередь чата для показа после рестарта (состояние — «не отправлено»). */
    fun outboxMessagesFor(chatId: Long): List<Message> {
        val norm = normalizeId(chatId)
        return outbox.itemsFor(norm).map { item ->
            Message(
                id = item.cid,
                mid = 0L,
                chatId = item.chatId,
                userId = 0L,
                text = item.text,
                time = item.createdAt,
                cid = item.cid,
                replyTo = item.replyTo,
                state = Message.STATE_NOT_SENT,
                isForward = item.replyTo != 0L,
                forwardType = if (item.replyTo != 0L) "REPLY" else null
            )
        }
    }

    // markRead responses carry no chatId — attribute them by request seq.
    private val markSeqChat = mutableMapOf<Int, Long>()

    fun markRead(chatId: Long, messageId: Long) {
        val normChat = normalizeId(chatId)
        if (messageId != 0L && messageId <= (lastReadIdMark[normChat] ?: 0L)) return
        try {
            val seq = client.markRead(chatId, messageId)
            synchronized(markSeqChat) { markSeqChat[seq] = normChat }
        } catch (_: Exception) {
            client.markRead(chatId, messageId)
        }
        // Optimistic: we just marked up to messageId, so our in-session blue
        // checks have something to persist even if the server ack is empty.
        // Server pushes/acks remain authoritative via updateMessagesToRead.
        if (messageId != 0L) {
            updateMessagesToRead(normChat, messageId)
        }
    }

    fun forwardMessage(targetChatId: Long, sourceChatId: Long, messageId: Long) {
        client.forwardMessage(targetChatId, sourceChatId, messageId)
    }

    // Pending deletes: seq -> (chatId, messageIds), applied on server ack
    private val pendingDeletes = mutableMapOf<Int, Pair<Long, List<Long>>>()

    // Pending reaction loads: seq -> chatId (bulk response has no chatId)
    private val reactionSeqChat = mutableMapOf<Int, Long>()

    // Pending add/remove reaction: seq -> (chatId, messageId) — ответ без messageId
    private val reactionSeqTarget = mutableMapOf<Int, Pair<Long, Long>>()

    /** Локальное редактирование: оптимистично обновляем текст, потом шлём opcode 67. */
    fun editMessage(chatId: Long, messageId: Long, newText: String) {
        val normChat = normalizeId(chatId)
        if (messageId == 0L || newText.isBlank()) return
        synchronized(chatCache) {
            chatCache[normChat] = (chatCache[normChat] ?: emptyList()).map { m ->
                if (m.id == messageId || m.mid == messageId) m.copy(text = newText, edited = true) else m
            }
        }
        _messages.value = _messages.value.map { m ->
            if (m.id == messageId || m.mid == messageId) m.copy(text = newText, edited = true) else m
        }
        syncVisibleToCache()
        try {
            client.editMessage(normChat, messageId, newText)
        } catch (e: Exception) {
            Log.e(TAG, "editMessage failed", e)
        }
    }

    /** Поставить/снять реакцию. Повторный тап по своей реакции — снять. */
    fun toggleReaction(chatId: Long, messageId: Long, emoji: String) {
        val normChat = normalizeId(chatId)
        if (messageId == 0L || emoji.isBlank()) return
        val mine = _messages.value.firstOrNull { it.id == messageId || it.mid == messageId }
            ?.reactions?.firstOrNull { it.emoji == emoji }?.mine == true
        // Оптимистично правим счётчики
        updateReactionLocally(normChat, messageId) { list ->
            val existing = list.firstOrNull { it.emoji == emoji }
            when {
                mine && existing != null -> list.mapNotNull {
                    if (it.emoji != emoji) it
                    else if (it.count <= 1) null else it.copy(count = it.count - 1, mine = false)
                }
                !mine && existing != null -> list.map {
                    if (it.emoji == emoji) it.copy(count = it.count + 1, mine = true) else it
                }
                !mine -> list + com.freemax.api.model.Reaction(emoji, 1, true)
                else -> list
            }
        }
        try {
            if (mine) {
                val seq = client.removeReaction(normChat, messageId)
                synchronized(reactionSeqTarget) {
                    reactionSeqTarget[seq] = normChat to messageId
                    if (reactionSeqTarget.size > 50) reactionSeqTarget.clear()
                }
            } else {
                val seq = client.addReaction(normChat, messageId, emoji)
                synchronized(reactionSeqTarget) {
                    reactionSeqTarget[seq] = normChat to messageId
                    if (reactionSeqTarget.size > 50) reactionSeqTarget.clear()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleReaction failed", e)
        }
    }

    /** Запросить реакции для списка сообщений (opcode 180, bulk). */
    fun loadReactionsFor(chatId: Long, messageIds: List<Long>) {
        val ids = messageIds.filter { it != 0L }.distinct().take(60)
        if (ids.isEmpty()) return
        val normChat = normalizeId(chatId)
        try {
            val seq = client.loadReactions(normChat, ids)
            synchronized(reactionSeqChat) {
                reactionSeqChat[seq] = normChat
                if (reactionSeqChat.size > 50) reactionSeqChat.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadReactionsFor failed", e)
        }
    }

    private fun updateReactionLocally(
        chatId: Long,
        messageId: Long,
        transform: (List<com.freemax.api.model.Reaction>) -> List<com.freemax.api.model.Reaction>
    ) {
        fun apply(list: List<Message>): List<Message> = list.map { m ->
            if (m.id == messageId || m.mid == messageId) m.copy(reactions = transform(m.reactions)) else m
        }
        synchronized(chatCache) {
            chatCache[chatId]?.let { chatCache[chatId] = apply(it) }
        }
        _messages.value = apply(_messages.value)
    }

    /** Пуши/ответы реакций: {chatId, messageId, reactionInfo} или {messagesReactions}. */
    @Suppress("UNCHECKED_CAST")
    private fun handleReactionUpdate(payload: Map<String, Any?>?, seq: Int? = null) {
        if (payload == null) return
        try {
            // 0 — валидный chatId (Избранное): «нет chatId» — null.
            val chatId: Long? = (if (payload.containsKey("chatId")) normalizeId(asLong(payload["chatId"])) else null)
                ?: synchronized(reactionSeqChat) { if (seq != null) reactionSeqChat.remove(seq) else null }
            val bulk = payload["messagesReactions"] as? Map<String, Any?>
            if (bulk != null) {
                for ((key, value) in bulk) {
                    val mid = key.toLongOrNull() ?: continue
                    val reactions = parseReactionInfo(value as? Map<*, *>)
                    val target = chatId ?: chatIdForMessage(mid) ?: continue
                    replaceReactions(target, mid, reactions)
                }
                return
            }
            val mid = asLong(payload["messageId"] ?: payload["id"])
            val reactions = parseReactionInfo(payload["reactionInfo"] as? Map<*, *>)
            if (mid == 0L) {
                // Ответ на ADD/REMOVE_REACTION не содержит messageId — берём из seq
                val target = synchronized(reactionSeqTarget) { if (seq != null) reactionSeqTarget.remove(seq) else null }
                if (target != null && reactions.isNotEmpty()) {
                    replaceReactions(target.first, target.second, reactions)
                }
                return
            }
            val target = chatId ?: chatIdForMessage(mid) ?: currentChatId.takeIf { it != NO_CHAT }
            if (target != null) replaceReactions(target, mid, reactions)
        } catch (e: Exception) {
            Log.e(TAG, "handleReactionUpdate failed", e)
        }
    }

    private fun chatIdForMessage(messageId: Long): Long? {
        val inVisible = _messages.value.firstOrNull { it.id == messageId || it.mid == messageId }?.chatId
        if (inVisible != null) return inVisible
        synchronized(chatCache) {
            for ((chat, list) in chatCache) {
                if (list.any { it.id == messageId || it.mid == messageId }) return chat
            }
        }
        return null
    }

    private fun replaceReactions(chatId: Long, messageId: Long, reactions: List<com.freemax.api.model.Reaction>) {
        fun apply(list: List<Message>): Pair<List<Message>, Boolean> {
            var found = false
            val out = list.map { m ->
                if (m.id == messageId || m.mid == messageId) {
                    found = true
                    m.copy(reactions = reactions)
                } else m
            }
            return out to found
        }
        val inVisible = if (chatId == currentChatId || currentChatId == NO_CHAT) apply(_messages.value) else null
        if (inVisible?.second == true) {
            _messages.value = inVisible.first
            syncVisibleToCache()
        }
        var foundInCache = false
        synchronized(chatCache) {
            chatCache[chatId]?.let { cached ->
                val res = apply(cached)
                if (res.second) {
                    foundInCache = true
                    chatCache[chatId] = res.first
                }
            }
        }
        if (foundInCache) persistChat(chatId)
        // Сообщение ещё не загружено — запомним реакцию и применим при появлении
        val wasFound = (inVisible?.second == true) || foundInCache
        synchronized(pendingReactions) {
            if (wasFound && reactions.isEmpty()) pendingReactions.remove(chatId to messageId)
            else if (!wasFound) {
                pendingReactions[chatId to messageId] = reactions
                if (pendingReactions.size > 300) pendingReactions.clear()
            }
        }
    }

    /** Реакции, пришедшие раньше самого сообщения: (chatId,messageId) -> реакции. */
    private val pendingReactions = mutableMapOf<Pair<Long, Long>, List<com.freemax.api.model.Reaction>>()

    /** Если для сообщения уже прилетала реакция — подставляем её при парсинге. */
    private fun applyPendingReactions(chatId: Long, msg: Message): Message {
        if (msg.reactions.isNotEmpty() || msg.id == 0L) return msg
        val pending = synchronized(pendingReactions) { pendingReactions[chatId to msg.id] }
        return if (pending != null) msg.copy(reactions = pending) else msg
    }

    /** reactionInfo -> список реакций. */
    private fun parseReactionInfo(info: Map<*, *>?): List<com.freemax.api.model.Reaction> {
        if (info == null) return emptyList()
        val counters = info["counters"] as? List<*> ?: return emptyList()
        val mine = info["yourReaction"]?.toString()
        return counters.mapNotNull { c ->
            val cm = c as? Map<*, *> ?: return@mapNotNull null
            val emoji = cm["reaction"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.freemax.api.model.Reaction(
                emoji = emoji,
                count = (cm["count"] as? Number)?.toInt() ?: 0,
                mine = mine != null && emoji == mine
            )
        }
    }

    /** status=EDITED или есть updateTime — сообщение редактировали. */
    private fun isEditedMap(map: Map<*, *>): Boolean {
        if (map["status"]?.toString() == "EDITED") return true
        val upd = (map["updateTime"] as? Number)?.toLong() ?: 0L
        val time = (map["time"] as? Number)?.toLong() ?: 0L
        return upd > 0L && time > 0L && upd != time
    }

    /** Ответ на edit (67) / пуш 128 об изменении: тот же id, новый текст. */
    @Suppress("UNCHECKED_CAST")
    private fun handleEditResponse(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            val msgMap = (payload["message"] as? Map<String, Any?>) ?: payload
            val msg = parseMessage(msgMap) ?: return
            if (msg.id == 0L) return
            val chatId = (if (payload.containsKey("chatId")) normalizeId(asLong(payload["chatId"])) else null)
                ?: (if (msgMap.containsKey("chatId")) msg.chatId else null)
                ?: currentChatId.takeIf { it != NO_CHAT }
                ?: return
            val fixed = msg.copy(chatId = chatId, edited = true)
            mergeIntoCache(chatId, listOf(fixed))
            if (chatId == currentChatId) {
                _messages.value = applyStoredMarks(cachedOrEmpty(chatId))
            }
            Log.d(TAG, "Message ${msg.id} edited")
        } catch (e: Exception) {
            Log.e(TAG, "handleEditResponse failed", e)
        }
    }

    /** Пуш об удалении сообщения (142) или диапазона (140). */
    @Suppress("UNCHECKED_CAST")
    private fun handleDeletedPush(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            // 0 — валидный chatId (Избранное); «нет chatId» — null.
            val chatId: Long? = when {
                payload.containsKey("chatId") -> normalizeId(asLong(payload["chatId"]))
                payload.containsKey("cid") -> normalizeId(asLong(payload["cid"]))
                else -> null
            }
            val ids = mutableListOf<Long>()
            (payload["messageIds"] as? List<*>)?.forEach { ids.add(asLong(it)) }
            (payload["id"] ?: payload["messageId"])?.let { ids.add(asLong(it)) }
            val idsSet = ids.filter { it != 0L }.toSet()
            if (idsSet.isEmpty()) return
            if (chatId != null) {
                synchronized(chatCache) {
                    chatCache[chatId] = (chatCache[chatId] ?: emptyList()).filterNot { it.id in idsSet }
                }
                persistChat(chatId)
            }
            _messages.value = _messages.value.filterNot { it.id in idsSet }
            syncVisibleToCache()
            Log.d(TAG, "Push removed ${idsSet.size} messages from chat $chatId")
        } catch (e: Exception) {
            Log.e(TAG, "handleDeletedPush failed", e)
        }
    }

    fun deleteMessages(chatId: Long, messageIds: List<Long>, forAll: Boolean) {
        if (messageIds.isEmpty()) return
        // Optimistic removal so UI reacts instantly
        _messages.value = _messages.value.filterNot { it.id in messageIds }
        syncVisibleToCache()
        try {
            val seq = client.deleteMessages(chatId, messageIds, forAll)
            synchronized(pendingDeletes) { pendingDeletes[seq] = normalizeId(chatId) to messageIds }
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessages send failed", e)
        }
    }

    private fun handleDeleteResponse(frame: WsFrame) {
        try {
            val payload = frame.payload
            if (frame.cmd != BinaryCodec.CMD_RESPONSE || payload == null) {
                // Error or unknown — resync the open chat to restore truth
                if (currentChatId != NO_CHAT && client.isConnected()) client.loadMessages(currentChatId, 40)
                return
            }
            @Suppress("UNCHECKED_CAST")
            val confirmed = (payload["messageIds"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toLong() }?.toSet()
                ?: synchronized(pendingDeletes) { pendingDeletes[frame.seq]?.second?.toSet() }
                ?: emptySet()
            if (confirmed.isNotEmpty()) {
                _messages.value = _messages.value.filterNot { it.id in confirmed }
                syncVisibleToCache()
                Log.d(TAG, "Deleted ${confirmed.size} messages")
            }
            synchronized(pendingDeletes) { pendingDeletes.remove(frame.seq) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle delete response", e)
        }
    }

    fun handleFrame(frame: WsFrame) {
        Log.d(TAG, "handleFrame opcode=0x${frame.opcode.toString(16)} keys=${frame.payload?.keys} payload=${frame.payload?.toString()?.take(500)}")
        when (frame.opcode) {
            Opcodes.LOAD_MESSAGES, Opcodes.AUTH_SYNC -> handleMessagesResponse(frame.payload, frame.seq)
            Opcodes.NOTIF_MESSAGE -> handleNewMessage(frame.payload)
            Opcodes.SEND_MESSAGE -> handleSentMessage(frame.payload)
            Opcodes.EDIT_MESSAGE -> handleEditResponse(frame.payload)
            Opcodes.DELETE_MESSAGES -> handleDeleteResponse(frame)
            Opcodes.NOTIF_MARK, Opcodes.MARK_READ, Opcodes.MARK_MESSAGES_READ -> handleReadMark(frame.payload, frame.seq)
            Opcodes.NOTIF_CONFIG -> handleConfigPush(frame.payload)
            Opcodes.NOTIF_TYPING -> handleTyping(frame.payload)
            Opcodes.NOTIF_CHAT -> handleChatNotification(frame.payload)
            Opcodes.NOTIF_REACTION, Opcodes.ADD_REACTION, Opcodes.REMOVE_REACTION ->
                handleReactionUpdate(frame.payload, frame.seq)
            Opcodes.LOAD_REACTIONS -> handleReactionUpdate(frame.payload, frame.seq)
            Opcodes.NOTIF_MSG_DELETE, Opcodes.NOTIF_MSG_DELETE_RANGE -> handleDeletedPush(frame.payload)
        }
    }

    private fun startTypingCleaner() {
        if (typingCleanerStarted) return
        typingCleanerStarted = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                try {
                    val now = System.currentTimeMillis()
                    val cur = _typing.value
                    if (cur.isEmpty()) continue
                    val pruned = cur.mapValues { (_, users) ->
                        users.filterValues { it > now }
                    }.filterValues { it.isNotEmpty() }
                    if (pruned != cur) _typing.value = pruned
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Typing pushes look like {chatId, userId} or {chatId, userIds:[...]}.
     * Kept for ~6s, pruned by [startTypingCleaner].
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleTyping(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            // 0 — валидный chatId (Избранное): отбрасываем только пуш без chatId.
            val rawChat = payload["chatId"] ?: payload["cid"] ?: payload["chat_id"] ?: return
            val chatId = normalizeId(asLong(rawChat))
            val users = mutableListOf<Long>()
            (payload["userIds"] as? List<*>)?.forEach { users.add(normalizeId(asLong(it))) }
            (payload["userId"] ?: payload["sender"] ?: payload["senderId"])?.let {
                users.add(normalizeId(asLong(it)))
            }
            // Some servers send typing-stop as empty/status flag — ignore those.
            val stop = (payload["stop"] as? Boolean)
                ?: (payload["isTyping"] as? Boolean)?.not()
                ?: false
            val valid = users.filter { it != 0L }.distinct()
            if (valid.isEmpty()) return
            val cur = _typing.value.toMutableMap()
            if (stop) {
                val inner = cur[chatId]?.toMutableMap() ?: mutableMapOf()
                valid.forEach { inner.remove(it) }
                if (inner.isEmpty()) cur.remove(chatId) else cur[chatId] = inner
            } else {
                val exp = System.currentTimeMillis() + 6000
                val inner = cur[chatId]?.toMutableMap() ?: mutableMapOf()
                valid.forEach { inner[it] = exp }
                cur[chatId] = inner
            }
            _typing.value = cur
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle typing", e)
        }
    }

    /**
     * Server pushes read marks as NOTIF_CONFIG {chatId, userId, mark, setAsUnread}.
     * Route only mark-shaped pushes here; other config pushes are ignored.
     */
    private fun handleConfigPush(payload: Map<String, Any?>?) {
        if (payload == null) return
        if (payload.containsKey("mark") || payload.containsKey("messageId") || payload.containsKey("mid")) {
            handleReadMark(payload, null)
        } else {
            Log.d(TAG, "handleConfigPush ignored keys=${payload.keys}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleMessagesResponse(payload: Map<String, Any?>?, seq: Int?) {
        // Attribute the response to the requesting chat (never to whoever is
        // open *now* — a late response must not leak into another chat).
        // reqChat == null = «неизвестный запрос», reqChat == 0 — валидный чат
        // (Избранное), поэтому 0 нельзя использовать как сентинел.
        val req = synchronized(loadSeqChat) {
            if (seq != null) loadSeqChat.remove(seq) else null
        }
        val reqChat: Long? = req?.first
        val reqBefore = req?.second ?: 0L
        val reqCount = req?.third ?: 0
        if (payload == null) {
            if (reqChat != null) setChatLoading(reqChat, false)
            return
        }
        // Server error (e.g. Must be ONLINE session after reconnect): never leave UI stuck loading.
        // Reconnect + re-auth will follow, retry loops will reload.
        if (payload.containsKey("error")) {
            Log.d(TAG, "handleMessagesResponse server error: ${payload["message"]}")
            if (reqChat != null) setChatLoading(reqChat, false)
            return
        }
        Log.d(TAG, "handleMessagesResponse keys=${payload.keys} rawMessagesType=${payload["messages"]?.javaClass?.simpleName} raw=${payload["messages"]?.toString()?.take(1000)}")

        try {
            val raw = payload["messages"]
            if (raw == null) {
                // No message list in this payload — just release this request.
                if (reqChat != null) setChatLoading(reqChat, false)
                return
            }
            // (chatHint, rawMap): for the Map form (AUTH_SYNC) the key is the
            // chat id — keep it. Otherwise messages of chat 0 (Избранное) lose
            // attribution and get their own message id as chatId.
            fun keyChat(k: Any?): Long? = when (k) {
                is Number -> normalizeId(k.toLong())
                is ByteArray -> normalizeId(asLong(k))
                is String -> k.toLongOrNull()?.let { normalizeId(it) }
                else -> null
            }
            val entries = mutableListOf<Pair<Long?, Map<String, Any?>>>()
            when (raw) {
                is List<*> -> raw.forEach { item ->
                    (item as? Map<*, *>)?.let { entries.add(null to it as Map<String, Any?>) }
                }
                is Map<*, *> -> {
                    for ((k, v) in raw) {
                        val hint = keyChat(k)
                        Log.d(TAG, "messages map entry key=${k?.javaClass?.simpleName} ${k.toString().take(20)} valueType=${v?.javaClass?.simpleName}")
                        when (v) {
                            is List<*> -> v.forEach { item ->
                                (item as? Map<*, *>)?.let { entries.add(hint to it as Map<String, Any?>) }
                            }
                            is Map<*, *> -> entries.add(hint to v as Map<String, Any?>)
                            else -> {}
                        }
                    }
                    // If map itself looks like a message (has text/id), treat as single
                    if (entries.isEmpty() && raw.containsKey("text")) {
                        entries.add(null to raw as Map<String, Any?>)
                    }
                }
                else -> {
                    if (reqChat != null) setChatLoading(reqChat, false)
                    return
                }
            }

            if (entries.isEmpty()) {
                // Empty page: chat has no (more) messages — a finished state, not loading.
                Log.d(TAG, "No messages in payload after flatten, keys=${payload.keys}")
                if (reqChat != null) {
                    if (reqBefore > 0L) synchronized(exhaustedChats) { exhaustedChats.add(reqChat) }
                    markChatLoaded(reqChat)
                    if (reqChat == currentChatId) _messages.value = applyStoredMarks(cachedOrEmpty(reqChat))
                }
                return
            }
            val newMessages = entries.mapNotNull { (hint, rawMap) ->
                parseMessage(rawMap)?.let { parsed ->
                    // LOAD_MESSAGES items carry NO chatId key — only the request
                    // (or the AUTH_SYNC map key) knows the chat. Never let the
                    // id/cid fallback (message's own id!) win when chat is known.
                    val explicit = rawMap.containsKey("chatId")
                    val chat = when {
                        explicit -> parsed.chatId
                        hint != null -> hint
                        reqChat != null -> reqChat
                        currentChatId != NO_CHAT -> currentChatId
                        else -> parsed.chatId
                    }
                    parsed.copy(chatId = chat)
                }
            }
            if (newMessages.isEmpty()) {
                if (reqChat != null) {
                    markChatLoaded(reqChat)
                    if (reqChat == currentChatId) _messages.value = applyStoredMarks(cachedOrEmpty(reqChat))
                }
                return
            }
            // Group by chat — AUTH_SYNC flattens many chats at once.
            val byChat = newMessages.groupBy { it.chatId }
            for ((chat, msgs) in byChat) {
                val added = mergeIntoCache(chat, msgs)
                markChatLoaded(chat)
                if (reqChat == chat && reqBefore > 0L && added == 0) {
                    synchronized(exhaustedChats) { exhaustedChats.add(chat) }
                }
                Log.d(TAG, "Merged ${msgs.size} messages ($added new) for chatId=$chat cacheNow=${cachedOrEmpty(chat).size}")
            }
            if (reqChat != null) {
                // Запрошенный чат ответил (даже если его сообщения отфильтровались) — done.
                // Короткая страница на догрузке = история закончилась, без лишнего запроса.
                if (reqBefore > 0L && reqCount > 0 && entries.size < reqCount) {
                    synchronized(exhaustedChats) { exhaustedChats.add(reqChat) }
                }
                markChatLoaded(reqChat)
            }
            // Refresh the visible list only when the open chat actually got data.
            if (currentChatId != NO_CHAT && (byChat.containsKey(currentChatId) || reqChat == currentChatId)) {
                _messages.value = applyStoredMarks(cachedOrEmpty(currentChatId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse messages", e)
            if (reqChat != null) setChatLoading(reqChat, false)
        }
    }

    private var lastPushReloadAt = 0L

    @Suppress("UNCHECKED_CAST")
    private fun handleNewMessage(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            // Push shape: {chatId, message:{...}, prevMessageId, ttl, unread, mark}.
            // The inner message has NO chatId — never let parseMessage fall back
            // to the message id as chat id; the outer chatId wins. 0 — валидный
            // chatId (Избранное), «нет chatId» — это null.
            val outerChatId: Long? = when {
                payload.containsKey("chatId") -> normalizeId(asLong(payload["chatId"]))
                payload.containsKey("cid") -> normalizeId(asLong(payload["cid"]))
                else -> null
            }
            val msgMap = (payload["message"] as? Map<String, Any?>) ?: payload
            val msg = parseMessage(msgMap)
            if (msg == null) {
                Log.d(TAG, "Push message unparseable, scheduling reload")
                reloadOnPush()
                return
            }
            val msgWithChat = when {
                outerChatId != null -> msg.copy(chatId = outerChatId)
                msgMap.containsKey("chatId") -> msg
                currentChatId != NO_CHAT -> msg.copy(chatId = currentChatId)
                else -> {
                    Log.d(TAG, "Push message without chat, scheduling reload")
                    reloadOnPush()
                    return
                }
            }
            // Server read mark that rides along with the push
            val pushMark = asLong(payload["mark"])
            if (pushMark != 0L && outerChatId != null) {
                updateMessagesToRead(outerChatId, pushMark)
            }
            // Cache every chat (background chats open instantly later), show only the open one.
            if (mergeIntoCache(msgWithChat.chatId, listOf(msgWithChat)) == 0) {
                Log.d(TAG, "Duplicate incoming message ${msgWithChat.id} ignored")
                return
            }
            if (msgWithChat.chatId == currentChatId) {
                _messages.value = applyStoredMarks(cachedOrEmpty(msgWithChat.chatId))
                Log.d(TAG, "Added incoming message ${msgWithChat.id} to chat $currentChatId")
            } else {
                Log.d(TAG, "Incoming message for other chat ${msgWithChat.chatId} cached (current $currentChatId)")
            }
            // Локальное уведомление (если приложение в фоне или чат не открыт)
            try {
                val isFromMe = normalizeId(msgWithChat.userId) == normalizeId(secureStorage.getUserId())
                val openHere = notifier.appInForeground && msgWithChat.chatId == currentChatId
                if (!isFromMe && !openHere) {
                    val chat = chatRepository.chats.value.firstOrNull {
                        normalizeId(it.id) == msgWithChat.chatId || normalizeId(it.cid) == msgWithChat.chatId
                    }
                    val preview = when {
                        msgWithChat.text.isNotBlank() -> msgWithChat.text
                        msgWithChat.attachments.isNotEmpty() -> "Вложение"
                        else -> ""
                    }
                    notifier.notifyMessage(
                        chatId = msgWithChat.chatId,
                        chatTitle = chat?.title.orEmpty(),
                        senderName = chatRepository.getContactName(msgWithChat.userId),
                        text = preview,
                        isGroup = chat?.type == 2 || chat?.type == 3,
                        muted = secureStorage.isChatMuted(msgWithChat.chatId)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "notify failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle new message", e)
        }
    }

    /** Last-resort: a push we couldn't consume → reload open chat (debounced). */
    private fun reloadOnPush() {
        if (currentChatId == NO_CHAT || !client.isConnected()) return
        val now = System.currentTimeMillis()
        if (now - lastPushReloadAt < 3000) return
        lastPushReloadAt = now
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.loadMessages(currentChatId, 40)
        }
    }

    private fun handleSentMessage(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            if (payload.containsKey("error")) {
                handleSendError(payload)
                return
            }
            Log.d(TAG, "handleSentMessage payload=$payload")
            // Server may echo the full message nested under "message"
            val inner = payload["message"] as? Map<String, Any?>
            fun pick(vararg keys: String): Any? {
                for (k in keys) {
                    payload[k]?.let { return it }
                    inner?.get(k)?.let { return it }
                }
                return null
            }
            val msgId = (pick("messageId", "mid", "id") as? Number)?.toLong()
                ?: asLong(pick("messageId", "mid", "id"))
            val cid = asLong(pick("cid", "clientMsgId", "tempId"))
            val rawChatId = pick("chatId", "cidChat", "chat_id")
            val chatId: Long? = if (rawChatId != null) normalizeId(asLong(rawChatId)) else null
            Log.d(TAG, "handleSentMessage msgId=$msgId cid=$cid chatId=$chatId")
            if (msgId == 0L && cid == 0L) return
            // Эхо сервера подтверждает доставку — убираем из outbox и из повторов
            if (cid != 0L) {
                outbox.remove(cid)
                setInflight(cid, false)
                refreshOutbox()
                synchronized(attachResend) { attachResend.remove(cid) }
                synchronized(failedCids) { failedCids.remove(cid) }
            }
            val current = _messages.value.toMutableList()
            // Try to find pending by cid, then by chatId+text, then last NOT_SENT
            var idx = -1
            if (cid != 0L) {
                idx = current.indexOfFirst { it.cid == cid || it.id == cid }
            }
            if (idx == -1 && chatId != null) {
                // Find last NOT_SENT in this chat
                idx = current.indexOfLast { it.state == Message.STATE_NOT_SENT && (it.chatId == chatId || it.chatId == currentChatId) }
            }
            if (idx == -1) {
                idx = current.indexOfLast { it.state == Message.STATE_NOT_SENT }
            }
            if (idx != -1) {
                val old = current[idx]
                current[idx] = old.copy(
                    mid = if (msgId != 0L) msgId else old.mid,
                    id = if (msgId != 0L) msgId else old.id,
                    state = Message.STATE_SENT
                )
                _messages.value = current.sortedBy { it.time }
                syncVisibleToCache()
                Log.d(TAG, "Updated pending $idx to SENT mid=$msgId")
                return
            }
            // If not found in current list, it may be in ChatViewModel pending — still update _messages if we have a temp with same cid
            // Fallback: update last NOT_SENT
            val lastIdx = current.indexOfLast { it.state == Message.STATE_NOT_SENT }
            if (lastIdx != -1 && msgId != 0L) {
                val old = current[lastIdx]
                current[lastIdx] = old.copy(mid = msgId, id = msgId, state = Message.STATE_SENT)
                _messages.value = current.sortedBy { it.time }
                syncVisibleToCache()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle sent message", e)
        }
    }

    private fun hexOf(v: Any?): String = when (v) {
        is ByteArray -> v.joinToString("") { "%02x".format(it) }
        else -> v.toString()
    }

    private fun handleReadMark(payload: Map<String, Any?>?, seq: Int?) {
        if (payload == null) return
        try {
            Log.d(TAG, "handleReadMark payload=$payload")
            // 0 — валидный chatId (Избранное); «нет chatId» — null.
            var chatId: Long? = when {
                payload.containsKey("chatId") -> normalizeId(asLong(payload["chatId"]))
                payload.containsKey("cid") -> normalizeId(asLong(payload["cid"]))
                payload.containsKey("chat_id") -> normalizeId(asLong(payload["chat_id"]))
                else -> null
            }
            if (chatId == null && seq != null) {
                chatId = synchronized(markSeqChat) {
                    val fromSeq = markSeqChat.remove(seq)
                    if (markSeqChat.size > 50) markSeqChat.clear()
                    fromSeq
                }
            }
            val rawMark = payload["mark"] ?: payload["messageId"] ?: payload["mid"] ?: payload["readMark"] ?: payload["lastReadId"] ?: payload["id"]
            val markId = asLong(rawMark)
            Log.d(TAG, "handleReadMark chat=$chatId rawMarkHex=${hexOf(rawMark)} markId=$markId current=$currentChatId")
            val userId = normalizeId(asLong(payload["userId"] ?: payload["sender"] ?: payload["senderId"]))
            // If no specific mark, try to get from "marks" map
            if (markId == 0L) {
                // Try marks map: {chatId: mark}
                val marks = payload["marks"] as? Map<*, *>
                if (marks != null) {
                    for ((k, v) in marks) {
                        val cId = normalizeId(asLong(k))
                        val mId = asLong(v)
                        if (mId != 0L) {
                            updateMessagesToRead(cId, mId)
                        }
                    }
                    return
                }
                // Try chatId+messageId pair
                return
            }
            val targetChat = chatId ?: currentChatId.takeIf { it != NO_CHAT }
            if (targetChat != null && markId != 0L) {
                updateMessagesToRead(targetChat, markId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle read mark", e)
        }
    }

    private fun updateMessagesToRead(chatId: Long, upToId: Long) {
        if (upToId > 1_000_000_000_000L) {
            val prev = lastReadTsMark[chatId] ?: 0L
            if (upToId > prev) {
                lastReadTsMark[chatId] = upToId
                persistMarks(chatId)
            }
        } else {
            val prev = lastReadIdMark[chatId] ?: 0L
            if (upToId > prev) {
                lastReadIdMark[chatId] = upToId
                persistMarks(chatId)
            }
        }
        val current = _messages.value
        val byTime = upToId > 1_000_000_000_000L
        fun mark(list: List<Message>) = list.map { msg ->
            val reached = if (byTime) msg.time != 0L && msg.time <= upToId else msg.id != 0L && msg.id <= upToId
            if (msg.chatId == chatId && reached && msg.state < Message.STATE_READ) {
                msg.copy(state = Message.STATE_READ)
            } else msg
        }
        // Marks may target a background chat — update its cache entry too.
        synchronized(chatCache) {
            chatCache[chatId]?.let { chatCache[chatId] = mark(it) }
        }
        if (current.isEmpty()) return
        _messages.value = mark(current)
        syncVisibleToCache()
        Log.d(TAG, "Updated messages in chat $chatId up to $upToId to READ")
    }

    private fun persistMarks(chatId: Long) {
        try {
            secureStorage.saveReadMarks(chatId, lastReadIdMark[chatId] ?: 0L, lastReadTsMark[chatId] ?: 0L)
        } catch (_: Exception) {}
    }

    /**
     * Fully client-side read state: mark every loaded message of the chat as read,
     * no network involved. Persists max id/time marks so checkmarks survive restart.
     */
    fun markAllLocallyRead(chatId: Long) {
        val normChat = normalizeId(chatId)
        try { notifier.cancelForChat(normChat) } catch (_: Exception) {}
        val current = _messages.value
        var maxId = lastReadIdMark[normChat] ?: 0L
        var maxTs = lastReadTsMark[normChat] ?: 0L
        var changed = false
        val updated = current.map { msg ->
            if (msg.chatId == normChat && msg.state < Message.STATE_READ) {
                changed = true
                if (msg.id > maxId) maxId = msg.id
                if (msg.time > maxTs) maxTs = msg.time
                msg.copy(state = Message.STATE_READ)
            } else msg
        }
        if (changed) {
            lastReadIdMark[normChat] = maxId
            lastReadTsMark[normChat] = maxTs
            persistMarks(normChat)
            _messages.value = updated
            syncVisibleToCache()
            Log.d(TAG, "markAllLocallyRead chat=$normChat id=$maxId ts=$maxTs")
        }
    }

    private fun applyStoredMarks(messages: List<Message>): List<Message> {
        if (lastReadIdMark.isEmpty() && lastReadTsMark.isEmpty()) return messages
        return messages.map { msg ->
            if (msg.state >= Message.STATE_READ) return@map msg
            val idMark = lastReadIdMark[msg.chatId] ?: 0L
            val tsMark = lastReadTsMark[msg.chatId] ?: 0L
            val reached = (idMark != 0L && msg.id != 0L && msg.id <= idMark) ||
                (tsMark != 0L && msg.time != 0L && msg.time <= tsMark)
            if (reached) msg.copy(state = Message.STATE_READ) else msg
        }
    }

    private fun handleChatNotification(payload: Map<String, Any?>?) {
        // NOTIF_CHAT may contain chat update with lastMessage — forward to ChatRepository via separate flow
        // For now just log, ChatRepository will handle via its own observer
        Log.d(TAG, "handleChatNotification $payload")
    }

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
    private fun asLong(v: Any?): Long {
        if (v is Number) return v.toLong()
        if (v is ByteArray) {
            if (v.isNotEmpty() && v[0].toInt() and 0xFF == 0xD2 && v.size == 5) {
                var r = 0L
                for (i in 1 until 5) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                return if (r and 0x80000000L != 0L) r or -0x100000000L else r
            }
            if (v.isNotEmpty() && (v[0].toInt() and 0xFF == 0xCF || v[0].toInt() and 0xFF == 0xD3) && v.size == 9) {
                var r = 0L
                for (i in 1 until 9) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                return r
            }
            if (v.isNotEmpty() && v[0].toInt() and 0xFF == 0xC7 && v.size >= 4 && v[2].toInt() and 0xFF == 0x01) {
                val inner = v[3].toInt() and 0xFF
                if (inner == 0xCF || inner == 0xD3) {
                    var r = 0L; for (i in 4 until v.size) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong(); return r
                }
                if (inner == 0xCE || inner == 0xD2) {
                    var r = 0L; for (i in 4 until v.size) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                    return if (inner == 0xD2 && r and 0x80000000L != 0L) r or -0x100000000L else r
                }
                var r = 0L; for (i in 3 until v.size) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong(); return r
            }
            var r = 0L; for (b in v) r = (r shl 8) or (b.toInt() and 0xFF).toLong(); return r
        }
        if (v is String) return v.toLongOrNull() ?: 0L
        return 0L
    }
    private fun normMs(v: Any?): Long {
        // Durations come in mixed units: video ms, audio µs. Heuristic: >1000s can't be ms.
        val raw = asLong(v)
        if (raw <= 0) return 0
        return if (raw > 1_000_000L) raw / 1000L else raw
    }

    private fun parseAttach(am: Map<*, *>): com.freemax.api.model.Attachment? {
        return try {
            when (am["_type"]?.toString()) {
                "PHOTO", "ALBUM" -> {
                    val base = (am["baseUrl"] ?: am["url"])?.toString()
                    val preview = am["previewData"] as? ByteArray
                    if (base == null && preview == null) return null
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_PHOTO,
                        id = asLong(am["photoId"]),
                        photoUrl = base,
                        url = base,
                        width = (am["width"] as? Number)?.toInt() ?: 0,
                        height = (am["height"] as? Number)?.toInt() ?: 0,
                        preview = preview
                    )
                }
                "VIDEO" -> {
                    val thumb = am["thumbnail"]?.toString()
                    val preview = am["previewData"] as? ByteArray
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_VIDEO,
                        id = asLong(am["videoId"]),
                        videoUrl = (am["MP4_1080"] ?: am["mp4_1080"] ?: am["MP4_720"])
                            ?.toString()?.takeIf { it.startsWith("http") },
                        thumbUrl = thumb,
                        preview = preview,
                        width = (am["width"] as? Number)?.toInt() ?: 0,
                        height = (am["height"] as? Number)?.toInt() ?: 0,
                        durationMs = normMs(am["duration"]),
                        extra = am["token"]?.toString()
                    )
                }
                "FILE" -> {
                    val rawId = am["fileId"]
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_FILE,
                        id = asLong(rawId),
                        fileId = rawId?.toString(),
                        name = am["name"]?.toString(),
                        size = normalizeId(asLong(am["size"])),
                        url = (am["url"] ?: am["baseUrl"])?.toString()?.takeIf { it.startsWith("http") },
                        extra = am["token"]?.toString(),
                        thumbUrl = (am["preview"] as? Map<*, *>)?.let {
                            (it["baseUrl"] ?: it["url"] ?: it["thumbnail"])?.toString()
                        }
                    )
                }
                "AUDIO" -> {
                    val wave = (am["wave"] as? ByteArray)?.map { it.toInt() and 0xFF } ?: emptyList()
                    val url = am["url"]?.toString()
                    if (url == null && wave.isEmpty()) return null
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_AUDIO,
                        id = asLong(am["audioId"]),
                        audioUrl = url,
                        url = url,
                        durationMs = normMs(am["duration"]),
                        wave = wave,
                        extra = am["token"]?.toString()
                    )
                }
                "STICKER" -> {
                    val url = am["url"]?.toString() ?: return null
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_STICKER,
                        id = asLong(am["stickerId"]),
                        url = url,
                        photoUrl = url,
                        width = (am["width"] as? Number)?.toInt() ?: 0,
                        height = (am["height"] as? Number)?.toInt() ?: 0,
                        extra = (am["tags"] as? List<*>)?.firstOrNull()?.toString()
                    )
                }
                "CALL" -> {
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_CALL,
                        durationMs = normMs(am["duration"]),
                        extra = listOfNotNull(
                            am["callType"]?.toString(),
                            am["hangupType"]?.toString()
                        ).joinToString("/").ifBlank { null }
                    )
                }
                "CONTACT" -> {
                    val name = am["name"]?.toString()
                        ?: listOfNotNull(am["firstName"]?.toString(), am["lastName"]?.toString())
                            .joinToString(" ").trim().ifBlank { null }
                        ?: am["phone"]?.toString() ?: return null
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_CONTACT,
                        id = asLong(am["contactId"]),
                        name = name,
                        phone = am["phone"]?.toString(),
                        photoUrl = am["photoUrl"]?.toString() ?: am["baseUrl"]?.toString(),
                        extra = am["vcfBody"]?.toString()
                    )
                }
                "LOCATION" -> {
                    val lat = (am["latitude"] as? Number)?.toDouble()
                    val lon = (am["longitude"] as? Number)?.toDouble()
                    if (lat == null || lon == null) return null
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_LOCATION,
                        name = "$lat, $lon",
                        extra = (am["zoom"] as? Number)?.toString()
                    )
                }
                "POLL" -> {
                    val opts = (am["answers"] as? List<*>)?.mapNotNull {
                        (it as? Map<*, *>)?.get("text")?.toString()
                            ?: (it as? String)
                    } ?: emptyList()
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_POLL,
                        id = asLong(am["pollId"]),
                        title = am["title"]?.toString() ?: "Опрос",
                        pollOptions = opts
                    )
                }
                "SHARE" -> {
                    val img = am["image"] as? Map<*, *>
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_SHARE,
                        id = asLong(am["shareId"]),
                        title = am["title"]?.toString(),
                        description = am["description"]?.toString(),
                        targetUrl = am["url"]?.toString(),
                        thumbUrl = (img?.get("baseUrl") ?: img?.get("url"))?.toString()
                    )
                }
                "INLINE_KEYBOARD" -> {
                    val rows = ((am["keyboard"] as? Map<*, *>)?.get("buttons") as? List<*>)
                        ?.mapNotNull { row ->
                            (row as? List<*>)?.mapNotNull { b ->
                                (b as? Map<*, *>)?.get("text")?.toString()
                                    ?: (b as? Map<*, *>)?.get("title")?.toString()
                            }?.takeIf { it.isNotEmpty() }
                        } ?: emptyList()
                    if (rows.isEmpty()) return null
                    com.freemax.api.model.Attachment(
                        type = com.freemax.api.model.Attachment.TYPE_INLINE_KEYBOARD,
                        keyboard = rows
                    )
                }
                // CONTROL/WIDGET/APP/BOT_PAYLOAD etc. are not media — handled separately
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun serviceText(am: Map<*, *>): String? {
        if (am["_type"]?.toString() != "CONTROL") return null
        return when (am["event"]?.toString()) {
            "new" -> if (am["title"] != null) "Группа «${am["title"]}» создана" else "Чат создан"
            "add" -> "Новый участник присоединился"
            "remove", "leave" -> "Участник покинул чат"
            "joinByLink" -> "Участник присоединился по ссылке"
            "title" -> if (am["title"] != null) "Новое название: ${am["title"]}" else null
            "icon" -> "Обновлено фото чата"
            "pin" -> "Сообщение закреплено"
            "system", "hello", "privacyWarning" ->
                am["message"]?.toString() ?: am["shortMessage"]?.toString()
            else -> null
        }
    }

    private fun parseMessage(map: Map<String, Any?>): Message? {
        return try {
            val text = map["text"]?.toString() ?: ""
            val rawAttaches = (map["attaches"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            val attachments = rawAttaches.mapNotNull { parseAttach(it) }.toMutableList()

            // Forwarded / replied message inside link — parse recursively
            val link = map["link"] as? Map<*, *>
            var isForward = false
            var forwardType: String? = null
            var quoteText: String? = null
            if (link != null) {
                val fwd = link["message"] as? Map<*, *>
                if (fwd != null) {
                    isForward = true
                    forwardType = link["type"]?.toString()
                    val isReply = forwardType == "REPLY"
                    val fwdText = fwd["text"]?.toString() ?: ""
                    val fwdAttaches = (fwd["attaches"] as? List<*>)
                        ?.mapNotNull { (it as? Map<*, *>)?.let { m -> parseAttach(m) } } ?: emptyList()
                    // Nested forward inside (e.g. reply to a forward)
                    val nestedLink = fwd["link"] as? Map<*, *>
                    val nestedFwd = nestedLink?.get("message") as? Map<*, *>
                    if (nestedFwd != null) {
                        val nn = parseMessage(
                            mapOf(
                                "text" to (nestedFwd["text"] ?: ""),
                                "attaches" to (nestedFwd["attaches"] ?: emptyList<Any>()),
                                "link" to (nestedFwd["link"] ?: emptyMap<String, Any?>())
                            )
                        )
                        if (nn != null && (nn.text.isNotBlank() || nn.attachments.isNotEmpty())) {
                            attachments.add(
                                com.freemax.api.model.Attachment(
                                    type = com.freemax.api.model.Attachment.TYPE_SHARE,
                                    title = "Переслано",
                                    description = nn.text,
                                    nested = nn.attachments
                                )
                            )
                        }
                    }
                    // REPLY: body is our own text, quoted content goes to the quote block.
                    // FORWARD: body is the forwarded text (plus our comment if present).
                    val bodyText: String
                    val inlineAttaches: List<com.freemax.api.model.Attachment>
                    val quoteAttaches: List<com.freemax.api.model.Attachment>
                    if (isReply) {
                        if (fwdText.isNotBlank()) quoteText = fwdText
                        bodyText = text
                        inlineAttaches = attachments
                        quoteAttaches = fwdAttaches
                    } else {
                        bodyText = if (text.isBlank()) fwdText
                        else if (fwdText.isBlank()) text
                        else text + "\n\n" + fwdText
                        inlineAttaches = attachments + fwdAttaches
                        quoteAttaches = emptyList()
                    }
                    return Message(
                        id = asLong(map["mid"] ?: map["id"]),
                        mid = asLong(map["mid"] ?: map["id"]),
                        chatId = normalizeId(asLong(map["chatId"] ?: map["cid"] ?: map["id"])),
                        userId = normalizeId(asLong(map["userId"] ?: map["sender"] ?: map["senderId"])),
                        text = bodyText,
                        time = asLong(map["time"] ?: map["updateTime"]),
                        cid = asLong(map["cid"]),
                        state = (map["state"] as? Number)?.toInt() ?: 0,
                        attachments = inlineAttaches
                            .distinctBy { it.type to it.id to (it.url ?: it.photoUrl ?: it.thumbUrl) },
                        isForward = true,
                        forwardType = forwardType,
                        quoteText = quoteText,
                        quoteAttachments = quoteAttaches
                            .distinctBy { it.type to it.id to (it.url ?: it.photoUrl ?: it.thumbUrl) },
                        edited = isEditedMap(map),
                        reactions = parseReactionInfo(map["reactionInfo"] as? Map<*, *>)
                    ).takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
                } else if (link["title"] != null || link["url"] != null) {
                    // Plain link preview
                    attachments.add(
                        com.freemax.api.model.Attachment(
                            type = com.freemax.api.model.Attachment.TYPE_SHARE,
                            title = link["title"]?.toString(),
                            targetUrl = (link["url"] ?: link["fullUrl"])?.toString(),
                            thumbUrl = (link["photo"] as? Map<*, *>)?.get("url")?.toString()
                                ?: link["baseUrl"]?.toString()
                        )
                    )
                }
            }

            // Service (CONTROL) messages render as centered text, no placeholders
            val services = rawAttaches.mapNotNull { serviceText(it) }
            if (text.isBlank() && attachments.isEmpty() && services.isNotEmpty()) {
                return Message(
                    id = asLong(map["mid"] ?: map["id"]),
                    mid = asLong(map["mid"] ?: map["id"]),
                    chatId = normalizeId(asLong(map["chatId"] ?: map["cid"] ?: map["id"])),
                    userId = normalizeId(asLong(map["userId"] ?: map["sender"] ?: map["senderId"])),
                    text = services.first(),
                    time = asLong(map["time"] ?: map["updateTime"]),
                    cid = asLong(map["cid"]),
                    state = (map["state"] as? Number)?.toInt() ?: 0,
                    isService = true
                )
            }

            if (text.isBlank() && attachments.isEmpty()) return null
            Message(
                id = asLong(map["mid"] ?: map["id"]),
                mid = asLong(map["mid"] ?: map["id"]),
                chatId = normalizeId(asLong(map["chatId"] ?: map["cid"] ?: map["id"])),
                userId = normalizeId(asLong(map["userId"] ?: map["sender"] ?: map["senderId"])),
                text = text,
                time = asLong(map["time"] ?: map["updateTime"]),
                cid = asLong(map["cid"]),
                state = (map["state"] as? Number)?.toInt() ?: 0,
                attachments = attachments,
                isForward = isForward,
                forwardType = forwardType,
                edited = isEditedMap(map),
                reactions = parseReactionInfo(map["reactionInfo"] as? Map<*, *>)
            )
        } catch (e: Exception) {
            null
        }
    }

    // Инициализация — в конце класса: все свойства уже созданы, поэтому
    // корутины ниже не могут обратиться к ещё не инициализированным полям.
    init {
        // Restore read marks from previous sessions so checkmarks survive restart
        try {
            secureStorage.getReadMarks().forEach { (chat, marks) ->
                if (marks.first != 0L) lastReadIdMark[chat] = marks.first
                if (marks.second != 0L) lastReadTsMark[chat] = marks.second
            }
            if (lastReadIdMark.isNotEmpty() || lastReadTsMark.isNotEmpty()) {
                Log.d(TAG, "Restored idMarks=${lastReadIdMark.size} tsMarks=${lastReadTsMark.size}")
            }
        } catch (_: Exception) {}
        // Replay already received AUTH_SYNC/messages + восстановление очереди
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.frames.replayCache.forEach { frame ->
                handleFrame(frame)
                if (isAuthSyncFrame(frame)) onAuthSync()
            }
            // Восстанавливаем очередь активного аккаунта (если остались неотправленные)
            refreshOutbox()
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.observeFrames().collect { frame ->
                handleFrame(frame)
                if (isAuthSyncFrame(frame)) onAuthSync()
            }
        }
        // Автоотправка очереди, когда сессия стала авторизованной (закрывает
        // гонку «AUTH_SYNC-кадр пришёл раньше, чем выставился authState»).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.authState.collect { authed ->
                if (authed) {
                    delay(300)
                    flushOutbox()
                }
            }
        }
        // Auto-retry pending load when reconnected; on every reconnect back to
        // active also silently reload the open chat to fill gaps missed offline.
        // loadMessages() reuses the cache, so this never flashes a loader.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.state.collect { st ->
                val active = st.isActive()
                if (!active) {
                    // Сокет упал: всё «в полёте» снова ждёт отправки
                    synchronized(inflight) { inflight.clear() }
                    _inflightCids.value = emptySet()
                }
                if (active && !wasActive && currentChatId != NO_CHAT) {
                    delay(600)
                    if (client.isConnected()) {
                        android.util.Log.d(TAG, "Reloading chat $currentChatId after reconnect")
                        loadMessages(currentChatId, 40)
                    }
                }
                wasActive = active
            }
        }
        startTypingCleaner()
    }

    fun clear() {
        _messages.value = emptyList()
        currentChatId = NO_CHAT
        lastReadIdMark.clear()
        lastReadTsMark.clear()
        _typing.value = emptyMap()
        synchronized(chatCache) { chatCache.clear() }
        synchronized(loadingChats) {
            loadingChats.clear()
            _loadingChats.value = emptySet()
            _isLoading.value = false
        }
        synchronized(loadedChats) { loadedChats.clear() }
        synchronized(exhaustedChats) { exhaustedChats.clear() }
        synchronized(loadSeqChat) { loadSeqChat.clear() }
        synchronized(attachResend) { attachResend.clear() }
        synchronized(failedCids) { failedCids.clear() }
        // При смене аккаунта перечитываем очередь нового (у каждого она своя)
        synchronized(inflight) { inflight.clear() }
        _inflightCids.value = emptySet()
        refreshOutbox()
    }
}
