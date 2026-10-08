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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.privee.app.R
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
    var safetyNumberVisible by mutableStateOf(false)
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
        viewModelScope.launch(Dispatchers.IO) {
            safetyNumber = conversation.safetyNumber()
            safetyNumberVisible = true
        }
    }

    fun hideSafetyNumber() {
        safetyNumber = null
        safetyNumberVisible = false
    }

    fun approve() = viewModelScope.launch(Dispatchers.IO) { conversation.approveIdentity() }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { conversation.clearHistory() }

    override fun onCleared() {
        conversation.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, session: PriveeSession, peerName: String, onSettings: () -> Unit, onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat:$peerName") { ChatViewModel(session, peerName) }
    val c = vm.conversation
    val items by c.items.collectAsStateWithLifecycle()
    val notFound by c.notFound.collectAsStateWithLifecycle()
    val notice by c.notice.collectAsStateWithLifecycle()
    val identityChanged by c.identityChanged.collectAsStateWithLifecycle()
    val peerId by c.peerId.collectAsStateWithLifecycle()
    val deviceState by session.deviceState.collectAsStateWithLifecycle()
    val connected by session.connected.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var editHint by rememberSaveable { mutableStateOf(false) }
    val signalState by session.signal.changes.collectAsStateWithLifecycle()
    val hint = peerId?.let { signalState.peers[it.toString()]?.hint }

    DisposableEffect(peerName) {
        container.activeChat.value = peerName
        onDispose { if (container.activeChat.value == peerName) container.activeChat.value = null }
    }
    ChatContent(
        peerName = peerName,
        items = items,
        notFound = notFound,
        notice = notice,
        identityChanged = identityChanged,
        hasPeer = peerId != null,
        deviceState = deviceState,
        connected = connected,
        draft = vm.draft,
        onDraftChange = { vm.draft = it },
        onSend = vm::send,
        onBack = onBack,
        onSettings = onSettings,
        onShowSafetyNumber = vm::showSafetyNumber,
        onClearHistory = { confirmClear = true },
        onApprove = { vm.approve() },
        onDismissNotice = c::dismissNotice,
        onResetIdentity = { vm.viewModelScope.launch(Dispatchers.IO) { runCatching { session.resetIdentity() } } },
        hint = hint,
        onEditHint = { editHint = true },
    )

    val hintPeer = peerId
    if (editHint && hintPeer != null) {
        HintDialog(
            peerName = peerName,
            current = hint,
            onSave = { value ->
                editHint = false
                vm.viewModelScope.launch(Dispatchers.IO) { runCatching { session.signal.setPeerHint(hintPeer, value) } }
            },
            onDismiss = { editHint = false },
        )
    }

    if (vm.safetyNumberVisible) SafetyNumberDialog(peerName, vm.safetyNumber, vm::hideSafetyNumber)

    if (confirmClear) {
        PriveeAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_description)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearHistory()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** [ChatScreen] without the conversation, so it renders from plain state (store screenshots). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    peerName: String,
    items: List<ChatItem>,
    notFound: Boolean,
    notice: ChatNotice?,
    identityChanged: Boolean,
    hasPeer: Boolean,
    deviceState: DeviceState?,
    connected: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onShowSafetyNumber: () -> Unit,
    onClearHistory: () -> Unit,
    onApprove: () -> Unit,
    onDismissNotice: () -> Unit,
    onResetIdentity: () -> Unit,
    hint: String? = null,
    onEditHint: () -> Unit = {},
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    val canSend = deviceState == DeviceState.Ready && !identityChanged && hasPeer && !notFound
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(peerName, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(peerName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            hint?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("chat-hint"),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ConnectionDot(connected)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(if (connected) R.string.encrypted else R.string.connecting),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chat-menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ProtectedWindow()
                            DropdownMenuItem(
                                text = { Text(stringResource(if (hint == null) R.string.hint_add else R.string.hint_edit)) },
                                onClick = {
                                    menu = false
                                    onEditHint()
                                },
                                enabled = hasPeer,
                                modifier = Modifier.testTag("edit-hint"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.safety_number)) },
                                onClick = {
                                    menu = false
                                    onShowSafetyNumber()
                                },
                                enabled = hasPeer,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_history)) },
                                onClick = {
                                    menu = false
                                    onClearHistory()
                                },
                                enabled = hasPeer,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = { menu = false; onSettings() },
                                modifier = Modifier.testTag("settings"),
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
                Banner(stringResource(R.string.peer_not_found, peerName))
            }
            if (deviceState == DeviceState.NeedsReset || deviceState == DeviceState.Superseded) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    DeviceBanner(deviceState!!, onResetIdentity)
                }
            }
            if (identityChanged) {
                Banner(
                    stringResource(R.string.identity_changed, peerName),
                    tag = "identity-banner",
                ) {
                    TextButton(onClick = onShowSafetyNumber) { Text(stringResource(R.string.view_safety_number)) }
                    TextButton(onClick = onApprove, modifier = Modifier.testTag("approve-identity")) { Text(stringResource(R.string.approve)) }
                }
            }
            notice?.let {
                val text = when (it) {
                    ChatNotice.NoPeerKeys -> stringResource(R.string.no_peer_keys, peerName)
                    ChatNotice.SendFailed -> stringResource(R.string.send_failed)
                    ChatNotice.SyncFailed -> stringResource(R.string.sync_failed)
                }
                Banner(text, tag = "notice") { TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.dismiss)) } }
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
                                stringResource(R.string.messages_empty),
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
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(R.string.message)) },
                    enabled = canSend,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).testTag("composer"),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend && draft.isNotBlank(),
                    modifier = Modifier.size(52.dp).testTag("send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
        }
    }
}

/** The safety number of the conversation with [peerName]; `null` while it cannot be computed. */
@Composable
fun SafetyNumberDialog(peerName: String, number: String?, onDismiss: () -> Unit) {
    PriveeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.safety_number)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.safety_number_description, peerName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(16.dp))
                if (number == null) {
                    Text(stringResource(R.string.safety_number_unavailable))
                } else {
                    Text(number.chunked(5).chunked(4).joinToString("\n") { it.joinToString(" ") }, style = SafetyNumberStyle)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
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

@Composable
private fun Bubble(item: ChatItem) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
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
                Text(stringResource(R.string.decryption_failed), color = color, fontStyle = FontStyle.Italic)
            }
            Text(
                timeFormat.format(Date(item.ts)).let { if (item.pending) stringResource(R.string.sending_time, it) else it },
                color = color.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
