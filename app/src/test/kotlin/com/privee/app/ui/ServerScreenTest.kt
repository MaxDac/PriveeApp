package com.privee.app.ui

import com.privee.net.ServerCheckException
import com.privee.net.ServerProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerScreenTest {
    @Test
    fun `explains every server problem`() {
        val messages = ServerProblem.entries.map { serverProblemMessage(ServerCheckException(it)) }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() })
    }

    @Test
    fun `names the unsupported API version`() {
        val message = serverProblemMessage(ServerCheckException(ServerProblem.UnsupportedVersion, apiVersion = 2))
        assertTrue("API version 2" in message, message)
    }
}
