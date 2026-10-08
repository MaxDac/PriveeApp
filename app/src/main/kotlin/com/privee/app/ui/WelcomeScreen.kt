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
import androidx.compose.ui.res.stringResource
import com.privee.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    serverLabel: String,
    serverName: String?,
    pendingPeer: String?,
    onRegister: () -> Unit,
    onLogIn: () -> Unit,
    onChangeServer: () -> Unit,
    onLanguage: () -> Unit,
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            pendingPeer?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.pending_invite, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(40.dp))
            Button(onClick = onRegister, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("go-register")) {
                Text(stringResource(R.string.create_session))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onLogIn, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("go-login")) {
                Text(stringResource(R.string.existing_session))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.server_label, serverLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("server-name"),
            )
            // Chosen by the server itself, so only a secondary hint next to the address.
            serverName?.let {
                Text(
                    stringResource(R.string.server_name, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(onClick = onChangeServer, modifier = Modifier.testTag("change-server")) {
                Text(stringResource(R.string.change_server))
            }
            TextButton(onClick = onLanguage, modifier = Modifier.testTag("language")) { Text(stringResource(R.string.language)) }
        }
    }
}
