package com.freemax.api.transport

import com.freemax.api.protocol.BinaryCodec
import com.freemax.api.protocol.ConnectionState
import com.freemax.api.protocol.WsFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class MaxWebSocket(
    private val scope: CoroutineScope,
    private val onConnected: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "MaxWS"
        private const val WS_URL = "wss://api.oneme.ru/websocket"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val PING_INTERVAL_MS = 30_000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        /**
         * Лог кадров в logcat. Приложение выставляет BuildConfig.DEBUG
         * в FreeMAXApp, чтобы в release payload'ы не попадали в логи.
         */
        var debug = true

        private fun log(msg: String) { if (debug) println("[$TAG] $msg") }
        private fun logWarn(msg: String) { println("[$TAG WARN] $msg") }
        private fun logErr(msg: String, e: Throwable? = null) {
            println("[$TAG ERROR] $msg")
            e?.printStackTrace()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private val request = Request.Builder()
        .url(WS_URL)
        .header("Origin", "https://api.oneme.ru")
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<WsFrame>(
        replay = 64,
        extraBufferCapacity = 256
    )
    val frames: SharedFlow<WsFrame> = _frames.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentAttempt = 0
    private val reconnectLock = Mutex()
    @Volatile private var closedByClient = false

    private fun isCurrentSocket(ws: WebSocket): Boolean = ws === webSocket

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrentSocket(webSocket)) {
                log("onOpen from stale socket, closing it")
                try { webSocket.close(1000, "stale") } catch (_: Exception) {}
                return
            }
            log("WebSocket opened")
            _state.value = ConnectionState.Connected
            currentAttempt = 0
            onConnected?.invoke()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrentSocket(webSocket)) return
            scope.launch {
                try {
                    val data = bytes.toByteArray()
                    log("← recv ${data.size} bytes: ${data.take(20).joinToString(" ") { "%02X".format(it) }}")
                    val frame = BinaryCodec.decode(data)
                    log("← cmd=${frame.cmd} seq=${frame.seq} opcode=0x${frame.opcode.toString(16)} payload=${frame.payload?.keys}")

                    when (frame.cmd) {
                        BinaryCodec.CMD_REQUEST -> {
                            handleServerRequest(frame)
                            _frames.emit(frame)
                        }
                        BinaryCodec.CMD_RESPONSE -> {
                            _frames.emit(frame)
                        }
                        3 -> {
                            // Error response
                            log("← ERROR cmd=3 seq=${frame.seq} opcode=0x${frame.opcode.toString(16)} payload=${frame.payload}")
                            _frames.emit(frame)
                        }
                        else -> {
                            log("← unexpected cmd=${frame.cmd}, emitting anyway")
                            _frames.emit(frame)
                        }
                    }
                } catch (e: Exception) {
                    logErr("Failed to decode frame: ${e.message}")
                    // Dump first 50 bytes hex for debugging
                    val data = bytes.toByteArray()
                    val hex = data.take(50).joinToString(" ") { "%02X".format(it) }
                    log("Raw hex: $hex")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            logWarn("Received text message (unexpected), ${text.length} chars")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrentSocket(webSocket)) return
            log("WebSocket closing: code=$code reason=$reason")
            try { webSocket.close(1000, null) } catch (_: Exception) {}
            // onClosed will follow and decide about reconnect; don't schedule here
            // to avoid double reconnect jobs for a single event.
            _state.value = ConnectionState.Disconnected
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val current = isCurrentSocket(webSocket)
            log("WebSocket closed: code=$code reason=$reason current=$current")
            if (!current) return
            this@MaxWebSocket.webSocket = null
            _state.value = ConnectionState.Disconnected
            if (!closedByClient) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val current = isCurrentSocket(webSocket)
            logErr("WebSocket failure: ${t.message} current=$current", t)
            if (!current) return
            this@MaxWebSocket.webSocket = null
            _state.value = ConnectionState.Error(t.message ?: "Unknown error", t)
            if (!closedByClient) scheduleReconnect()
        }
    }

    fun connect() {
        val st = _state.value
        if (st is ConnectionState.Connecting || st is ConnectionState.Connected) {
            log("connect() ignored, already $st")
            return
        }
        closedByClient = false
        // Close any stale socket without triggering its reconnect path
        val stale = webSocket
        webSocket = null
        if (stale != null) {
            try { stale.cancel() } catch (_: Exception) {}
        }
        _state.value = ConnectionState.Connecting
        log("Connecting to $WS_URL")
        webSocket = client.newWebSocket(request, listener)
    }

    /** Принудительный реконнект: сбрасывает backoff и пересоздаёт сокет. */
    fun reconnectNow() {
        closedByClient = false
        reconnectJob?.cancel()
        reconnectJob = null
        currentAttempt = 0
        val stale = webSocket
        webSocket = null
        if (stale != null) {
            try { stale.cancel() } catch (_: Exception) {}
        }
        _state.value = ConnectionState.Disconnected
        connect()
    }

    fun disconnect() {
        closedByClient = true
        reconnectJob?.cancel()
        reconnectJob = null
        currentAttempt = 0
        val ws = webSocket
        webSocket = null
        if (ws != null) {
            try { ws.close(1000, "Client disconnect") } catch (_: Exception) {}
        }
        _state.value = ConnectionState.Disconnected
    }

    fun sendRequest(seq: Int, opcode: Int, payload: Map<String, Any?>? = null): Boolean {
        val ws = webSocket ?: return false
        val frame = BinaryCodec.encodeRequest(seq, opcode, payload)
        log("→ send cmd=0 seq=$seq opcode=0x${opcode.toString(16)} payload=${payload?.keys}")
        val byteString = frame.toByteString(0, frame.size)
        return ws.send(byteString)
    }

    fun sendRequestWithAuth(seq: Int, opcode: Int, savedAuth: Map<String, Any?>, payload: Map<String, Any?>? = null): Boolean {
        val ws = webSocket ?: return false
        val frame = BinaryCodec.encodeRequestWithAuth(seq, opcode, savedAuth, payload)
        log("→ send cmd=0 seq=$seq opcode=0x${opcode.toString(16)} savedAuth keys=${savedAuth.keys} payload=${payload?.keys}")
        val byteString = frame.toByteString(0, frame.size)
        return ws.send(byteString)
    }

    fun sendAck(seq: Int, opcode: Int): Boolean {
        val ws = webSocket ?: return false
        val frame = BinaryCodec.encodeAck(seq, opcode)
        log("→ ack seq=$seq opcode=0x${opcode.toString(16)}")
        return ws.send(frame.toByteString(0, frame.size))
    }

    fun transitionTo(newState: ConnectionState) {
        _state.value = newState
    }

    private fun handleServerRequest(frame: WsFrame) {
        if (frame.opcode == 1) {
            sendAck(frame.seq, 1)
            return
        }
        // NOTE: opcode 128 is a one-way message push ({chatId,message,...}),
        // not a request — do NOT ack it. Bogus acks got our session killed.
    }

    private fun scheduleReconnect() {
        if (closedByClient) return
        // Single-flight: never run two reconnect jobs (double onFailure/onClosed
        // for one event used to open two sockets and the server killed both).
        if (reconnectJob?.isActive == true) {
            log("scheduleReconnect ignored, job already active")
            return
        }
        reconnectJob = scope.launch {
            reconnectLock.withLock {
                if (closedByClient) return@withLock
                // Re-check under lock: another job may have been scheduled meanwhile.
                if (_state.value is ConnectionState.Connected ||
                    _state.value is ConnectionState.Connecting
                ) {
                    log("scheduleReconnect ignored under lock, already ${_state.value}")
                    return@withLock
                }
                val delayMs = calculateReconnectDelay(currentAttempt)
                currentAttempt++
                log("Reconnecting in ${delayMs}ms (attempt $currentAttempt)")
                _state.value = ConnectionState.Reconnecting(currentAttempt)
                delay(delayMs)
                if (!closedByClient) connect()
            }
        }
    }

    private fun calculateReconnectDelay(attempt: Int): Long {
        val delayMs = RECONNECT_BASE_DELAY_MS * (1L shl attempt)
        return delayMs.coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }
}
