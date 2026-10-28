package com.freemax.api.model

data class Chat(
    val id: Long = 0,
    val serverId: Long = 0,
    val type: Int = 0,
    val status: Int = 0,
    val title: String = "",
    val lastMessageId: Long = 0,
    val lastEventTime: Long = 0,
    val newMessages: Int = 0,
    val cid: Long = 0,
    val description: String = "",
    val link: String? = null,
    val iconUrl: String? = null,
    val ownerId: Long = 0,
    val adminIds: List<Long> = emptyList(),
    /** Избранное (Saved Messages): self-диалог с id=0. */
    val isSavedMessages: Boolean = false
) {
    companion object {
        const val TYPE_DIALOG = 0
        const val TYPE_GROUP = 1
        const val TYPE_CHANNEL = 2
        const val TYPE_GROUP_CHAT = 3
        const val TYPE_COMMENTS = 4

        const val STATUS_ACTIVE = 0
        const val STATUS_LEFT = 1
        const val STATUS_LEAVING = 2
        const val STATUS_REMOVED = 3
    }

    val isDialog: Boolean get() = type == TYPE_DIALOG
    val isGroup: Boolean get() = type == TYPE_GROUP || type == TYPE_GROUP_CHAT
    val isChannel: Boolean get() = type == TYPE_CHANNEL
    val isActive: Boolean get() = status == STATUS_ACTIVE
    val hasUnread: Boolean get() = newMessages > 0
}
