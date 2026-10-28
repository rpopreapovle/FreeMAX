package com.freemax.api

import com.freemax.api.protocol.BinaryCodec
import com.freemax.api.protocol.MsgPack
import com.freemax.api.protocol.Opcodes
import org.junit.Assert.*
import org.junit.Test

class LoginFrameTest {

    private val TOKEN = "test-token-not-a-real-credential"
    private val VIEWER_ID = 123456789L

    @Test
    fun `viewerId encodes as uint32`() {
        val bytes = MsgPack.encodeMap(mapOf("viewerId" to VIEWER_ID))
        println("viewerId encoding: ${bytes.joinToString(" ") { "0x%02X".format(it) }}")
        // 123456789 = 0x075BCD15 — fits in uint32, encoded as 0xCE
        // Map header 0x81 + key "viewerId" (0xA8 ...) + value 0xCE ...
        assertTrue("should contain uint32 marker 0xCE", bytes.any { (it.toInt() and 0xFF) == 0xCE })
        val decoded = MsgPack.decodeMap(bytes)
        assertEquals(VIEWER_ID, (decoded["viewerId"] as Number).toLong())
    }

    @Test
    fun `decode our viewerId encoding`() {
        val bytes = MsgPack.encodeMap(mapOf("viewerId" to VIEWER_ID))
        val decoded = MsgPack.decodeMap(bytes)
        println("decoded viewerId: ${decoded["viewerId"]} (type=${decoded["viewerId"]?.javaClass})")
        assertEquals(VIEWER_ID, (decoded["viewerId"] as Number).toLong())
    }

    @Test
    fun `full savedAuth map encoding`() {
        val savedAuth = mapOf<String, Any?>(
            "token" to TOKEN,
            "viewerId" to VIEWER_ID
        )
        val bytes = MsgPack.encodeMap(savedAuth)
        println("savedAuth hex: ${bytes.joinToString(" ") { "%02X".format(it) }}")
        val decoded = MsgPack.decodeMap(bytes)
        println("decoded savedAuth: $decoded")
        assertEquals(TOKEN, decoded["token"])
        assertEquals(VIEWER_ID, (decoded["viewerId"] as Number).toLong())
    }

    @Test
    fun `LOGIN frame with savedAuth - flat structure`() {
        // This is how the web client sends it: savedAuth + userAgent + deviceId at the same level
        val savedAuth = mapOf<String, Any?>(
            "token" to TOKEN,
            "viewerId" to VIEWER_ID
        )
        val payload = mapOf<String, Any?>(
            "userAgent" to mapOf(
                "deviceType" to "WEB",
                "pushDeviceType" to "WEBPUSH",
                "locale" to "ru",
                "deviceLocale" to "ru",
                "osVersion" to "16",
                "deviceName" to "Android SDK 36",
                "headerUserAgent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
                "isPwa" to false,
                "appVersion" to "26.9.1",
                "screen" to "1080x2340 2.75x",
                "timezone" to "Europe/Moscow"
            ),
            "deviceId" to "test-device-id"
        )

        // Flat: {savedAuth: {...}, userAgent: {...}, deviceId: "..."}
        val combined = mutableMapOf<String, Any?>("savedAuth" to savedAuth)
        combined.putAll(payload)

        val frame = BinaryCodec.encodeRequestWithAuth(1, Opcodes.LOGIN, savedAuth, payload)
        println("Frame hex (first 60 bytes): ${frame.take(60).joinToString(" ") { "%02X".format(it) }}")
        println("Frame size: ${frame.size}")

        // Decode the payload portion (after 10-byte header)
        val payloadBytes = frame.copyOfRange(10, frame.size)
        val decoded = MsgPack.decodeMap(payloadBytes)
        println("Decoded frame payload keys: ${decoded.keys}")

        // Check structure
        assertTrue("should have savedAuth key", decoded.containsKey("savedAuth"))
        assertTrue("should have userAgent key", decoded.containsKey("userAgent"))
        assertTrue("should have deviceId key", decoded.containsKey("deviceId"))
        assertFalse("should NOT have 'payload' key wrapping", decoded.containsKey("payload"))

        val savedAuthDecoded = decoded["savedAuth"] as? Map<*, *>
        assertNotNull("savedAuth should be a map", savedAuthDecoded)
        assertEquals(TOKEN, savedAuthDecoded!!["token"])
        assertEquals(VIEWER_ID, (savedAuthDecoded["viewerId"] as Number).toLong())
    }

    @Test
    fun `compare with web client binary - first 10 bytes header`() {
        val savedAuth = mapOf<String, Any?>("token" to TOKEN, "viewerId" to VIEWER_ID)
        val payload = mapOf<String, Any?>(
            "userAgent" to mapOf("deviceType" to "WEB"),
            "deviceId" to "test"
        )
        val frame = BinaryCodec.encodeRequestWithAuth(2, Opcodes.LOGIN, savedAuth, payload)

        println("Header: ${frame.take(10).joinToString(" ") { "%02X".format(it) }}")
        println("  version=0x%02X cmd=0x%02X seq=%d opcode=0x%02X flags=0x%02X payloadLen=%d".format(
            frame[0].toInt() and 0xFF,
            frame[1].toInt() and 0xFF,
            (frame[2].toInt() and 0xFF) shl 8 or (frame[3].toInt() and 0xFF),
            (frame[4].toInt() and 0xFF) shl 8 or (frame[5].toInt() and 0xFF),
            frame[6].toInt() and 0xFF,
            (frame[7].toInt() and 0xFF) shl 16 or (frame[8].toInt() and 0xFF) shl 8 or (frame[9].toInt() and 0xFF)
        ))

        assertEquals(0x0A, frame[0].toInt() and 0xFF) // protocol version
        assertEquals(0x00, frame[1].toInt() and 0xFF) // CMD_REQUEST
        assertEquals(0x00, frame[6].toInt() and 0xFF) // no flags (no LZ4)
    }
}
