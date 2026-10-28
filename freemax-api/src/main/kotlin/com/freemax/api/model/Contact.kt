package com.freemax.api.model

data class Contact(
    val uid: Long = 0,
    val firstName: String = "",
    val lastName: String = "",
    val avatarUrl: String? = null,
    val phone: Long = 0,
    val status: Int = 0,
    val type: Int = 0,
    val description: String? = null
) {
    companion object {
        const val STATUS_ACTIVE = 0
        const val STATUS_BLOCKED = 1
        const val STATUS_REMOVED = 2

        const val TYPE_UNKNOWN = 0
        const val TYPE_BOT = 1
        const val TYPE_SERVICE = 2
    }

    val fullName: String
        get() = listOf(firstName, lastName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Unknown" }

    val isBot: Boolean get() = type == TYPE_BOT
    val isService: Boolean get() = type == TYPE_SERVICE
}
