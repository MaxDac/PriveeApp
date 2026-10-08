package com.privee.app.ui

import com.privee.signal.Direction
import com.privee.signal.HistoryRow
import com.privee.signal.PeerMeta
import com.privee.signal.SignalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecentsTest {
    private fun row(id: String, peer: Long, dir: Direction, text: String?, ts: Long) =
        HistoryRow(id, peer, "e", ts, dir, text, ts)

    @Test
    fun `lists named peers by their latest message`() {
        val state = SignalState(
            peers = mapOf(
                "1" to PeerMeta(name = "alice-session"),
                "2" to PeerMeta(name = "bob-session"),
                "3" to PeerMeta(name = "carol-session"),
                "4" to PeerMeta(),
            ),
            history = mapOf(
                "a" to row("a", 1, Direction.In, "hi", 10),
                "b" to row("b", 1, Direction.Out, "hello", 30),
                "c" to row("c", 2, Direction.In, null, 20),
                "d" to row("d", 4, Direction.In, "unnamed", 40),
            ),
        )

        assertEquals(
            listOf(
                Recent("alice-session", "hello", 30, outgoing = true),
                Recent("bob-session", "…", 20),
                Recent("carol-session", null, 0),
            ),
            recents(state),
        )
    }
}
