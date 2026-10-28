package com.freemax.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Один аккаунт MAX: сессионный токен + id. */
data class MaxAccount(
    val viewerId: Long,
    val token: String,
    val name: String = "",
    val phone: String = "",
    val addedAt: Long = 0L
)

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        // Мультиаккаунт: если id уже известен, обновляем запись аккаунта
        val uid = prefs.getLong(KEY_USER_ID, -1L)
        if (uid > 0) {
            upsertAccount(MaxAccount(viewerId = uid, token = token, phone = getAuthPhone().orEmpty()))
        }
    }

    fun getToken(): String? = getActiveAccount()?.token ?: prefs.getString(KEY_TOKEN, null)

    fun saveSessionKey(sessionKey: String) {
        prefs.edit().putString(KEY_SESSION_KEY, sessionKey).apply()
    }

    fun getSessionKey(): String? = prefs.getString(KEY_SESSION_KEY, null)

    fun saveUserId(userId: Long) {
        prefs.edit().putLong(KEY_USER_ID, userId).apply()
        // Мультиаккаунт: активируем (и при необходимости создаём) этот аккаунт
        val token = prefs.getString(KEY_TOKEN, null)
        if (token != null && userId > 0) {
            upsertAccount(MaxAccount(viewerId = userId, token = token, phone = getAuthPhone().orEmpty()))
            setActiveAccountId(userId)
        }
    }

    fun getUserId(): Long = getActiveAccount()?.viewerId ?: prefs.getLong(KEY_USER_ID, -1L)

    // ------------------------------------------------------------------
    // Мультиаккаунт
    // ------------------------------------------------------------------

    /** Список сохранённых аккаунтов. */
    fun getAccounts(): List<MaxAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null)
        if (raw.isNullOrBlank()) return migrateLegacyAccount()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val token = o.optString("token", "")
                val id = o.optLong("viewerId", 0L)
                if (id <= 0L || token.isBlank()) null
                else MaxAccount(
                    viewerId = id,
                    token = token,
                    name = o.optString("name", ""),
                    phone = o.optString("phone", ""),
                    addedAt = o.optLong("addedAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(accounts: List<MaxAccount>) {
        val arr = org.json.JSONArray()
        for (a in accounts) {
            arr.put(org.json.JSONObject().apply {
                put("viewerId", a.viewerId)
                put("token", a.token)
                put("name", a.name)
                put("phone", a.phone)
                put("addedAt", a.addedAt)
            })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    /** Добавляет аккаунт или обновляет существующий (по viewerId). */
    fun upsertAccount(account: MaxAccount) {
        val list = getAccounts().toMutableList()
        val idx = list.indexOfFirst { it.viewerId == account.viewerId }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = account.copy(
                name = account.name.ifBlank { old.name },
                phone = account.phone.ifBlank { old.phone },
                addedAt = if (account.addedAt != 0L) account.addedAt else old.addedAt
            )
        } else {
            list.add(account.copy(addedAt = account.addedAt.takeIf { it != 0L } ?: System.currentTimeMillis()))
        }
        saveAccounts(list)
    }

    fun removeAccount(viewerId: Long) {
        saveAccounts(getAccounts().filterNot { it.viewerId == viewerId })
        if (getActiveAccountId() == viewerId) {
            val next = getAccounts().firstOrNull()
            if (next != null) setActiveAccountId(next.viewerId) else clearActiveAccount()
        }
    }

    fun getActiveAccountId(): Long = prefs.getLong(KEY_ACTIVE_ACCOUNT, -1L)

    fun setActiveAccountId(viewerId: Long) {
        prefs.edit().putLong(KEY_ACTIVE_ACCOUNT, viewerId).apply()
        // legacy-ключи тоже держим в актуальном состоянии
        val acc = getAccounts().firstOrNull { it.viewerId == viewerId }
        if (acc != null) {
            prefs.edit().putLong(KEY_USER_ID, acc.viewerId).putString(KEY_TOKEN, acc.token).apply()
        }
    }

    private fun clearActiveAccount() {
        prefs.edit().remove(KEY_ACTIVE_ACCOUNT).remove(KEY_USER_ID).remove(KEY_TOKEN).apply()
    }

    fun getActiveAccount(): MaxAccount? {
        val id = getActiveAccountId()
        return getAccounts().firstOrNull { it.viewerId == id }
    }

    /** Обновляет токен аккаунта (и legacy-ключ, если он активный). */
    fun updateAccountToken(viewerId: Long, token: String) {
        val acc = getAccounts().firstOrNull { it.viewerId == viewerId } ?: return
        upsertAccount(acc.copy(token = token))
        if (getActiveAccountId() == viewerId) {
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
    }

    fun updateAccountName(viewerId: Long, name: String) {
        val acc = getAccounts().firstOrNull { it.viewerId == viewerId } ?: return
        upsertAccount(acc.copy(name = name))
    }

    /** Переносит старый одиночный токен в список аккаунтов. */
    private fun migrateLegacyAccount(): List<MaxAccount> {
        val token = prefs.getString(KEY_TOKEN, null) ?: return emptyList()
        val uid = prefs.getLong(KEY_USER_ID, -1L)
        if (token.isBlank() || uid <= 0L) return emptyList()
        val acc = MaxAccount(
            viewerId = uid,
            token = token,
            phone = getAuthPhone().orEmpty(),
            addedAt = System.currentTimeMillis()
        )
        saveAccounts(listOf(acc))
        setActiveAccountId(uid)
        return listOf(acc)
    }

    fun isLoggedIn(): Boolean = getAccounts().isNotEmpty()

    fun saveTrackId(trackId: String) {
        prefs.edit().putString(KEY_TRACK_ID, trackId).apply()
    }

    fun getTrackId(): String? = prefs.getString(KEY_TRACK_ID, null)

    fun saveAuthPhone(phone: String) {
        prefs.edit().putString(KEY_AUTH_PHONE, phone).apply()
    }

    fun getAuthPhone(): String? = prefs.getString(KEY_AUTH_PHONE, null)

    fun getPinnedChatIds(): Set<Long> {
        return prefs.getStringSet(KEY_PINNED_CHATS, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    fun setPinnedChatIds(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_PINNED_CHATS, ids.map { it.toString() }.toSet()).apply()
    }

    fun isChatPinned(chatId: Long): Boolean = getPinnedChatIds().contains(chatId)

    // ------------------------------------------------------------------
    // Тихие чаты (уведомления отключены)
    // ------------------------------------------------------------------

    fun getMutedChatIds(): Set<Long> =
        prefs.getStringSet(KEY_MUTED_CHATS, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet()
            ?: emptySet()

    fun isChatMuted(chatId: Long): Boolean = getMutedChatIds().contains(chatId)

    fun toggleMuteChat(chatId: Long): Boolean {
        val current = getMutedChatIds().toMutableSet()
        val muted = current.contains(chatId)
        if (muted) current.remove(chatId) else current.add(chatId)
        prefs.edit().putStringSet(KEY_MUTED_CHATS, current.map { it.toString() }.toSet()).apply()
        return !muted
    }

    fun togglePinChat(chatId: Long): Boolean {
        val current = getPinnedChatIds().toMutableSet()
        val isPinned = current.contains(chatId)
        if (isPinned) current.remove(chatId) else current.add(chatId)
        setPinnedChatIds(current)
        return !isPinned
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Persisted read marks per chat: Pair(idMark, timeMark).
     * Marks come in two units (message ids vs timestamps) and must not be mixed.
     * Format: "chat:idMark:timeMark,...", legacy "chat:mark" routed by magnitude.
     */
    fun getReadMarks(): Map<Long, Pair<Long, Long>> {
        val raw = prefs.getString(KEY_READ_MARKS, null)
        android.util.Log.d("SecureStorage", "getReadMarks raw=${raw?.take(200)}")
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { part ->
            val kv = part.split(":")
            when (kv.size) {
                3 -> {
                    val chat = kv[0].toLongOrNull()
                    val idMark = kv[1].toLongOrNull() ?: 0L
                    val tsMark = kv[2].toLongOrNull() ?: 0L
                    if (chat == null || (idMark == 0L && tsMark == 0L)) null
                    else chat to (idMark to tsMark)
                }
                2 -> {
                    val chat = kv[0].toLongOrNull()
                    val mark = kv[1].toLongOrNull()
                    if (chat == null || mark == null || mark == 0L) null
                    else if (mark > 1_000_000_000_000L) chat to (0L to mark)
                    else chat to (mark to 0L)
                }
                else -> null
            }
        }.toMap()
    }

    fun saveReadMarks(chatId: Long, idMark: Long, tsMark: Long) {
        // chatId == 0 — валидный чат «Избранное», не сентинел.
        if (idMark == 0L && tsMark == 0L) {
            android.util.Log.d("SecureStorage", "saveReadMarks skip chat=$chatId id=$idMark ts=$tsMark")
            return
        }
        val current = getReadMarks().toMutableMap()
        val prev = current[chatId] ?: (0L to 0L)
        if (prev.first >= idMark && prev.second >= tsMark) return
        current[chatId] = maxOf(prev.first, idMark) to maxOf(prev.second, tsMark)
        // Cap size to avoid unbounded growth
        val trimmed = current.entries
            .sortedByDescending { maxOf(it.value.first, it.value.second) }
            .take(100)
            .associate { it.key to it.value }
        val encoded = trimmed.entries.joinToString(",") { "${it.key}:${it.value.first}:${it.value.second}" }
        android.util.Log.d("SecureStorage", "saveReadMarks chat=$chatId id=$idMark ts=$tsMark")
        prefs.edit().putString(KEY_READ_MARKS, encoded).apply()
    }

    fun saveReadMark(chatId: Long, mark: Long) {
        if (mark > 1_000_000_000_000L) saveReadMarks(chatId, 0L, mark)
        else saveReadMarks(chatId, mark, 0L)
    }

    // Private owner-only feature: auto speech-to-text under voice messages.
    fun isSttEnabled(): Boolean = prefs.getBoolean(KEY_STT_ENABLED, true)
    private val _sttFlow = kotlinx.coroutines.flow.MutableStateFlow(isSttEnabled())
    val sttFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _sttFlow

    fun setSttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STT_ENABLED, enabled).apply()
        _sttFlow.value = enabled
    }

    /** Hard cache of voice transcripts: audioKey -> text (JSON object string). */
    fun getTranscripts(): Map<Long, String> {
        val raw = prefs.getString(KEY_STT_CACHE, null) ?: return emptyMap()
        return try {
            val json = org.json.JSONObject(raw)
            val out = LinkedHashMap<Long, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id = k.toLongOrNull() ?: continue
                val text = json.optString(k, "")
                if (id != 0L && text.isNotBlank()) out[id] = text
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveTranscript(audioKey: Long, text: String) {
        if (audioKey == 0L || text.isBlank()) return
        try {
            val current = getTranscripts().toMutableMap() as LinkedHashMap<Long, String>
            current.remove(audioKey)
            current[audioKey] = text.take(STT_TEXT_MAX)
            while (current.size > STT_CACHE_MAX) {
                current.remove(current.keys.first())
            }
            val json = org.json.JSONObject()
            for ((k, v) in current) json.put(k.toString(), v)
            prefs.edit().putString(KEY_STT_CACHE, json.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "saveTranscript failed", e)
        }
    }

    fun getThemeMode(): String = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM

    // ------------------------------------------------------------------
    // История поиска (локально, последние запросы)
    // ------------------------------------------------------------------

    fun getSearchHistory(): List<String> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        return raw.split("\u0001").filter { it.isNotBlank() }.take(SEARCH_HISTORY_MAX)
    }

    fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val current = getSearchHistory().toMutableList()
        current.remove(q)
        current.add(0, q)
        prefs.edit().putString(
            KEY_SEARCH_HISTORY,
            current.take(SEARCH_HISTORY_MAX).joinToString("\u0001")
        ).apply()
    }

    fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    // ------------------------------------------------------------------
    // Настройки автоочистки кэша
    // ------------------------------------------------------------------

    /** 0 — не чистить автоматически, иначе количество дней. */
    fun getAutoClearDays(): Int = prefs.getInt(KEY_AUTO_CLEAR_DAYS, 0)

    fun setAutoClearDays(days: Int) {
        prefs.edit().putInt(KEY_AUTO_CLEAR_DAYS, days.coerceIn(0, 365)).apply()
    }

    private val _autoClearFlow = kotlinx.coroutines.flow.MutableStateFlow(getAutoClearDays())
    val autoClearFlow: kotlinx.coroutines.flow.StateFlow<Int> = _autoClearFlow

    // ------------------------------------------------------------------
    // Блокировка приложения (PIN)
    // ------------------------------------------------------------------

    fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)

    private val _appLockFlow = kotlinx.coroutines.flow.MutableStateFlow(isAppLockEnabled())
    val appLockFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _appLockFlow

    /** PIN хранится только как соль+хеш (SHA-256), сам код нигде не лежит. */
    fun setAppLockPin(pin: String) {
        val salt = java.util.UUID.randomUUID().toString()
        val hash = sha256(salt + pin)
        prefs.edit()
            .putBoolean(KEY_APP_LOCK, true)
            .putString(KEY_APP_LOCK_SALT, salt)
            .putString(KEY_APP_LOCK_HASH, hash)
            .apply()
        _appLockFlow.value = true
    }

    fun disableAppLock() {
        prefs.edit()
            .putBoolean(KEY_APP_LOCK, false)
            .remove(KEY_APP_LOCK_SALT)
            .remove(KEY_APP_LOCK_HASH)
            .apply()
        _appLockFlow.value = false
    }

    fun checkAppLockPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_APP_LOCK_SALT, null) ?: return false
        val hash = prefs.getString(KEY_APP_LOCK_HASH, null) ?: return false
        return sha256(salt + pin) == hash
    }

    private fun sha256(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val _themeFlow = kotlinx.coroutines.flow.MutableStateFlow(getThemeMode())
    val themeFlow: kotlinx.coroutines.flow.StateFlow<String> = _themeFlow

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        _themeFlow.value = mode
    }

    companion object {
        private const val PREFS_FILE_NAME = "freemax_secure_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE_ACCOUNT = "active_account"
        private const val KEY_SESSION_KEY = "session_key"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_TRACK_ID = "track_id"
        private const val KEY_AUTH_PHONE = "auth_phone"
        private const val KEY_PINNED_CHATS = "pinned_chats"
        private const val KEY_MUTED_CHATS = "muted_chats"
        private const val KEY_READ_MARKS = "read_marks"
        private const val KEY_STT_ENABLED = "stt_enabled"
        private const val KEY_STT_CACHE = "stt_cache"
        private const val STT_CACHE_MAX = 200
        private const val STT_TEXT_MAX = 2000
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val SEARCH_HISTORY_MAX = 20
        private const val KEY_AUTO_CLEAR_DAYS = "auto_clear_days"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_APP_LOCK_SALT = "app_lock_salt"
        private const val KEY_APP_LOCK_HASH = "app_lock_hash"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
