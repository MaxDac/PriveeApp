package com.privee.app.ui

import com.privee.app.R
import com.privee.net.ServerProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerScreenTest {
    @Test
    fun `explains every server problem`() {
        val messages = ServerProblem.entries.map(::serverProblemResource)
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { it != 0 })
    }

    @Test
    fun `uses the parameterized resource for unsupported API versions`() {
        assertEquals(R.string.server_unsupported_version, serverProblemResource(ServerProblem.UnsupportedVersion))
    }
}
