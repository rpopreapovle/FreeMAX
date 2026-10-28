package com.freemax.api

import com.freemax.api.protocol.BinaryCodec
import com.freemax.api.protocol.MsgPack
import org.junit.Test

class DecompressTest {
    @Test
    fun testDecompress() {
        val hex = "f02185a6636861744964c70901d3ffffc180c2bdd574a466726f6dcf000001a05d66d393a7666f727761726400a86261636b0a00e01eab6765744d65737361676573c3"
        val compressed = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        println("compressed ${compressed.size}")
        for (size in listOf(100, 150, 200, 300, 500, 1000)) {
            try {
                val m = BinaryCodec::class.java.getDeclaredMethod("lz4Decompress", ByteArray::class.java, Int::class.javaPrimitiveType)
                m.isAccessible = true
                val dec = m.invoke(BinaryCodec, compressed, size) as ByteArray
                println("size $size -> ${dec.size} ${dec.take(20).joinToString(" "){"%02x".format(it)}}")
                try {
                    val map = MsgPack.decodeMap(dec)
                    println("decoded $map")
                    break
                } catch (e: Exception) {
                    println("decode fail $e")
                }
            } catch (e: Exception) {
                println("decompress $size fail ${e.cause?.message}")
            }
        }
    }
}
