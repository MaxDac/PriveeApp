package com.privee.app.push

import com.privee.app.PriveeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events. The server pushes no content (messages are
 * end-to-end encrypted and fetched over the socket), only a wake-up signal.
 */
class PriveePushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val container get() = (application as PriveeApplication).container

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        scope.launch { container.onPushEndpoint(endpoint.url) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (Notifications.shouldNotifyPush(
                signedIn = container.session.value != null,
                foreground = container.foreground.value,
                directListening = container.backgroundListening.enabled.value,
            )
        ) {
            Notifications.newMessage(this, from = null, serverUrl = null)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = Unit

    override fun onUnregistered(instance: String) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
