package com.privee.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `builds app links tagged with the server`() {
        assertEquals("privee://share/abc?server=https%3A%2F%2Fchat.example.org", appLink(server, "abc"))
        assertEquals(Invite("abc", server), parseAppLink(appLink(server, "abc")))
        val subPath = "https://example.org/privee"
        assertEquals(Invite("abc", subPath), parseAppLink(appLink(subPath, "abc")))
        assertEquals(Invite("abc", "http://10.0.2.2:4000"), parseAppLink(appLink("http://10.0.2.2:4000", "abc")))
    }

    @Test
    fun `normalizes the server of app links`() {
        assertEquals(Invite("abc", server), parseAppLink("privee://share/abc?server=HTTPS%3A%2F%2FChat.Example.org%3A443%2F"))
        assertEquals(Invite("abc", server), parseAppLink("privee://share/abc/?server=chat.example.org"))
    }

    @Test
    fun `parses legacy app links without a server`() {
        assertEquals(Invite("abc", null), parseAppLink("privee://share/abc"))
        assertEquals(Invite("abc", null), parseAppLink("privee://share/abc?other=1"))
    }

    @Test
    fun `refuses malformed app links`() {
        assertNull(parseAppLink("privee://share/"))
        assertNull(parseAppLink("privee://share/a/b"))
        assertNull(parseAppLink("privee://other/abc"))
        assertNull(parseAppLink("https://share/abc"))
        assertNull(parseAppLink("privee://share/bad%20name"))
        assertNull(parseAppLink("privee://share/abc?server=ftp%3A%2F%2Fexample.org"))
        assertNull(parseAppLink("privee://share/abc?server=https%3A%2F%2Fa.org&server=https%3A%2F%2Fb.org"))
        assertNull(parseAppLink("not a link"))
    }

    @Test
    fun `opens invites directly only on their server`() {
        assertTrue(Invite("abc", server).isFor(server))
        assertFalse(Invite("abc", "https://other.example.org").isFor(server))
        assertFalse(Invite("abc", null).isFor(server))
    }

    @Test
    fun `names both servers when an invite is for another one`() {
        val other = inviteMismatchMessage(Invite("abc", "https://other.example.org"), server)
        assertTrue("other.example.org" in other && "chat.example.org" in other && "abc" in other, other)
        val legacy = inviteMismatchMessage(Invite("abc", null), server)
        assertTrue("doesn't say which server" in legacy && "chat.example.org" in legacy, legacy)
    }

    @Test
    fun `accepts pasted app links of the selected server only`() {
        assertEquals(name, sessionNameFromInput(appLink(server, name), server))
        assertNull(sessionNameFromInput(appLink("https://other.example.org", name), server))
        assertNull(sessionNameFromInput("privee://share/$name", server))
    }
}
