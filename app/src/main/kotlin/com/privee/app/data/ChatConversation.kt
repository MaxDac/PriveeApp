package com.privee.app.data

import com.privee.net.ChannelException
import com.privee.signal.DeviceNotReadyException
import com.privee.signal.DeviceState
import com.privee.signal.Direction
import com.privee.signal.IdentityChangedException
import com.privee.signal.NoPeerKeysException
import com.privee.signal.ServerCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A message of the conversation, from the local history or the outbox. */
data class ChatItem(
    val key: String,
    val text: String?,
    val outgoing: Boolean,
    val pending: Boolean,
    val ts: Long,
)

/** A transient problem to show above the composer. */
enum class ChatNotice { NoPeerKeys, SendFailed, SyncFailed }

/**
 * One open conversation with [peerName], over its `chat:` channel. Like the
 * web chat, it catches up with missed messages and delivers the outbox on
 * every (re)join, and the history is kept on this device only.
 */
class ChatConversation(
    private val session: PriveeSession,
    val peerName: String,
    private val scope: CoroutineScope,
) {
    private val signal = session.signal
    private val channel = session.socket.channel("chat:$peerName")
    private val chat = ServerCall { event, payload -> channel.push(event, payload) }
    private val syncLock = Mutex()
    private val jobs = mutableListOf<Job>()

    private val _peerId = MutableStateFlow<Long?>(null)
    val peerId: StateFlow<Long?> = _peerId.asStateFlow()

    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound.asStateFlow()

    private val _notice = MutableStateFlow<ChatNotice?>(null)
    val notice: StateFlow<ChatNotice?> = _notice.asStateFlow()

    val identityChanged: StateFlow<Boolean> = combine(signal.changes, _peerId) { state, id ->
        id != null && state.trust[id.toString()]?.pendingKey != null
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val items: StateFlow<List<ChatItem>> = combine(signal.changes, _peerId) { state, id ->
        if (id == null) return@combine emptyList()
        val history = signal.history(id, state).map {
            ChatItem(it.id, it.plaintext, it.direction == Direction.Out, pending = false, ts = it.ts)
        }
        val outbox = signal.pendingOutbox(id, state).map {
            ChatItem(it.nonce, it.plaintext, outgoing = true, pending = true, ts = it.ts)
        }
        history + outbox
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun start() {
        jobs += scope.launch {
            channel.events.collect { event ->
                if (event.event == "new_message" || event.event == "peer_keys_ready") sync()
            }
        }
        jobs += scope.launch {
            channel.joins.collect { reply ->
                val id = reply["peer_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                signal.rememberPeer(id, reply.string("peer_session_name") ?: peerName)
                _peerId.value = id
                sync()
            }
        }
        // Keys are reconciled on the session channel; sync again once ready.
        jobs += scope.launch {
            session.deviceState.collect { if (it == DeviceState.Ready) sync() }
        }
        jobs += scope.launch {
            try {
                channel.join()
            } catch (e: ChannelException) {
                if (e.reason == "not_found") _notFound.value = true
            } catch (_: Exception) {
                // Retried by the socket on reconnection.
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        channel.leave()
    }

    /** Catches up with missed messages and delivers pending ones. */
    suspend fun sync() = syncLock.withLock {
        val id = _peerId.value ?: return@withLock
        if (session.deviceState.value != DeviceState.Ready) return@withLock
        try {
            val epoch = signal.openConversation(chat)
            if (!signal.hasPendingIdentity(id)) signal.catchUp(chat, id, epoch)
            if (signal.pendingOutbox(id).isNotEmpty()) signal.flushOutbox(chat, id)
            if (_notice.value == ChatNotice.SyncFailed) _notice.value = null
        } catch (_: IdentityChangedException) {
            // Shown through [identityChanged] until the user approves.
        } catch (_: NoPeerKeysException) {
            _notice.value = ChatNotice.NoPeerKeys
        } catch (_: DeviceNotReadyException) {
            // Shown through the device state.
        } catch (_: Exception) {
            _notice.value = ChatNotice.SyncFailed
        }
    }

    /** Encrypts and sends [text]; it stays in the outbox when delivery fails. */
    suspend fun send(text: String): Boolean = syncLock.withLock {
        val id = _peerId.value ?: return@withLock false
        val started = System.currentTimeMillis()
        try {
            signal.send(chat, id, text)
            _notice.value = null
            true
        } catch (_: IdentityChangedException) {
            false
        } catch (_: NoPeerKeysException) {
            _notice.value = ChatNotice.NoPeerKeys
            false
        } catch (_: DeviceNotReadyException) {
            false
        } catch (_: Exception) {
            _notice.value = ChatNotice.SendFailed
            // Encrypted rows stay in the outbox and are retried on the next sync.
            signal.pendingOutbox(id).any { it.plaintext == text && it.ts >= started }
        }
    }

    suspend fun approveIdentity() {
        val id = _peerId.value ?: return
        signal.approveIdentity(id)
        sync()
    }

    suspend fun safetyNumber(): String? = _peerId.value?.let { signal.safetyNumber(it) }

    suspend fun clearHistory() {
        _peerId.value?.let { signal.clearHistory(it) }
    }

    fun dismissNotice() {
        _notice.value = null
    }
}
