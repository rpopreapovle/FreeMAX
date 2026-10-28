package com.freemax.api

import com.freemax.api.protocol.BinaryCodec
import com.freemax.api.protocol.MsgPack
import com.freemax.api.protocol.Opcodes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Local integration test — run with:
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :freemax-api:test --tests "com.freemax.api.WsIntegrationTest.localLoginWithToken" --info
 *
 * Set env or edit TOKEN/VIEWER_ID below.
 */
class WsIntegrationTest {

    // Local integration test only. Provide credentials through env:
    //   MAX_TEST_TOKEN=... MAX_TEST_VIEWER_ID=...
    // Without them the test is skipped, so public CI never touches a real account.
    private val token = System.getenv("MAX_TEST_TOKEN").orEmpty()
    private val viewerId = System.getenv("MAX_TEST_VIEWER_ID")?.toLongOrNull() ?: 0L

    @org.junit.Test
    fun localLoginWithToken() = runBlocking {
        org.junit.Assume.assumeTrue(
            "MAX_TEST_TOKEN and MAX_TEST_VIEWER_ID are required",
            token.isNotBlank() && viewerId > 0L
        )
        val latch = CountDownLatch(1)
        val frames = mutableListOf<String>()

        val client = OkHttpClient.Builder().build()
        val request = Request.Builder()
            .url("wss://api.oneme.ru/websocket")
            .header("Origin", "https://web.max.ru")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
            .build()

        // Build LOGIN frame with savedAuth flat (same as MaxClient.connectWithToken)
        val savedAuth = mapOf<String, Any?>("token" to token, "viewerId" to viewerId)
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
            "deviceId" to java.util.UUID.randomUUID().toString()
        )

        // Web client LOGIN is anonymous (no savedAuth), even with token available
        val loginFrame = BinaryCodec.encodeRequest(1, Opcodes.LOGIN, payload)
        println("=== LOGIN frame (anonymous) ===")
        println("header: ${loginFrame.take(10).joinToString(" ") { "%02X".format(it) }}")
        val decodedPayload = MsgPack.decodeMap(loginFrame.copyOfRange(10, loginFrame.size))
        println("decoded keys: ${decodedPayload.keys}")
        println("decoded userAgent present: ${decodedPayload.containsKey("userAgent")}")
        println()

        // For comparison: LOGIN with savedAuth (old way)
        val loginWithAuthFrame = BinaryCodec.encodeRequestWithAuth(99, Opcodes.LOGIN, savedAuth, payload)
        println("=== LOGIN frame (with savedAuth, for reference) decoded keys: ${MsgPack.decodeMap(loginWithAuthFrame.copyOfRange(10, loginWithAuthFrame.size)).keys} ===")

        // Also build flat token frames for opcode 23 and 19
        val subPayload = mapOf<String, Any?>("token" to token, "viewerId" to viewerId)
        val subFrame = BinaryCodec.encodeRequest(2, Opcodes.REGISTER, subPayload)
        println("=== SUBSCRIBE (opcode 23) flat frame ===")
        println("header: ${subFrame.take(10).joinToString(" ") { "%02X".format(it) }}")
        val decodedSub = MsgPack.decodeMap(subFrame.copyOfRange(10, subFrame.size))
        println("decoded sub keys: ${decodedSub.keys}")

        val subPayloadWithType = mapOf<String, Any?>("token" to token, "tokenType" to "LOGIN", "viewerId" to viewerId)
        val subFrameWithType = BinaryCodec.encodeRequest(3, Opcodes.REGISTER, subPayloadWithType)
        println("=== SUBSCRIBE (opcode 23) with tokenType frame ===")
        println("header: ${subFrameWithType.take(10).joinToString(" ") { "%02X".format(it) }}")
        println("decoded: ${MsgPack.decodeMap(subFrameWithType.copyOfRange(10, subFrameWithType.size)).keys}")

        // Web client actually uses opcode 19 for sync: th.cmd(19,{token, chatsCount:15, lastLogin, interactive, ...})
        val syncPayload = mapOf<String, Any?>(
            "token" to token,
            "chatsCount" to 15,
            "lastLogin" to 0,
            "interactive" to true
        )
        val syncFrame = BinaryCodec.encodeRequest(2, 19, syncPayload)
        println("=== SYNC (opcode 19) frame seq=2 ===")
        println("header: ${syncFrame.take(10).joinToString(" ") { "%02X".format(it) }}")
        println("decoded: ${MsgPack.decodeMap(syncFrame.copyOfRange(10, syncFrame.size)).keys}")
        println()

        // Now actually connect and send
        println("=== Connecting to wss://api.oneme.ru/websocket ===")
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("[WS] opened, sending anonymous LOGIN")
                webSocket.send(loginFrame.toByteString(0, loginFrame.size))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                println("[WS] recv ${data.size} bytes: ${data.take(20).joinToString(" ") { "%02X".format(it) }}...")
                try {
                    val frame = BinaryCodec.decode(data)
                    println("[WS] decoded: cmd=${frame.cmd} seq=${frame.seq} opcode=0x${frame.opcode.toString(16)} payloadKeys=${frame.payload?.keys}")
                    if (frame.opcode == 19) {
                        val chats = frame.payload?.get("chats") as? List<Map<String, Any?>>
                        val contacts = frame.payload?.get("contacts") as? List<Map<String, Any?>>
                        println("[WS] AUTH_SYNC contacts: ${contacts?.take(2)?.map { it["names"] }}")
                        chats?.take(3)?.forEachIndexed { idx, c ->
                            val type = c["type"]
                            val title = c["title"]
                            val participants = c["participants"] as? Map<*, *>
                            val pids = participants?.keys?.map { k ->
                                when(k) {
                                    is ByteArray -> k.joinToString("") { "%02x".format(it) }.take(8) + "->" + run { var r=0L; for(b in k) r = (r shl 8) or (b.toInt() and 0xFF).toLong(); r }
                                    else -> k.toString()
                                }
                            }
                            println("[WS] chat[$idx] type=$type title=$title pids=$pids lastMsg=${(c["lastMessage"] as? Map<*,*>)?.get("text")?.toString()?.take(20)}")
                        }
                    } else {
                        println("[WS] payload: ${frame.payload}")
                    }
                    frames.add(frame.payload.toString())
                    // After LOGIN response, send SYNC (19) as web client does
                    if (frame.opcode == Opcodes.LOGIN && frame.cmd == BinaryCodec.CMD_RESPONSE) {
                        println("[WS] LOGIN response received, sending AUTH_SYNC(19) in 500ms")
                        Thread {
                            Thread.sleep(500)
                            println("[WS] sending AUTH_SYNC (19) with token")
                            webSocket.send(syncFrame.toByteString(0, syncFrame.size))
                        }.start()
                    }
                    if (frame.opcode == 19 && frame.cmd == BinaryCodec.CMD_RESPONSE) {
                        val chats = frame.payload?.get("chats") as? List<Map<String, Any?>>
                        val target = chats?.firstOrNull { it["type"] == "CHAT" } ?: chats?.firstOrNull()
                        if (target != null) {
                            val chatId = when(val v = target["id"]) {
                                is Number -> v.toLong()
                                is ByteArray -> { var r=0L; for(b in v) r = (r shl 8) or (b.toInt() and 0xFF).toLong(); r }
                                else -> 0L
                            }
                            val cid = when(val v = target["cid"]) {
                                is Number -> v.toLong()
                                is ByteArray -> { var r=0L; for(b in v) r = (r shl 8) or (b.toInt() and 0xFF).toLong(); r }
                                else -> 0L
                            }
                            println("[WS] Trying brute force for chat title=${target["title"]} id=$chatId cid=$cid")
                            Thread {
                                val ops = listOf(49 to "LOAD_MESSAGES", 51 to "CTX", 58 to "OPEN_CHAT", 64 to "SEND_MESSAGE?", 71 to "BATCH", 74 to "MARK_READ", 76 to "GET_CALL_HISTORY", 80 to "GET_OWN_PROFILE", 96 to "GET_CONFIG")
                                var seq2 = 10
                                for ((op, name) in ops) {
                                    Thread.sleep(800)
                                    val pl = if (op==58) mapOf<String, Any?>("chatId" to cid) else mapOf<String, Any?>("chatId" to cid, "count" to 20)
                                    val f = BinaryCodec.encodeRequest(seq2++, op, pl)
                                    println("[WS] sending $name op=$op chatId=$cid")
                                    webSocket.send(f.toByteString(0, f.size))
                                }
                            }.start()
                        }
                    }
                    if (frames.size >= 12) latch.countDown()
                } catch (e: Exception) {
                    println("[WS] decode error: $e")
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("[WS] failure: $t")
                latch.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("[WS] closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("[WS] closed: $code $reason")
                latch.countDown()
            }
        })

        latch.await(30, TimeUnit.SECONDS)
        ws.close(1000, "")
        client.dispatcher.executorService.shutdown()
        println("=== Done, frames: $frames ===")
    }
}
