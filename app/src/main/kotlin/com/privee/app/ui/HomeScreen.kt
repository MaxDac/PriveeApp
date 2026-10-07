package com.privee.app.ui

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privee.app.data.AppContainer
import com.privee.app.data.PriveeSession
import com.privee.app.data.sessionNameFromInput
import com.privee.signal.DeviceState
import com.privee.signal.Direction
import com.privee.signal.SignalState
import kotlinx.coroutines.launch

/** A conversation of the local history, for the home screen. */
data class Recent(val peerName: String, val preview: String?, val ts: Long)

fun recents(state: SignalState): List<Recent> {
    val last = state.history.values.groupBy { it.peerId }.mapValues { (_, rows) -> rows.maxBy { it.ts } }
    return state.peers.mapNotNull { (id, meta) ->
        val name = meta.name ?: return@mapNotNull null
        val row = last[id.toLongOrNull()]
        val preview = row?.let { (if (it.direction == Direction.Out) "You: " else "") + (it.plaintext ?: "…") }
        Recent(name, preview, row?.ts ?: 0)
    }.sortedByDescending { it.ts }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, session: PriveeSession, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.signal.changes.collectAsStateWithLifecycle()
    val connected by session.connected.collectAsStateWithLifecycle()
    val deviceState by session.deviceState.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf("") }
    var peerError by remember { mutableStateOf(false) }
    val ownName = session.account.session.sessionName

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Talk to me privately on Privee: ${session.server.shareLink(ownName)}")
        }
        context.startActivity(Intent.createChooser(intent, "Share your session"))
    }

    fun open() {
        val name = sessionNameFromInput(peer, session.server.config.url)
        if (name == null) {
            peerError = true
        } else if (name != ownName) {
            peer = ""
            peerError = false
            onOpenChat(name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privee") },
                actions = {
                    IconButton(onClick = ::share, modifier = Modifier.testTag("share")) {
                        Icon(Icons.Filled.Share, contentDescription = "Share your session")
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                onClick = {
                                    menu = false
                                    scope.launch { container.signOut() }
                                },
                                modifier = Modifier.testTag("logout"),
                            )
                            DropdownMenuItem(
                                text = { Text("Forget this device") },
                                onClick = {
                                    menu = false
                                    confirmForget = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionDot(connected)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (connected) "Connected to ${session.server.config.displayName}" else "Connecting…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("server-status"),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Your session", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            ownName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testTag("own-session-name"),
                        )
                    }
                }
            }

            if (deviceState == DeviceState.NeedsReset || deviceState == DeviceState.Superseded) {
                item {
                    DeviceBanner(deviceState!!) { scope.launch { runCatching { session.resetIdentity() } } }
                }
            }

            item {
                Text("Start a conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = peer,
                        onValueChange = {
                            peer = it
                            peerError = false
                        },
                        placeholder = { Text("Session name or share link") },
                        isError = peerError,
                        supportingText = if (peerError) {
                            { Text("Not a session name or a share link of this server.") }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { open() }),
                        modifier = Modifier.weight(1f).testTag("peer-name"),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = ::open, enabled = peer.isNotBlank(), modifier = Modifier.size(52.dp).testTag("open-chat")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open conversation")
                    }
                }
            }

            val list = recents(state)
            if (list.isNotEmpty()) {
                item {
                    Text("Conversations on this device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(list, key = { it.peerName }) { recent ->
                RecentRow(recent) { onOpenChat(recent.peerName) }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this device?") },
            text = {
                Text("Delete all encryption keys and history of this session from this device? You will need to reset encryption to chat again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { container.forgetDevice() }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ConnectionDot(connected: Boolean) {
    val color by animateColorAsState(
        if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
        label = "connection",
    )
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun DeviceBanner(state: DeviceState, onReset: () -> Unit) {
    val text = when (state) {
        DeviceState.Superseded ->
            "Encryption for this session was reset on another device, so this device can no longer send or receive messages."
        else ->
            "This device has no encryption keys for this session. Reset the encryption identity to continue; messages sent to your previous device will not be readable here."
    }
    var confirm by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("device-banner"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.End).testTag("reset-identity")) {
                Text("Reset encryption", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset encryption?") },
            text = { Text("Your contacts will be asked to verify your new safety number.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReset()
                }, modifier = Modifier.testTag("confirm-reset")) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecentRow(recent: Recent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(recent.peerName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(recent.peerName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            recent.preview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun Avatar(name: String, size: Int = 44) {
    val palette = listOf(0xFF7C5CFF, 0xFF00897B, 0xFFE07A5F, 0xFF3D85C6, 0xFFB565A7, 0xFF6A994E)
    val color = androidx.compose.ui.graphics.Color(palette[Math.floorMod(name.hashCode(), palette.size)])
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
