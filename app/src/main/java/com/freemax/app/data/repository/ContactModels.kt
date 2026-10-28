package com.freemax.app.data.repository

data class ContactInfo(
    val id: Long = 0,
    val name: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val photoUrl: String? = null,
    /** Описание профиля («О себе»). */
    val description: String? = null,
    /** Статус пользователя (about). */
    val about: String? = null,
    /** Был в сети: unix ms (0 — неизвестно). */
    val lastSeen: Long = 0L,
    /** Онлайн по последнему push/ответу присутствия. */
    val online: Boolean = false
)

data class SelfProfile(
    val contactId: Long = 0,
    val name: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val photoUrl: String? = null,
    /** Описание профиля («О себе»). */
    val description: String? = null
)

data class MemberInfo(
    val contactId: Long = 0,
    val name: String = "",
    val phone: String? = null,
    val photoUrl: String? = null,
    val isSelf: Boolean = false,
    /** OWNER / ADMIN / MEMBER (если известно). */
    val role: String = "MEMBER",
    /** Короткий статус из профиля. */
    val about: String? = null,
    /** «О себе» из карточки контакта. */
    val description: String? = null
)
