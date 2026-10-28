package com.freemax.api

import com.freemax.api.protocol.BinaryCodec
import com.freemax.api.protocol.ConnectionState
import com.freemax.api.protocol.Opcodes
import com.freemax.api.protocol.WsFrame
import com.freemax.api.transport.MaxWebSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MaxClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "MaxClient"
    }

    private val webSocket = MaxWebSocket(scope) { onConnected() }
    private var seqCounter = 0
    private var loggedIn = false
    private var savedAuth: Map<String, Any?>? = null
    private var awaitingLoginConfig = false

    val state: StateFlow<ConnectionState> = webSocket.state
    val frames: SharedFlow<WsFrame> = webSocket.frames

    private val _authed = MutableStateFlow(false)
    val authState: StateFlow<Boolean> = _authed.asStateFlow()

    init {
        scope.launch {
            webSocket.frames.collect { frame ->
                if (frame.opcode == Opcodes.LOGIN && savedAuth != null) {
                    handleLoginConfigResponse(frame)
                }
                if (frame.opcode == Opcodes.AUTH_SYNC &&
                    frame.cmd == BinaryCodec.CMD_RESPONSE &&
                    frame.payload?.containsKey("profile") == true
                ) {
                    loggedIn = true
                    _authed.value = true
                    println("[$TAG] AUTH_SYNC ok, session authenticated")
                }
            }
        }
    }

    fun version(): String = "0.1.0"

    fun connect() {
        loggedIn = false
        _authed.value = false
        awaitingLoginConfig = false
        webSocket.connect()
    }

    fun connectWithToken(token: String, viewerId: Long) {
        savedAuth = mapOf(
            "token" to token,
            "viewerId" to viewerId
        )
        loggedIn = false
        _authed.value = false
        awaitingLoginConfig = false
        webSocket.connect()
    }

    /**
     * Login with saved token (from WebView).
     * Sends LOGIN (opcode 6) with savedAuth — same as web client reconnect.
     */
    fun loginWithToken(token: String, viewerId: Long): Int {
        savedAuth = mapOf(
            "token" to token,
            "viewerId" to viewerId
        )
        // If already connected, send LOGIN now; otherwise onConnected will do it
        if (isConnected()) {
            val seq = nextSeq()
            val payload = userAgentPayload()
            webSocket.sendRequestWithAuth(seq, Opcodes.LOGIN, savedAuth!!, payload)
            return seq
        }
        return -1
    }

    private fun userAgentPayload(): Map<String, Any?> = mapOf(
        "userAgent" to mapOf(
            "deviceType" to "WEB",
            "pushDeviceType" to "WEBPUSH",
            "locale" to "ru",
            "deviceLocale" to "ru",
            "osVersion" to "16",
            "deviceName" to "Android SDK 36",
            "headerUserAgent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
            "isPwa" to false,
            "appVersion" to "26.9.1",
            "screen" to "1080x2340 2.75x",
            "timezone" to "Europe/Moscow"
        ),
        "deviceId" to java.util.UUID.randomUUID().toString()
    )

    private fun onConnected() {
        // Web client sends LOGIN anonymously first, even with savedAuth
        // Token is sent via AUTH_SYNC (19) after config.
        // Reset the flag on every (re)connect, otherwise AUTH_SYNC is sent only
        // once and all requests after a reconnect fail with "Must be ONLINE session".
        awaitingLoginConfig = false
        loggedIn = false
        _authed.value = false
        println("[$TAG] Connected, sending LOGIN")
        sendLogin()
    }

    private fun handleLoginConfigResponse(frame: WsFrame) {
        if (frame.cmd != BinaryCodec.CMD_RESPONSE) return
        if (awaitingLoginConfig) return
        awaitingLoginConfig = true

        val auth = savedAuth ?: return
        val token = auth["token"]?.toString() ?: return

        println("[$TAG] Got LOGIN config, sending AUTH_SYNC (19) with token")

        // Web client: th.cmd(19,{token:c.savedAuth.token,chatsCount:50,lastLogin:...,interactive:...})
        val seq = nextSeq()
        val syncPayload = mapOf<String, Any?>(
            "token" to token,
            "chatsCount" to 50,
            "lastLogin" to 0,
            "interactive" to true
        )
        webSocket.sendRequest(seq, Opcodes.AUTH_SYNC, syncPayload)
    }

    fun disconnect() {
        loggedIn = false
        _authed.value = false
        // Drop saved auth too, otherwise the next anonymous LOGIN auto re-auths
        // with the stale token and the user is logged straight back in.
        savedAuth = null
        awaitingLoginConfig = false
        webSocket.disconnect()
    }

    fun isConnected(): Boolean = state.value.isActive()

    /** Socket open AND AUTH_SYNC completed. Data requests must wait for this. */
    fun isAuthed(): Boolean = _authed.value && state.value.isActive()

    /**
     * Send LOGIN command (opcode 6) — must be first after connection.
     * This tells the server who we are (user-agent, device info).
     */
    fun sendLogin(): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.LOGIN, userAgentPayload())
        return seq
    }

    /**
     * Send phone auth request (opcode 224) — new auth flow.
     * Returns trackId when server responds.
     */
    fun requestCode(phone: String, language: String = "ru"): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "source" to "auth",
            "identifier" to phone
        )
        webSocket.sendRequest(seq, Opcodes.PHONE_AUTH_REQUEST, payload)
        return seq
    }

    /**
     * Send verify code (opcode 110) — verify the SMS code.
     */
    fun verifyCode(trackId: String, code: String): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "trackId" to trackId,
            "verifyCode" to code
        )
        webSocket.sendRequest(seq, Opcodes.AUTH_EMAIL_VERIFY, payload)
        return seq
    }

    /**
     * Legacy auth request (opcode 17) — old flow.
     */
    fun requestCodeLegacy(phone: String, language: String = "ru"): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "phone" to phone,
            "type" to 0,
            "language" to language
        )
        webSocket.sendRequest(seq, Opcodes.AUTH_REQUEST, payload)
        return seq
    }

    /**
     * Legacy verify code (opcode 18) — old flow.
     */
    fun verifyCodeLegacy(token: String, code: String): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "token" to token,
            "verifyCode" to code,
            "authTokenType" to "CHECK_CODE"
        )
        webSocket.sendRequest(seq, Opcodes.AUTH, payload)
        return seq
    }

    fun register(token: String, firstName: String, lastName: String): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "token" to token,
            "tokenType" to "REGISTER",
            "firstName" to firstName,
            "lastName" to lastName
        )
        webSocket.sendRequest(seq, Opcodes.REGISTER, payload)
        return seq
    }

    fun loginPassword(trackId: String, password: String): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "trackId" to trackId,
            "password" to password
        )
        webSocket.sendRequest(seq, Opcodes.LOGIN_PASSWORD, payload)
        return seq
    }

    // --- Chats ---

    fun loadChats(marker: String? = null): Int {
        val seq = nextSeq()
        val payload = if (marker != null) mapOf("marker" to marker) else null
        webSocket.sendRequest(seq, Opcodes.LOAD_CHATS, payload)
        return seq
    }

    fun loadMoreChats(marker: String): Int {
        val seq = nextSeq()
        val payload = mapOf("marker" to marker)
        webSocket.sendRequest(seq, Opcodes.LOAD_MORE_CHATS, payload)
        return seq
    }

    fun openChat(chatId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf("chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId))
        webSocket.sendRequest(seq, Opcodes.OPEN_CHAT, payload)
        return seq
    }

    fun subscribeChat(chatId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "subscribe" to true
        )
        webSocket.sendRequest(seq, Opcodes.SUBSCRIBE_UNSUBSCRIBE, payload)
        return seq
    }

    fun getChatInfo(chatId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf("chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId))
        webSocket.sendRequest(seq, Opcodes.GET_CHAT_INFO, payload)
        return seq
    }

    fun searchChats(query: String, count: Int = 40): Int {
        val seq = nextSeq()
        val payload = mapOf("query" to query, "count" to count)
        webSocket.sendRequest(seq, Opcodes.SEARCH_CHATS, payload)
        return seq
    }

    // --- Messages ---

    fun loadMessages(chatId: Long, count: Int = 40, before: Long = 0): Int {
        val seq = nextSeq()
        val fromVal = if (before > 0) before else System.currentTimeMillis()
        val payload = mutableMapOf<String, Any?>(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "from" to com.freemax.api.protocol.MsgPack.BigInt(fromVal),
            "forward" to 0,
            "backward" to count,
            "getMessages" to true
        )
        webSocket.sendRequest(seq, Opcodes.LOAD_MESSAGES, payload)
        return seq
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        postId: Long = 0,
        replyToId: Long? = null,
        attaches: List<Map<String, Any?>> = emptyList()
    ): Int {
        val seq = nextSeq()
        val cid = if (postId != 0L) postId else System.currentTimeMillis()
        val message = mutableMapOf<String, Any?>(
            "cid" to com.freemax.api.protocol.MsgPack.BigInt(cid),
            "text" to text,
            "elements" to emptyList<Any>(),
            "attaches" to attaches
        )
        if (replyToId != null && replyToId != 0L) {
            message["link"] = mapOf(
                "type" to "REPLY",
                "messageId" to com.freemax.api.protocol.MsgPack.BigInt(replyToId)
            )
        }
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "message" to message,
            "notify" to true
        )
        webSocket.sendRequest(seq, Opcodes.SEND_MESSAGE, payload)
        return seq
    }

    fun loadContacts(contactIds: List<Long>): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.LOAD_CONTACTS, mapOf("contactIds" to contactIds))
        return seq
    }

    /** Присутствие контактов (opcode 35): {presence: {userId: {seen, status}}}. */
    fun loadPresence(contactIds: List<Long>): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.CONTACTS_PRESENCE, mapOf("contactIds" to contactIds))
        return seq
    }

    /** Индикатор «печатает» (opcode 65). Сервер отвечает ACK, эффект — push 129. */
    fun sendTyping(chatId: Long): Int {
        val seq = nextSeq()
        webSocket.sendRequest(
            seq, Opcodes.SEND_TYPING,
            mapOf("chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId))
        )
        return seq
    }

    /**
     * Редактирование своего сообщения (opcode 67).
     * Обязательные поля по протоколу: messageId, text, elements, attachments.
     */
    fun editMessage(chatId: Long, messageId: Long, text: String): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "messageId" to com.freemax.api.protocol.MsgPack.BigInt(messageId),
            "text" to text,
            "elements" to emptyList<Any>(),
            "attachments" to emptyList<Any>()
        )
        webSocket.sendRequest(seq, Opcodes.EDIT_MESSAGE, payload)
        return seq
    }

    /** Поставить реакцию (opcode 178): reaction — объект {reactionType:EMOJI, id:"👍"}. */
    fun addReaction(chatId: Long, messageId: Long, emoji: String): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "messageId" to com.freemax.api.protocol.MsgPack.BigInt(messageId),
            "reaction" to mapOf("reactionType" to "EMOJI", "id" to emoji)
        )
        webSocket.sendRequest(seq, Opcodes.ADD_REACTION, payload)
        return seq
    }

    /** Снять свою реакцию (opcode 179). */
    fun removeReaction(chatId: Long, messageId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "messageId" to com.freemax.api.protocol.MsgPack.BigInt(messageId)
        )
        webSocket.sendRequest(seq, Opcodes.REMOVE_REACTION, payload)
        return seq
    }

    /** Реакции сразу для нескольких сообщений (opcode 180, bulk). */
    fun loadReactions(chatId: Long, messageIds: List<Long>): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "messageIds" to messageIds.map { com.freemax.api.protocol.MsgPack.BigInt(it) }
        )
        webSocket.sendRequest(seq, Opcodes.LOAD_REACTIONS, payload)
        return seq
    }

    /** Поиск по сообщениям одного чата (opcode 73). */
    fun searchMessages(chatId: Long, query: String, count: Int = 40): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "query" to query,
            "count" to count
        )
        webSocket.sendRequest(seq, Opcodes.SEARCH_MESSAGES, payload)
        return seq
    }

    /**
     * Вступление в чат по ссылке-приглашению (opcode 57).
     * Сервер ждёт либо link, либо token — отправляем то, что есть.
     */
    fun joinChat(link: String? = null, token: String? = null): Int {
        val seq = nextSeq()
        val payload = mutableMapOf<String, Any?>()
        if (!link.isNullOrBlank()) payload["link"] = link.trim()
        if (!token.isNullOrBlank()) payload["token"] = token.trim()
        webSocket.sendRequest(seq, Opcodes.JOIN_CHAT, payload)
        return seq
    }

    /**
     * Forward a message: link {type:FORWARD, messageId, chatId:sourceChatId}.
     * Returns the SEND seq (server echo carries our cid like a normal send).
     */
    fun forwardMessage(targetChatId: Long, sourceChatId: Long, messageId: Long): Int {
        val seq = nextSeq()
        val cid = System.currentTimeMillis()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(targetChatId),
            "message" to mapOf(
                "cid" to com.freemax.api.protocol.MsgPack.BigInt(cid),
                "text" to "",
                "elements" to emptyList<Any>(),
                "link" to mapOf(
                    "type" to "FORWARD",
                    "messageId" to com.freemax.api.protocol.MsgPack.BigInt(messageId),
                    "chatId" to com.freemax.api.protocol.MsgPack.BigInt(sourceChatId)
                ),
                "attaches" to emptyList<Any>()
            ),
            "notify" to true
        )
        webSocket.sendRequest(seq, Opcodes.SEND_MESSAGE, payload)
        return seq
    }

    fun deleteMessages(chatId: Long, messageIds: List<Long>, forAll: Boolean): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "messageIds" to messageIds,
            "forMe" to !forAll
        )
        webSocket.sendRequest(seq, Opcodes.DELETE_MESSAGES, payload)
        return seq
    }

    fun deleteChat(chatId: Long, forAll: Boolean, lastEventTime: Long): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "forAll" to forAll,
            "lastEventTime" to com.freemax.api.protocol.MsgPack.BigInt(lastEventTime)
        )
        webSocket.sendRequest(seq, Opcodes.DELETE_CHAT, payload)
        return seq
    }

    fun getPhotoUploadSlot(): Int {
        val seq = nextSeq()
        // Photo slots come from iu.oneme.ru/uploadImage and take multipart uploads
        webSocket.sendRequest(seq, Opcodes.PHOTO_UPLOAD_SLOT, mapOf("count" to 1))
        return seq
    }

    fun getFileUploadSlot(): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.FILE_UPLOAD_SLOT, mapOf("count" to 1))
        return seq
    }

    /**
     * Send a request and suspend until the response frame with the same seq arrives.
     * Used for upload slots. Returns null on timeout / disconnect.
     */
    suspend fun request(opcode: Int, payload: Map<String, Any?>?, timeoutMs: Long = 15000): WsFrame? {
        val seq = nextSeq()
        if (!isConnected()) return null
        webSocket.sendRequest(seq, opcode, payload)
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                frames.first { it.seq == seq && it.cmd == BinaryCodec.CMD_RESPONSE }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun markRead(chatId: Long, messageId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "messageId" to com.freemax.api.protocol.MsgPack.BigInt(messageId)
        )
        webSocket.sendRequest(seq, Opcodes.MARK_READ, payload)
        return seq
    }

    // --- Settings ---

    /**
     * Update own profile (mirrors web client op 16).
     * Only non-null fields are sent.
     */
    fun updateProfile(
        firstName: String? = null,
        lastName: String? = null,
        photoToken: String? = null,
        avatarType: String? = null
    ): Int {
        val seq = nextSeq()
        val payload = mutableMapOf<String, Any?>()
        if (firstName != null) payload["firstName"] = firstName
        if (lastName != null) payload["lastName"] = lastName
        if (photoToken != null) payload["photoToken"] = photoToken
        if (avatarType != null) payload["avatarType"] = avatarType
        webSocket.sendRequest(seq, 16, payload)
        return seq
    }

    /**
     * Edit group/channel (mirrors web client op 55). Only non-null fields sent.
     */
    fun editChat(
        chatId: Long,
        title: String? = null,
        photoToken: String? = null
    ): Int {
        val seq = nextSeq()
        val payload = mutableMapOf<String, Any?>(
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId)
        )
        if (title != null) payload["title"] = title
        if (photoToken != null) payload["photoToken"] = photoToken
        webSocket.sendRequest(seq, Opcodes.EDIT_CHAT, payload)
        return seq
    }

    fun getProfile(): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.GET_OWN_PROFILE)
        return seq
    }

    fun getConfig(): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.GET_CONFIG)
        return seq
    }

    // --- Contacts ---

    fun loadContactInfo(userIds: List<Long>): Int {
        val seq = nextSeq()
        // Try contactIds (server expects contactIds, not userIds for bulk)
        val payload = mapOf("contactIds" to userIds)
        webSocket.sendRequest(seq, Opcodes.LOAD_CONTACT_INFO, payload)
        return seq
    }
    fun loadContactInfoByUserIds(userIds: List<Long>): Int {
        val seq = nextSeq()
        val payload = mapOf("userIds" to userIds)
        webSocket.sendRequest(seq, Opcodes.LOAD_CONTACT_INFO, payload)
        return seq
    }

    fun loadParticipants(chatId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf("chatId" to chatId)
        webSocket.sendRequest(seq, Opcodes.LOAD_PARTICIPANTS, payload)
        return seq
    }

    fun loadContactById(contactId: Long): Int {
        val seq = nextSeq()
        val payload = mapOf("contactId" to contactId)
        webSocket.sendRequest(seq, Opcodes.LOAD_PARTICIPANTS, payload)
        return seq
    }

    fun loadMembers(chatId: Long, count: Int = 50): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "type" to "MEMBER",
            "chatId" to com.freemax.api.protocol.MsgPack.BigInt(chatId),
            "count" to count
        )
        webSocket.sendRequest(seq, Opcodes.SEARCH_PARTICIPANTS, payload)
        return seq
    }

    fun searchAll(query: String, count: Int = 40): Int {
        val seq = nextSeq()
        val payload = mapOf(
            "query" to query,
            "count" to count,
            "type" to "ALL"
        )
        webSocket.sendRequest(seq, Opcodes.SEARCH_ALL, payload)
        return seq
    }

    /** Поиск пользователя по номеру телефона (opcode 46). */
    fun lookupPhone(phone: String): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, Opcodes.LOOKUP_PHONE, mapOf("phone" to phone))
        return seq
    }

    fun sendRaw(opcode: Int, payload: Map<String, Any?>?): Int {
        val seq = nextSeq()
        webSocket.sendRequest(seq, opcode, payload)
        return seq
    }

    // --- Sync ---

    /**
     * Повторный AUTH_SYNC с токеном: сервер вернёт пропущенные chats/messages/
     * updates, это и есть инкрементальная синхронизация (так делает web.max.ru
     * при реконнекте). Opcode 27 (SYNC_SECTION) для чатов сервер НЕ принимает
     * ("No enum constant: CHATS" — он только для STICKER/REACTION/ANIMOJI_SET),
     * a LOAD_CHATS без marker тоже отвергается, поэтому используем 19.
     */
    fun syncChats(): Int {
        val token = savedAuth?.get("token")?.toString() ?: return -1
        val seq = nextSeq()
        webSocket.sendRequest(
            seq, Opcodes.AUTH_SYNC,
            mapOf(
                "token" to token,
                "chatsCount" to 50,
                "lastLogin" to 0,
                "interactive" to true
            )
        )
        return seq
    }

    // --- Helpers ---

    private fun nextSeq(): Int = ++seqCounter

    fun observeFrames(): Flow<WsFrame> = frames

    fun <T> filterFrames(opcode: Int, transform: (WsFrame) -> T?): Flow<T> {
        return frames.filter { it.opcode == opcode }
            .mapNotNull { transform(it) }
    }
}
