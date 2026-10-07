package com.privee.app.data

import com.privee.net.ChannelEvent
import com.privee.net.PhoenixSocket
import com.privee.signal.DeviceState
import com.privee.signal.ServerCall
import com.privee.signal.SignalClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.io.File

/** A message notice of the `session` channel: [from] is the sender's session name. */
data class IncomingNotice(val from: String)

/**
 * The live connection of a signed-in session: the socket, the `session`
 * channel used for key management, and the [SignalClient] of this device.
 */
class PriveeSession(
    val server: ActiveServer,
    val account: Account,
    client: OkHttpClient,
    private val onUnauthorized: () -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val socket = PhoenixSocket(server.config.url, account.token, client, scope)
    private val sessionChannel = socket.channel("session")

    val signal = SignalClient(
        ownId = account.session.id,
        storage = EncryptedFileStorage(signalFile(server, account.session.id)),
        keys = ServerCall { event, payload -> sessionChannel.push(event, payload) },
    )

    val connected: StateFlow<Boolean> = socket.connected
    val deviceState: StateFlow<DeviceState?> =
        signal.changes.map { it.deviceState }.stateIn(scope, SharingStarted.Eagerly, signal.deviceState)

    private val _keysError = MutableStateFlow(false)

    /** Whether the last key reconciliation with the server failed. */
    val keysError: StateFlow<Boolean> = _keysError.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingNotice>(extraBufferCapacity = 16)
    val incoming: SharedFlow<IncomingNotice> = _incoming.asSharedFlow()

    private val keysLock = Mutex()

    fun start() {
        scope.launch { socket.refused.collect { onUnauthorized() } }
        scope.launch { sessionChannel.events.collect(::handle) }
        scope.launch { sessionChannel.joins.collect { refreshKeys() } }
        socket.connect()
        scope.launch { runCatching { sessionChannel.join() } }
    }

    fun stop() {
        socket.disconnect()
        scope.cancel()
    }

    /** Reconciles the local keys with the server; safe to call concurrently. */
    suspend fun refreshKeys(): DeviceState? = keysLock.withLock {
        runCatching { signal.ensureKeys() }
            .onSuccess { _keysError.value = false }
            .onFailure { _keysError.value = true }
            .getOrNull()
    }

    suspend fun resetIdentity(): DeviceState = keysLock.withLock { signal.resetIdentity() }

    private suspend fun handle(event: ChannelEvent) {
        when (event.event) {
            "replenish_prekeys" -> refreshKeys()
            "identity_superseded" -> event.payload.string("identity_key")?.let { signal.onIdentitySuperseded(it) }
            "message_received" -> event.payload.string("from_session_name")?.let { _incoming.emit(IncomingNotice(it)) }
        }
    }

    companion object {
        /** The Signal state of session [sessionId], stored with the other state of its server. */
        fun signalFile(server: ActiveServer, sessionId: Long) = File(server.directory, "signal-$sessionId.bin")
    }
}

internal fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull
