package com.freemax.api.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire format for MAX WebSocket protocol.
 *
 * Header (10 bytes):
 *   [0]     uint8   = 0x0A (protocol version 10)
 *   [1]     uint8   = cmd (0=request, 1=ack/response, 2=resend-after-login)
 *   [2-3]   int16   = seq (sequence number, big-endian)
 *   [4-5]   int16   = opcode (command code, big-endian)
 *   [6]     uint8   = flags (0=none, >0=LZ4 compressed, decompressed size = payload_length * flags)
 *   [7-9]   uint24  = payload length (big-endian)
 *
 * Payload: MsgPack-encoded data (possibly LZ4 compressed)
 */
object BinaryCodec {
    private const val HEADER_SIZE = 10
    private const val PROTOCOL_VERSION = 0x0A // 10

    // cmd constants
    const val CMD_REQUEST = 0
    const val CMD_RESPONSE = 1
    const val CMD_RESEND = 2

    // --- Encoding ---

    fun encodeRequest(seq: Int, opcode: Int, payload: Map<String, Any?>? = null): ByteArray {
        val payloadBytes = if (payload != null) MsgPack.encodeMap(payload) else ByteArray(0)
        return buildFrame(CMD_REQUEST, seq, opcode, payloadBytes)
    }

    /**
     * Encode request with savedAuth (used for LOGIN with token).
     * The web client sends: {savedAuth: {token, viewerId}, userAgent: {...}, deviceId: "..."}
     */
    fun encodeRequestWithAuth(seq: Int, opcode: Int, savedAuth: Map<String, Any?>, payload: Map<String, Any?>? = null): ByteArray {
        val combined = mutableMapOf<String, Any?>("savedAuth" to savedAuth)
        if (payload != null) combined.putAll(payload)
        val payloadBytes = MsgPack.encodeMap(combined)
        return buildFrame(CMD_REQUEST, seq, opcode, payloadBytes)
    }

    fun encodeResponse(seq: Int, opcode: Int, payload: Map<String, Any?>? = null): ByteArray {
        val payloadBytes = if (payload != null) MsgPack.encodeMap(payload) else ByteArray(0)
        return buildFrame(CMD_RESPONSE, seq, opcode, payloadBytes)
    }

    fun encodeAck(seq: Int, opcode: Int): ByteArray {
        return buildFrame(CMD_RESPONSE, seq, opcode, ByteArray(0))
    }

    private fun buildFrame(cmd: Int, seq: Int, opcode: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(PROTOCOL_VERSION.toByte())
        buffer.put(cmd.toByte())
        buffer.putShort(seq.toShort())
        buffer.putShort(opcode.toShort())
        buffer.put(0.toByte()) // flags = no compression
        // 24-bit big-endian payload length
        buffer.put(((payload.size shr 16) and 0xFF).toByte())
        buffer.put(((payload.size shr 8) and 0xFF).toByte())
        buffer.put((payload.size and 0xFF).toByte())
        buffer.put(payload)
        return buffer.array()
    }

    // --- Decoding ---

    fun decode(data: ByteArray): WsFrame {
        if (data.size < HEADER_SIZE) {
            throw ProtocolException("Frame too short: ${data.size} bytes")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        val cmd = buf.get().toInt() and 0xFF
        val seq = buf.short.toInt()
        val opcode = buf.short.toInt()
        val flags = buf.get().toInt() and 0xFF
        val length = (buf.get().toInt() and 0xFF) shl 16 or
                ((buf.get().toInt() and 0xFF) shl 8) or
                (buf.get().toInt() and 0xFF)

        if (length < 0 || data.size < HEADER_SIZE + length) {
            throw ProtocolException("Invalid payload length: $length (available: ${data.size - HEADER_SIZE})")
        }

        var payloadBytes = if (length > 0) {
            data.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
        } else {
            null
        }

        // Decompress if flags > 0 (LZ4 compression, decompressed size = length * flags)
        if (flags > 0 && payloadBytes != null) {
            val decompressedSize = length * flags
            payloadBytes = lz4Decompress(payloadBytes, decompressedSize)
        }

        val payload = if (payloadBytes != null && payloadBytes.isNotEmpty()) {
            MsgPack.decodeMap(payloadBytes)
        } else {
            null
        }

        return WsFrame(cmd = cmd, seq = seq, opcode = opcode, flags = flags, payload = payload)
    }

    /**
     * LZ4 block decompression — matches the web client's Kne() function.
     * Format: token byte (high 4 bits = literal length, low 4 bits = match length - 4),
     * followed by literals, then 2-byte LE offset + match copy.
     */
    private fun lz4Decompress(input: ByteArray, targetSize: Int): ByteArray {
        val output = ByteArray(targetSize)
        var inPos = 0
        var outPos = 0
        val inLen = input.size

        while (inPos < inLen) {
            val token = input[inPos++].toInt() and 0xFF
            var literalLen = token ushr 4

            // Extended literal length
            if (literalLen == 15) {
                var extra: Int
                do {
                    if (inPos >= inLen) throw ProtocolException("lz4: unexpected end of input")
                    extra = input[inPos++].toInt() and 0xFF
                    literalLen += extra
                } while (extra == 255)
            }

            // Bounds check
            if (inPos + literalLen > inLen || outPos + literalLen > targetSize) {
                throw ProtocolException("lz4: literal overflow at $inPos")
            }

            // Copy literals
            System.arraycopy(input, inPos, output, outPos, literalLen)
            inPos += literalLen
            outPos += literalLen

            // If we've consumed all input, we're done
            if (inPos >= inLen) break

            // Read match offset (2 bytes, little-endian)
            if (inPos + 2 > inLen) throw ProtocolException("lz4: truncated offset")
            val offset = (input[inPos].toInt() and 0xFF) or ((input[inPos + 1].toInt() and 0xFF) shl 8)
            inPos += 2

            if (offset == 0 || offset > outPos) {
                throw ProtocolException("lz4: invalid offset $offset at output pos $outPos")
            }

            // Match length
            var matchLen = (token and 0x0F) + 4
            if ((token and 0x0F) == 0x0F) {
                var extra: Int
                do {
                    if (inPos >= inLen) throw ProtocolException("lz4: unexpected end in match length")
                    extra = input[inPos++].toInt() and 0xFF
                    matchLen += extra
                } while (extra == 255)
            }

            // Copy match bytes
            if (outPos + matchLen > targetSize) {
                throw ProtocolException("lz4: match overflow")
            }
            var srcPos = outPos - offset
            repeat(matchLen) {
                output[outPos++] = output[srcPos++]
            }
        }

        return output
    }
}

data class WsFrame(
    val cmd: Int,
    val seq: Int,
    val opcode: Int,
    val flags: Int,
    val payload: Map<String, Any?>? = null
)

class ProtocolException(message: String) : RuntimeException(message)
