package com.privee.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ShareLinksTest {
    private val server = "https://chat.example.org"
    private val name = "alice-session-0123456789abcdef"

    @Test
    fun `builds the share link on the server`() {
        assertEquals("https://chat.example.org/share/abc", shareLink(server, "abc"))
        assertEquals("https://example.org/privee/share/abc", shareLink("https://example.org/privee", "abc"))
    }

    @Test
    fun `accepts a bare session name`() {
        assertEquals(name, sessionNameFromInput("  $name ", server))
        assertNull(sessionNameFromInput("", server))
        assertNull(sessionNameFromInput("not a name", server))
    }

    @Test
    fun `accepts share links of the selected server`() {
        assertEquals(name, sessionNameFromInput("https://chat.example.org/share/$name", server))
        assertEquals(name, sessionNameFromInput("https://chat.example.org/share/$name/", server))
        assertEquals(name, sessionNameFromInput("https://CHAT.example.org:443/share/$name", server))
        assertEquals(
            name,
            sessionNameFromInput("https://example.org/privee/share/$name", "https://example.org/privee"),
        )
    }

    @Test
    fun `refuses share links of other servers`() {
        assertNull(sessionNameFromInput("https://privee.fly.dev/share/$name", server))
        assertNull(sessionNameFromInput("http://chat.example.org/share/$name", server))
        assertNull(sessionNameFromInput("https://chat.example.org:8443/share/$name", server))
        assertNull(sessionNameFromInput("https://example.org/share/$name", "https://example.org/privee"))
        assertNull(sessionNameFromInput("https://example.org/other/share/$name", "https://example.org/privee"))
    }

    @Test
    fun `refuses malformed share links`() {
        assertNull(sessionNameFromInput("https://chat.example.org/share/", server))
        assertNull(sessionNameFromInput("https://chat.example.org/share/a/b", server))
        assertNull(sessionNameFromInput("https://chat.example.org/users/$name", server))
        assertNull(sessionNameFromInput("https://chat.example.org/share/bad%20name", server))
    }
}
