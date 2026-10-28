package com.freemax.api

import com.freemax.api.protocol.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class MaxClientTest {
    @Test
    fun `clientCanBeCreated`() {
        val client = MaxClient()
        assertEquals(ConnectionState.Disconnected, client.state.value)
    }

    @Test
    fun `client starts disconnected`() {
        val client = MaxClient()
        assertFalse(client.isConnected())
    }

    @Test
    fun `client version is correct`() {
        val client = MaxClient()
        assertEquals("0.1.0", client.version())
    }

    @Test
    fun `seq counter increments`() {
        val client = MaxClient()
        val seq1 = client.requestCode("+79001234567")
        val seq2 = client.requestCode("+79001234567")
        assertTrue(seq2 > seq1)
    }
}
