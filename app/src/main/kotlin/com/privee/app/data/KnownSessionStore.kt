package com.privee.app.data

import com.privee.net.SessionInfo
import com.privee.signal.StateStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The sessions this device has signed into on one server, by session name,
 * with their server id. Kept, encrypted, after sign-out like the Signal state,
 * so a failed log in can tell that a session used here may no longer exist:
 * the server deletes inactive sessions, and their names can be registered again.
 */
class KnownSessionStore(private val storage: StateStorage) {
    @Synchronized
    fun load(): Map<String, Long> = runCatching {
        val json = Json.parseToJsonElement(storage.load() ?: return emptyMap()).jsonObject
        json.mapNotNull { (name, id) -> (id as? JsonPrimitive)?.longOrNull?.let { name to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun contains(sessionName: String): Boolean = sessionName.trim() in load()

    /**
     * Records a signed-in [session]. Returns the id the same name had before
     * when it differs: that session was deleted on the server, and the state
     * kept for it on the device is stale.
     */
    @Synchronized
    fun record(session: SessionInfo): Long? {
        val known = load()
        val previous = known[session.sessionName]
        if (previous == session.id) return null
        save(known + (session.sessionName to session.id))
        return previous
    }

    @Synchronized
    fun remove(sessionName: String) {
        val known = load()
        if (sessionName in known) save(known - sessionName)
    }

    private fun save(known: Map<String, Long>) {
        if (known.isEmpty()) return storage.clear()
        storage.save(JsonObject(known.mapValues { JsonPrimitive(it.value) }).toString())
    }
}
