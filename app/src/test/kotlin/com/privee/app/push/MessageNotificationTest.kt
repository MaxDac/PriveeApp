package com.privee.app.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privee.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Notifications are readable by notification listeners and on the lock screen: nothing but generic text. */
@RunWith(AndroidJUnit4::class)
class MessageNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sender = "amber-falcon-north-meadow"
    private val server = "https://chat.example.org"

    @Test
    fun `message notifications never name the sender`() {
        val notification = Notifications.messageNotification(app, sender, server)
        assertEquals(app.getString(R.string.notification_new_message), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(app.getString(R.string.notification_open_to_read), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = assertNotNullAndGet(notification.publicVersion)
        assertEquals(app.getString(R.string.notification_new_message), public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(app.getString(R.string.notification_open_to_read), public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        for (shown in listOf(notification, public)) {
            for (key in shown.extras.keySet()) {
                @Suppress("DEPRECATION")
                assertFalse(key, shown.extras.get(key).toString().contains(sender))
            }
            assertNull(shown.tickerText)
        }
    }

    @Test
    fun `every message shares one untagged notification`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.newMessage(app, sender, server)
        Notifications.newMessage(app, "quiet-harbor-lantern-evening", server)
        Notifications.newMessage(app, null, null)
        val posted = app.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(1, posted.size)
        assertEquals(Notifications.MESSAGE_ID, posted.single().id)
        assertNull(posted.single().tag)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
