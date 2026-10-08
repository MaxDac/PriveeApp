package com.privee.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privee.app.R
import com.privee.app.data.AppContainer
import com.privee.app.data.PriveeSession
import com.privee.app.push.BackgroundMessageService
import com.privee.app.push.Notifications

@Composable
fun BackgroundAlerts(container: AppContainer, session: PriveeSession) {
    val context = LocalContext.current
    val listening = container.backgroundListening
    val enabled by listening.enabled.collectAsStateWithLifecycle()
    val running by listening.running.collectAsStateWithLifecycle()
    val failed by listening.failed.collectAsStateWithLifecycle()
    val connected by session.connected.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(Notifications.listenerAllowed(context)) }
    var batteryExempt by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = Notifications.listenerAllowed(context)
        batteryExempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    fun enable() {
        error = null
        allowed = Notifications.listenerAllowed(context)
        if (!allowed) return
        try {
            listening.enable()
            BackgroundMessageService.start(context)
        } catch (e: IllegalStateException) {
            Log.e("BackgroundAlerts", "Could not save listening preference", e)
            error = R.string.listener_preference_failed
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = Notifications.listenerAllowed(context)
        if (granted) enable()
    }

    fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("BackgroundAlerts", "Settings activity unavailable", e)
            error = R.string.listener_settings_unavailable
        } catch (e: SecurityException) {
            Log.w("BackgroundAlerts", "Settings activity inaccessible", e)
            error = R.string.listener_settings_unavailable
        }
    }

    val status = when {
        !allowed -> R.string.listener_notifications_blocked
        failed -> R.string.listener_failed
        !enabled -> R.string.listener_off
        !running -> R.string.listener_paused
        connected -> R.string.listener_connected
        else -> R.string.listener_connecting
    }
    Card(Modifier.fillMaxWidth().testTag("background-alerts")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.background_alerts), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { dialog = true }, modifier = Modifier.testTag("background-alert-settings")) {
                Text(stringResource(R.string.listener_settings))
            }
        }
    }
    if (dialog) {
        PriveeAlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(stringResource(R.string.background_alerts)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.listener_description))
                    Text(stringResource(status), Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.listener_battery_guide), Modifier.padding(top = 12.dp))
                    if (batteryExempt) {
                        Text(stringResource(R.string.listener_battery_exempt), Modifier.padding(top = 12.dp))
                    }
                    Text(stringResource(R.string.listener_limits), Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.listener_delivery), Modifier.padding(top = 12.dp))
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text(stringResource(R.string.listener_notification_settings)) }
                    TextButton(onClick = {
                        openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text(stringResource(R.string.listener_battery_settings)) }
                    if (enabled) {
                        TextButton(onClick = {
                            try {
                                listening.disable()
                                BackgroundMessageService.stop(context)
                                error = null
                            } catch (e: IllegalStateException) {
                                Log.e("BackgroundAlerts", "Could not disable listening", e)
                                error = R.string.listener_preference_failed
                            }
                        }) { Text(stringResource(R.string.listener_stop)) }
                    }
                }
            },
            confirmButton = {
                if (!enabled || !running) {
                    TextButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enable()
                            }
                        },
                        modifier = Modifier.testTag("enable-background-alerts"),
                    ) {
                        Text(stringResource(if (enabled) R.string.listener_retry else R.string.listener_enable))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = false }) { Text(stringResource(R.string.listener_close)) }
            },
        )
    }
}
