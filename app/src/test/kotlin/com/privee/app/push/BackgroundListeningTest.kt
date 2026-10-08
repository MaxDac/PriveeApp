package com.privee.app.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackgroundListeningTest {
    @Test
    fun `start and sticky restart require opt-in account and notification availability`() {
        for (enabled in listOf(false, true)) {
            for (account in listOf(false, true)) {
                for (allowed in listOf(false, true)) {
                    val listening = BackgroundListening(enabled) { }
                    val actual = listening.canRun(account, allowed)
                    if (enabled && account && allowed) assertTrue(actual) else assertFalse(actual)
                }
            }
        }
        val listening = BackgroundListening(true) { }
        listening.disable()
        assertFalse(listening.canRun(true, true))
    }

    @Test
    fun `opt-in persists but running state never survives process death`() {
        var saved = false
        val listening = BackgroundListening(saved) { saved = it }
        assertFalse(listening.enabled.value)
        assertFalse(listening.running.value)
        listening.enable()
        assertTrue(saved)
        assertFalse(listening.running.value)
        listening.started()
        assertTrue(listening.running.value)

        val restored = BackgroundListening(saved) { saved = it }
        assertTrue(restored.enabled.value)
        assertFalse(restored.running.value)
        assertFalse(restored.failed.value)
    }

    @Test
    fun `stop clears persistent opt-in while service termination preserves it`() {
        var saved = false
        val listening = BackgroundListening(saved) { saved = it }
        listening.enable()
        listening.started()
        listening.stopped()
        assertTrue(saved)
        assertTrue(listening.enabled.value)
        assertFalse(listening.running.value)
        listening.disable()
        assertFalse(saved)
        assertFalse(listening.enabled.value)
    }

    @Test
    fun `failure is visible and successful restart clears it`() {
        val listening = BackgroundListening(true) { }
        listening.startFailed()
        listening.stopped()
        assertTrue(listening.enabled.value)
        assertTrue(listening.failed.value)
        assertFalse(listening.running.value)
        listening.started()
        assertTrue(listening.running.value)
        assertFalse(listening.failed.value)
    }

    @Test
    fun `failed persistence does not change requested state`() {
        val listening = BackgroundListening(false) { error("Disk unavailable") }
        assertThrows(IllegalStateException::class.java) { listening.enable() }
        assertFalse(listening.enabled.value)
        val enabled = BackgroundListening(true) { error("Disk unavailable") }
        assertThrows(IllegalStateException::class.java) { enabled.disable() }
        assertTrue(enabled.enabled.value)
    }
}
