package com.freemax.api.protocol

import org.junit.Assert.*
import org.junit.Test

class OpcodesTest {
    @Test
    fun `auth opcodes are correct`() {
        assertEquals(17, Opcodes.AUTH_REQUEST)
        assertEquals(18, Opcodes.AUTH)
        assertEquals(23, Opcodes.REGISTER)
    }

    @Test
    fun `chat opcodes are correct`() {
        assertEquals(48, Opcodes.LOAD_CHATS)
        assertEquals(53, Opcodes.LOAD_MORE_CHATS)
        assertEquals(72, Opcodes.GET_CHAT_INFO)
    }

    @Test
    fun `message opcodes are correct`() {
        assertEquals(49, Opcodes.LOAD_MESSAGES)
        assertEquals(64, Opcodes.SEND_MESSAGE)
        assertEquals(74, Opcodes.MARK_MESSAGES_READ)
    }

    @Test
    fun `ping opcode is correct`() {
        assertEquals(1, Opcodes.PING)
    }
}
