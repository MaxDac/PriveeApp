package com.privee.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privee.app.data.isSessionName
import com.privee.app.push.PushRegistration
import com.privee.app.ui.AuthMode
import com.privee.app.ui.AuthScreen
import com.privee.app.ui.ChatScreen
import com.privee.app.ui.HomeScreen
import com.privee.app.ui.PriveeTheme
import com.privee.app.ui.ServerScreen
import com.privee.app.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    /** A conversation requested by a share link or a notification, opened once signed in. */
    private var pendingPeer by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingPeer = peerFrom(intent)
        val container = (application as PriveeApplication).container

        setContent {
            PriveeTheme {
                val server by container.server.collectAsStateWithLifecycle()
                val session by container.session.collectAsStateWithLifecycle()
                val selected = server
                val current = session

                if (selected == null) {
                    ServerScreen(container)
                } else if (current == null) {
                    key(selected) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = "welcome") {
                            composable("welcome") {
                                WelcomeScreen(
                                    serverName = selected.config.displayName,
                                    pendingPeer = pendingPeer,
                                    onRegister = { nav.navigate("register") },
                                    onLogIn = { nav.navigate("login") },
                                    onChangeServer = container::changeServer,
                                )
                            }
                            composable("register") {
                                AuthScreen(container, selected, AuthMode.Register, onBack = nav::popBackStack)
                            }
                            composable("login") {
                                AuthScreen(container, selected, AuthMode.LogIn, onBack = nav::popBackStack)
                            }
                        }
                    }
                } else {
                    LaunchedEffect(current) { onSignedIn() }
                    val nav = rememberNavController()
                    LaunchedEffect(current, pendingPeer) {
                        val peer = pendingPeer ?: return@LaunchedEffect
                        pendingPeer = null
                        if (peer != current.account.session.sessionName) {
                            nav.navigate("chat/$peer") { popUpTo("home") }
                        }
                    }
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(container, current, onOpenChat = { nav.navigate("chat/$it") })
                        }
                        composable("chat/{name}") { entry ->
                            val name = entry.arguments?.getString("name").orEmpty()
                            ChatScreen(container, current, name, onBack = nav::popBackStack)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        peerFrom(intent)?.let { pendingPeer = it }
    }

    private fun onSignedIn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PushRegistration.register(this)
    }

    companion object {
        /**
         * The session name of `privee://share/<name>`, opened on the selected server.
         * Share pages of any server link to it.
         */
        fun peerFrom(intent: Intent?): String? {
            val uri: Uri = intent?.data ?: return null
            if (uri.scheme != "privee" || uri.host != "share") return null
            return uri.pathSegments.singleOrNull()?.takeIf(::isSessionName)
        }
    }
}
