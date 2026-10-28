package com.freemax.api.model

import org.junit.Assert.*
import org.junit.Test

class MessageTest {
    @Test
    fun `message with text only`() {
        val msg = Message(text = "Hello")
        assertTrue(msg.isText)
        assertFalse(msg.hasAttachments)
    }

    @Test
    fun `message with attachment`() {
        val msg = Message(attachments = listOf(Attachment(type = Attachment.TYPE_PHOTO)))
        assertFalse(msg.isText)
        assertTrue(msg.hasAttachments)
    }

    @Test
    fun `attachment types`() {
        assertTrue(Attachment(type = Attachment.TYPE_PHOTO).isPhoto)
        assertTrue(Attachment(type = Attachment.TYPE_VIDEO).isVideo)
        assertTrue(Attachment(type = Attachment.TYPE_AUDIO).isAudio)
        assertTrue(Attachment(type = Attachment.TYPE_STICKER).isSticker)
        assertTrue(Attachment(type = Attachment.TYPE_FILE).isFile)
    }

    @Test
    fun `isMedia`() {
        assertTrue(Attachment(type = Attachment.TYPE_PHOTO).isMedia)
        assertTrue(Attachment(type = Attachment.TYPE_VIDEO).isMedia)
        assertTrue(Attachment(type = Attachment.TYPE_AUDIO).isMedia)
        assertFalse(Attachment(type = Attachment.TYPE_FILE).isMedia)
    }
}
