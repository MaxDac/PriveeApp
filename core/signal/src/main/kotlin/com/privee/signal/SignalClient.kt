package com.privee.signal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.time.Instant
import java.util.UUID

/** A reply-based channel push; replies carry an `error` field on failure. */
fun interface ServerCall {
    suspend fun call(event: String, payload: JsonObject): JsonObject
}

/**
 * Signal Protocol client of one Privee session on this device: a port of the
 * web `signal-client.mjs`, so both clients interoperate and follow the same
 * key management, trust and conversation epoch rules.
 *
 * Every operation runs under a single lock on a working copy of the
 * [SignalState]: it is persisted when the operation succeeds and discarded
 * when it fails, so ratchet steps are atomic with history and outbox changes.
 *
 * @param ownId the numeric id of the own session (for safety numbers).
 * @param keys the `session` channel, for key management events.
 */
class SignalClient(
    private val ownId: Long,
    private val storage: StateStorage,
    private val keys: ServerCall,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var committed: SignalState = storage.load()?.let { json.decodeFromString(it) } ?: SignalState()
    private val holder = StateHolder(committed)
    private val store = PriveeProtocolStore(holder)

    private val _changes = MutableStateFlow(committed)

    /** The last persisted state, to observe history, outbox and device state. */
    val changes: StateFlow<SignalState> = _changes.asStateFlow()

    // -- Transactions ---------------------------------------------------------

    private suspend fun <T> transaction(block: suspend Tx.() -> T): T = mutex.withLock {
        holder.state = committed
        try {
            Tx().block().also { commit() }
        } catch (e: Throwable) {
            holder.state = committed
            throw e
        }
    }

    private fun commit() {
        if (holder.state === committed) return
        storage.save(json.encodeToString(SignalState.serializer(), holder.state))
        committed = holder.state
        _changes.value = committed
    }

    private inner class Tx {
        var state: SignalState
            get() = holder.state
            set(value) {
                holder.state = value
            }

        /** Persists the changes so far, before the operation is complete. */
        fun commit() = this@SignalClient.commit()

        /** Drops the changes since the last commit. */
        fun discard() {
            holder.state = committed
        }

        fun peer(peerId: Long): PeerMeta = state.peers[peerId.toString()] ?: PeerMeta()

        fun putPeer(peerId: Long, meta: PeerMeta) {
            state = state.copy(peers = state.peers + (peerId.toString() to meta))
        }
    }

    // -- Device state and key management --------------------------------------

    val deviceState: DeviceState? get() = _changes.value.deviceState

    /** Own identity key (base64), if any. */
    val identityKey: String? get() = _changes.value.identity?.let { publicIdentity(it) }

    private fun publicIdentity(serialized: String): String =
        IdentityKeyPair(serialized.unb64()).publicKey.serialize().b64()

    /** Reconciles local keys with the published bundle; returns the device state. */
    suspend fun ensureKeys(): DeviceState = transaction {
        val status = keys.call("signal_status", JsonObject(emptyMap())).orThrow()
        val serverKey = status.string("identity_key")
        val localKey = state.identity?.let(::publicIdentity)
        val maxOpkId = status.int("max_opk_id") ?: 0

        val result = when {
            localKey == null && serverKey != null -> DeviceState.NeedsReset
            localKey == null -> {
                publishNewIdentity("publish_identity", maxOpkId)
                DeviceState.Ready
            }
            serverKey == null -> {
                publishBundle("publish_identity", maxOpkId)
                DeviceState.Ready
            }
            serverKey != localKey -> DeviceState.Superseded
            else -> {
                maintainKeys(status)
                DeviceState.Ready
            }
        }
        state = state.copy(deviceState = result)
        result
    }

    /**
     * Replaces the identity of the session with a new one generated on this
     * device: other devices become superseded and every peer session is rebuilt.
     */
    suspend fun resetIdentity(): DeviceState = transaction {
        state = state.copy(deviceState = DeviceState.Resetting)
        commit()
        val status = keys.call("signal_status", JsonObject(emptyMap())).orThrow()
        publishNewIdentity("reset_identity", status.int("max_opk_id") ?: 0)
        state = state.copy(deviceState = DeviceState.Ready)
        DeviceState.Ready
    }

    /** Marks the device superseded after the server reported another identity. */
    suspend fun onIdentitySuperseded(serverKey: String) = transaction {
        if (state.identity?.let(::publicIdentity) != serverKey) {
            state = state.copy(deviceState = DeviceState.Superseded)
        }
    }

    private suspend fun Tx.publishNewIdentity(event: String, maxOpkId: Int) {
        state = state.copy(
            identity = IdentityKeyPair.generate().serialize().b64(),
            registrationId = KeyHelper.generateRegistrationId(false),
            identities = emptyMap(),
            sessions = emptyMap(),
        )
        if (event == "reset_identity") {
            state = state.copy(
                signedPreKeys = emptyMap(),
                kyberPreKeys = emptyMap(),
                signedPreKeyMeta = emptyMap(),
                spkCurrent = null,
                preKeys = emptyMap(),
                peers = state.peers.mapValues { (_, meta) -> meta.copy(sessionEpoch = null) },
            )
        }
        publishBundle(event, maxOpkId)
    }

    private suspend fun Tx.publishBundle(event: String, maxOpkId: Int) {
        val signed = createSignedPreKey()
        val oneTime = createOneTimePreKeys(OPK_TARGET, maxOpkId)

        // Private keys are persisted before the public halves are published.
        commit()
        keys.call(
            event,
            buildJsonObject {
                put("identity_key", ownKey())
                put("registration_id", state.registrationId)
                put("signed_prekey", signed.first)
                put("kyber_prekey", signed.second)
                put("one_time_prekeys", JsonArray(oneTime))
            },
        ).orThrow()
    }

    private suspend fun Tx.maintainKeys(status: JsonObject) {
        val now = now()
        val current = state.spkCurrent?.let { state.signedPreKeyMeta[it] }
        if (current == null || now - current.createdAt >= SPK_ROTATION_MS) {
            val signed = createSignedPreKey()
            commit()
            keys.call(
                "rotate_signed_prekey",
                buildJsonObject {
                    put("identity_key", ownKey())
                    put("signed_prekey", signed.first)
                    put("kyber_prekey", signed.second)
                },
            ).orThrow()
        }

        // Old prekeys stay available for PreKey messages still on the server.
        val retention = (status.long("max_age_ms") ?: DEFAULT_MAX_AGE_MS) + DAY_MS
        val expired = state.signedPreKeyMeta.filter { (id, meta) ->
            id != state.spkCurrent && meta.retiredAt != null && now - meta.retiredAt > retention
        }.keys
        if (expired.isNotEmpty()) {
            state = state.copy(
                signedPreKeys = state.signedPreKeys - expired,
                kyberPreKeys = state.kyberPreKeys - expired,
                signedPreKeyMeta = state.signedPreKeyMeta - expired,
            )
            commit()
        }

        val opkCount = status.int("opk_count") ?: 0
        if (opkCount < OPK_LOW_WATERMARK) {
            val oneTime = createOneTimePreKeys(OPK_TARGET - opkCount, status.int("max_opk_id") ?: 0)
            commit()
            keys.call(
                "add_prekeys",
                buildJsonObject {
                    put("identity_key", ownKey())
                    put("one_time_prekeys", JsonArray(oneTime))
                },
            ).orThrow()
        }
    }

    private fun Tx.ownKey(): String = publicIdentity(checkNotNull(state.identity))

    /** Generates a signed prekey and the Kyber last-resort prekey sharing its id. */
    private fun Tx.createSignedPreKey(): Pair<JsonObject, JsonObject> {
        val identity = store.identityKeyPair
        val keyId = state.spkCounter + 1
        val createdAt = now()

        val ec = ECKeyPair.generate()
        val ecSignature = identity.privateKey.calculateSignature(ec.publicKey.serialize())
        store.storeSignedPreKey(keyId, SignedPreKeyRecord(keyId, createdAt, ec, ecSignature))

        val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kemSignature = identity.privateKey.calculateSignature(kem.publicKey.serialize())
        store.storeKyberPreKey(keyId, KyberPreKeyRecord(keyId, createdAt, kem, kemSignature))

        // Retention of the previous key starts when it stops being published.
        val meta = state.signedPreKeyMeta.toMutableMap()
        state.spkCurrent?.let { previous -> meta[previous]?.let { meta[previous] = it.copy(retiredAt = createdAt) } }
        meta[keyId] = SignedPreKeyMeta(createdAt)
        state = state.copy(spkCounter = keyId, spkCurrent = keyId, signedPreKeyMeta = meta)

        return publishedKey(keyId, ec.publicKey.serialize(), ecSignature) to
            publishedKey(keyId, kem.publicKey.serialize(), kemSignature)
    }

    /** Reserves new one-time prekey ids above any used locally or accepted by the server. */
    private fun Tx.createOneTimePreKeys(count: Int, serverMaxId: Int): List<JsonObject> {
        val start = maxOf(state.opkCounter, serverMaxId)
        val published = (start + 1..start + count).map { keyId ->
            val pair = ECKeyPair.generate()
            store.storePreKey(keyId, PreKeyRecord(keyId, pair))
            publishedKey(keyId, pair.publicKey.serialize(), null)
        }
        state = state.copy(opkCounter = start + count)
        return published
    }

    // -- Trust ------------------------------------------------------------------

    /** Whether a changed identity of [peerId] waits for the user's approval. */
    fun hasPendingIdentity(peerId: Long): Boolean = _changes.value.trust[peerId.toString()]?.pendingKey != null

    /** Accepts the pending identity of [peerId]; the next message rebuilds the session. */
    suspend fun approveIdentity(peerId: Long) = transaction {
        val name = peerId.toString()
        val pending = state.trust[name]?.pendingKey ?: return@transaction
        state = state.copy(
            trust = state.trust + (name to Trust(pending)),
            identities = state.identities + (name to pending),
            sessions = state.sessions - name,
        )
        putPeer(peerId, peer(peerId).copy(sessionEpoch = null))
    }

    /** Safety number of the conversation with [peerId], once its identity is known. */
    suspend fun safetyNumber(peerId: Long): String? = transaction {
        val trust = state.trust[peerId.toString()] ?: return@transaction null
        val peerKey = IdentityKey((trust.pendingKey ?: trust.publicKey).unb64())
        val ownKey = state.identity?.let { IdentityKeyPair(it.unb64()).publicKey } ?: return@transaction null
        NumericFingerprintGenerator(FINGERPRINT_ITERATIONS)
            .createFor(
                FINGERPRINT_VERSION,
                ownId.toString().toByteArray(),
                ownKey,
                peerId.toString().toByteArray(),
                peerKey,
            )
            .displayableFingerprint
            .displayText
    }

    private fun Tx.isTrusted(name: String, key: String): Boolean {
        val trust = state.trust[name] ?: return true
        return trust.publicKey == key
    }

    private fun Tx.assertTrusted(peerId: Long, key: String) {
        val name = peerId.toString()
        if (isTrusted(name, key)) {
            state = state.copy(identities = state.identities + (name to key))
            return
        }
        val trust = state.trust.getValue(name)
        if (trust.pendingKey != key) {
            state = state.copy(trust = state.trust + (name to trust.copy(pendingKey = key)))
            commit()
        }
        throw IdentityChangedException(peerId)
    }

    private fun Tx.pinIdentity(name: String, key: String) {
        if (name !in state.trust) state = state.copy(trust = state.trust + (name to Trust(key)))
    }

    // -- Sessions -----------------------------------------------------------------

    /** Records the session name of [peerId], for the conversations list. */
    suspend fun rememberPeer(peerId: Long, name: String) = transaction {
        if (peer(peerId).name != name) putPeer(peerId, peer(peerId).copy(name = name))
    }

    private fun Tx.hasUsableSession(name: String): Boolean {
        val record = state.sessions[name]?.let { SessionRecord(it.unb64()) } ?: return false
        return record.sessionVersion >= PQXDH_VERSION && record.hasSenderChain(Instant.ofEpochMilli(now()))
    }

    private suspend fun Tx.buildSession(chat: ServerCall, peerId: Long) {
        val reply = chat.call("request_peer_bundle", JsonObject(emptyMap()))
        val bundle = reply["bundle"] as? JsonObject
        val kyber = bundle?.get("kyber_prekey") as? JsonObject
        if (reply.string("error") != null || bundle == null || kyber == null) throw NoPeerKeysException()

        val name = peerId.toString()
        val identityKey = bundle.getValue("identity_key").jsonPrimitive.content
        assertTrusted(peerId, identityKey)

        val spk = bundle.getValue("signed_prekey").jsonObject
        val opk = bundle["one_time_prekey"] as? JsonObject
        val preKeyBundle = PreKeyBundle(
            bundle.getValue("registration_id").jsonPrimitive.int,
            DEVICE_ID,
            opk?.int("key_id") ?: PreKeyBundle.NULL_PRE_KEY_ID,
            opk?.let { ECPublicKey(it.bytes("public_key")) },
            spk.getValue("key_id").jsonPrimitive.int,
            ECPublicKey(spk.bytes("public_key")),
            spk.bytes("signature"),
            IdentityKey(identityKey.unb64()),
            kyber.getValue("key_id").jsonPrimitive.int,
            KEMPublicKey(kyber.bytes("public_key")),
            kyber.bytes("signature"),
        )
        state = state.copy(sessions = state.sessions - name)
        SessionBuilder(store, address(peerId)).process(preKeyBundle, Instant.ofEpochMilli(now()))
        pinIdentity(name, identityKey)
    }

    private fun Tx.assertCanSend() {
        if (state.deviceState != DeviceState.Ready) throw DeviceNotReadyException(state.deviceState)
    }

    /**
     * Encrypts [plaintext] for [peerId] in [epoch] and queues it in the
     * outbox. With [replaceNonce], returns `null` if that row is no longer
     * pending, so a message is never sent twice.
     */
    suspend fun encryptMessage(
        chat: ServerCall,
        peerId: Long,
        plaintext: String,
        epoch: String,
        replaceNonce: String? = null,
        ts: Long? = null,
        rebuild: Boolean = false,
    ): OutboxRow? = transaction {
        assertCanSend()
        if (replaceNonce != null && replaceNonce !in state.outbox) return@transaction null
        if (state.trust[peerId.toString()]?.pendingKey != null) throw IdentityChangedException(peerId)

        val name = peerId.toString()
        if (rebuild || !hasUsableSession(name) || peer(peerId).sessionEpoch != epoch) {
            buildSession(chat, peerId)
        }

        val ciphertext = SessionCipher(store, address(peerId))
            .encrypt(plaintext.toByteArray(), Instant.ofEpochMilli(now()))
        val row = OutboxRow(
            nonce = UUID.randomUUID().toString(),
            peerId = peerId,
            type = ciphertext.type,
            body = ciphertext.serialize().b64(),
            epoch = epoch,
            identityKey = ownKey(),
            plaintext = plaintext,
            ts = ts ?: now(),
        )
        state = state.copy(outbox = state.outbox - listOfNotNull(replaceNonce) + (row.nonce to row))
        putPeer(peerId, peer(peerId).copy(sessionEpoch = epoch))
        row
    }

    /**
     * Decrypts an incoming message into the local history. Returns the cached
     * plaintext for messages already processed and `null` for undecryptable
     * ones; throws [IdentityChangedException] until a changed identity is approved.
     */
    suspend fun decryptMessage(peerId: Long, message: ServerMessage): String? = transaction {
        state.history[message.id]?.let { return@transaction it.plaintext }
        if (state.deviceState == DeviceState.Resetting) throw DeviceNotReadyException(DeviceState.Resetting)

        val meta = peer(peerId)
        val preKey = message.type == CiphertextMessage.PREKEY_TYPE
        val plaintext = try {
            val body = message.body.unb64()
            val cipher = SessionCipher(store, address(peerId))
            if (preKey) {
                val parsed = PreKeySignalMessage(body)
                val identityKey = parsed.identityKey.serialize().b64()
                assertTrusted(peerId, identityKey)
                // A PreKey message from a newer epoch starts a new session.
                if (meta.sessionEpoch != null && meta.sessionEpoch != message.epoch) {
                    state = state.copy(sessions = state.sessions - peerId.toString())
                }
                val decrypted = cipher.decrypt(parsed)
                pinIdentity(peerId.toString(), identityKey)
                String(decrypted)
            } else {
                String(cipher.decrypt(SignalMessage(body)))
            }
        } catch (e: IdentityChangedException) {
            throw e
        } catch (e: Exception) {
            discard()
            null
        }

        val cursor = meta.cursor
            ?.takeIf { it.epoch == message.epoch }
            ?.let { it.copy(lastSeq = maxOf(it.lastSeq, message.seq)) }
            ?: Cursor(message.epoch, message.seq)
        val row = HistoryRow(message.id, peerId, message.epoch, message.seq, Direction.In, plaintext, now())
        state = state.copy(history = state.history + (row.id to row))
        val updated = peer(peerId).copy(cursor = cursor)
        putPeer(peerId, if (preKey && plaintext != null) updated.copy(sessionEpoch = message.epoch) else updated)
        plaintext
    }

    // -- History and outbox -------------------------------------------------------

    /** Local history with [peerId], oldest first. */
    fun history(peerId: Long, state: SignalState = _changes.value): List<HistoryRow> =
        state.history.values
            .filter { it.peerId == peerId }
            .sortedWith(compareBy<HistoryRow> { it.ts }.thenBy { it.seq })

    /** Pending messages for [peerId], oldest first. */
    fun pendingOutbox(peerId: Long, state: SignalState = _changes.value): List<OutboxRow> =
        state.outbox.values.filter { it.peerId == peerId }.sortedBy { it.ts }

    /** Moves an acknowledged outbox row into the history; idempotent. */
    suspend fun acknowledge(nonce: String, ack: Ack): HistoryRow? = transaction {
        val row = state.outbox[nonce] ?: return@transaction state.history[ack.id]
        val entry = HistoryRow(ack.id, row.peerId, ack.epoch, ack.seq, Direction.Out, row.plaintext, row.ts)
        state = state.copy(history = state.history + (entry.id to entry), outbox = state.outbox - nonce)
        entry
    }

    private suspend fun dropOutboxEntry(nonce: String) = transaction {
        state = state.copy(outbox = state.outbox - nonce)
    }

    /** Deletes the local history of the conversation with [peerId]. */
    suspend fun clearHistory(peerId: Long) = transaction {
        state = state.copy(history = state.history.filterValues { it.peerId != peerId })
    }

    // -- Sending ------------------------------------------------------------------

    suspend fun openConversation(chat: ServerCall): String {
        val reply = chat.call("open_conversation", JsonObject(emptyMap()))
        return reply.string("epoch") ?: throw ServerException(reply.string("error") ?: "no_epoch")
    }

    /** Encrypts, queues and delivers a message. */
    suspend fun send(chat: ServerCall, peerId: Long, plaintext: String): HistoryRow? {
        val epoch = openConversation(chat)
        val row = checkNotNull(encryptMessage(chat, peerId, plaintext, epoch))
        return deliver(chat, row)
    }

    private suspend fun deliver(chat: ServerCall, row: OutboxRow, attempt: Int = 0): HistoryRow? {
        val reply = chat.call(
            "send_message",
            buildJsonObject {
                put("type", row.type)
                put("body", row.body)
                put("client_nonce", row.nonce)
                put("epoch", row.epoch)
                put("identity_key", row.identityKey)
            },
        )
        reply.string("id")?.let { id ->
            return acknowledge(row.nonce, Ack(id, reply.getValue("seq").jsonPrimitive.long, reply.getValue("epoch").jsonPrimitive.content))
        }

        val error = reply.string("error")
        if (attempt + 1 >= MAX_SEND_ATTEMPTS) throw ServerException(error)

        return when (error) {
            "stale_epoch" -> reencrypt(chat, row, reply.string("epoch") ?: throw ServerException(error))
                ?.let { deliver(chat, it, attempt + 1) }
            "in_flight" -> {
                delay(250L shl attempt)
                deliver(chat, row, attempt + 1)
            }
            "superseded" -> if (row.identityKey != identityKey) {
                reencrypt(chat, row, row.epoch)?.let { deliver(chat, it, attempt + 1) }
            } else {
                transaction { state = state.copy(deviceState = DeviceState.Superseded) }
                throw DeviceNotReadyException(DeviceState.Superseded)
            }
            "invalid" -> {
                dropOutboxEntry(row.nonce)
                throw ServerException(error)
            }
            else -> throw ServerException(error)
        }
    }

    private suspend fun reencrypt(chat: ServerCall, row: OutboxRow, epoch: String): OutboxRow? =
        encryptMessage(chat, row.peerId, row.plaintext, epoch, replaceNonce = row.nonce, ts = row.ts, rebuild = true)

    /** Delivers every pending row for [peerId], re-encrypting stale ones. */
    suspend fun flushOutbox(chat: ServerCall, peerId: Long): List<HistoryRow?> {
        val rows = pendingOutbox(peerId)
        if (rows.isEmpty()) return emptyList()

        val epoch = openConversation(chat)
        val identityKey = identityKey
        return rows.mapNotNull { row ->
            val current = row.epoch == epoch && row.identityKey == identityKey
            val next = if (current) row else reencrypt(chat, row, epoch)
            next?.let { deliver(chat, it) }
        }
    }

    /** Fetches and decrypts the messages of [epoch] newer than the local cursor. */
    suspend fun catchUp(chat: ServerCall, peerId: Long, epoch: String) {
        val cursor = _changes.value.peers[peerId.toString()]?.cursor
        var after = if (cursor?.epoch == epoch) cursor.lastSeq else 0

        while (true) {
            val reply = chat.call(
                "fetch_messages",
                buildJsonObject {
                    put("epoch", epoch)
                    put("after_seq", after)
                },
            ).orThrow()

            for (element in reply["messages"]?.jsonArray.orEmpty()) {
                val message = ServerMessage.from(element.jsonObject)
                if (message.direction == Direction.In) {
                    try {
                        decryptMessage(peerId, message)
                    } catch (e: IdentityChangedException) {
                        throw e
                    } catch (_: Exception) {
                        // Left for the next catch-up.
                    }
                }
            }

            after = (reply["next_cursor"] as? JsonPrimitive)?.longOrNull ?: return
        }
    }

    /** Deletes every key and message of this session from the device. */
    suspend fun wipe() = mutex.withLock {
        storage.clear()
        committed = SignalState()
        holder.state = committed
        _changes.value = committed
    }

    companion object {
        const val DEVICE_ID = 1
        const val OPK_TARGET = 50
        const val OPK_LOW_WATERMARK = 20
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val SPK_ROTATION_MS = 7 * DAY_MS
        private const val DEFAULT_MAX_AGE_MS = 7 * DAY_MS
        private const val MAX_SEND_ATTEMPTS = 4
        private const val PQXDH_VERSION = 4
        const val FINGERPRINT_VERSION = 2
        const val FINGERPRINT_ITERATIONS = 5200

        internal val json = Json { ignoreUnknownKeys = true }

        private fun address(peerId: Long) = SignalProtocolAddress(peerId.toString(), DEVICE_ID)

        private fun publishedKey(keyId: Int, publicKey: ByteArray, signature: ByteArray?) = buildJsonObject {
            put("key_id", keyId)
            put("public_key", publicKey.b64())
            if (signature != null) put("signature", signature.b64())
        }

        private fun JsonObject.orThrow(): JsonObject {
            string("error")?.let { throw ServerException(it) }
            return this
        }
    }
}

data class Ack(val id: String, val seq: Long, val epoch: String)

/** A message as serialized by the server. */
data class ServerMessage(
    val id: String,
    val seq: Long,
    val epoch: String,
    val type: Int,
    val body: String,
    val direction: Direction,
    val clientNonce: String?,
) {
    companion object {
        fun from(json: JsonObject) = ServerMessage(
            id = json.getValue("id").jsonPrimitive.content,
            seq = json.getValue("seq").jsonPrimitive.long,
            epoch = json.getValue("epoch").jsonPrimitive.content,
            type = json.getValue("type").jsonPrimitive.int,
            body = json.getValue("body").jsonPrimitive.content,
            direction = if (json.string("direction") == "out") Direction.Out else Direction.In,
            clientNonce = json.string("client_nonce"),
        )
    }
}

internal fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bytes(key: String): ByteArray = getValue(key).jsonPrimitive.content.unb64()
