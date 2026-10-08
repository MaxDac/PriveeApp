package com.privee.app.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationPolicyTest {
    @Test
    fun `socket alerts suppress only the visible conversation`() {
        assertFalse(Notifications.shouldNotifySocket(true, "alice", "alice"))
        assertTrue(Notifications.shouldNotifySocket(true, "bob", "alice"))
        assertTrue(Notifications.shouldNotifySocket(true, null, "alice"))
        assertTrue(Notifications.shouldNotifySocket(false, "alice", "alice"))
    }

    @Test
    fun `distributor alerts require a signed-in background account and no direct listener`() {
        for (signedIn in listOf(false, true)) {
            for (foreground in listOf(false, true)) {
                for (direct in listOf(false, true)) {
                    val actual = Notifications.shouldNotifyPush(signedIn, foreground, direct)
                    if (signedIn && !foreground && !direct) assertTrue(actual) else assertFalse(actual)
                }
            }
        }
    }
}
