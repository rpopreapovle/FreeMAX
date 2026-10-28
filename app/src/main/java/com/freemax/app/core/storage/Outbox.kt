package com.freemax.app.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Очередь исходящих сообщений (outbox): текст, который ещё не подтверждён сервером.
 * Хранится per-account рядом с кэшем — files/local_cache/acc_<id>/outbox.json —
 * поэтому переживает офлайн и перезапуск приложения. Элемент удаляется только
 * когда сервер пришлёт эхо SEND_MESSAGE с тем же cid.
 */
data class OutboxItem(
    val chatId: Long,
    val cid: Long,
    val text: String,
    val replyTo: Long = 0L,
    val createdAt: Long = 0L,
    val attempts: Int = 0
)

@Singleton
class Outbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val crypto: CryptoStore
) {
    companion object {
        /** Сколько автоотправок допускаем, прежде чем ждать ручного повтора (тап по сообщению). */
        const val MAX_ATTEMPTS = 5
    }

    private val lock = Any()

    private fun file(): File {
        val id = secureStorage.getActiveAccountId().takeIf { it > 0 }
            ?: secureStorage.getUserId().takeIf { it > 0 }
            ?: 0L
        val dir = File(File(context.filesDir, "local_cache"), "acc_$id").apply { mkdirs() }
        return File(dir, "outbox.json")
    }

    fun items(): List<OutboxItem> = synchronized(lock) { read() }

    fun itemsFor(chatId: Long): List<OutboxItem> =
        items().filter { it.chatId == chatId }

    /** Добавляет элемент или заменяет существующий с тем же cid (ручной повтор). */
    fun upsert(item: OutboxItem) = synchronized(lock) {
        write(read().filterNot { it.cid == item.cid } + item)
    }

    /** Увеличивает счётчик попыток; возвращает обновлённый элемент или null. */
    fun bumpAttempts(cid: Long): OutboxItem? = synchronized(lock) {
        val list = read()
        val idx = list.indexOfFirst { it.cid == cid }
        if (idx == -1) return null
        val updated = list[idx].copy(attempts = list[idx].attempts + 1)
        list[idx] = updated
        write(list)
        updated
    }

    fun resetAttempts(cid: Long) = synchronized(lock) {
        val list = read()
        val idx = list.indexOfFirst { it.cid == cid }
        if (idx != -1 && list[idx].attempts != 0) {
            list[idx] = list[idx].copy(attempts = 0)
            write(list)
        }
    }

    fun remove(cid: Long) = synchronized(lock) {
        val list = read()
        val filtered = list.filterNot { it.cid == cid }
        if (filtered.size != list.size) write(filtered)
    }

    fun clear() = synchronized(lock) {
        runCatching { file().delete() }
    }

    // ------------------------------------------------------------------
    // Сериализация
    // ------------------------------------------------------------------

    private fun read(): MutableList<OutboxItem> {
        val f = file()
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val text = crypto.readText(f) ?: return mutableListOf()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::fromJson)
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun write(list: List<OutboxItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        runCatching {
            crypto.writeAtomic(file(), arr.toString())
        }
    }

    private fun toJson(item: OutboxItem): JSONObject = JSONObject().apply {
        put("chatId", item.chatId)
        put("cid", item.cid)
        put("text", item.text)
        put("replyTo", item.replyTo)
        put("createdAt", item.createdAt)
        put("attempts", item.attempts)
    }

    private fun fromJson(o: JSONObject): OutboxItem = OutboxItem(
        chatId = o.optLong("chatId"),
        cid = o.optLong("cid"),
        text = o.optString("text"),
        replyTo = o.optLong("replyTo"),
        createdAt = o.optLong("createdAt"),
        attempts = o.optInt("attempts")
    )
}
