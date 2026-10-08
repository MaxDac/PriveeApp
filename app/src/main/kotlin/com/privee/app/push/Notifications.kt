package com.privee.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.privee.app.MainActivity
import com.privee.app.R
import com.privee.app.localizedContext
import com.privee.app.data.appLink

/** Message notifications: generic text only, never the message, the sender or a per-sender tag. */
object Notifications {
    private const val CHANNEL = "messages"
    private const val LISTENER_CHANNEL = "background-listening"
    const val MESSAGE_ID = 1
    const val LISTENER_ID = 2

    fun shouldNotifySocket(foreground: Boolean, activeChat: String?, from: String): Boolean =
        !foreground || activeChat != from

    fun shouldNotifyPush(signedIn: Boolean, foreground: Boolean, directListening: Boolean): Boolean =
        signedIn && !foreground && !directListening

    fun createChannel(context: Context) {
        val strings = context.localizedContext()
        val channel = NotificationChannel(
            CHANNEL,
            strings.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                LISTENER_CHANNEL,
                strings.getString(R.string.notification_channel_listener),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun messagesAllowed(context: Context): Boolean = permissionGranted(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
            ?.importance != NotificationManager.IMPORTANCE_NONE

    fun listenerAllowed(context: Context): Boolean = messagesAllowed(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(LISTENER_CHANNEL)
            ?.importance != NotificationManager.IMPORTANCE_NONE

    private fun permissionGranted(context: Context): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun listener(context: Context, connected: Boolean): Notification {
        val strings = context.localizedContext()
        val open = PendingIntent.getActivity(
            context, LISTENER_ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, LISTENER_ID, BackgroundMessageService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, LISTENER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(strings.getString(R.string.background_alerts))
            .setContentText(strings.getString(if (connected) R.string.listener_connected else R.string.listener_connecting))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, strings.getString(R.string.listener_stop), stop)
            .build()
    }

    fun updateListener(context: Context, connected: Boolean): Boolean {
        if (!listenerAllowed(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(LISTENER_ID, listener(context, connected))
            true
        } catch (e: SecurityException) {
            Log.w("Notifications", "Background notification permission was revoked", e)
            false
        }
    }

    fun cancelListener(context: Context) {
        NotificationManagerCompat.from(context).cancel(LISTENER_ID)
    }

    /**
     * A message notification. Other apps can read posted notifications (notification listeners)
     * and they show on the lock screen, so it is always the same generic text: no sender, no
     * message text, and a fixed id with no tag, which also hides how many people wrote. Tapping it
     * opens the chat of the latest sender; the PendingIntent is not readable by other apps.
     */
    fun messageNotification(context: Context, from: String?, serverUrl: String?): Notification {
        val strings = context.localizedContext()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Tagged with the server, so the chat doesn't open on another one selected meanwhile.
            if (from != null && serverUrl != null) data = appLink(serverUrl, from).toUri()
        }
        val pending = PendingIntent.getActivity(
            context,
            MESSAGE_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = strings.getString(R.string.notification_new_message)
        val text = strings.getString(R.string.notification_open_to_read)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }

    fun newMessage(context: Context, from: String?, serverUrl: String?) {
        createChannel(context)
        if (!messagesAllowed(context)) return
        try {
            NotificationManagerCompat.from(context).notify(MESSAGE_ID, messageNotification(context, from, serverUrl))
        } catch (e: SecurityException) {
            Log.w("Notifications", "Message notification permission was revoked", e)
        }
    }
}