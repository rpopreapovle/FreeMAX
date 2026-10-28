package com.freemax.api.model

data class Attachment(
    val id: Long = 0,
    val type: Int = 0,
    val url: String? = null,
    val name: String? = null,
    val size: Long = 0,
    /** Исходный fileId с сервера (может быть UUID-строкой; [id] тогда 0). */
    val fileId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
    val photoUrl: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val stickerId: Long? = null,
    // Extended fields for full rendering (all defaulted)
    val durationMs: Long = 0,
    val wave: List<Int> = emptyList(),
    val thumbUrl: String? = null,
    val preview: ByteArray? = null,
    val title: String? = null,
    val description: String? = null,
    val targetUrl: String? = null,
    val phone: String? = null,
    val extra: String? = null,
    val nested: List<Attachment> = emptyList(),
    val keyboard: List<List<String>> = emptyList(),
    val pollOptions: List<String> = emptyList()
) {
    companion object {
        const val TYPE_CONTROL = 1
        const val TYPE_PHOTO = 2
        const val TYPE_VIDEO = 3
        const val TYPE_AUDIO = 4
        const val TYPE_STICKER = 5
        const val TYPE_SHARE = 6
        const val TYPE_APP = 7
        const val TYPE_CALL = 8
        const val TYPE_MUSIC = 9
        const val TYPE_FILE = 10
        const val TYPE_CONTACT = 11
        const val TYPE_PRESENT = 12
        const val TYPE_INLINE_KEYBOARD = 13
        const val TYPE_LOCATION = 14
        const val TYPE_DAILY_MEDIA = 15
        const val TYPE_WIDGET = 16
        const val TYPE_POLL = 17
        const val TYPE_STORY_REPLY = 18
    }

    val isPhoto: Boolean get() = type == TYPE_PHOTO
    val isVideo: Boolean get() = type == TYPE_VIDEO
    val isAudio: Boolean get() = type == TYPE_AUDIO
    val isSticker: Boolean get() = type == TYPE_STICKER
    val isFile: Boolean get() = type == TYPE_FILE
    val isCall: Boolean get() = type == TYPE_CALL
    val isContact: Boolean get() = type == TYPE_CONTACT
    val isLocation: Boolean get() = type == TYPE_LOCATION
    val isPoll: Boolean get() = type == TYPE_POLL
    val isShare: Boolean get() = type == TYPE_SHARE
    val isMedia: Boolean get() = type in listOf(TYPE_PHOTO, TYPE_VIDEO, TYPE_AUDIO)
}
