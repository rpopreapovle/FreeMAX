package com.freemax.app

import android.content.Context
import android.content.Intent
import com.freemax.api.MaxClient
import com.freemax.app.core.notify.Notifier
import com.freemax.app.core.storage.LocalCache
import com.freemax.app.core.storage.SecureStorage
import com.freemax.app.data.repository.AuthRepository
import com.freemax.app.data.repository.ChatRepository
import com.freemax.app.data.repository.MessageRepository
import com.freemax.app.data.repository.UploadRepository

/**
 * Точка подключения отладочных хуков (ночная смена, adb-extras freemax_test_*).
 *
 * Сама реализация лежит только в debug source-set (src/debug) и подгружается
 * через ServiceLoader. В release реализаций нет, поэтому ни ночной хук, ни его
 * код в APK не попадают и запустить что-либо через adb-extras невозможно.
 */
interface DebugHook {
    fun handle(
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
    )
}

object DebugHooks {
    /** Список установленных хуков: в debug — один, в release — пустой. */
    fun installed(): List<DebugHook> = try {
        java.util.ServiceLoader.load(DebugHook::class.java).toList()
    } catch (_: Throwable) {
        emptyList()
    }
}
