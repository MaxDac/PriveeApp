package com.privee.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privee.app.data.AppContainer
import com.privee.app.data.ChatConversation
import com.privee.app.data.ChatItem
import com.privee.app.data.ChatNotice
import com.privee.app.data.PriveeSession
import com.privee.signal.DeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ChatViewModel(session: PriveeSession, peerName: String) : ViewModel() {
    val conversation = ChatConversation(session, peerName, kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO))

    var draft by mutableStateOf("")
    var safetyNumber by mutableStateOf<String?>(null)
        private set

    init {
        conversation.start()
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        viewModelScope.launch(Dispatchers.IO) {
            if (!conversation.send(text)) draft = text
        }
    }

    fun showSafetyNumber() {
        viewModelScope.launch(Dispatchers.IO) { safetyNumber = conversation.safetyNumber() ?: "Not available yet." }
    }

    fun hideSafetyNumber() {
        safetyNumber = null
    }

    fun approve() = viewModelScope.launch(Dispatchers.IO) { conversation.approveIdentity() }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { conversation.clearHistory() }

    override fun onCleared() {
        conversation.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, session: PriveeSession, peerName: String, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat:$peerName") { ChatViewModel(session, peerName) }
    val c = vm.conversation
    val items by c.items.collectAsStateWithLifecycle()
    val notFound by c.notFound.collectAsStateWithLifecycle()
    val notice by c.notice.collectAsStateWithLifecycle()
    val identityChanged by c.identityChanged.collectAsStateWithLifecycle()
    val peerId by c.peerId.collectAsStateWithLifecycle()
    val deviceState by session.deviceState.collectAsStateWithLifecycle()
    val connected by session.connected.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DisposableEffect(peerName) {
        container.activeChat.value = peerName
        onDispose { if (container.activeChat.value == peerName) container.activeChat.value = null }
    }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    val canSend = deviceState == DeviceState.Ready && !identityChanged && peerId != null && !notFound

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(peerName, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(peerName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ConnectionDot(connected)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (connected) "End-to-end encrypted" else "Connecting…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chat-menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Safety number") },
                                onClick = {
                                    menu = false
                                    vm.showSafetyNumber()
                                },
                                enabled = peerId != null,
                            )
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                },
                                enabled = peerId != null,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (notFound) {
                Banner("There is no session named “$peerName”. Check the name or link and try again.")
            }
            if (deviceState == DeviceState.NeedsReset || deviceState == DeviceState.Superseded) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    DeviceBanner(deviceState!!) { vm.viewModelScope.launch(Dispatchers.IO) { runCatching { session.resetIdentity() } } }
                }
            }
            if (identityChanged) {
                Banner(
                    "$peerName's safety number has changed. They may have reset encryption or logged in on a new device. Verify it with them before continuing.",
                    tag = "identity-banner",
                ) {
                    TextButton(onClick = vm::showSafetyNumber) { Text("View safety number") }
                    TextButton(onClick = { vm.approve() }, modifier = Modifier.testTag("approve-identity")) { Text("Approve") }
                }
            }
            notice?.let {
                val text = when (it) {
                    ChatNotice.NoPeerKeys -> "$peerName has no encryption keys yet. Your message will be delivered once they open Privee."
                    ChatNotice.SendFailed -> "The message could not be sent. It will be retried automatically."
                    ChatNotice.SyncFailed -> "Could not fetch new messages. Retrying when the connection is back."
                }
                Banner(text, tag = "notice") { TextButton(onClick = c::dismissNotice) { Text("Dismiss") } }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("messages"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (items.isEmpty() && !notFound) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "Messages are end-to-end encrypted and stored only on your devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(items, key = { it.key }) { Bubble(it) }
            }

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = vm.draft,
                    onValueChange = { vm.draft = it },
                    placeholder = { Text("Message") },
                    enabled = canSend,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).testTag("composer"),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = vm::send,
                    enabled = canSend && vm.draft.isNotBlank(),
                    modifier = Modifier.size(52.dp).testTag("send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    vm.safetyNumber?.let { number ->
        AlertDialog(
            onDismissRequest = vm::hideSafetyNumber,
            title = { Text("Safety number") },
            text = {
                Column {
                    Text(
                        "Compare these numbers with $peerName in person or over another channel. If they match, your conversation is secure.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(number.chunked(5).chunked(4).joinToString("\n") { it.joinToString(" ") }, style = SafetyNumberStyle)
                }
            },
            confirmButton = { TextButton(onClick = vm::hideSafetyNumber) { Text("Close") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Delete all messages of this conversation from this device?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearHistory()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Banner(text: String, tag: String = "banner", actions: @Composable () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(Modifier.align(Alignment.End)) { actions() }
        }
    }
}

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

@Composable
private fun Bubble(item: ChatItem) {
    val outgoing = item.outgoing
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (outgoing) 18.dp else 4.dp,
                        bottomEnd = if (outgoing) 4.dp else 18.dp,
                    ),
                )
                .background(if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            val color = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            if (item.text != null) {
                Text(item.text, color = color, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("This message could not be decrypted.", color = color, fontStyle = FontStyle.Italic)
            }
            Text(
                (if (item.pending) "Sending… " else "") + timeFormat.format(Date(item.ts)),
                color = color.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
