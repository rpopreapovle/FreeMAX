package com.freemax.api.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal MsgPack encoder/decoder for MAX WebSocket protocol.
 * Handles the types used in MAX messages: maps, strings, integers, booleans, nil, arrays.
 */
object MsgPack {

    // --- Encoding ---

    fun encodeMap(map: Map<String, Any?>): ByteArray {
        val out = ByteArrayOutputStream()
        encodeMapInternal(out, map)
        return out.toByteArray()
    }

    private fun encodeMapInternal(out: ByteArrayOutputStream, map: Map<String, Any?>) {
        val size = map.size
        when {
            size <= 15 -> out.write(0x80 or size) // fixmap
            size <= 0xFFFF -> {
                out.write(0xDE) // map 16
                out.write((size shr 8) and 0xFF)
                out.write(size and 0xFF)
            }
            else -> {
                out.write(0xDF) // map 32
                out.write((size shr 24) and 0xFF)
                out.write((size shr 16) and 0xFF)
                out.write((size shr 8) and 0xFF)
                out.write(size and 0xFF)
            }
        }
        for ((key, value) in map) {
            encodeString(out, key)
            encodeValue(out, value)
        }
    }

    data class BigInt(val value: Long)

    private fun encodeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xC0) // nil
            is Boolean -> out.write(if (value) 0xC3 else 0xC2)
            is BigInt -> encodeBigInt(out, value.value)
            is Int -> encodeInt(out, value.toLong())
            is Long -> encodeInt(out, value)
            is String -> encodeString(out, value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                encodeMapInternal(out, value as Map<String, Any?>)
            }
            is List<*> -> encodeArray(out, value)
            is ByteArray -> encodeBinary(out, value)
            else -> encodeString(out, value.toString())
        }
    }

    private fun encodeBigInt(out: ByteArrayOutputStream, value: Long) {
        // Web client encodes BigInt as ext type 1 with msgpack-encoded int64 inside
        val inner = ByteArrayOutputStream()
        if (value >= 0) {
            inner.write(0xCF) // uint64
            for (i in 7 downTo 0) inner.write(((value shr (i * 8)) and 0xFF).toInt())
        } else {
            inner.write(0xD3) // int64
            for (i in 7 downTo 0) inner.write(((value shr (i * 8)) and 0xFF).toInt())
        }
        val data = inner.toByteArray()
        out.write(0xC7) // ext 8
        out.write(data.size)
        out.write(1) // type 1 = BigInt
        out.write(data)
    }

    private fun encodeInt(out: ByteArrayOutputStream, value: Long) {
        when {
            value >= 0 && value <= 127 -> out.write(value.toInt()) // positive fixint
            value >= -32 && value < 0 -> out.write((0xE0 or (value.toInt() and 0x1F))) // negative fixint
            value >= 0 && value <= 0xFF -> {
                out.write(0xCC) // uint8
                out.write(value.toInt())
            }
            value >= 0 && value <= 0xFFFF -> {
                out.write(0xCD) // uint16
                out.write((value.toInt() shr 8) and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value >= 0 && value <= 0xFFFFFFFFL -> {
                out.write(0xCE) // uint32
                out.write((value shr 24).toInt() and 0xFF)
                out.write((value shr 16).toInt() and 0xFF)
                out.write((value shr 8).toInt() and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value >= 0 -> {
                out.write(0xCF) // uint64
                for (i in 7 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
            }
            value >= -128 -> {
                out.write(0xD0) // int8
                out.write(value.toInt() and 0xFF)
            }
            value >= -0x8000 -> {
                out.write(0xD1) // int16
                out.write((value.toInt() shr 8) and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value >= -0x80000000L -> {
                out.write(0xD2) // int32
                out.write((value shr 24).toInt() and 0xFF)
                out.write((value shr 16).toInt() and 0xFF)
                out.write((value shr 8).toInt() and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            else -> {
                out.write(0xD3) // int64
                for (i in 7 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
            }
        }
    }

    private fun encodeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        when {
            bytes.size <= 31 -> out.write(0xA0 or bytes.size) // fixstr
            bytes.size <= 0xFF -> {
                out.write(0xD9) // str 8
                out.write(bytes.size)
            }
            bytes.size <= 0xFFFF -> {
                out.write(0xDA) // str 16
                out.write((bytes.size shr 8) and 0xFF)
                out.write(bytes.size and 0xFF)
            }
            else -> {
                out.write(0xDB) // str 32
                out.write((bytes.size shr 24) and 0xFF)
                out.write((bytes.size shr 16) and 0xFF)
                out.write((bytes.size shr 8) and 0xFF)
                out.write(bytes.size and 0xFF)
            }
        }
        out.write(bytes)
    }

    private fun encodeArray(out: ByteArrayOutputStream, list: List<*>) {
        val size = list.size
        when {
            size <= 15 -> out.write(0x90 or size) // fixarray
            size <= 0xFFFF -> {
                out.write(0xDC) // array 16
                out.write((size shr 8) and 0xFF)
                out.write(size and 0xFF)
            }
            else -> {
                out.write(0xDD) // array 32
                out.write((size shr 24) and 0xFF)
                out.write((size shr 16) and 0xFF)
                out.write((size shr 8) and 0xFF)
                out.write(size and 0xFF)
            }
        }
        for (item in list) encodeValue(out, item)
    }

    private fun encodeBinary(out: ByteArrayOutputStream, data: ByteArray) {
        when {
            data.size <= 0xFF -> {
                out.write(0xC4) // bin 8
                out.write(data.size)
            }
            data.size <= 0xFFFF -> {
                out.write(0xC5) // bin 16
                out.write((data.size shr 8) and 0xFF)
                out.write(data.size and 0xFF)
            }
            else -> {
                out.write(0xC6) // bin 32
                out.write((data.size shr 24) and 0xFF)
                out.write((data.size shr 16) and 0xFF)
                out.write((data.size shr 8) and 0xFF)
                out.write(data.size and 0xFF)
            }
        }
        out.write(data)
    }

    // --- Decoding ---

    fun decodeMap(data: ByteArray): Map<String, Any?> {
        val input = MsgPackInput(data)
        val result = decodeMapInternal(input)
        return result
    }

    private fun decodeMapInternal(input: MsgPackInput): LinkedHashMap<String, Any?> {
        val header = input.readByte()
        val size = when (header) {
            in 0x80..0x8F -> header and 0x0F
            0xDE -> input.readShort() and 0xFFFF
            0xDF -> input.readInt()
            else -> throw MsgPackException("Expected map header, got 0x${header.toString(16)}")
        }
        val map = LinkedHashMap<String, Any?>()
        repeat(size) {
            val key = decodeValue(input)
            val value = decodeValue(input)
            val keyStr = when (key) {
                is ByteArray -> {
                    var r = 0L
                    for (b in key) r = (r shl 8) or (b.toInt() and 0xFF).toLong()
                    if (key.all { it in 32..126 }) String(key, Charsets.UTF_8) else r.toString()
                }
                else -> key.toString()
            }
            map[keyStr] = value
        }
        return map
    }

    private fun decodeValue(input: MsgPackInput): Any? {
        val marker = input.readByte()
        return when (marker) {
            0xC0 -> null                          // nil
            0xC2 -> false                         // false
            0xC3 -> true                          // true
            // positive fixint
            in 0x00..0x7F -> marker.toInt()
            // fixmap
            in 0x80..0x8F -> {
                val size = marker and 0x0F
                decodeMapOfSize(input, size)
            }
            // fixarray
            in 0x90..0x9F -> {
                val size = marker and 0x0F
                decodeArrayOfSize(input, size)
            }
            // fixstr
            in 0xA0..0xBF -> {
                val len = marker and 0x1F
                input.readString(len)
            }
            // bin 8/16/32
            0xC4 -> input.readBinary(input.readByte() and 0xFF)
            0xC5 -> input.readBinary(input.readShort() and 0xFFFF)
            0xC6 -> input.readBinary(input.readInt())
            // ext 8/16/32
            0xC7 -> decodeExt(input, input.readByte() and 0xFF)
            0xC8 -> decodeExt(input, input.readShort() and 0xFFFF)
            0xC9 -> decodeExt(input, input.readInt())
            // fixext 1/2/4/8/16
            0xD4 -> decodeExt(input, 1)
            0xD5 -> decodeExt(input, 2)
            0xD6 -> decodeExt(input, 4)
            0xD7 -> decodeExt(input, 8)
            0xD8 -> decodeExt(input, 16)
            // str 8/16/32
            0xD9 -> input.readString(input.readByte() and 0xFF)
            0xDA -> input.readString(input.readShort() and 0xFFFF)
            0xDB -> input.readString(input.readInt())
            // int 8/16/32/64
            0xD0 -> input.readByte()  // int8
            0xD1 -> input.readShort() // int16
            0xD2 -> input.readInt()   // int32
            0xD3 -> input.readLong()  // int64
            // uint 8/16/32/64
            0xCC -> input.readByte() and 0xFF
            0xCD -> input.readShort() and 0xFFFF
            0xCE -> input.readInt()
            0xCF -> input.readLong()
            // float 32/64
            0xCA -> input.readFloat()
            0xCB -> input.readDouble()
            // negative fixint
            in 0xE0..0xFF -> marker.toInt() - 256
            // map 16/32
            0xDE -> decodeMapOfSize(input, input.readShort() and 0xFFFF)
            0xDF -> decodeMapOfSize(input, input.readInt())
            // array 16/32
            0xDC -> decodeArrayOfSize(input, input.readShort() and 0xFFFF)
            0xDD -> decodeArrayOfSize(input, input.readInt())
            else -> throw MsgPackException("Unknown msgpack marker: 0x${marker.toString(16)}")
        }
    }

    private fun decodeExt(input: MsgPackInput, len: Int): Any? {
        val type = input.readByte() // ext type
        val data = ByteArray(len) { input.readByte().toByte() }
        // Type 1 is BigInt — data is msgpack-encoded int (0xCF/0xD3 + 8 bytes) or raw 8 bytes
        if (type == 1) {
            if (len == 9 && (data[0].toInt() and 0xFF == 0xCF || data[0].toInt() and 0xFF == 0xD3)) {
                var v = 0L
                for (i in 1 until 9) v = (v shl 8) or (data[i].toInt() and 0xFF).toLong()
                return v
            }
            if (len == 8) {
                var v = 0L
                for (b in data) v = (v shl 8) or (b.toInt() and 0xFF).toLong()
                return v
            }
        }
        if (len == 8) {
            var v = 0L
            for (b in data) v = (v shl 8) or (b.toInt() and 0xFF).toLong()
            return v
        }
        return data
    }

    private fun decodeMapOfSize(input: MsgPackInput, size: Int): LinkedHashMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        repeat(size) {
            val key = decodeValue(input)
            val value = decodeValue(input)
            val keyStr = when (key) {
                is ByteArray -> {
                    // Participant IDs etc. encoded as bin — convert to numeric string
                    var r = 0L
                    for (b in key) r = (r shl 8) or (b.toInt() and 0xFF).toLong()
                    // If bytes look like UTF-8 string, try string, else numeric
                    if (key.all { it in 32..126 }) String(key, Charsets.UTF_8) else r.toString()
                }
                else -> key.toString()
            }
            map[keyStr] = value
        }
        return map
    }

    private fun decodeArrayOfSize(input: MsgPackInput, size: Int): List<Any?> {
        val list = ArrayList<Any?>()
        repeat(size) { list.add(decodeValue(input)) }
        return list
    }
}

private class MsgPackInput(private val data: ByteArray) {
    private var pos = 0

    fun readByte(): Int {
        if (pos >= data.size) throw MsgPackException("Unexpected end of msgpack data at pos $pos")
        return data[pos++].toInt() and 0xFF
    }

    fun readShort(): Int {
        return (readByte() shl 8) or readByte()
    }

    fun readInt(): Int {
        return (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
    }

    fun readLong(): Long {
        var result = 0L
        repeat(8) { result = (result shl 8) or readByte().toLong() }
        return result
    }

    fun readString(len: Int): String {
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        return String(bytes, Charsets.UTF_8)
    }

    fun readBinary(len: Int): ByteArray {
        val bytes = data.copyOfRange(pos, pos + len)
        pos += len
        return bytes
    }

    fun readFloat(): Float {
        val bytes = readInt()
        return java.lang.Float.intBitsToFloat(bytes)
    }

    fun readDouble(): Double {
        val bytes = readLong()
        return java.lang.Double.longBitsToDouble(bytes)
    }
}

class MsgPackException(message: String) : RuntimeException(message)
