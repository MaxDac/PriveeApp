package com.privee.app.data

import com.privee.signal.StateStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerStoreTest {
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
    fun `saves, loads and clears the selected server`() {
        val storage = MemoryStorage()
        val store = ServerStore(storage)
        assertNull(store.load())

        store.save(ServerConfig("https://chat.example.org", "My Privee"))
        assertEquals(ServerConfig("https://chat.example.org", "My Privee"), ServerStore(storage).load())

        store.save(ServerConfig("http://10.0.2.2:4000", null))
        assertEquals(ServerConfig("http://10.0.2.2:4000", null), store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `ignores unreadable state`() {
        assertNull(ServerStore(MemoryStorage("not json")).load())
        assertNull(ServerStore(MemoryStorage("{\"name\":\"x\"}")).load())
    }

    @Test
    fun `displays the name, or the host without one`() {
        assertEquals("My Privee", ServerConfig("https://chat.example.org", "My Privee").displayName)
        assertEquals("chat.example.org", ServerConfig("https://chat.example.org/privee", null).displayName)
    }

    @Test
    fun `gives each server its own directory`() {
        val a = ServerConfig("https://a.example.org", null).directoryName
        assertEquals(32, a.length)
        assertEquals(a, ServerConfig("https://a.example.org", "renamed").directoryName)
        assertNotEquals(a, ServerConfig("https://b.example.org", null).directoryName)
        assertNotEquals(a, ServerConfig("http://a.example.org", null).directoryName)
        assertNotEquals(a, ServerConfig("https://a.example.org:8443", null).directoryName)
    }
}
