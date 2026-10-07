package com.privee.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    serverName: String,
    pendingPeer: String?,
    onRegister: () -> Unit,
    onLogIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(20.dp).size(40.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Privee", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Anonymous, end-to-end encrypted conversations. No phone number, no email.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            pendingPeer?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Create or open a session to talk to $it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(40.dp))
            Button(onClick = onRegister, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("go-register")) {
                Text("Create a session")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onLogIn, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("go-login")) {
                Text("I already have a session")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Server: $serverName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("server-name"),
            )
            TextButton(onClick = onChangeServer, modifier = Modifier.testTag("change-server")) {
                Text("Change server")
            }
        }
    }
}
