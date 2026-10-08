package com.privee.signal

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** In-memory stand-in of the Privee key and chat channels. */
private class FakeServer {
    class Bundle(
        var identityKey: String? = null,
        var registrationId: Int = 0,
        var signed: JsonObject? = null,
        var kyber: JsonObject? = null,
        val opks: ArrayDeque<JsonObject> = ArrayDeque(),
        var maxOpkId: Int = 0,
    )

    data class Message(val id: String, val from: Long, val to: Long, val seq: Long, val epoch: String, val type: Int, val body: String, val nonce: String)

    val bundles = mutableMapOf<Long, Bundle>()
    val messages = mutableListOf<Message>()
    var epoch = "epoch-1"

    fun keys(session: Long) = ServerCall { event, payload ->
        val bundle = bundles.getOrPut(session) { Bundle() }
        when (event) {
            "signal_status" -> buildJsonObject {
                bundle.identityKey?.let { put("identity_key", it) }
                put("opk_count", bundle.opks.size)
                put("max_opk_id", bundle.maxOpkId)
            }
            "publish_identity", "reset_identity" -> {
                bundle.identityKey = payload["identity_key"]!!.jsonPrimitive.content
                bundle.registrationId = payload["registration_id"]!!.jsonPrimitive.int
                bundle.opks.clear()
                bundle.signed = payload["signed_prekey"]!!.jsonObject
                bundle.kyber = payload["kyber_prekey"]!!.jsonObject
                addOpks(bundle, payload)
                ok()
            }
            "rotate_signed_prekey" -> {
                bundle.signed = payload["signed_prekey"]!!.jsonObject
                bundle.kyber = payload["kyber_prekey"]!!.jsonObject
                ok()
            }
            "add_prekeys" -> {
                addOpks(bundle, payload)
                ok()
            }
            else -> buildJsonObject { put("error", "unknown_event") }
        }
    }


    private fun addOpks(bundle: Bundle, payload: JsonObject) {
        for (key in payload["one_time_prekeys"]!!.jsonArray) {
            bundle.opks.addLast(key.jsonObject)
            bundle.maxOpkId = maxOf(bundle.maxOpkId, key.jsonObject["key_id"]!!.jsonPrimitive.int)
        }
    }

    fun chat(me: Long, peer: Long) = ServerCall { event, payload ->
        when (event) {
            "open_conversation" -> buildJsonObject { put("epoch", epoch) }
            "request_peer_bundle" -> {
                val bundle = bundles[peer]
                if (bundle?.identityKey == null) {
                    buildJsonObject { put("error", "no_keys") }
                } else {
                    buildJsonObject {
                        put(
                            "bundle",
                            buildJsonObject {
                                put("identity_key", bundle.identityKey)
                                put("registration_id", bundle.registrationId)
                                put("signed_prekey", bundle.signed!!)
                                put("kyber_prekey", bundle.kyber!!)
                                bundle.opks.removeFirstOrNull()?.let { put("one_time_prekey", it) }
                            },
                        )
                    }
                }
            }
            "send_message" -> {
                val sent = payload["epoch"]!!.jsonPrimitive.content
                if (sent != epoch) {
                    buildJsonObject {
                        put("error", "stale_epoch")
                        put("epoch", epoch)
                    }
                } else {
                    val seq = messages.count { it.epoch == epoch && setOf(it.from, it.to) == setOf(me, peer) } + 1L
                    val message = Message(
                        id = "m${messages.size + 1}",
                        from = me,
                        to = peer,
                        seq = seq,
                        epoch = epoch,
                        type = payload["type"]!!.jsonPrimitive.int,
                        body = payload["body"]!!.jsonPrimitive.content,
                        nonce = payload["client_nonce"]!!.jsonPrimitive.content,
                    )
                    messages += message
                    buildJsonObject {
                        put("id", message.id)
                        put("seq", message.seq)
                        put("epoch", message.epoch)
                        put("client_nonce", message.nonce)
                    }
                }
            }
            "fetch_messages" -> {
                val after = payload["after_seq"]!!.jsonPrimitive.long
                val wanted = payload["epoch"]!!.jsonPrimitive.content
                buildJsonObject {
                    put(
                        "messages",
                        buildJsonArray {
                            messages
                                .filter { it.epoch == wanted && it.seq > after && setOf(it.from, it.to) == setOf(me, peer) }
                                .forEach { add(serialize(it, me)) }
                        },
                    )
                    put("next_cursor", JsonPrimitive(null as String?))
                }
            }
            else -> buildJsonObject { put("error", "unknown_event") }
        }
    }

