package com.privee.app.ui

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.privee.app.R
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
data class Recent(
    val peerName: String,
    val preview: String?,
    val ts: Long,
    val outgoing: Boolean = false,
    val peerId: Long? = null,
    /** The user's local note about who the peer is. */
    val hint: String? = null,
)

fun recents(state: SignalState): List<Recent> {
    val last = state.history.values.groupBy { it.peerId }.mapValues { (_, rows) -> rows.maxBy { it.ts } }
    return state.peers.mapNotNull { (id, meta) ->
        val name = meta.name ?: return@mapNotNull null
        val peerId = id.toLongOrNull()
        val row = last[peerId]
        val preview = row?.let { it.plaintext ?: "…" }
        Recent(name, preview, row?.ts ?: 0, row?.direction == Direction.Out, peerId, meta.hint)
    }.sortedByDescending { it.ts }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AppContainer, session: PriveeSession, onLanguage: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.signal.changes.collectAsStateWithLifecycle()
    val connected by session.connected.collectAsStateWithLifecycle()
    val deviceState by session.deviceState.collectAsStateWithLifecycle()
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var peer by rememberSaveable { mutableStateOf("") }
    var peerError by rememberSaveable { mutableStateOf(false) }
    var editingHint by rememberSaveable { mutableStateOf<String?>(null) }
    val ownName = session.account.session.sessionName
    val shareText = stringResource(R.string.share_text, session.server.shareLink(ownName), session.server.appLink(ownName))
    val shareTitle = stringResource(R.string.share_session)

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                shareText,
            )
        }
        context.startActivity(Intent.createChooser(intent, shareTitle))
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

    val recents = recents(state)
    HomeContent(
        ownName = ownName,
        serverLabel = session.server.config.label,
        connected = connected,
        deviceState = deviceState,
        recents = recents,
        peer = peer,
        peerError = peerError,
        onPeerChange = {
            peer = it
            peerError = false
        },
        onOpen = ::open,
        onOpenChat = onOpenChat,
        onShare = ::share,
        onLanguage = onLanguage,
        onLogOut = { scope.launch { container.signOut() } },
        onForgetDevice = { confirmForget = true },
        onResetIdentity = { scope.launch { runCatching { session.resetIdentity() } } },
        alerts = { BackgroundAlerts(container, session) },
        onEditHint = { editingHint = it.peerName },
    )

    recents.firstOrNull { it.peerName == editingHint }?.let { recent ->
        val peerId = recent.peerId
        HintDialog(
            peerName = recent.peerName,
            current = recent.hint,
            onSave = { hint ->
                editingHint = null
                if (peerId != null) scope.launch { runCatching { session.signal.setPeerHint(peerId, hint) } }
            },
            onDismiss = { editingHint = null },
        )
    }

    if (confirmForget) {
        PriveeAlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.forget_device_title)) },
            text = {
                Text(stringResource(R.string.forget_device_description))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { container.forgetDevice() }
                }) { Text(stringResource(R.string.forget)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** [HomeScreen] without the session, so it renders from plain state (store screenshots). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    ownName: String,
    serverLabel: String,
    connected: Boolean,
    deviceState: DeviceState?,
    recents: List<Recent>,
    peer: String,
    peerError: Boolean,
    onPeerChange: (String) -> Unit,
    onOpen: () -> Unit,
    onOpenChat: (String) -> Unit,
    onShare: () -> Unit,
    onLanguage: () -> Unit,
    onLogOut: () -> Unit,
    onForgetDevice: () -> Unit,
    onResetIdentity: () -> Unit,
    alerts: @Composable () -> Unit,
    onEditHint: (Recent) -> Unit = {},
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onShare, modifier = Modifier.testTag("share")) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_session))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ProtectedWindow()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language)) },
                                onClick = { menu = false; onLanguage() },
                                modifier = Modifier.testTag("language"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_out)) },
                                onClick = {
                                    menu = false
                                    onLogOut()
                                },
                                modifier = Modifier.testTag("logout"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.forget_device)) },
                                onClick = {
                                    menu = false
                                    onForgetDevice()
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
            item { alerts() }
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
                                if (connected) stringResource(R.string.connected_server, serverLabel) else stringResource(R.string.connecting),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("server-status"),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.your_session), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
                    DeviceBanner(deviceState!!, onResetIdentity)
                }
            }

            item {
                Text(stringResource(R.string.start_conversation), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = peer,
                        onValueChange = onPeerChange,
                        placeholder = { Text(stringResource(R.string.peer_hint)) },
                        isError = peerError,
                        supportingText = if (peerError) {
                            { Text(stringResource(R.string.peer_invalid)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onOpen() }),
                        modifier = Modifier.weight(1f).testTag("peer-name"),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = onOpen, enabled = peer.isNotBlank(), modifier = Modifier.size(52.dp).testTag("open-chat")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.open_conversation))
                    }
                }
            }

            if (recents.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.conversations_device), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        stringResource(R.string.hint_long_press),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(recents, key = { it.peerName }) { recent ->
                RecentRow(recent, onClick = { onOpenChat(recent.peerName) }, onLongClick = { onEditHint(recent) })
            }
        }
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
    val text = stringResource(if (state == DeviceState.Superseded) R.string.device_superseded else R.string.device_needs_reset)
    var confirm by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().testTag("device-banner"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.End).testTag("reset-identity")) {
                Text(stringResource(R.string.reset_encryption), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (confirm) {
        PriveeAlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.reset_encryption_title)) },
            text = { Text(stringResource(R.string.reset_encryption_description)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReset()
                }, modifier = Modifier.testTag("confirm-reset")) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RecentRow(recent: Recent, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (recent.peerId != null) onLongClick else null,
                onLongClickLabel = stringResource(if (recent.hint == null) R.string.hint_add else R.string.hint_edit),
            )
            .padding(16.dp)
            .testTag("recent-${recent.peerName}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(recent.peerName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(recent.peerName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            recent.hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("recent-hint"),
                )
            }
            recent.preview?.let {
                Text(
                    if (recent.outgoing) stringResource(R.string.outgoing_preview, it) else it,
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
