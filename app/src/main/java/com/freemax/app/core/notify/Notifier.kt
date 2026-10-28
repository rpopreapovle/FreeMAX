package com.freemax.app.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.freemax.app.MainActivity
import com.freemax.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальные уведомления о новых сообщениях (когда приложение в фоне).
 * Канал один — «Сообщения»; тихие чаты просто не уведомляют.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_MESSAGES = "messages"
        private const val GROUP = "freemax_messages"
    }

    /** Находится ли приложение на переднем плане (ставится ActivityLifecycleCallbacks). */
    @Volatile
    var appInForeground: Boolean = false

    /** id чата -> последнее показанное уведомление (чтобы не спамить). */
    private val lastNotified = mutableMapOf<Long, Long>()

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Сообщения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Новые сообщения в MAX"
        }
        nm.createNotificationChannel(channel)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Показывает уведомление о сообщении. [chatId] нужен для перехода в чат,
     * [muted] — тихий чат (уведомление не показываем).
     */
    @SuppressLint("MissingPermission")
    fun notifyMessage(
        chatId: Long,
        chatTitle: String,
        senderName: String?,
        text: String,
        isGroup: Boolean,
        muted: Boolean
    ) {
        if (muted) return
        if (!canNotify()) return
        ensureChannel()
        val now = System.currentTimeMillis()
        synchronized(lastNotified) {
            val prev = lastNotified[chatId] ?: 0L
            if (now - prev < 1500) return
            lastNotified[chatId] = now
            if (lastNotified.size > 100) lastNotified.clear()
        }
        val title = when {
            isGroup && !senderName.isNullOrBlank() -> "$chatTitle · $senderName"
            chatTitle.isNotBlank() -> chatTitle
            else -> "Новое сообщение"
        }
        val body = text.ifBlank { "Вложение" }.take(300)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            (chatId xor (chatId ushr 32)).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                (chatId and 0x7FFFFFFF).toInt(),
                notification
            )
        } catch (e: Exception) {
            android.util.Log.e("Notifier", "notify failed", e)
        }
    }

    fun cancelForChat(chatId: Long) {
        try {
            NotificationManagerCompat.from(context).cancel((chatId and 0x7FFFFFFF).toInt())
        } catch (_: Exception) {
        }
    }
}