    private fun serialize(message: Message, me: Long) = buildJsonObject {
        put("id", message.id)
        put("seq", message.seq)
        put("epoch", message.epoch)
        put("type", message.type)
        put("body", message.body)
        put("direction", if (message.from == me) "out" else "in")
    }

    private fun ok() = buildJsonObject { put("ok", true) }
}

private class MemoryStorage : StateStorage {
    var value: String? = null

    override fun load() = value

    override fun save(state: String) {
        value = state
    }

    override fun clear() {
        value = null
    }
}

class SignalClientTest {
    private val server = FakeServer()
    private val alice = 1L
    private val bob = 2L

    private fun client(id: Long, storage: StateStorage = MemoryStorage()) =
        SignalClient(id, storage, server.keys(id))

    @Test
    fun `publishes keys and exchanges messages both ways`() = runTest {
        val a = client(alice)
        val b = client(bob)
        assertEquals(DeviceState.Ready, a.ensureKeys())
        assertEquals(DeviceState.Ready, b.ensureKeys())
        assertEquals(SignalClient.OPK_TARGET, server.bundles.getValue(bob).opks.size)

        val sent = a.send(server.chat(alice, bob), bob, "hello bob")
        assertNotNull(sent)
        assertTrue(a.pendingOutbox(bob).isEmpty())

        b.catchUp(server.chat(bob, alice), alice, server.epoch)
        assertEquals(listOf("hello bob"), b.history(alice).map { it.plaintext })

        b.send(server.chat(bob, alice), alice, "hi alice")
        a.catchUp(server.chat(alice, bob), bob, server.epoch)
        assertEquals(listOf("hello bob", "hi alice"), a.history(bob).map { it.plaintext })

        // Safety numbers match on both sides.
        val numberA = a.safetyNumber(bob)
        assertNotNull(numberA)
        assertEquals(numberA, b.safetyNumber(alice))
        assertEquals(60, numberA!!.length)
    }

    @Test
    fun `state survives a restart`() = runTest {
        val storage = MemoryStorage()
        client(alice, storage).ensureKeys()
        val b = client(bob)
        b.ensureKeys()

        client(alice, storage).send(server.chat(alice, bob), bob, "one")
        val restarted = client(alice, storage)
        assertEquals(DeviceState.Ready, restarted.ensureKeys())
        restarted.send(server.chat(alice, bob), bob, "two")

        b.catchUp(server.chat(bob, alice), alice, server.epoch)
        assertEquals(listOf("one", "two"), b.history(alice).map { it.plaintext })
    }

    @Test
    fun `peer hints are local, normalized and survive clearing the history`() = runTest {
        val storage = MemoryStorage()
        val a = client(alice, storage)
        a.ensureKeys()
        client(bob).ensureKeys()
        a.send(server.chat(alice, bob), bob, "hello")

        val messagesBefore = server.messages.size
        assertEquals("from the gym", a.setPeerHint(bob, "  from \n the   gym "))
        assertEquals("from the gym", a.changes.value.peers[bob.toString()]?.hint)
        assertEquals(messagesBefore, server.messages.size)

        a.clearHistory(bob)
        assertEquals("from the gym", client(alice, storage).changes.value.peers[bob.toString()]?.hint)

        assertNull(a.setPeerHint(bob, "   "))
        assertNull(a.changes.value.peers[bob.toString()]?.hint)
    }

    @Test
    fun `clearing peer hints keeps the rest of the peer metadata`() = runTest {
        val a = client(alice)
        a.ensureKeys()
        client(bob).ensureKeys()
        a.send(server.chat(alice, bob), bob, "hello")
        a.setPeerHint(bob, "neighbour")
        val before = a.changes.value.peers.getValue(bob.toString())

        a.clearPeerHints()

        assertEquals(before.copy(hint = null), a.changes.value.peers.getValue(bob.toString()))
        a.setPeerHint(bob, "neighbour")
        a.wipe()
        assertTrue(a.changes.value.peers.isEmpty())
    }

