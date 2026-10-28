package com.freemax.api.model

import org.junit.Assert.*
import org.junit.Test

class ChatTest {
    @Test
    fun `dialog type`() {
        val chat = Chat(type = Chat.TYPE_DIALOG)
        assertTrue(chat.isDialog)
        assertFalse(chat.isGroup)
        assertFalse(chat.isChannel)
    }

    @Test
    fun `group type`() {
        val chat = Chat(type = Chat.TYPE_GROUP)
        assertFalse(chat.isDialog)
        assertTrue(chat.isGroup)
    }

    @Test
    fun `channel type`() {
        val chat = Chat(type = Chat.TYPE_CHANNEL)
        assertTrue(chat.isChannel)
    }

    @Test
    fun `unread count`() {
        val chat = Chat(newMessages = 5)
        assertTrue(chat.hasUnread)
    }

    @Test
    fun `no unread`() {
        val chat = Chat(newMessages = 0)
        assertFalse(chat.hasUnread)
    }
}
