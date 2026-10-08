package com.privee.signal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Whether this device can use the identity published for its session. */
@Serializable
enum class DeviceState {
    @SerialName("ready") Ready,
    @SerialName("needs_reset") NeedsReset,
    @SerialName("superseded") Superseded,
    @SerialName("resetting") Resetting,
}

@Serializable
data class SignedPreKeyMeta(val createdAt: Long, val retiredAt: Long? = null)

/** The identity the user trusts for a peer, and a changed one awaiting approval. */
@Serializable
data class Trust(val publicKey: String, val pendingKey: String? = null)

@Serializable
data class Cursor(val epoch: String, val lastSeq: Long)

@Serializable
data class PeerMeta(
    val name: String? = null,
    val sessionEpoch: String? = null,
    val cursor: Cursor? = null,
    /** Private note of the user about who this peer is. Never leaves the device. */
    val hint: String? = null,
)

@Serializable
enum class Direction {
    @SerialName("in") In,
    @SerialName("out") Out,
}

/** A message of the local plaintext history, keyed by server message id. */
@Serializable
data class HistoryRow(
    val id: String,
    val peerId: Long,
    val epoch: String,
    val seq: Long,
    val direction: Direction,
    /** `null` when the message could not be decrypted. */
    val plaintext: String?,
    val ts: Long,
)

/** An encrypted message waiting for the server acknowledgement, keyed by client nonce. */
@Serializable
data class OutboxRow(
    val nonce: String,
    val peerId: Long,
    val type: Int,
    val body: String,
    val epoch: String,
    val identityKey: String,
    val plaintext: String,
    val ts: Long,
)

/**
 * Everything the Signal client of one session persists on this device. Binary
 * values (keys, records) are base64 strings.
 */
@Serializable
data class SignalState(
    val identity: String? = null,
    val registrationId: Int = 0,
    val deviceState: DeviceState? = null,
    val preKeys: Map<Int, String> = emptyMap(),
    val signedPreKeys: Map<Int, String> = emptyMap(),
    val kyberPreKeys: Map<Int, String> = emptyMap(),
    /** `kyberId:signedPreKeyId:baseKey` of every PreKey message accepted with a last-resort Kyber prekey. */
    val usedKyberPreKeys: Set<String> = emptySet(),
    val signedPreKeyMeta: Map<Int, SignedPreKeyMeta> = emptyMap(),
    val spkCounter: Int = 0,
    val spkCurrent: Int? = null,
    val opkCounter: Int = 0,
    val sessions: Map<String, String> = emptyMap(),
    /** Identities known to libsignal, by address name. */
    val identities: Map<String, String> = emptyMap(),
    val trust: Map<String, Trust> = emptyMap(),
    val peers: Map<String, PeerMeta> = emptyMap(),
    val history: Map<String, HistoryRow> = emptyMap(),
    val outbox: Map<String, OutboxRow> = emptyMap(),
)

/** Persistence of the serialized [SignalState] (e.g. an encrypted file). */
interface StateStorage {
    fun load(): String?

    fun save(state: String)

    fun clear()
}

class IdentityChangedException(val peerId: Long) :
    Exception("The identity key of $peerId changed and must be approved")

class NoPeerKeysException : Exception("The peer has not published encryption keys yet")

class DeviceNotReadyException(val state: DeviceState?) :
    Exception("This device cannot send messages (${state ?: "not initialized"})")

class ServerException(val reason: String?) : Exception("Server error: $reason")
