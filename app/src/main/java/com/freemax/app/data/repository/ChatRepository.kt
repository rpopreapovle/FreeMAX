package com.freemax.app.data.repository

import android.util.Log
import com.freemax.api.MaxClient
import com.freemax.api.model.Chat
import com.freemax.api.protocol.Opcodes
import com.freemax.api.protocol.WsFrame
import com.freemax.app.core.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val client: MaxClient,
    private val secureStorage: SecureStorage,
    private val localCache: com.freemax.app.core.storage.LocalCache
) {
    companion object {
        private const val TAG = "ChatRepo"
    }

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var marker: String? = null
    private var lastChatsList: List<Map<String, Any?>>? = null
    private val contactsCache = mutableMapOf<Long, ContactInfo>()
    private val requestedContactIds = mutableSetOf<Long>()

    private val _selfProfile = MutableStateFlow<SelfProfile?>(null)
    val selfProfile: StateFlow<SelfProfile?> = _selfProfile.asStateFlow()

    private val _members = MutableStateFlow<List<MemberInfo>>(emptyList())
    val members: StateFlow<List<MemberInfo>> = _members.asStateFlow()

    /** Присутствие: userId -> последний визит (ms). Пусто = неизвестно. */
    private val _presence = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val presence: StateFlow<Map<Long, Long>> = _presence.asStateFlow()

    /** Пользователи, про которых сервер сказал «онлайн» (status=1). */
    private val _online = MutableStateFlow<Set<Long>>(emptySet())
    val online: StateFlow<Set<Long>> = _online.asStateFlow()

    /** Запросить присутствие у сервера (opcode 35). Сервер ждёт «сырые» D2-id. */
    fun requestPresence(userIds: Collection<Long>) {
        val ids = userIds.map { normalizeId(it) }
            .filter { it != 0L }
            .distinct()
            .map { rawIdByNormalized[it] ?: it }
            .distinct()
            .take(50)
        if (ids.isEmpty()) return
        try { client.loadPresence(ids) } catch (_: Exception) {}
    }

    private fun applyPresence(rawUserId: Long, seen: Long, online: Boolean?) {
        val uid = normalizeId(rawUserId)
        if (uid == 0L) return
        if (seen > 0) {
            val cur = _presence.value
            if ((cur[uid] ?: 0L) < seen) _presence.value = cur + (uid to seen)
        }
        if (online == true) {
            if (uid !in _online.value) _online.value = _online.value + uid
        } else if (online == false) {
            if (uid in _online.value) _online.value = _online.value - uid
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handlePresenceResponse(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            val rawPresence = payload["presence"] ?: return
            val presence: Map<*, *> = when (rawPresence) {
                is Map<*, *> -> rawPresence
                is List<*> -> rawPresence.mapNotNull { item ->
                    val m = item as? Map<*, *> ?: return@mapNotNull null
                    val uid = m["userId"] ?: m["id"] ?: return@mapNotNull null
                    uid to m
                }.toMap()
                else -> return
            }
            var applied = 0
            for ((k, v) in presence) {
                val uid = when (k) {
                    is Number -> k.toLong()
                    is String -> k.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val seen: Long
                val online: Boolean?
                when (v) {
                    is Map<*, *> -> {
                        seen = asLong(v["seen"])
                        online = (v["status"] as? Number)?.toInt()?.let { it == 1 }
                            ?: (v["on"] as? String)?.let { it.equals("ON", true) }
                    }
                    is Number -> {
                        seen = v.toLong()
                        online = null
                    }
                    else -> continue
                }
                val seenMs = if (seen in 1..99_999_999_999L) seen * 1000 else seen
                applyPresence(uid, seenMs, online)
                applied++
            }
            if (applied > 0) Log.d(TAG, "presence applied for $applied users")
        } catch (e: Exception) {
            Log.e(TAG, "handlePresenceResponse failed", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handlePresencePush(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            val uid = asLong(payload["userId"] ?: payload["contactId"] ?: payload["id"])
            val presence = payload["presence"] as? Map<*, *>
            val seen = asLong(presence?.get("seen"))
            val seenMs = if (seen in 1..99_999_999_999L) seen * 1000 else seen
            val status = (presence?.get("status") as? Number)?.toInt()
                ?: (payload["status"] as? Number)?.toInt()
            applyPresence(uid, seenMs, status?.let { it == 1 })
        } catch (e: Exception) {
            Log.e(TAG, "handlePresencePush failed", e)
        }
    }

    private fun putContact(
        rawCid: Long,
        name: String,
        phone: String?,
        photoUrl: String?,
        description: String? = null,
        about: String? = null
    ) {
        if (rawCid == 0L || name.isBlank()) return
        val cid = normalizeId(rawCid)
        val prev = contactsCache[cid]
        val info = ContactInfo(
            id = cid,
            name = name,
            phone = phone ?: prev?.phone,
            photoUrl = photoUrl ?: prev?.photoUrl,
            description = description ?: prev?.description,
            about = about ?: prev?.about
        )
        contactsCache[cid] = info
        if (rawCid != cid) {
            contactsCache[rawCid] = info
            // Запоминаем «сырой» id (D2…), его сервер ждёт в presence-запросах
            rawIdByNormalized[cid] = rawCid
        }
    }

    /** normalized id -> raw id из participants/contact (для presence и т.п.). */
    private val rawIdByNormalized = mutableMapOf<Long, Long>()

    @Suppress("UNCHECKED_CAST")
    fun contactInfoOf(c: Map<String, Any?>): Pair<Long, ContactInfo>? {
        val rawCid = asLong(c["id"] ?: c["contactId"] ?: c["userId"])
        if (rawCid == 0L) return null
        val names = c["names"] as? List<Map<String, Any?>>
        val primary = names?.firstOrNull { it["type"] == "ONEME" } ?: names?.firstOrNull()
        val displayName = primary?.get("name")?.toString()
            ?: c["name"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: c["phone"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val phone = c["phone"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        val photo = (c["baseUrl"] ?: c["baseRawUrl"] ?: c["photoUrl"])?.toString()?.takeIf { it.isNotBlank() }
        val description = c["description"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        return rawCid to ContactInfo(
            id = normalizeId(rawCid),
            name = displayName,
            firstName = primary?.get("firstName")?.toString()?.takeIf { it.isNotBlank() },
            lastName = primary?.get("lastName")?.toString()?.takeIf { it.isNotBlank() },
            phone = phone,
            photoUrl = photo,
            description = description,
            about = c["about"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun loadChats() {
        _isLoading.value = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < 25 && !client.isAuthed()) {
                kotlinx.coroutines.delay(400)
                attempts++
            }
            if (!client.isAuthed()) {
                _isLoading.value = false
                return@launch
            }
            client.loadChats(marker)
            // Fallback: if still not authed, state collector will retry
            if (!client.isAuthed()) {
                kotlinx.coroutines.delay(1500)
                if (client.isAuthed() && _chats.value.isEmpty()) client.loadChats(marker)
                else _isLoading.value = false
            }
        }
    }

    fun loadMoreChats() {
        val m = marker ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < 25 && !client.isAuthed()) {
                kotlinx.coroutines.delay(400)
                attempts++
            }
            client.loadMoreChats(m)
        }
    }

    fun openChat(chatId: Long) {
        client.openChat(chatId)
    }

    /** Публичный декодер id для SearchRepository (BigInt и т.п.). */
    fun asLongPublic(v: Any?): Long = asLong(v)

    private fun asLong(v: Any?): Long = when (v) {        is Number -> v.toLong()
        is ByteArray -> {
            android.util.Log.d("asLong", "ByteArray hex=${v.joinToString(""){"%02x".format(it)}} size=${v.size} first=${"%02x".format(v[0])}")
            if (v.isNotEmpty() && v[0].toInt() and 0xFF == 0xD2 && v.size == 5) {
                var r = 0L
                for (i in 1 until 5) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                if (r and 0x80000000L != 0L) r = r or -0x100000000L
                android.util.Log.d("asLong", " -> D2 decoded $r")
                r
            } else if (v.isNotEmpty() && v[0].toInt() and 0xFF == 0xCE && v.size == 5) {
                var r = 0L
                for (i in 1 until 5) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                android.util.Log.d("asLong", " -> CE decoded $r")
                r
            } else if (v.isNotEmpty() && (v[0].toInt() and 0xFF == 0xCF || v[0].toInt() and 0xFF == 0xD3) && v.size == 9) {
                var r = 0L
                for (i in 1 until 9) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                android.util.Log.d("asLong", " -> CF/D3 decoded $r")
                r
            } else if (v.isNotEmpty() && v[0].toInt() and 0xFF == 0xC7 && v.size >= 4 && v[2].toInt() and 0xFF == 0x01) {
                // Ext BigInt: C7 09 01 CF <8 bytes> or C7 09 01 D3 etc.
                // Payload starts after 3-byte header, first byte inside may be CF/D3 etc.
                var start = 3
                // If next byte is CF/D3/CE/D2 etc., skip its prefix and decode accordingly
                val inner = v[3].toInt() and 0xFF
                if (inner == 0xCF || inner == 0xD3) {
                    var r = 0L
                    for (i in 4 until v.size) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                    android.util.Log.d("asLong", " -> C7/CF BigInt decoded $r")
                    r
                } else if (inner == 0xCE || inner == 0xD2) {
                    var r = 0L
                    for (i in 4 until v.size) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                    if (inner == 0xD2 && r and 0x80000000L != 0L) r = r or -0x100000000L
                    android.util.Log.d("asLong", " -> C7/D2 BigInt decoded $r")
                    r
                } else {
                    var r = 0L
                    for (i in 3 until v.size) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                    android.util.Log.d("asLong", " -> C7 generic ext decoded $r")
                    r
                }
            } else {
                var r = 0L; for (b in v) r = (r shl 8) or (b.toInt() and 0xFF).toLong()
                android.util.Log.d("asLong", " -> generic $r")
                r
            }
        }
        is String -> v.toLongOrNull() ?: 0L.also { android.util.Log.d("asLong", "String $v -> $it") }
        else -> 0L.also { android.util.Log.d("asLong", "else $v -> 0") }
    }

    private var selfId: Long = secureStorage.getUserId()
    private fun normalizeId(id: Long): Long {
        // Only DIALOG-style ids 0xD2XXXXXXXX (upper byte D2) map to low32 signed.
        // Genuine 48/64-bit ids (group chats such as -1000000000000, message ids 117...) keep as is.
        if (id > 0xFFFFFFFFL) {
            val high = (id ushr 32) and 0xFF
            if (high == 0xD2L) {
                val low = id and 0xFFFFFFFFL
                return if (low and 0x80000000L != 0L) low or -0x100000000L else low
            }
        }
        return id
    }

    fun handleFrame(frame: WsFrame) {
        when (frame.opcode) {
            Opcodes.LOAD_CHATS, Opcodes.LOAD_MORE_CHATS, Opcodes.AUTH_SYNC -> {
                // Update selfId + own profile from profile.contact if present
                (frame.payload?.get("profile") as? Map<*, *>)?.let { p ->
                    val contact = p["contact"] as? Map<String, Any?>
                    val decoded = asLong(contact?.get("id"))
                    if (decoded != 0L) selfId = decoded
                    if (contact != null) {
                        contactInfoOf(contact)?.let { (_, info) ->
                            _selfProfile.value = SelfProfile(
                                contactId = info.id,
                                name = info.name,
                                firstName = info.firstName,
                                lastName = info.lastName,
                                phone = info.phone,
                                photoUrl = info.photoUrl,
                                description = info.description
                            )
                            putContact(rawCid = info.id, name = info.name, phone = info.phone, photoUrl = info.photoUrl, description = info.description, about = info.about)
                        }
                    }
                }
                handleChatsList(frame.payload, isLoadMore = frame.opcode == Opcodes.LOAD_MORE_CHATS)
                // AUTH_SYNC приносит presence одним пакетом
                handlePresenceResponse(frame.payload)
            }
            Opcodes.SYNC_SECTION -> handleChatsSync(frame.payload)
            Opcodes.LOAD_CONTACT_INFO, Opcodes.LOAD_PARTICIPANTS,
            Opcodes.LOAD_CONTACTS -> handleContactsResponse(frame.payload)
            Opcodes.GET_CHAT_INFO -> handleChatUpdate(frame.payload)
            Opcodes.SEARCH_PARTICIPANTS -> handleMembersResponse(frame.payload)
            Opcodes.NOTIF_CHAT -> handleChatUpdate(frame.payload)
            Opcodes.NOTIF_MESSAGE -> handleIncomingMessageForChatList(frame.payload)
            Opcodes.CONTACTS_PRESENCE -> handlePresenceResponse(frame.payload)
            Opcodes.NOTIF_PRESENCE -> handlePresencePush(frame.payload)
            else -> {
                // Generic: if payload looks like contacts, handle
                val p = frame.payload ?: return
                if (p.containsKey("contacts") || p.containsKey("contact") || p.containsKey("names") || p.containsKey("participants") || p.containsKey("chat")) {
                    handleContactsResponse(p)
                } else if (frame.cmd == 1) {
                    // Log unknown success for debug
                    android.util.Log.d(TAG, "Unhandled opcode 0x${frame.opcode.toString(16)} keys=${p.keys}")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleContactsResponse(payload: Map<String, Any?>?) {
        if (payload == null) {
            Log.d(TAG, "Contacts response null payload")
            return
        }
        Log.d(TAG, "Contacts response keys=${payload.keys} raw=${payload.toString().take(800)}")
        // If payload is GET_CHAT_INFO {chat:{...}}, unwrap
        var effectivePayload: Map<String, Any?> = payload
        (payload["chat"] as? Map<String, Any?>)?.let { chatMap ->
            // Try to extract contacts from chat participants
            effectivePayload = chatMap
            // Also directly try to build contacts from chat participants contacts
            val chatParticipants = chatMap["participants"] as? Map<*, *>
            if (chatParticipants != null) {
                val chatContacts = chatParticipants.values.mapNotNull { v ->
                    val vm = v as? Map<String, Any?> ?: return@mapNotNull null
                    (vm["contact"] as? Map<String, Any?>) ?: vm.takeIf { it.containsKey("names") }
                }
                if (chatContacts.isNotEmpty()) {
                    Log.d(TAG, "Contacts from chat participants: ${chatContacts.size}")
                    // Process directly
                    var addedChat = 0
                    chatContacts.forEach { c ->
                        val parsed = contactInfoOf(c) ?: return@forEach
                        val (rawCid, info) = parsed
                        if (!contactsCache.containsKey(info.id)) {
                            putContact(rawCid, info.name, info.phone, info.photoUrl, info.description, info.about)
                            addedChat++
                        }
                    }
                    if (addedChat > 0) {
                        Log.d(TAG, "Added $addedChat from chat info, cache size=${contactsCache.size}")
                        lastChatsList?.let { list ->
                            val updated = list.mapNotNull { parseChat(it, contactsCache)?.let(::mergeChatIdentity) }
                            if (updated.isNotEmpty()) {
                                _chats.value = applyLocalReadState(updated.sortedWith(compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }.thenByDescending { it.lastEventTime }))
                                Log.d(TAG, "Refreshed titles after chat info: ${updated.take(5).map { it.title }}")
                            }
                        }
                        return
                    }
                }
            }
        }
        // Try various shapes: {contacts:[...]}, {contact:[...]}, {participants:{...}}, {participants:[...]}
        var contacts: List<Map<String, Any?>>? = effectivePayload["contacts"] as? List<Map<String, Any?>>
            ?: effectivePayload["contact"] as? List<Map<String, Any?>>
            ?: effectivePayload["contactInfos"] as? List<Map<String, Any?>>
            ?: effectivePayload["users"] as? List<Map<String, Any?>>
            ?: (effectivePayload["payload"] as? Map<String, Any?>)?.get("contacts") as? List<Map<String, Any?>>
        // Handle LOAD_PARTICIPANTS shape: {participants: {userId: {contact: {...}, ...}}}
        if (contacts == null) {
            val participants = effectivePayload["participants"]
            if (participants is Map<*, *>) {
                contacts = participants.values.mapNotNull { v ->
                    val vm = v as? Map<String, Any?> ?: return@mapNotNull null
                    (vm["contact"] as? Map<String, Any?>) ?: vm.takeIf { it.containsKey("names") }
                }
            } else if (participants is List<*>) {
                contacts = participants.mapNotNull { v ->
                    val vm = v as? Map<String, Any?> ?: return@mapNotNull null
                    (vm["contact"] as? Map<String, Any?>) ?: vm.takeIf { it.containsKey("names") }
                }
            }
        }
        // Also try {participant:{contact:{...}}}
        if (contacts == null) {
            (effectivePayload["participant"] as? Map<String, Any?>)?.let { p ->
                val c = p["contact"] as? Map<String, Any?>
                if (c != null) contacts = listOf(c)
            }
        }
        // Direct contact: {contact:{...}} single map or payload itself is contact
        if (contacts == null) {
            (effectivePayload["contact"] as? Map<String, Any?>)?.let { c ->
                contacts = listOf(c)
            }
        }
        if (contacts == null && effectivePayload.containsKey("names")) {
            @Suppress("UNCHECKED_CAST")
            contacts = listOf(effectivePayload as Map<String, Any?>)
        }
        if (contacts == null) {
            Log.d(TAG, "Contacts response unrecognized shape, keys=${effectivePayload.keys}")
            return
        }
        Log.d(TAG, "Contacts response: ${contacts.size} contacts")
        var added = 0
        contacts.forEach { c ->
            val parsed = contactInfoOf(c) ?: return@forEach
            val (rawCid, info) = parsed
            val isNew = !contactsCache.containsKey(info.id)
            // Обновляем и уже закэшированные карточки: принудительный
            // LOAD_CONTACT_INFO должен приносить свежие «О себе», аватар и т.п.
            putContact(rawCid, info.name, info.phone, info.photoUrl, info.description, info.about)
            if (isNew) added++
        }
        Log.d(TAG, "Added $added contacts, cache size=${contactsCache.size}")
        // Участники группы могли быть загружены раньше карточек контактов —
        // подмешиваем в них свежие статусы/фото.
        refreshMemberContactDetails()
        // Refresh titles with new contacts
        lastChatsList?.let { list ->
            val updated = list.mapNotNull { parseChat(it, contactsCache)?.let(::mergeChatIdentity) }
            if (updated.isNotEmpty()) {
                _chats.value = applyLocalReadState(updated.sortedWith(compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }.thenByDescending { it.lastEventTime }))
                Log.d(TAG, "Refreshed titles after contacts: ${updated.take(5).map { it.title }}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleChatsList(payload: Map<String, Any?>?, isLoadMore: Boolean = false) {
        if (payload == null) return

        try {
            val chatsList = payload["chats"] as? List<Map<String, Any?>> ?: return
            val nextMarker = payload["marker"]?.toString() ?: payload["chatMarker"]?.toString()
            // Build contacts map for DIALOG title resolution
            (payload["contacts"] as? List<Map<String, Any?>>)?.forEach { c ->
                val parsed = contactInfoOf(c) ?: return@forEach
                putContact(parsed.first, parsed.second.name, parsed.second.phone, parsed.second.photoUrl, parsed.second.description, parsed.second.about)
            }
            if (contactsCache.isNotEmpty()) Log.d(TAG, "Contacts cache size=${contactsCache.size} sample=${contactsCache.entries.take(2)}")
            // Debug: log first 5 chats' raw id/cid
            chatsList.take(5).forEach { c ->
                fun toHex(v: Any?): String = when(v) {
                    is ByteArray -> v.joinToString("") { "%02x".format(it) } + " dec=" + run { var r=0L; for(b in v as ByteArray) r=(r shl 8) or (b.toInt() and 0xFF).toLong(); r.toString()}
                    is Number -> "${v.toLong()} hex=%x".format(v.toLong())
                    else -> v.toString()
                }
                Log.d(TAG, "Raw chat id=${toHex(c["id"])} cid=${toHex(c["cid"])} serverId=${toHex(c["serverId"])} type=${c["type"]} title=${c["title"]} lastMsgId=${toHex((c["lastMessage"] as? Map<*,*>)?.get("id"))}")
            }
            lastChatsList = chatsList

            marker = if (nextMarker == "null" || nextMarker.isNullOrBlank()) null else nextMarker

            val newChats = chatsList.mapNotNull { parseChat(it, contactsCache)?.let(::mergeChatIdentity) }
            val combinedRaw = if (isLoadMore) {
                val existing = _chats.value
                // Merge and dedup by id
                (existing + newChats).distinctBy { it.id }
                    .sortedWith(compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }.thenByDescending { it.lastEventTime })
            } else {
                newChats.sortedWith(compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }.thenByDescending { it.lastEventTime })
            }
            // Сервер может прислать устаревший newMessages — не даём бейджу
            // вернуться после того, как чат уже был прочитан.
            val combined = applyLocalReadState(combinedRaw)
            if (isLoadMore) {
                lastChatsList = ((lastChatsList ?: emptyList()) + chatsList)
            } else {
                lastChatsList = chatsList
            }
            _chats.value = combined
            persistChats(combined)
            _isLoading.value = false
            Log.d(TAG, "Loaded ${combined.size} chats (new ${newChats.size}, loadMore=$isLoadMore), titles=${combined.take(5).map { it.title }}")
            // Fetch real names for placeholder dialogs via LOAD_CONTACTS (opcode 32)
            if (!isLoadMore) {
                val missing = combined.filter { it.title == "Диалог" }.mapNotNull { ch ->
                    val raw = (lastChatsList ?: emptyList()).find { c -> asLong(c["id"]) == ch.id }
                    val parts = raw?.get("participants") as? Map<*, *>
                    parts?.keys?.map { k -> normalizeId(asLong(k)) }
                        ?.firstOrNull { pid -> pid != 0L && pid != normalizeId(selfId) && !contactsCache.containsKey(pid) }
                }.distinct().take(20)
                val toFetch = missing.filter { !requestedContactIds.contains(it) }
                if (toFetch.isNotEmpty()) {
                    Log.d(TAG, "Fetching ${toFetch.size} missing contacts: $toFetch")
                    requestedContactIds.addAll(toFetch)
                    client.loadContacts(toFetch)
                }
                val m = marker
                if (combined.any { it.title == "Диалог" } && m != null) {
                    Log.d(TAG, "Has placeholder titles, loading more chats marker=$m")
                    client.loadMoreChats(m)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse chats list", e)
            _isLoading.value = false
        }
    }

    /**
     * Инкрементальная синхронизация (SYNC_SECTION/CHATS): сервер может прислать
     * как полный список, так и только изменившиеся чаты. Мержим по id (новые
     * данные побеждают), чтобы не потерять локальный список и подтянуть
     * непрочитанные (Chat.newMessages) с сервера.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleChatsSync(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            val chatsList = payload["chats"] as? List<Map<String, Any?>> ?: return
            if (chatsList.isEmpty()) return
            (payload["contacts"] as? List<Map<String, Any?>>)?.forEach { c ->
                contactInfoOf(c)?.let { (raw, info) -> putContact(raw, info.name, info.phone, info.photoUrl, info.description, info.about) }
            }
            val byId = _chats.value.associateByTo(LinkedHashMap()) { it.id }
            var changed = 0
            for (raw in chatsList) {
                val parsed = parseChat(raw, contactsCache) ?: continue
                val prev = byId[parsed.id] ?: previousChatFor(parsed)
                val mergedChat = preserveChatIdentity(parsed, prev)
                if (prev == null || prev != mergedChat) {
                    byId[parsed.id] = mergedChat
                    changed++
                }
            }
            if (changed == 0) return
            val merged = applyLocalReadState(
                byId.values.sortedWith(
                    compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }
                        .thenByDescending { it.lastEventTime }
                )
            )
            _chats.value = merged
            persistChats(merged)
            Log.d(TAG, "SYNC_SECTION merged $changed chats (total ${merged.size})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle chats sync", e)
        }
    }

    /** Can I edit this group (title/avatar)? Owner or admin. Null = unknown. */
    fun canEditChat(chat: Chat): Boolean {
        val me = normalizeId(selfId)
        if (me == 0L) return false
        return chat.ownerId == me || chat.adminIds.contains(me)
    }

    /** Local optimistic update of title/icon; server confirms via chat updates. */
    fun updateChatLocal(chatId: Long, title: String? = null, iconUrl: String? = null) {
        _chats.value = _chats.value.map { c ->
            val normId = normalizeId(chatId)
            if (normalizeId(c.id) == normId || normalizeId(c.cid) == normId) {
                c.copy(
                    title = title ?: c.title,
                    iconUrl = iconUrl ?: c.iconUrl
                )
            } else c
        }
    }

    fun updateSelfProfile(profile: SelfProfile) {
        _selfProfile.value = profile
        if (profile.name.isNotBlank()) {
            putContact(profile.contactId, profile.name, profile.phone, profile.photoUrl, profile.description)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleChatUpdate(payload: Map<String, Any?>?) {
        if (payload == null) return

        try {
            // GET_CHAT_INFO wraps into {chat:{...}}
            val map = (payload["chat"] as? Map<String, Any?>) ?: payload
            val parsed = parseChat(map) ?: return
            val currentChats = _chats.value.toMutableList()
            val index = currentChats.indexOfFirst { it.id == parsed.id }
            val chat = preserveChatIdentity(parsed, currentChats.getOrNull(index))
            if (index >= 0) {
                currentChats[index] = chat
            } else {
                currentChats.add(0, chat)
            }
            _chats.value = applyLocalReadState(
                currentChats.sortedWith(
                    compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }
                        .thenByDescending { it.lastEventTime }
                )
            )
            persistChats(_chats.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle chat update", e)
        }
    }

    private fun handleIncomingMessageForChatList(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            // NOTIF_MESSAGE payload may be {message:{...}, chatId:...} or direct message.
            // 0 — валидный id «Избранного»: «нет chatId» — это null, а не 0.
            val msgMap = (payload["message"] as? Map<String, Any?>) ?: payload
            val targetId: Long? = when {
                payload.containsKey("chatId") -> normalizeId(asLong(payload["chatId"]))
                payload.containsKey("cid") -> normalizeId(asLong(payload["cid"]))
                msgMap.containsKey("chatId") -> normalizeId(asLong(msgMap["chatId"]))
                msgMap.containsKey("cid") -> normalizeId(asLong(msgMap["cid"]))
                else -> null
            }
            if (targetId == null) {
                Log.d(TAG, "NOTIF_MESSAGE without chatId, refreshing chats")
                try { client.loadChats(null) } catch (_: Exception) {}
                return
            }
            val text = msgMap["text"]?.toString() ?: ""
            val time = asLong(msgMap["time"] ?: msgMap["updateTime"] ?: System.currentTimeMillis())
            val current = _chats.value.toMutableList()
            val idx = current.indexOfFirst { normalizeId(it.id) == targetId || normalizeId(it.cid) == targetId }
            if (idx != -1) {
                val old = current[idx]
                // Update lastMessage preview and time, bump unread if not from self
                val senderId = normalizeId(asLong(msgMap["sender"] ?: msgMap["senderId"] ?: msgMap["userId"]))
                val isFromMe = senderId == normalizeId(selfId)
                val newDesc = if (text.isNotBlank()) text else {
                    attachLabel(msgMap["attaches"] as? List<*>).ifBlank { old.description }
                }
                // Server sends the true unread counter in the push — trust it.
                val serverUnread = (payload["unread"] as? Number)?.toInt()
                val updated = old.copy(
                    lastMessageId = asLong(msgMap["id"] ?: msgMap["mid"] ?: old.lastMessageId),
                    lastEventTime = time,
                    newMessages = serverUnread ?: if (isFromMe) old.newMessages else old.newMessages + 1,
                    description = newDesc
                )
                current[idx] = updated
                // Move to top and sort (pinned first)
                val moved = current.removeAt(idx)
                current.add(0, moved)
                _chats.value = applyLocalReadState(
                    current.sortedWith(
                        compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }
                            .thenByDescending { it.lastEventTime }
                    )
                )
            } else {
                // Unknown chat — request refresh
                Log.d(TAG, "NOTIF_MESSAGE for unknown chat $targetId, refreshing chats")
                // Trigger loadChats to fetch new chat
                // Use direct client call
                try { client.loadChats(null) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message for chat list", e)
        }
    }

    fun togglePin(chatId: Long) {
        secureStorage.togglePinChat(chatId)
        // Re-sort with new pin state
        _chats.value = _chats.value.sortedWith(compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }.thenByDescending { it.lastEventTime })
    }

    fun isPinned(chatId: Long): Boolean = secureStorage.isChatPinned(chatId)

    fun toggleMute(chatId: Long): Boolean = secureStorage.toggleMuteChat(normalizeId(chatId))

    fun isMuted(chatId: Long): Boolean = secureStorage.isChatMuted(normalizeId(chatId))

    fun parseChat(map: Map<String, Any?>, contactsMap: Map<Long, ContactInfo> = contactsCache): Chat? {
        return try {
            fun asInt(v: Any?): Int = asLong(v).toInt()
            fun asString(v: Any?): String? = when (v) {
                is ByteArray -> String(v, Charsets.UTF_8)
                else -> v?.toString()
            }
            val idVal = asLong(map["id"])
            val cidVal = asLong(map["cid"])
            val cid = if (cidVal != 0L) cidVal else idVal
            val id = if (idVal != 0L) idVal else cid
            val typeStr = asString(map["type"]) ?: ""
            val typeInt = when (typeStr) {
                "DIALOG" -> 1
                "CHAT" -> 2
                "CHANNEL" -> 3
                else -> asInt(map["type"])
            }
            // Title: for DIALOG resolve via contacts, otherwise use server title.
            // Saved messages («Избранное») — self-диалог с id=0/только собой.
            var title = map["title"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: map["name"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: ""
            val parts = map["participants"] as? Map<*, *>
            var hasSelf = false
            var hasOther = false
            val selfNorm = normalizeId(selfId)
            if (parts != null) {
                for ((k, _) in parts) {
                    val rawPid = asLong(k)
                    if (rawPid == 0L) continue
                    val pid = normalizeId(rawPid)
                    val lookupName = contactsMap[pid]?.name
                        ?: contactsMap[rawPid]?.name
                        ?: contactsMap[normalizeId(pid)]?.name
                    if (pid == selfNorm || rawPid == selfId) {
                        hasSelf = true
                    } else {
                        hasOther = true
                        // Prefer interlocutor (not self)
                        if (title.isBlank() && !lookupName.isNullOrBlank()) title = lookupName
                    }
                }
            }
            // Избранное: сервер не шлёт title, участник только сам пользователь.
            // Даже если контакт себя ещё не подгружен — id=0 у DIALOG это Saved Messages.
            val isSelfDialog = typeStr == "DIALOG" && !hasOther &&
                ((id == 0L && cid == 0L) || (parts != null && parts.isNotEmpty() && hasSelf))
            if (title.isBlank() && isSelfDialog) {
                title = "Избранное"
            }
            if (title.isBlank() && typeStr == "DIALOG") {
                // Will be fetched via LOAD_CONTACT_INFO, show placeholder not ID
                title = ""
            }
            // Final fallback without IDs — never show numeric id
            if (title.isBlank()) {
                title = when (typeInt) {
                    1 -> "Диалог" // DIALOG without known contact (no digits, no id)
                    2, 3 -> map["title"]?.toString()?.takeIf { it.isNotBlank() } ?: "Группа"
                    else -> "Чат"
                }
            }
            val lastMsg = map["lastMessage"] as? Map<*, *>
            var desc = map["description"]?.toString()?.takeIf { it.isNotBlank() && !it.startsWith("[{") && !it.startsWith("[") }
                ?: lastMsg?.get("text")?.toString()?.takeIf { it.isNotBlank() } ?: ""
            if (desc.isBlank()) {
                // Forwarded message text lives inside link.message
                val link = lastMsg?.get("link") as? Map<*, *>
                val fwd = link?.get("message") as? Map<*, *>
                desc = fwd?.get("text")?.toString()?.takeIf { it.isNotBlank() }
                    ?: link?.get("title")?.toString()?.takeIf { it.isNotBlank() } ?: ""
                if (desc.isBlank()) desc = attachLabel(lastMsg?.get("attaches") as? List<*>)
                if (desc.isBlank()) {
                    val fwdAttaches = fwd?.get("attaches") as? List<*>
                    desc = attachLabel(fwdAttaches)
                }
            }
            // Avatar: server icon first, then dialog peer photo from contacts
            var iconUrl: String? = (map["baseIconUrl"] ?: map["baseRawIconUrl"] ?: map["iconUrl"])
                ?.toString()?.takeIf { it.isNotBlank() }
                ?: (map["icon"] as? Map<*, *>)?.get("url")?.toString()?.takeIf { it.isNotBlank() }
            if (iconUrl == null && typeStr == "DIALOG") {
                val parts = map["participants"] as? Map<*, *>
                if (parts != null) {
                    val self = normalizeId(selfId)
                    for ((k, _) in parts) {
                        val pid = normalizeId(asLong(k))
                        if (pid == 0L || pid == self) continue
                        val photo = contactsMap[pid]?.photoUrl
                            ?: contactsMap[normalizeId(pid)]?.photoUrl
                        if (!photo.isNullOrBlank()) {
                            iconUrl = photo
                            break
                        }
                    }
                }
            }
            val ownerId = normalizeId(asLong(map["owner"]))
            val adminIds = ((map["adminParticipants"] as? Map<*, *>)?.keys
                ?: (map["admins"] as? List<*>)?.toSet()
                ?: emptySet<Any?>())
                .map { k -> normalizeId(asLong(k)) }
                .filter { it != 0L }
                .distinct()
            // Ссылка-приглашение: каналы всегда, группы — если сервер прислал
            val inviteLink = (map["link"] ?: map["joinLink"] ?: map["inviteLink"])
                ?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            Chat(
                id = id,
                serverId = asLong(map["serverId"]),
                type = typeInt,
                status = asInt(map["status"]),
                title = title,
                lastMessageId = asLong(map["lastMessageId"] ?: lastMsg?.get("id")),
                lastEventTime = asLong(map["lastEventTime"] ?: map["time"] ?: lastMsg?.get("time")),
                newMessages = asInt(map["newMessages"]),
                cid = cid,
                description = desc,
                link = inviteLink,
                iconUrl = iconUrl,
                ownerId = ownerId,
                adminIds = adminIds,
                isSavedMessages = isSelfDialog
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun attachLabel(attaches: List<*>?): String {
        if (attaches.isNullOrEmpty()) return ""
        val types = attaches.mapNotNull { (it as? Map<*, *>)?.get("_type")?.toString() }
        if (types.isEmpty()) return ""
        // Service messages carry no preview
        if (types.all { it == "CONTROL" || it == "WIDGET" }) return ""
        fun label(t: String) = when (t) {
            "PHOTO", "ALBUM" -> "Фото"
            "VIDEO" -> "Видео"
            "FILE" -> "Файл"
            "AUDIO" -> "Голосовое сообщение"
            "STICKER" -> "Стикер"
            "CALL" -> "Звонок"
            "CONTACT" -> "Контакт"
            "LOCATION" -> "Геопозиция"
            "POLL" -> "Опрос"
            "SHARE" -> "Репост"
            else -> ""
        }
        val labels = types.mapNotNull { label(it).takeIf { s -> s.isNotEmpty() } }.distinct()
        if (labels.isEmpty()) return ""
        return labels.joinToString(", ")
    }

    fun getContactName(userId: Long): String? {
        val norm = normalizeId(userId)
        return contactsCache[norm]?.name ?: contactsCache[userId]?.name
    }

    fun getContact(userId: Long): ContactInfo? {
        val norm = normalizeId(userId)
        return contactsCache[norm] ?: contactsCache[userId]
    }

    /** Other participant of a DIALOG (not self), or null. */
    fun dialogPeerId(chat: Chat): Long? {
        val raw = lastChatsList?.find { c -> asLong(c["id"]) == chat.id }
        val parts = raw?.get("participants") as? Map<*, *> ?: return null
        val self = normalizeId(selfId)
        return parts.keys.map { k -> normalizeId(asLong(k)) }
            .firstOrNull { it != 0L && it != self }
    }

    fun loadMembers(chatId: Long) {
        _members.value = emptyList()
        currentMembersChatId = normalizeId(chatId)
        client.loadMembers(chatId)
    }

    private var currentMembersChatId: Long = 0L

    /**
     * Вступить в чат по ссылке-приглашению (opcode 57).
     * Ссылка вида https://max.ru/join/<token> — токен достаём из хвоста,
     * но отправляем и полную ссылку: сервер сам выбирает, что ему нужно.
     */
    fun joinChat(link: String) {
        val clean = link.trim()
        if (clean.isBlank()) return
        val token = clean.substringAfterLast('/').takeIf { it.isNotBlank() && !it.contains('.') }
        client.joinChat(link = clean, token = token)
        // После вступления сервер пришлёт NOTIF_CHAT/обновление списка — просим
        // инкрементальную синхронизацию (AUTH_SYNC), а не LOAD_CHATS (opcode 48
        // сервер отвергает без корректного payload).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2000)
            if (client.isAuthed()) client.syncChats()
        }
    }

    /** Ссылка-приглашение чата, если сервер её прислал. */
    fun inviteLinkOf(chatId: Long): String? {
        return _chats.value.firstOrNull {
            normalizeId(it.id) == normalizeId(chatId) || normalizeId(it.cid) == normalizeId(chatId)
        }?.link
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleMembersResponse(payload: Map<String, Any?>?) {
        if (payload == null) return
        try {
            val members = payload["members"] as? List<Map<String, Any?>>
                ?: (payload["participants"] as? List<Map<String, Any?>>)
                ?: return
            val self = normalizeId(selfId)
            val chat = _chats.value.firstOrNull { it.id == currentMembersChatId }
            val ownerId = chat?.ownerId ?: 0L
            val adminIds = chat?.adminIds ?: emptyList()
            _members.value = members.mapNotNull { m ->
                val contact = (m as? Map<String, Any?>)?.get("contact") as? Map<String, Any?> ?: m as? Map<String, Any?>
                val parsed = contact?.let { contactInfoOf(it) } ?: return@mapNotNull null
                val (rawCid, info) = parsed
                if (!contactsCache.containsKey(info.id)) {
                    putContact(rawCid, info.name, info.phone, info.photoUrl, info.description, info.about)
                }
                MemberInfo(
                    contactId = info.id,
                    name = info.name,
                    phone = info.phone,
                    photoUrl = info.photoUrl,
                    isSelf = info.id == self,
                    role = when {
                        ownerId != 0L && info.id == ownerId -> "OWNER"
                        adminIds.contains(info.id) -> "ADMIN"
                        else -> "MEMBER"
                    },
                    about = info.about,
                    description = info.description
                )
            }.sortedWith(compareBy({ it.role == "MEMBER" }, { it.name.lowercase() }))
            Log.d(TAG, "Loaded ${_members.value.size} members")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse members", e)
        }
    }

    /**
     * Обновляет уже показанных участников данными из кэша контактов.
     * Так список группы получает «О себе», телефон и актуальный аватар
     * после LOAD_CONTACT_INFO / LOAD_CONTACTS.
     */
    private fun refreshMemberContactDetails() {
        val current = _members.value
        if (current.isEmpty()) return
        val updated = current.map { member ->
            val contact = contactsCache[normalizeId(member.contactId)]
                ?: contactsCache[member.contactId]
                ?: return@map member
            member.copy(
                name = contact.name.takeIf { it.isNotBlank() } ?: member.name,
                phone = contact.phone ?: member.phone,
                photoUrl = contact.photoUrl ?: member.photoUrl,
                about = contact.about ?: member.about,
                description = contact.description ?: member.description
            )
        }
        if (updated != current) _members.value = updated
    }

    fun deleteChat(chatId: Long, forAll: Boolean = false) {
        val chat = _chats.value.find {
            normalizeId(it.id) == normalizeId(chatId) || normalizeId(it.cid) == normalizeId(chatId)
        }
        client.deleteChat(chatId, forAll, chat?.lastEventTime ?: 0L)
        // Optimistic removal; server confirms via chat list updates
        _chats.value = _chats.value.filterNot {
            normalizeId(it.id) == normalizeId(chatId) || normalizeId(it.cid) == normalizeId(chatId)
        }
        lastChatsList = lastChatsList?.filterNot { c -> asLong(c["id"]) == chat?.id }
    }

    /** Fetch names for unknown senders (e.g. reply authors in groups). */
    fun ensureContacts(userIds: Collection<Long>, force: Boolean = false) {
        val toFetch = userIds.map { normalizeId(it) }
            .filter {
                it != 0L && it != normalizeId(selfId) &&
                    (force || (!contactsCache.containsKey(it) && !requestedContactIds.contains(it)))
            }
            .distinct().take(50)
        if (toFetch.isNotEmpty()) {
            requestedContactIds.addAll(toFetch)
            client.loadContacts(toFetch)
            if (force) {
                // Purpose-built request: full contact card (may carry «О себе»)
                try { client.loadContactInfo(toFetch) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Сервер иногда присылает DIALOG без title/icon (например, пока контакты
     * ещё не догрузились). Не затираем уже известное имя и аватар плейсхолдером
     * «Диалог» — иначе чат «теряет» имя при каждой перерисовке/рестарте.
     */
    private fun previousChatFor(chat: Chat): Chat? = _chats.value.firstOrNull { old ->
        old.id == chat.id || old.cid == chat.cid ||
            (chat.cid != 0L && old.id == chat.cid) || (old.cid != 0L && old.cid == chat.id)
    }

    private fun preserveChatIdentity(parsed: Chat, previous: Chat?): Chat {
        if (previous == null) return parsed
        val placeholder = parsed.title.isBlank() ||
            parsed.title == "Диалог" || parsed.title == "Группа" || parsed.title == "Чат"
        return parsed.copy(
            title = if (placeholder && previous.title.isNotBlank()) previous.title else parsed.title,
            iconUrl = parsed.iconUrl ?: previous.iconUrl
        )
    }

    private fun mergeChatIdentity(parsed: Chat): Chat =
        preserveChatIdentity(parsed, previousChatFor(parsed))

    /** Instantly clear the unread badge when a chat is opened/read. */
    fun markChatRead(chatId: Long) {
        val current = _chats.value
        val idx = current.indexOfFirst {
            normalizeId(it.id) == normalizeId(chatId) || normalizeId(it.cid) == normalizeId(chatId)
        }
        // Фиксируем факт открытия чата локально: даже если сервер при
        // следующей синхронизации пришлёт старый newMessages, бейдж
        // «99+» не вернётся. Точная метка сообщения обновится, когда
        // загрузятся сообщения (markAllLocallyRead).
        try {
            secureStorage.saveReadMarks(normalizeId(chatId), 0L, System.currentTimeMillis())
        } catch (_: Exception) {}
        if (idx != -1 && current[idx].newMessages != 0) {
            val updated = current.toMutableList()
            updated[idx] = updated[idx].copy(newMessages = 0)
            _chats.value = updated
            // Бейдж «99+» не должен возвращаться после перерисовки списка
            // или перезапуска: сохраняем прочитанное состояние на диск.
            persistChats(updated)
        }
    }

    /**
     * Перекрывает серверный счётчик непрочитанных локальными метками чтения.
     *
     * Сервер после перезапуска/перерисовки может снова прислать старый
     * `newMessages`, хотя последнее сообщение уже прочитано. Если id/time
     * последнего сообщения не больше сохранённой метки — чат считается
     * прочитанным, и бейдж не возвращается.
     */
    private fun applyLocalReadState(chats: List<Chat>): List<Chat> {
        if (chats.isEmpty()) return chats
        val marks = try {
            secureStorage.getReadMarks()
        } catch (_: Exception) {
            emptyMap()
        }
        if (marks.isEmpty()) return chats
        return chats.map { chat ->
            if (chat.newMessages == 0) return@map chat
            val keys = listOf(chat.id, normalizeId(chat.id), chat.cid, normalizeId(chat.cid))
                .filter { it != 0L }
                .distinct()
            val mark = keys.firstNotNullOfOrNull { marks[it] } ?: return@map chat
            val (idMark, tsMark) = mark
            val readById = idMark != 0L && chat.lastMessageId != 0L &&
                chat.lastMessageId <= idMark
            val readByTime = tsMark != 0L && chat.lastEventTime != 0L &&
                chat.lastEventTime <= tsMark
            if (readById || readByTime) chat.copy(newMessages = 0) else chat
        }
    }

    private fun persistChats(chats: List<Chat>) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                localCache.saveChats(chats)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache chats", e)
            }
        }
    }

    // Инициализация — в конце класса: все свойства уже созданы, поэтому
    // синхронный replay кадров и корутины ниже не могут обратиться к ещё
    // не инициализированным полям (_chats/_isLoading/marker/contactsCache/...).
    init {
        // Мгновенно показываем локальный кэш — работает и без интернета.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val cachedChats = localCache.loadChats()
                if (cachedChats.isNotEmpty() && _chats.value.isEmpty()) {
                    _chats.value = applyLocalReadState(
                        cachedChats.sortedWith(
                            compareByDescending<Chat> { secureStorage.isChatPinned(it.id) }
                                .thenByDescending { it.lastEventTime }
                        )
                    )
                    Log.d(TAG, "Loaded ${cachedChats.size} chats from local cache")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cached chats", e)
            }
        }
        // Handle already replayed frames (e.g., AUTH_SYNC received before repository creation)
        client.frames.replayCache.forEach { handleFrame(it) }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.observeFrames().collect { handleFrame(it) }
        }
        // Auto-retry chats when reconnected and still empty
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            client.state.collect { st ->
                if (st.isActive() && _chats.value.isEmpty() && !_isLoading.value) {
                    kotlinx.coroutines.delay(700)
                    if (_chats.value.isEmpty()) {
                        android.util.Log.d(TAG, "Retrying loadChats after reconnect")
                        loadChats()
                    }
                }
            }
        }
    }

    fun clear() {
        _chats.value = emptyList()
        marker = null
        requestedContactIds.clear()
    }
}
