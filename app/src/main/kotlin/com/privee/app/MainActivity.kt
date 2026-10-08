package com.privee.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privee.app.data.Invite
import com.privee.app.data.serverLabel
import com.privee.app.data.parseAppLink
import com.privee.app.push.PushRegistration
import com.privee.app.push.Notifications
import com.privee.app.ui.NoPersonalizedLearning
import com.privee.app.ui.PriveeAlertDialog
import com.privee.app.ui.protectWindow
import com.privee.app.push.BackgroundMessageService
import com.privee.app.ui.AuthMode
import com.privee.app.ui.AuthScreen
import com.privee.app.ui.ChatScreen
import com.privee.app.ui.HomeScreen
import com.privee.app.ui.PriveeTheme
import com.privee.app.ui.ServerScreen
import com.privee.app.ui.SettingsDialog
import com.privee.app.ui.WelcomeScreen

class MainActivity : AppCompatActivity() {
    private val container get() = (application as PriveeApplication).container
    private val languages get() = (application as PriveeApplication).languages
    private val themes get() = (application as PriveeApplication).themes
    private var notificationDenied by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        (newBase.applicationContext as PriveeApplication).languages.initialize()
        super.attachBaseContext(newBase)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationDenied = !granted
            if (granted && container.backgroundListening.enabled.value) BackgroundMessageService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        protectWindow(window)
        Notifications.createChannel(this)
        if (savedInstanceState == null) inviteFrom(intent)?.let { container.pendingInvite.value = it }
        val container = container

        setContent {
            val accent by themes.accent.collectAsStateWithLifecycle()
            PriveeTheme(accent = accent) { NoPersonalizedLearning {
                var settingsDialog by rememberSaveable { mutableStateOf(false) }
                val onSettings = { settingsDialog = true }
                if (settingsDialog) {
                    SettingsDialog(
                        language = languages.language,
                        accent = accent,
                        onAccent = themes::select,
                        onLanguage = {
                            // The new locale recreates the activity.
                            settingsDialog = false
                            languages.select(it)
                            Notifications.createChannel(this)
                            if (container.backgroundListening.running.value &&
                                !Notifications.updateListener(this, container.session.value?.connected?.value == true)
                            ) {
                                container.backgroundListening.startFailed()
                                BackgroundMessageService.stop(this)
                            }
                        },
                        onDismiss = { settingsDialog = false },
                    )
                }
                val server by container.server.collectAsStateWithLifecycle()
                val session by container.session.collectAsStateWithLifecycle()
                val invite by container.pendingInvite.collectAsStateWithLifecycle()
                val selected = server
                val current = session
                if (notificationDenied) {
                    PriveeAlertDialog(
                        onDismissRequest = { notificationDenied = false },
                        title = { Text(getString(R.string.background_alerts)) },
                        text = { Text(getString(R.string.listener_notifications_blocked)) },
                        confirmButton = {
                            TextButton(onClick = { notificationDenied = false }) {
                                Text(getString(R.string.listener_close))
                            }
                        },
                    )
                }

                if (selected == null) {
                    ServerScreen(container, onSettings)
                } else if (current == null) {
                    key(selected) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = "welcome") {
                            composable("welcome") {
                                WelcomeScreen(
                                    serverLabel = selected.config.label,
                                    serverName = selected.config.name,
                                    pendingPeer = invite?.name,
                                    onRegister = { nav.navigate("register") },
                                    onLogIn = { nav.navigate("login") },
                                    onChangeServer = container::changeServer,
                                    onSettings = onSettings,
                                )
                            }
                            composable("register") {
                                AuthScreen(container, selected, AuthMode.Register, onSettings, onBack = nav::popBackStack)
                            }
                            composable("login") {
                                AuthScreen(container, selected, AuthMode.LogIn, onSettings, onBack = nav::popBackStack)
                            }
                        }
                    }
                } else {
                    LaunchedEffect(current) { onSignedIn() }
                    val nav = rememberNavController()
                    // An invite for another server (or not saying which) waits for the user's confirmation.
                    var unconfirmedName by rememberSaveable { mutableStateOf<String?>(null) }
                    var unconfirmedServer by rememberSaveable { mutableStateOf<String?>(null) }
                    LaunchedEffect(current, invite) {
                        val requested = invite ?: return@LaunchedEffect
                        container.pendingInvite.value = null
                        if (requested.name == current.account.session.sessionName) return@LaunchedEffect
                        if (requested.isFor(current.server.config.url)) {
                            nav.navigate("chat/${requested.name}") { popUpTo("home") }
                        } else {
                            unconfirmedName = requested.name
                            unconfirmedServer = requested.server
                        }
                    }
                    unconfirmedName?.let { name ->
                        val requested = Invite(name, unconfirmedServer)
                        PriveeAlertDialog(
                            onDismissRequest = { unconfirmedName = null },
                            title = { Text(stringResource(R.string.different_server)) },
                            text = {
                                val selectedLabel = current.server.config.label
                                Text(
                                    requested.server?.let {
                                        stringResource(R.string.invite_other_server, requested.name, serverLabel(it), selectedLabel)
                                    } ?: stringResource(R.string.invite_unspecified_server, requested.name, selectedLabel),
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        unconfirmedName = null
                                        nav.navigate("chat/${requested.name}") { popUpTo("home") }
                                    },
                                    modifier = Modifier.testTag("invite-open-anyway"),
                                ) { Text(stringResource(R.string.open_on_server, current.server.config.label)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { unconfirmedName = null }) { Text(stringResource(R.string.cancel)) }
                            },
                        )
                    }
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(container, current, onSettings, onOpenChat = { nav.navigate("chat/$it") })
                        }
                        composable("chat/{name}") { entry ->
                            val name = entry.arguments?.getString("name").orEmpty()
                            ChatScreen(container, current, name, onSettings, onBack = nav::popBackStack)
                        }
                    }
                }
            } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inviteFrom(intent)?.let { container.pendingInvite.value = it }
    }

    override fun onResume() {
        super.onResume()
        if (container.backgroundListening.enabled.value) BackgroundMessageService.start(this)
    }

    private fun onSignedIn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PushRegistration.register(this)
        if (container.backgroundListening.enabled.value) BackgroundMessageService.start(this)
    }

    companion object {
        /** The conversation requested by `privee://share/<name>?server=<url>`, see [parseAppLink]. */
        fun inviteFrom(intent: Intent?): Invite? = intent?.dataString?.let(::parseAppLink)
    }
}
