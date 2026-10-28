package com.freemax.api.protocol

import org.junit.Assert.*
import org.junit.Test

class BinaryCodecTest {

    @Test
    fun `encode request produces valid binary frame`() {
        val frame = BinaryCodec.encodeRequest(
            seq = 1,
            opcode = Opcodes.AUTH_REQUEST,
            payload = mapOf(
                "phone" to "+79001234567",
                "type" to 0
            )
        )
        // Header: 10 bytes
        assertEquals(0x0A, frame[0].toInt() and 0xFF) // protocol version
        assertEquals(BinaryCodec.CMD_REQUEST, frame[1].toInt() and 0xFF) // cmd
        // seq = 1 (big-endian int16 at offset 2-3)
        assertEquals(0, frame[2].toInt() and 0xFF)
        assertEquals(1, frame[3].toInt() and 0xFF)
        // opcode = 17 (0x0011)
        assertEquals(0, frame[4].toInt() and 0xFF)
        assertEquals(17, frame[5].toInt() and 0xFF)
        // flags = 0
        assertEquals(0, frame[6].toInt() and 0xFF)
        // payload length should be > 0
        val payloadLen = (frame[7].toInt() and 0xFF) shl 16 or
                ((frame[8].toInt() and 0xFF) shl 8) or
                (frame[9].toInt() and 0xFF)
        assertTrue("Payload length should be > 0, got $payloadLen", payloadLen > 0)
    }

    @Test
    fun `encode and decode roundtrip`() {
        val payload = mapOf(
            "phone" to "+79001234567",
            "type" to 0,
            "language" to "ru"
        )
        val encoded = BinaryCodec.encodeRequest(seq = 42, opcode = 17, payload = payload)
        val decoded = BinaryCodec.decode(encoded)

        assertEquals(BinaryCodec.CMD_REQUEST, decoded.cmd)
        assertEquals(42, decoded.seq)
        assertEquals(17, decoded.opcode)
        assertEquals("+79001234567", decoded.payload?.get("phone"))
        assertEquals(0, decoded.payload?.get("type"))
        assertEquals("ru", decoded.payload?.get("language"))
    }

    @Test
    fun `decode response frame`() {
        // Build a response frame manually
        val payload = MsgPack.encodeMap(mapOf("status" to "ok", "token" to "abc123"))
        val frame = BinaryCodec.encodeResponse(seq = 1, opcode = 18, payload = null)
        // Manually set payload in frame
        val frameWithPayload = ByteArray(10 + payload.size)
        System.arraycopy(frame, 0, frameWithPayload, 0, 10)
        frameWithPayload[7] = ((payload.size shr 16) and 0xFF).toByte()
        frameWithPayload[8] = ((payload.size shr 8) and 0xFF).toByte()
        frameWithPayload[9] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, frameWithPayload, 10, payload.size)

        val decoded = BinaryCodec.decode(frameWithPayload)
        assertEquals(BinaryCodec.CMD_RESPONSE, decoded.cmd)
        assertEquals(1, decoded.seq)
        assertEquals("ok", decoded.payload?.get("status"))
        assertEquals("abc123", decoded.payload?.get("token"))
    }

    @Test
    fun `frame too short throws exception`() {
        try {
            BinaryCodec.decode(ByteArray(5))
            fail("Expected ProtocolException")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("too short") == true)
        }
    }

    @Test
    fun `encode ack produces response frame`() {
        val ack = BinaryCodec.encodeAck(seq = 5, opcode = 128)
        assertEquals(BinaryCodec.CMD_RESPONSE, ack[1].toInt() and 0xFF)
        // seq = 5
        assertEquals(0, ack[2].toInt() and 0xFF)
        assertEquals(5, ack[3].toInt() and 0xFF)
        // opcode = 128
        assertEquals(0, ack[4].toInt() and 0xFF)
        assertEquals(128, ack[5].toInt() and 0xFF)
        // payload length = 0
        assertEquals(0, ack[7].toInt() and 0xFF)
        assertEquals(0, ack[8].toInt() and 0xFF)
        assertEquals(0, ack[9].toInt() and 0xFF)
    }
}
