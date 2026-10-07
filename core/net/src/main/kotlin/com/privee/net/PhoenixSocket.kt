package com.privee.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as QueueChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A channel reply with a non-`ok` status, or a join refused by the server. */
class ChannelException(val status: String, val response: JsonObject) :
    IOException("Channel reply $status: $response") {
    val reason: String? get() = (response["reason"] as? JsonPrimitive)?.contentOrNull
}

/** The server refused the socket (e.g. a revoked token). */
class SocketRefusedException(val code: Int) : IOException("Socket refused with HTTP $code")

data class ChannelEvent(val event: String, val payload: JsonObject)

enum class ChannelState { Closed, Joining, Joined, Errored }

/**
 * Minimal [Phoenix Channels](https://hexdocs.pm/phoenix/channels.html) client
 * (serializer v2) over an OkHttp WebSocket, authenticated with the channels
 * `auth_token` (sent as a `Sec-WebSocket-Protocol` entry, like phoenix.js).
 *
 * Reconnects with backoff and rejoins every channel that was joined.
 */
class PhoenixSocket(
    baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val heartbeatMs: Long = 30_000,
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000),
) {
    private val url = baseUrl.toHttpUrl().newBuilder()
        .addPathSegments("app/socket/websocket")
        .addQueryParameter("vsn", "2.0.0")
        .build()

    private val refs = AtomicLong(0)
    private val channels = ConcurrentHashMap<String, Channel>()
    private val incoming = QueueChannel<String>(QueueChannel.UNLIMITED)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var wanted = false
    private var attempts = 0
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    @Volatile private var pendingHeartbeat: String? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _refused = MutableSharedFlow<SocketRefusedException>(extraBufferCapacity = 1)

    /** Emits when the server rejects the connection: the token is no longer valid. */
    val refused: SharedFlow<SocketRefusedException> = _refused.asSharedFlow()

    init {
        scope.launch { for (text in incoming) dispatch(text) }
    }

    internal fun nextRef(): String = refs.incrementAndGet().toString()

    fun connect() {
        wanted = true
        if (webSocket != null) return
        reconnect?.cancel()
        attempts = 0
        open()
    }

    fun disconnect() {
        wanted = false
        reconnect?.cancel()
        heartbeat?.cancel()
        webSocket?.close(1000, null)
        webSocket = null
        onClosed()
    }

    fun channel(topic: String, params: JsonObject = JsonObject(emptyMap())): Channel =
        channels.getOrPut(topic) { Channel(this, topic, params) }

    internal fun remove(channel: Channel) {
        channels.remove(channel.topic, channel)
    }

    internal fun send(joinRef: String?, ref: String?, topic: String, event: String, payload: JsonObject): Boolean {
        val frame = JsonArray(
            listOf(
                joinRef?.let(::JsonPrimitive) ?: JsonNull,
                ref?.let(::JsonPrimitive) ?: JsonNull,
                JsonPrimitive(topic),
                JsonPrimitive(event),
                payload,
            ),
        )
        return webSocket?.takeIf { _connected.value }?.send(frame.toString()) ?: false
    }

    private fun open() {
        val protocol = "base64url.bearer.phx." +
            Base64.getEncoder().withoutPadding().encodeToString(token.toByteArray())
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "phoenix, $protocol")
            .build()
        webSocket = client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== this@PhoenixSocket.webSocket) return
            attempts = 0
            _connected.value = true
            startHeartbeat(webSocket)
            channels.values.forEach { it.rejoin() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket === this@PhoenixSocket.webSocket) incoming.trySend(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            if (code == 401 || code == 403) {
                wanted = false
                _refused.tryEmit(SocketRefusedException(code))
            }
            lost(webSocket)
        }
    }

    private fun lost(socket: WebSocket) {
        if (socket !== webSocket) return
        webSocket = null
        heartbeat?.cancel()
        onClosed()
        if (wanted) scheduleReconnect()
    }

    private fun onClosed() {
        _connected.value = false
        pendingHeartbeat = null
        channels.values.forEach { it.onSocketClosed() }
    }

    private fun scheduleReconnect() {
        val delayMs = reconnectDelaysMs[attempts.coerceAtMost(reconnectDelaysMs.lastIndex)]
        attempts++
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(delayMs)
            if (wanted && webSocket == null) open()
        }
    }

    private fun startHeartbeat(socket: WebSocket) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                if (pendingHeartbeat != null) {
                    // The previous heartbeat was never answered: the connection is dead.
                    socket.cancel()
                    lost(socket)
                    return@launch
                }
                val ref = nextRef()
                pendingHeartbeat = ref
                send(null, ref, "phoenix", "heartbeat", JsonObject(emptyMap()))
            }
        }
    }

    private fun dispatch(text: String) {
        val frame = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
        if (frame.size != 5) return
        val joinRef = frame[0].stringOrNull()
        val ref = frame[1].stringOrNull()
        val topic = frame[2].stringOrNull() ?: return
        val event = frame[3].stringOrNull() ?: return
        val payload = frame[4] as? JsonObject ?: JsonObject(emptyMap())

        if (topic == "phoenix") {
            if (ref != null && ref == pendingHeartbeat) pendingHeartbeat = null
            return
        }
        channels[topic]?.handle(joinRef, ref, event, payload)
    }

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

