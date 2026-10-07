package com.privee.app.push

import android.app.Activity
import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/**
 * UnifiedPush registration. Push is optional: without a distributor app
 * (ntfy, NextPush, …) messages are only received while the app is open.
 */
object PushRegistration {
    fun register(activity: Activity) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
            if (success) UnifiedPush.register(activity)
        }
    }

    fun unregister(context: Context) {
        runCatching { UnifiedPush.unregister(context) }
    }
}
