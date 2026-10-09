package com.privee.app.data

import com.privee.net.SessionInfo
import com.privee.signal.StateStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnownSessionStoreTest {
    private class MemoryStorage(var value: String? = null) : StateStorage {
        override fun load() = value
        override fun save(state: String) {
            value = state
        }
        override fun clear() {
            value = null
        }
    }

    @Test
    fun `remembers the sessions signed into, across instances`() {
        val storage = MemoryStorage()
        val store = KnownSessionStore(storage)
        assertFalse(store.contains("blue-fox"))

        assertNull(store.record(SessionInfo(7, "blue-fox", false)))
        assertNull(store.record(SessionInfo(9, "red-owl", true)))
        assertNull(store.record(SessionInfo(7, "blue-fox", false)))

        val reloaded = KnownSessionStore(storage)
        assertEquals(mapOf("blue-fox" to 7L, "red-owl" to 9L), reloaded.load())
        assertTrue(reloaded.contains("blue-fox"))
        assertTrue(reloaded.contains(" red-owl "))
        assertFalse(reloaded.contains("green-elk"))
    }

    @Test
    fun `reports the id of a deleted session whose name was registered again`() {
        val store = KnownSessionStore(MemoryStorage())
        store.record(SessionInfo(7, "blue-fox", false))

        assertEquals(7L, store.record(SessionInfo(42, "blue-fox", false)))
        assertEquals(mapOf("blue-fox" to 42L), store.load())
        assertNull(store.record(SessionInfo(42, "blue-fox", false)))
    }

    @Test
    fun `forgets a session and clears the file when none is left`() {
        val storage = MemoryStorage()
        val store = KnownSessionStore(storage)
        store.record(SessionInfo(7, "blue-fox", false))
        store.record(SessionInfo(9, "red-owl", true))

        store.remove("blue-fox")
        assertEquals(mapOf("red-owl" to 9L), store.load())
        store.remove("unknown")
        store.remove("red-owl")
        assertNull(storage.value)
    }

    @Test
    fun `ignores unreadable state`() {
        assertEquals(emptyMap<String, Long>(), KnownSessionStore(MemoryStorage("not json")).load())
        assertEquals(mapOf("a" to 1L), KnownSessionStore(MemoryStorage("""{"a":1,"b":"x","c":[]}""")).load())
    }
}