    @Test
    fun `normalizeHint limits the length by code points`() {
        assertNull(SignalClient.normalizeHint(null))
        assertNull(SignalClient.normalizeHint(" \t\n"))
        val long = "😀".repeat(SignalClient.MAX_HINT_LENGTH + 5)
        assertEquals("😀".repeat(SignalClient.MAX_HINT_LENGTH), SignalClient.normalizeHint(long))
    }

    @Test
    fun `state without hints still decodes`() {
        val state = SignalClient.json.decodeFromString(SignalState.serializer(), """{"peers":{"2":{"name":"bob"}}}""")
        assertEquals(PeerMeta(name = "bob"), state.peers["2"])
    }

    @Test
    fun `a new epoch rebuilds the session`() = runTest {
        val a = client(alice)
        val b = client(bob)
        a.ensureKeys()
        b.ensureKeys()
        a.send(server.chat(alice, bob), bob, "old")
        b.catchUp(server.chat(bob, alice), alice, server.epoch)

        server.epoch = "epoch-2"
        a.send(server.chat(alice, bob), bob, "new")
        b.catchUp(server.chat(bob, alice), alice, server.epoch)
        assertEquals(listOf("old", "new"), b.history(alice).map { it.plaintext })
    }

    @Test
    fun `a changed identity must be approved`() = runTest {
        val a = client(alice)
        val b = client(bob)
        a.ensureKeys()
        b.ensureKeys()
        a.send(server.chat(alice, bob), bob, "first")
        b.catchUp(server.chat(bob, alice), alice, server.epoch)

        // Bob resets its identity: Alice must approve the new key before reading.
        b.resetIdentity()
        b.send(server.chat(bob, alice), alice, "reset")
        assertThrows<IdentityChangedException> { a.catchUp(server.chat(alice, bob), bob, server.epoch) }
        assertTrue(a.hasPendingIdentity(bob))
        assertThrows<IdentityChangedException> { a.send(server.chat(alice, bob), bob, "blocked") }

        a.approveIdentity(bob)
        assertTrue(!a.hasPendingIdentity(bob))
        a.catchUp(server.chat(alice, bob), bob, server.epoch)
        assertEquals(listOf("first", "reset"), a.history(bob).map { it.plaintext })

        a.send(server.chat(alice, bob), bob, "second")
        b.catchUp(server.chat(bob, alice), alice, server.epoch)
        assertEquals(listOf("first", "reset", "second"), b.history(alice).map { it.plaintext })
    }

    @Test
    fun `a fresh device of an existing session needs a reset`() = runTest {
        client(alice).ensureKeys()
        val other = client(alice)
        assertEquals(DeviceState.NeedsReset, other.ensureKeys())
        assertThrows<DeviceNotReadyException> {
            other.encryptMessage(server.chat(alice, bob), bob, "x", "epoch-1")
        }
        assertNull(other.identityKey)
    }

    @Test
    fun `a PreKey message replayed under a new epoch is rejected`() = runTest {
        val a = client(alice)
        val b = client(bob)
        a.ensureKeys()
        b.ensureKeys()
        // Without one-time prekeys, only the last-resort Kyber prekey guards against replays.
        server.bundles.getValue(bob).opks.clear()
        a.send(server.chat(alice, bob), bob, "hi")
        b.catchUp(server.chat(bob, alice), alice, server.epoch)
        assertEquals(listOf("hi"), b.history(alice).map { it.plaintext })

        val original = server.messages.single()
        server.messages += original.copy(id = "replay", seq = 1, epoch = "epoch-2")
        b.catchUp(server.chat(bob, alice), alice, "epoch-2")
        assertEquals(listOf("hi", null), b.history(alice).map { it.plaintext })

        // The original session survives the rejected replay.
        a.send(server.chat(alice, bob), bob, "again")
        b.catchUp(server.chat(bob, alice), alice, server.epoch)
        assertEquals(listOf("hi", null, "again"), b.history(alice).map { it.plaintext })
    }

    @Test
    fun `replenishes one-time prekeys below the watermark`() = runTest {
        val a = client(alice)
        a.ensureKeys()
        val bundle = server.bundles.getValue(alice)
        repeat(40) { bundle.opks.removeFirst() }
        a.ensureKeys()
        assertEquals(SignalClient.OPK_TARGET, bundle.opks.size)
        assertEquals(90, bundle.maxOpkId)
    }
}

