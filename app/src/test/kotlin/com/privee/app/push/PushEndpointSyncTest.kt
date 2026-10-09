package com.privee.app.push

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class PushEndpointSyncTest {
    @Test
    fun `sends a new endpoint for the signed-in session`() = runBlocking {
        val sent = mutableListOf<String>()
        val sync = PushEndpointSync()

        sync.update("https://push.example/a") { sent += it }
        assertEquals(listOf("https://push.example/a"), sent)
    }

    @Test
    fun `sends the known endpoint again after a new sign-in`() = runBlocking {
        val sent = mutableListOf<String>()
        val sync = PushEndpointSync()
        sync.signedIn { sent += it }
        assertEquals(emptyList<String>(), sent)

        // The endpoint arrived while signed out: nothing to send it for yet.
        sync.update("https://push.example/a", null)
        sync.signedIn { sent += it }
        sync.signedIn { sent += it }
        assertEquals(listOf("https://push.example/a", "https://push.example/a"), sent)
    }

    @Test
    fun `forgets an unregistered endpoint and survives failures`() = runBlocking {
        val sent = mutableListOf<String>()
        val sync = PushEndpointSync()
        sync.update("https://push.example/a") { throw IOException("offline") }
        sync.signedIn { sent += it }
        assertEquals(listOf("https://push.example/a"), sent)

        sync.forget()
        sync.signedIn { sent += it }
        assertEquals(listOf("https://push.example/a"), sent)
    }
}
