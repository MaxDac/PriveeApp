package com.privee.app.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.privee.app.PriveeApplication
import com.privee.app.data.PriveeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BackgroundMessageService : Service() {
    private val container get() = (application as PriveeApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningSession: PriveeSession? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Notifications.createChannel(this)
        if (!Notifications.updateListener(this, container.session.value?.connected?.value == true)) {
            container.backgroundListening.startFailed()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        listeningSession = container.session.value
        if (intent?.action == STOP) {
            container.backgroundListening.disable()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!container.backgroundListening.enabled.value || container.session.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!container.backgroundListening.canRun(
                hasAccount = container.session.value != null,
                notificationsAllowed = Notifications.listenerAllowed(this),
            )
        ) {
            container.backgroundListening.startFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.LISTENER_ID,
                Notifications.listener(this, connected = container.session.value?.connected?.value == true),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: IllegalStateException) {
            fail(e)
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            fail(e)
            return START_NOT_STICKY
        }
        container.backgroundListening.started()
        if (!observing) {
            observing = true
            scope.launch {
                combine(container.backgroundListening.enabled, container.session) { enabled, session ->
                    session.takeIf { enabled }
                }.collectLatest { session ->
                    if (session == null) {
                        stopSelf()
                        return@collectLatest
                    }
                    listeningSession = session
                    session.socket.connect()
                    session.connected.collect { connected ->
                        if (!container.backgroundListening.enabled.value || container.session.value !== session) {
                            stopSelf()
                            return@collect
                        }
                        if (!Notifications.updateListener(this@BackgroundMessageService, connected)) {
                            container.backgroundListening.startFailed()
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun fail(error: RuntimeException) {
        Log.e(TAG, "Could not run background message listener", error)
        container.backgroundListening.startFailed()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        container.backgroundListening.stopped()
        if (!container.foreground.value && container.session.value === listeningSession) {
            listeningSession?.socket?.disconnect()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // A connection update can race the system removing foreground ownership during stopService.
        Notifications.cancelListener(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BackgroundMessages"
        private const val STOP = "com.privee.app.STOP_BACKGROUND_MESSAGES"

        fun stopIntent(context: Context) = Intent(context, BackgroundMessageService::class.java).setAction(STOP)

        /** Only called from a visible activity after explicit opt-in, or when that activity resumes. */
        fun start(context: Context): Boolean {
            val container = (context.applicationContext as PriveeApplication).container
            if (!container.backgroundListening.enabled.value || container.session.value == null) return false
            if (!container.backgroundListening.canRun(
                    hasAccount = container.session.value != null,
                    notificationsAllowed = Notifications.listenerAllowed(context),
                )
            ) {
                container.backgroundListening.startFailed()
                stop(context)
                return false
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, BackgroundMessageService::class.java))
                true
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Could not start background message listener", e)
                container.backgroundListening.startFailed()
                false
            } catch (e: SecurityException) {
                Log.e(TAG, "Could not start background message listener", e)
                container.backgroundListening.startFailed()
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundMessageService::class.java))
        }
    }
}
