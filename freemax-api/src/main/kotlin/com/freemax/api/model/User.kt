package com.freemax.api.model

data class User(
    val uid: Long = 0,
    val firstName: String = "",
    val lastName: String = "",
    val avatarUrl: String? = null,
    val phone: Long = 0,
    val status: Int = 0,
    val gender: Int = 0,
    val description: String? = null
) {
    companion object {
        const val STATUS_ACTIVE = 0
        const val STATUS_BLOCKED = 1
        const val STATUS_REMOVED = 2

        const val GENDER_UNKNOWN = 0
        const val GENDER_MALE = 1
        const val GENDER_FEMALE = 2
    }

    val fullName: String
        get() = listOf(firstName, lastName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Unknown" }

    val initials: String
        get() {
            val first = firstName.firstOrNull()?.uppercase() ?: ""
            val last = lastName.firstOrNull()?.uppercase() ?: ""
            return "$first$last".ifEmpty { "?" }
        }

    val isActive: Boolean get() = status == STATUS_ACTIVE
}
