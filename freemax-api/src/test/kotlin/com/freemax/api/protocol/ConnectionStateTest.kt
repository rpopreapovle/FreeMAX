package com.freemax.api.protocol

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun `initial state is Disconnected`() {
        val state = ConnectionState.initial()
        assertTrue(state is ConnectionState.Disconnected)
    }

    @Test
    fun `isActive returns true for Ready`() {
        assertTrue(ConnectionState.Ready.isActive())
    }

    @Test
    fun `isActive returns true for Authenticated`() {
        assertTrue(ConnectionState.Authenticated.isActive())
    }

    @Test
    fun `isActive returns false for Disconnected`() {
        assertFalse(ConnectionState.Disconnected.isActive())
    }

    @Test
    fun `isActive returns false for Connecting`() {
        assertFalse(ConnectionState.Connecting.isActive())
    }

    @Test
    fun `Reconnecting has attempt counter`() {
        val state = ConnectionState.Reconnecting(3)
        assertEquals(3, state.attempt)
    }

    @Test
    fun `Error contains message`() {
        val state = ConnectionState.Error("timeout")
        assertEquals("timeout", state.message)
    }
}
