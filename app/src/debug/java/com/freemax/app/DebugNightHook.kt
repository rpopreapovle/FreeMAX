package com.freemax.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.freemax.api.MaxClient
import com.freemax.app.core.notify.Notifier
import com.freemax.app.core.storage.LocalCache
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MessageRepository
import com.freemax.app.data.repository.UploadRepository

/**
 * DEBUG-хук ночной смены: прогон смоук-тестов через adb, когда экран
 * телефона заблокирован (пользователь спит).
 *
 * Живёт только в debug source-set (src/debug) и подключается через
 * ServiceLoader (см. DebugHooks в src/main). В release этого класса нет.
 * Активируется только если в intent явно переданы extras вида freemax_test_*.
 *
 * Примеры:
 *   adb shell am start -n com.freemax.app/.MainActivity \
 *     --es freemax_test_send_chat "-1000000000000" \
 *     --es freemax_test_send_text "ночной смоук"
 */
class DebugNightHook : DebugHook {
    private val TAG = "FreeMAXTest"

    override fun handle(
        context: Context,
        intent: Intent,
        messages: MessageRepository,
        chats: ChatRepository,
        auth: AuthRepository,
        client: MaxClient,
        notifier: Notifier,
        secureStorage: SecureStorage,
        localCache: LocalCache,
        upload: UploadRepository
    ) {
        val sendChat = intent.getStringExtra("freemax_test_send_chat")
        val sendPreset = intent.getStringExtra("freemax_test_send_preset")
        val uploadChat = intent.getStringExtra("freemax_test_upload_chat")
        val editChat = intent.getStringExtra("freemax_test_edit_chat")
        val reactChat = intent.getStringExtra("freemax_test_react_chat")
        val dumpChat = intent.getStringExtra("freemax_test_dump_chat")
        val joinLink = intent.getStringExtra("freemax_test_join_link")
        val presenceIds = intent.getStringExtra("freemax_test_presence_ids")
        val deleteChat = intent.getStringExtra("freemax_test_delete_chat")
        val searchChatId = intent.getStringExtra("freemax_test_search_chat")
        val setPin = intent.getStringExtra("freemax_test_set_pin")
        val disablePin = intent.getBooleanExtra("freemax_test_disable_pin", false)
        if (sendChat == null && uploadChat == null && editChat == null &&
            reactChat == null && dumpChat == null && joinLink == null &&
            presenceIds == null && deleteChat == null && searchChatId == null &&
            setPin == null && !disablePin &&
            !intent.getBooleanExtra("freemax_test_notify", false) &&
            !intent.getBooleanExtra("freemax_test_cache_dump", false) &&
            !intent.getBooleanExtra("freemax_test_restore", false)
        ) {
            return
        }
        Log.i(TAG, "DebugNightHook start")
        if (intent.getBooleanExtra("freemax_test_cache_dump", false)) {
            // Полный дамп кэша: используется для восстановления данных
            kotlinx.coroutines.runBlocking {
                val all = localCache.dumpAllCachedMessages()
                Log.i(TAG, "cache dump files=${all.size}")
                all.forEach { (file, list) ->
                    list.forEach { m ->
                        Log.i(
                            TAG,
                            "CACHE file=$file chatId=${m.chatId} id=${m.id} cid=${m.cid} user=${m.userId} " +
                                "time=${m.time} text=${m.text.replace("\n", "\\n")}"
                        )
                    }
                }
            }
        }
        if (setPin != null) {
            secureStorage.setAppLockPin(setPin)
            Log.i(TAG, "app lock enabled (test)")
        }
        if (disablePin) {
            secureStorage.disableAppLock()
            Log.i(TAG, "app lock disabled (test)")
        }
        // Слушаем кадры сервера, чтобы видеть ответы/ошибки на тестовые запросы
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(90_000) {
                        client.frames.collect { f ->
                            val watch = f.opcode in setOf(57, 35, 64, 66, 67, 73, 140, 142, 156, 178, 179, 180, 181) || f.cmd == 3
                            if (watch) {
                                Log.i(
                                    TAG,
                                    "frame cmd=${f.cmd} opcode=${f.opcode} seq=${f.seq} keys=${f.payload?.keys} " +
                                        "payload=${f.payload?.toString()?.take(400)}"
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
        Thread {
            // Ждём авторизацию (до ~60 c)
            var tries = 0
            while (tries < 120 && !auth.isAuthed()) {
                Thread.sleep(500)
                tries++
            }
            Log.i(TAG, "authed=${auth.isAuthed()}")
            val me = auth.activeAccountId()
            try {                if (dumpChat != null) {
                    if (dumpChat == "chats") {
                        Log.i(TAG, "chats=${chats.chats.value.size}")
                        chats.chats.value.take(40).forEach { c ->
                            Log.i(TAG, "  chat id=${c.id} cid=${c.cid} type=${c.type} title='${c.title}' saved=${c.isSavedMessages} icon=${c.iconUrl != null} link=${c.link}")
                        }
                    } else {
                        val id = dumpChat.toLong()
                        messages.loadMessages(id)
                        Thread.sleep(3000)
                        dump(id, messages)
                    }
                }
                if (sendChat != null) {
                    val id = sendChat.toLong()
                    messages.loadMessages(id)
                    Thread.sleep(1500)
                    val text = presetText(sendPreset)
                        ?: intent.getStringExtra("freemax_test_send_text") ?: "test"
                    waitAuthed(client)
                    val cid = messages.sendMessage(id, text)
                    Log.i(TAG, "send queued cid=$cid preset=$sendPreset")
                    waitAndDump(id, messages, cid)
                }
                if (uploadChat != null) {
                    val id = uploadChat.toLong()
                    messages.loadMessages(id)
                    Thread.sleep(1000)
                    val path = intent.getStringExtra("freemax_test_upload_path")
                    val text = intent.getStringExtra("freemax_test_send_text") ?: ""
                    waitAuthed(client)
                    if (path != null) uploadTest(id, path, text, messages, upload)
                }
                if (editChat != null) {
                    val id = editChat.toLong()
                    messages.loadMessages(id)
                    Thread.sleep(2500)
                    val newText = intent.getStringExtra("freemax_test_edit_text") ?: "edited-test"
                    val msgId = intent.getStringExtra("freemax_test_edit_id")?.toLongOrNull()
                        ?: lastOwnMessageId(id, messages, me)
                    if (msgId != null && msgId != 0L) {
                        waitAuthed(client)
                        Log.i(TAG, "edit messageId=$msgId -> '$newText'")
                        messages.editMessage(id, msgId, newText)
                    } else {
                        Log.e(TAG, "edit: no own message found in chat $editChat")
                    }
                    Thread.sleep(5000)
                    dump(id, messages)
                }
                if (reactChat != null) {                    val id = reactChat.toLong()
                    messages.loadMessages(id)
                    Thread.sleep(2500)
                    val emoji = intent.getStringExtra("freemax_test_react_emoji") ?: "👍"
                    val remove = intent.getBooleanExtra("freemax_test_react_remove", false)
                    val msgId = intent.getStringExtra("freemax_test_react_id")?.toLongOrNull()
                        ?: lastMessageId(id, messages)
                    if (msgId != null && msgId != 0L) {
                        waitAuthed(client)
                        if (remove) {
                            Log.i(TAG, "removeReaction messageId=$msgId")
                            client.removeReaction(id, msgId)
                        } else {
                            Log.i(TAG, "addReaction messageId=$msgId emoji=$emoji")
                            client.addReaction(id, msgId, emoji)
                        }
                    } else {
                        Log.e(TAG, "react: no message found in chat $reactChat")
                    }
                    Thread.sleep(6000)
                    dump(id, messages)
                }
                if (deleteChat != null) {
                    val id = deleteChat.toLong()
                    val explicitId = intent.getStringExtra("freemax_test_delete_id")?.toLongOrNull()
                    if (explicitId == null) {
                        messages.loadMessages(id)
                        Thread.sleep(2500)
                    }
                    val msgId = explicitId ?: lastOwnMessageId(id, messages, me)
                    waitAuthed(client)
                    if (msgId != null && msgId != 0L) {
                        Log.i(TAG, "delete messageId=$msgId forMe=true")
                        messages.deleteMessages(id, listOf(msgId), forAll = false)
                    } else {
                        Log.e(TAG, "delete: no own message found in chat $deleteChat")
                    }
                    Thread.sleep(5000)
                    dump(id, messages)
                }
                if (joinLink != null) {
                    waitAuthed(client)
                    Log.i(TAG, "joinChat link=$joinLink")
                    chats.joinChat(joinLink)
                    Thread.sleep(6000)
                }
                if (searchChatId != null) {
                    waitAuthed(client)
                    val q = intent.getStringExtra("freemax_test_search_query") ?: "тест"
                    Log.i(TAG, "searchMessages chat=$searchChatId query=$q")
                    client.searchMessages(searchChatId.toLong(), q, 20)
                    Thread.sleep(5000)
                }
                if (intent.getBooleanExtra("freemax_test_restore", false)) {
                    // Восстановление сообщений из files/restore.json в чат 0 (Избранное)
                    waitAuthed(client)
                    val file = java.io.File(context.filesDir, "restore.json")
                    if (file.exists()) {
                        val arr = org.json.JSONArray(file.readText())
                        Log.i(TAG, "restore: ${arr.length()} messages")
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val text = o.optString("text")
                            if (text.isBlank()) continue
                            val cid = System.currentTimeMillis() + i
                            messages.sendMessage(0L, text, cid = cid)
                            Log.i(TAG, "restore sent #$i (${text.take(30)})")
                            Thread.sleep(1200)
                        }
                        Thread.sleep(3000)
                    } else {
                        Log.e(TAG, "restore.json not found")
                    }
                }
                if (intent.getBooleanExtra("freemax_test_notify", false)) {                    Log.i(TAG, "notify test")
                    notifier.ensureChannel()
                    notifier.appInForeground = false
                    notifier.notifyMessage(
                        chatId = -1000000000000L,
                        chatTitle = "Тестовый чат",
                        senderName = "Тест",
                        text = "Проверка локального уведомления",
                        isGroup = true,
                        muted = false
                    )
                    Thread.sleep(2000)
                }
                if (presenceIds != null) {
                    val ids = presenceIds.split(",").mapNotNull { it.trim().toLongOrNull() }
                    waitAuthed(client)
                    Log.i(TAG, "presence request ids=$ids")
                    chats.requestPresence(ids)
                    Thread.sleep(4000)
                    ids.forEach { uid ->
                        Log.i(TAG, "  presence uid=$uid seen=${chats.presence.value[uid]} online=${uid in chats.online.value}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DebugNightHook failed", e)
            }
            Log.i(TAG, "DebugNightHook done")
        }.start()
    }

    /** Готовые тексты для ночного смоук-теста UI-фич (ссылки/markdown). */
    private fun presetText(preset: String?): String? = when (preset) {
        null -> null
        "links" ->
            "Ссылки: https://max.ru и www.max.ru, а также max.ru/dev " +
                "и mailto:test@max.ru, tel:+79990001122"
        "markdown" ->
            "# Заголовок\n" +
                "**Жирный** и *курсив*, _тоже курсив_, __подчёркнутый__, ~~зачёркнутый~~.\n" +
                "`код` в строке и [ссылка на max](https://max.ru).\n" +
                "## Подзаголовок\n" +
                "- пункт раз\n" +
                "- пункт два со **ссылкой** www.max.ru\n" +
                "1. первый\n" +
                "2. второй\n" +
                "> цитата со *стилем*\n" +
                "Обычный текст https://web.max.ru и max.ru/dev"
        else -> null
    }

    private fun chatAsLong(v: Any?): Long = when (v) {        is Number -> v.toLong()
        is ByteArray -> {
            if (v.isNotEmpty() && v[0].toInt() and 0xFF == 0xD2 && v.size == 5) {
                var r = 0L
                for (i in 1 until 5) r = (r shl 8) or (v[i].toInt() and 0xFF).toLong()
                if (r and 0x80000000L != 0L) r or -0x100000000L else r
            } else {
                var r = 0L
                for (b in v) r = (r shl 8) or (b.toInt() and 0xFF).toLong()
                r
            }
        }
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun waitAuthed(client: MaxClient, timeoutMs: Long = 120_000) {
        val start = System.currentTimeMillis()
        while (!client.isAuthed() && System.currentTimeMillis() - start < timeoutMs) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
        Log.i(TAG, "waitAuthed done isAuthed=${client.isAuthed()}")
    }

    private fun lastOwnMessageId(chatId: Long, messages: MessageRepository, me: Long): Long? =
        messages.messages.value
            .filter { it.chatId == chatId && it.id != 0L && (me == 0L || it.userId == me) }
            .maxByOrNull { it.time }?.id

    private fun lastMessageId(chatId: Long, messages: MessageRepository): Long? =
        messages.messages.value.filter { it.chatId == chatId && it.id != 0L }
            .maxByOrNull { it.time }?.id

    private fun waitAndDump(chatId: Long, messages: MessageRepository, cid: Long) {
        Thread.sleep(3000)
        messages.loadMessages(chatId)
        Thread.sleep(3000)
        val found = messages.messages.value.firstOrNull { it.cid == cid }
        Log.i(
            TAG,
            if (found != null) "echo OK: id=${found.id} state=${found.state} text='${found.text}'"
            else "echo NOT found for cid=$cid"
        )
        dump(chatId, messages)
    }

    private fun uploadTest(chatId: Long, path: String, text: String, messages: MessageRepository, repo: UploadRepository) {
        Log.i(TAG, "upload test path=$path")
        val uri = android.net.Uri.fromFile(java.io.File(path))
        val rf = repo.resolve(uri)
        if (rf == null) {
            Log.e(TAG, "resolve failed for $path")
            return
        }
        val kind = when {
            rf.mime.startsWith("image/") -> com.freemax.app.data.repository.AttachKind.PHOTO
            rf.mime.startsWith("video/") -> com.freemax.app.data.repository.AttachKind.VIDEO
            rf.mime.startsWith("audio/") -> com.freemax.app.data.repository.AttachKind.AUDIO
            else -> com.freemax.app.data.repository.AttachKind.FILE
        }
        val cid = System.currentTimeMillis()
        Log.i(TAG, "upload kind=$kind name=${rf.name} size=${rf.bytes.size}")
        val durationMs = if (kind == com.freemax.app.data.repository.AttachKind.AUDIO) repo.audioDurationMs(uri) else 0L
        Log.i(TAG, "audio durationMs=$durationMs")
        val wire = kotlinx.coroutines.runBlocking {
            repo.upload(kind, rf, cid, durationMs = durationMs)
        }
        Log.i(TAG, "upload wire=$wire")
        if (wire != null) {
            val local = messages.sendMessage(chatId, text, attaches = listOf(wire), cid = cid)
            Log.i(TAG, "attachment message queued cid=$local")
            waitAndDump(chatId, messages, local)
        } else {
            Log.e(TAG, "upload failed")
        }
    }

    private fun dump(chatId: Long, messages: MessageRepository) {
        val list = messages.messages.value.filter { it.chatId == chatId }
        // Проверяем и bulk-загрузку реакций (opcode 180)
        messages.loadReactionsFor(chatId, list.takeLast(20).map { it.id })
        Thread.sleep(2500)
        val updated = messages.messages.value.filter { it.chatId == chatId }
        Log.i(TAG, "dump chat=$chatId messages=${updated.size}")
        updated.takeLast(10).forEach { m ->
            Log.i(
                TAG,
                "  id=${m.id} cid=${m.cid} user=${m.userId} state=${m.state} edited=${m.edited} " +
                    "reactions=${m.reactions.map { "${it.emoji}x${it.count}${if (it.mine) "*" else ""}" }} " +
                    "text='${m.text.take(40)}'"
            )
        }
    }
}
