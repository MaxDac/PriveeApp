package com.privee.signal

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

/** Holds the working copy of the state during an operation. */
internal class StateHolder(var state: SignalState)

/**
 * libsignal store over a [StateHolder]. Trust decisions are taken by
 * [SignalClient] before libsignal is involved, so every identity is trusted
 * here.
 */
internal class PriveeProtocolStore(private val holder: StateHolder) : SignalProtocolStore {
    private var state: SignalState
        get() = holder.state
        set(value) {
            holder.state = value
        }

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(checkNotNull(state.identity) { "No identity" }.unb64())

    override fun getLocalRegistrationId(): Int = state.registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val key = identityKey.serialize().b64()
        val previous = state.identities[address.name]
        state = state.copy(identities = state.identities + (address.name to key))
        return if (previous != null && previous != key) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        state.identities[address.name]?.let { IdentityKey(it.unb64()) }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        state.preKeys[preKeyId]?.let { PreKeyRecord(it.unb64()) } ?: throw InvalidKeyIdException("No prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        state = state.copy(preKeys = state.preKeys + (preKeyId to record.serialize().b64()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeyId in state.preKeys

    override fun removePreKey(preKeyId: Int) {
        state = state.copy(preKeys = state.preKeys - preKeyId)
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        state.signedPreKeys[signedPreKeyId]?.let { SignedPreKeyRecord(it.unb64()) }
            ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        state.signedPreKeys.values.map { SignedPreKeyRecord(it.unb64()) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        state = state.copy(signedPreKeys = state.signedPreKeys + (signedPreKeyId to record.serialize().b64()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeyId in state.signedPreKeys

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        state = state.copy(signedPreKeys = state.signedPreKeys - signedPreKeyId)
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        state.kyberPreKeys[kyberPreKeyId]?.let { KyberPreKeyRecord(it.unb64()) }
            ?: throw InvalidKeyIdException("No Kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        state.kyberPreKeys.values.map { KyberPreKeyRecord(it.unb64()) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        state = state.copy(kyberPreKeys = state.kyberPreKeys + (kyberPreKeyId to record.serialize().b64()))
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeyId in state.kyberPreKeys

    // Only last-resort Kyber prekeys are published: they stay until retired.
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = Unit

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        state.sessions[address.name]?.let { SessionRecord(it.unb64()) }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "No session for $it") }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        state = state.copy(sessions = state.sessions + (address.name to record.serialize().b64()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = address.name in state.sessions

    override fun deleteSession(address: SignalProtocolAddress) {
        state = state.copy(sessions = state.sessions - address.name)
    }

    override fun deleteAllSessions(name: String) {
        state = state.copy(sessions = state.sessions - name)
    }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        throw UnsupportedOperationException("Group messaging is not supported")

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null
}
