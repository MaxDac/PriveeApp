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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privee.app.push.PushRegistration
import com.privee.app.ui.AuthMode
import com.privee.app.ui.AuthScreen
import com.privee.app.ui.ChatScreen
import com.privee.app.ui.HomeScreen
import com.privee.app.ui.PriveeTheme
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
                val session by container.session.collectAsStateWithLifecycle()
                val current = session

                if (current == null) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "welcome") {
                        composable("welcome") {
                            WelcomeScreen(
                                pendingPeer = pendingPeer,
                                onRegister = { nav.navigate("register") },
                                onLogIn = { nav.navigate("login") },
                            )
                        }
                        composable("register") { AuthScreen(container, AuthMode.Register, onBack = nav::popBackStack) }
                        composable("login") { AuthScreen(container, AuthMode.LogIn, onBack = nav::popBackStack) }
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
        private val sessionName = Regex("^[a-zA-Z0-9-]{1,72}$")

        /** The session name of `https://privee.fly.dev/share/<name>` or `privee://share/<name>`. */
        fun peerFrom(intent: Intent?): String? {
            val uri: Uri = intent?.data ?: return null
            val segments = uri.pathSegments
            val name = when (uri.scheme) {
                "privee" -> if (uri.host == "share") segments.firstOrNull() else null
                "https" -> if (segments.size >= 2 && segments[0] == "share") segments[1] else null
                else -> null
            }
            return name?.takeIf(sessionName::matches)
        }
    }
}
