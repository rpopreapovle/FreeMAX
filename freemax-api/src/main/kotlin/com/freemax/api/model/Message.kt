package com.freemax.api.model

/** Реакция на сообщение: эмодзи + сколько раз поставили (и моя ли она). */
data class Reaction(
    val emoji: String = "",
    val count: Int = 0,
    val mine: Boolean = false
)

data class Message(
    val id: Long = 0,
    val mid: Long = 0,
    val chatId: Long = 0,
    val userId: Long = 0,
    val text: String = "",
    val time: Long = 0,
    val cid: Long = 0,
    val replyTo: Long = 0,
    val state: Int = 0,
    val attachments: List<Attachment> = emptyList(),
    val isService: Boolean = false,
    val isForward: Boolean = false,
    val forwardType: String? = null,
    val quoteText: String? = null,
    val quoteAttachments: List<Attachment> = emptyList(),
    /** Сообщение было отредактировано (status=EDITED / локальное «Изменить»). */
    val edited: Boolean = false,
    /** Реакции из reactionInfo (эмодзи, счётчик, моя ли). */
    val reactions: List<Reaction> = emptyList()
) {
    companion object {
        const val STATE_UNKNOWN = 0
        const val STATE_SENT = 1
        const val STATE_DELIVERED = 2
        const val STATE_READ = 3
        const val STATE_NOT_SENT = 4
    }

    val isFromMe: Boolean get() = userId == 0L
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
    val isText: Boolean get() = text.isNotEmpty() && attachments.isEmpty()
}
