package com.freemax.app.core.storage

import android.content.Context
import com.freemax.api.model.Attachment
import com.freemax.api.model.Chat
import com.freemax.api.model.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальный кэш чатов и сообщений — чтобы приложение работало без интернета:
 * список чатов и последние сообщения читаются с диска сразу при запуске,
 * а сеть только дополняет их свежими данными.
 *
 * Хранится в filesDir/local_cache/acc_<viewerId>/ (у каждого аккаунта свой кэш):
 *   chats.json          — список чатов
 *   messages/<chatId>.json — последние N сообщений чата
 *
 * Формат — JSON (org.json), запись атомарная (tmp + rename).
 */
@Singleton
class LocalCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val crypto: CryptoStore
) {
    companion object {
        private const val MAX_MESSAGES_PER_CHAT = 500
        private const val MAX_CHATS = 300
    }

    private val root: File get() = File(context.filesDir, "local_cache")

    /** Каталог активного аккаунта; при смене аккаунта меняется автоматически. */
    private fun accountDir(): File {
        val id = secureStorage.getActiveAccountId().takeIf { it > 0 }
            ?: secureStorage.getUserId().takeIf { it > 0 }
            ?: 0L
        return File(root, "acc_$id").apply { mkdirs() }
    }

    /** Полная очистка кэша (например, при выходе из всех аккаунтов). */
    fun clearAll() {
        runCatching { root.deleteRecursively() }
    }

    /** Кэш конкретного аккаунта (при удалении аккаунта). */
    fun clearAccount(viewerId: Long) {
        runCatching { File(root, "acc_$viewerId").deleteRecursively() }
    }

    // ------------------------------------------------------------------
    // Чаты
    // ------------------------------------------------------------------

    fun loadChats(): List<Chat> {
        val file = File(accountDir(), "chats.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = crypto.readText(file) ?: return emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::chatFromJson)
            }
        }.getOrDefault(emptyList())
    }

    fun saveChats(chats: List<Chat>) {
        val list = chats.take(MAX_CHATS)
        val arr = JSONArray()
        list.forEach { arr.put(chatToJson(it)) }
        writeAtomic(File(accountDir(), "chats.json"), arr.toString())
    }

    // ------------------------------------------------------------------
    // Сообщения
    // ------------------------------------------------------------------

    fun loadMessages(chatId: Long): List<Message> {
        val file = messagesFile(chatId)
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = crypto.readText(file) ?: return emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::messageFromJson)
            }
        }.getOrDefault(emptyList())
    }

    fun saveMessages(chatId: Long, messages: List<Message>) {
        val list = messages.takeLast(MAX_MESSAGES_PER_CHAT)
        val arr = JSONArray()
        list.forEach { arr.put(messageToJson(it)) }
        val dir = File(accountDir(), "messages").apply { mkdirs() }
        writeAtomic(File(dir, "$chatId.json"), arr.toString())
    }

    fun removeMessages(chatId: Long) {
        runCatching { messagesFile(chatId).delete() }
    }

    private fun messagesFile(chatId: Long): File =
        File(File(accountDir(), "messages"), "$chatId.json")

    // ------------------------------------------------------------------
    // Черновики ввода (per-chat, локально)
    // ------------------------------------------------------------------

    private fun draftsFile(): File = File(accountDir(), "drafts.json")

    /** Черновик для чата (пусто — черновика нет). 0 — валидный чат «Избранное». */
    fun loadDraft(chatId: Long): String {
        val f = draftsFile()
        if (!f.exists()) return ""
        return runCatching {
            org.json.JSONObject(crypto.readText(f) ?: "").optString(chatId.toString(), "")
        }.getOrDefault("").let { if (it == "null") "" else it }
    }

    /** Сохраняет черновик; пустой текст удаляет запись. 0 — валидный чат «Избранное». */
    fun saveDraft(chatId: Long, text: String) {
        runCatching {
            val f = draftsFile()
            val obj = if (f.exists()) {
                try { org.json.JSONObject(crypto.readText(f) ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
            } else org.json.JSONObject()
            if (text.isBlank()) obj.remove(chatId.toString()) else obj.put(chatId.toString(), text)
            if (obj.length() == 0) { f.delete(); return }
            writeAtomic(f, obj.toString())
        }
    }

    /** Полная очистка кэша активного аккаунта (сообщения/чаты/черновики). */
    fun clearCurrentAccount() {
        runCatching { accountDir().deleteRecursively() }
    }

    /**
     * Автоочистка по времени: удаляет кэш сообщений чатов, которые не обновлялись
     * [days] дней. Черновики и список чатов не трогаем. Возвращает число удалённых файлов.
     */
    fun autoClearIfNeeded(days: Int): Int {
        if (days <= 0) return 0
        return try {
            val cutoff = System.currentTimeMillis() - days * 24L * 3600L * 1000L
            val dir = File(accountDir(), "messages")
            var removed = 0
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff && f.delete()) removed++
            }
            android.util.Log.d("LocalCache", "autoClear days=$days removed=$removed")
            removed
        } catch (e: Exception) {
            android.util.Log.e("LocalCache", "autoClear failed", e)
            0
        }
    }

    /** Размер кэша сообщений в байтах (для настроек). */
    fun messagesCacheSize(): Long {
        return try {
            File(accountDir(), "messages").listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** DEBUG: все закэшированные сообщения (файл -> сообщения) для восстановления. */
    fun dumpAllCachedMessages(): Map<String, List<Message>> {
        val dir = File(accountDir(), "messages")
        val out = LinkedHashMap<String, List<Message>>()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            try {
                val text = crypto.readText(f) ?: return@forEach
                val arr = JSONArray(text)
                val msgs = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let(::messageFromJson)
                }
                if (msgs.isNotEmpty()) out[f.nameWithoutExtension] = msgs
            } catch (_: Exception) {
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Сериализация
    // ------------------------------------------------------------------

    private fun writeAtomic(file: File, text: String) {
        // Шифрование AES-GCM (ключ в Keystore) — внутри CryptoStore
        crypto.writeAtomic(file, text)
    }

    private fun chatToJson(c: Chat): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("serverId", c.serverId)
        put("type", c.type)
        put("status", c.status)
        put("title", c.title)
        put("lastMessageId", c.lastMessageId)
        put("lastEventTime", c.lastEventTime)
        put("newMessages", c.newMessages)
        put("cid", c.cid)
        put("description", c.description)
        put("link", c.link)
        put("iconUrl", c.iconUrl)
        put("ownerId", c.ownerId)
        put("adminIds", JSONArray(c.adminIds))
        put("isSavedMessages", c.isSavedMessages)
    }

    private fun chatFromJson(o: JSONObject): Chat = Chat(
        id = o.optLong("id"),
        serverId = o.optLong("serverId"),
        type = o.optInt("type"),
        status = o.optInt("status"),
        title = o.optString("title"),
        lastMessageId = o.optLong("lastMessageId"),
        lastEventTime = o.optLong("lastEventTime"),
        newMessages = o.optInt("newMessages"),
        cid = o.optLong("cid"),
        description = o.optString("description"),
        link = o.optString("link").takeIf { it.isNotBlank() && it != "null" },
        iconUrl = o.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" },
        ownerId = o.optLong("ownerId"),
        adminIds = o.optJSONArray("adminIds")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList(),
        // Обратная совместимость со старым кэшем: id=0 + DIALOG = «Избранное»
        isSavedMessages = o.optBoolean("isSavedMessages") || (o.optLong("id") == 0L && o.optInt("type") == 1)
    )

    private fun messageToJson(m: Message): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("mid", m.mid)
        put("chatId", m.chatId)
        put("userId", m.userId)
        put("text", m.text)
        put("time", m.time)
        put("cid", m.cid)
        put("replyTo", m.replyTo)
        put("state", m.state)
        put("isService", m.isService)
        put("isForward", m.isForward)
        put("forwardType", m.forwardType)
        put("quoteText", m.quoteText)
        put("edited", m.edited)
        put("reactions", JSONArray().apply {
            m.reactions.forEach {
                put(JSONObject().apply {
                    put("emoji", it.emoji)
                    put("count", it.count)
                    put("mine", it.mine)
                })
            }
        })
        put("attachments", JSONArray().apply { m.attachments.forEach { put(attachToJson(it)) } })
        put("quoteAttachments", JSONArray().apply { m.quoteAttachments.forEach { put(attachToJson(it)) } })
    }

    private fun messageFromJson(o: JSONObject): Message = Message(
        id = o.optLong("id"),
        mid = o.optLong("mid"),
        chatId = o.optLong("chatId"),
        userId = o.optLong("userId"),
        text = o.optString("text"),
        time = o.optLong("time"),
        cid = o.optLong("cid"),
        replyTo = o.optLong("replyTo"),
        state = o.optInt("state"),
        isService = o.optBoolean("isService"),
        isForward = o.optBoolean("isForward"),
        forwardType = o.optString("forwardType").takeIf { it.isNotBlank() && it != "null" },
        quoteText = o.optString("quoteText").takeIf { it.isNotBlank() && it != "null" },
        edited = o.optBoolean("edited"),
        reactions = o.optJSONArray("reactions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    com.freemax.api.model.Reaction(
                        emoji = it.optString("emoji"),
                        count = it.optInt("count"),
                        mine = it.optBoolean("mine")
                    )
                }
            }
        } ?: emptyList(),
        attachments = o.optJSONArray("attachments")?.let(::attachmentsFromJson) ?: emptyList(),
        quoteAttachments = o.optJSONArray("quoteAttachments")?.let(::attachmentsFromJson) ?: emptyList()
    )

    private fun attachmentsFromJson(arr: JSONArray): List<Attachment> =
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::attachFromJson) }

    private fun attachToJson(a: Attachment): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("type", a.type)
        put("url", a.url)
        put("name", a.name)
        put("size", a.size)
        put("fileId", a.fileId)
        put("width", a.width)
        put("height", a.height)
        put("durationMs", a.durationMs)
        put("photoUrl", a.photoUrl)
        put("videoUrl", a.videoUrl)
        put("audioUrl", a.audioUrl)
        put("stickerId", a.stickerId)
        put("thumbUrl", a.thumbUrl)
        put("title", a.title)
        put("description", a.description)
        put("targetUrl", a.targetUrl)
        put("phone", a.phone)
        put("extra", a.extra)
        put("pollOptions", JSONArray(a.pollOptions))
    }

    private fun attachFromJson(o: JSONObject): Attachment = Attachment(
        id = o.optLong("id"),
        type = o.optInt("type"),
        url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
        name = o.optString("name").takeIf { it.isNotBlank() && it != "null" },
        size = o.optLong("size"),
        fileId = o.optString("fileId").takeIf { it.isNotBlank() && it != "null" },
        width = o.optInt("width"),
        height = o.optInt("height"),
        durationMs = o.optLong("durationMs"),
        photoUrl = o.optString("photoUrl").takeIf { it.isNotBlank() && it != "null" },
        videoUrl = o.optString("videoUrl").takeIf { it.isNotBlank() && it != "null" },
        audioUrl = o.optString("audioUrl").takeIf { it.isNotBlank() && it != "null" },
        stickerId = if (o.has("stickerId") && !o.isNull("stickerId")) o.optLong("stickerId") else null,
        thumbUrl = o.optString("thumbUrl").takeIf { it.isNotBlank() && it != "null" },
        title = o.optString("title").takeIf { it.isNotBlank() && it != "null" },
        description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
        targetUrl = o.optString("targetUrl").takeIf { it.isNotBlank() && it != "null" },
        phone = o.optString("phone").takeIf { it.isNotBlank() && it != "null" },
        extra = o.optString("extra").takeIf { it.isNotBlank() && it != "null" },
        pollOptions = o.optJSONArray("pollOptions")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    )
}