/** A topic subscription on a [PhoenixSocket]. */
class Channel internal constructor(
    private val socket: PhoenixSocket,
    val topic: String,
    private val params: JsonObject,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    @Volatile private var joinRef: String? = null
    @Volatile private var wanted = false
    private var firstJoin: CompletableDeferred<JsonObject>? = null

    private val _state = MutableStateFlow(ChannelState.Closed)
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 256)

    /** Server pushes on this topic. */
    val events: SharedFlow<ChannelEvent> = _events.asSharedFlow()

    private val _joins = MutableSharedFlow<JsonObject>(replay = 1, extraBufferCapacity = 8)

    /** Every successful join reply, including rejoins after a reconnection. */
    val joins: SharedFlow<JsonObject> = _joins.asSharedFlow()

    /** Joins the channel and returns the join reply, throwing [ChannelException] on refusal. */
    suspend fun join(timeoutMs: Long = 15_000): JsonObject {
        val deferred = synchronized(this) {
            if (_state.value == ChannelState.Joined) return _joins.replayCache.last()
            wanted = true
            firstJoin ?: CompletableDeferred<JsonObject>().also {
                firstJoin = it
                rejoin()
            }
        }
        return withTimeout(timeoutMs) { deferred.await() }
    }

    fun leave() {
        wanted = false
        val ref = joinRef
        if (ref != null) socket.send(ref, socket.nextRef(), topic, "phx_leave", JsonObject(emptyMap()))
        _state.value = ChannelState.Closed
        failPending(IOException("Channel left"))
        socket.remove(this)
    }

    /**
     * Pushes [event] once the channel is joined and returns the `ok` reply
     * response; throws [ChannelException] for other statuses.
     */
    suspend fun push(event: String, payload: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 15_000): JsonObject =
        withTimeout(timeoutMs) {
            state.first { it == ChannelState.Joined }
            val ref = socket.nextRef()
            val reply = CompletableDeferred<JsonObject>()
            pending[ref] = reply
            try {
                if (!socket.send(joinRef, ref, topic, event, payload)) throw IOException("Not connected")
                unwrap(reply.await())
            } finally {
                pending.remove(ref)
            }
        }

    internal fun rejoin() {
        if (!wanted || _state.value == ChannelState.Joining) return
        val ref = socket.nextRef()
        joinRef = ref
        _state.value = ChannelState.Joining
        val reply = CompletableDeferred<JsonObject>()
        pending[ref] = reply
        if (!socket.send(ref, ref, topic, "phx_join", params)) {
            pending.remove(ref)
            _state.value = ChannelState.Closed
            return
        }
        reply.invokeOnCompletion { error ->
            pending.remove(ref)
            if (ref != joinRef) return@invokeOnCompletion
            if (error != null) {
                _state.value = ChannelState.Closed
                return@invokeOnCompletion
            }
            val result = runCatching { unwrap(reply.getCompleted()) }
            result.onSuccess { response ->
                _state.value = ChannelState.Joined
                _joins.tryEmit(response)
                synchronized(this) { firstJoin.also { firstJoin = null } }?.complete(response)
            }
            result.onFailure { failure ->
                wanted = false
                _state.value = ChannelState.Errored
                synchronized(this) { firstJoin.also { firstJoin = null } }?.completeExceptionally(failure)
            }
        }
    }

    internal fun onSocketClosed() {
        if (_state.value != ChannelState.Closed) _state.value = ChannelState.Closed
        failPending(IOException("Socket closed"))
    }

    internal fun handle(joinRef: String?, ref: String?, event: String, payload: JsonObject) {
        when (event) {
            "phx_reply" -> ref?.let { pending[it] }?.complete(payload)
            "phx_error" -> if (joinRef == null || joinRef == this.joinRef) {
                _state.value = ChannelState.Errored
                failPending(IOException("Channel crashed"))
                _state.value = ChannelState.Closed
                rejoin()
            }
            "phx_close" -> if (joinRef == null || joinRef == this.joinRef) {
                _state.value = ChannelState.Closed
                failPending(IOException("Channel closed"))
            }
            else -> _events.tryEmit(ChannelEvent(event, payload))
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun unwrap(reply: JsonObject): JsonObject {
        val status = reply["status"]?.jsonPrimitive?.content ?: "error"
        val response = reply["response"] as? JsonObject ?: JsonObject(emptyMap())
        if (status != "ok") throw ChannelException(status, response)
        return response
    }
}
