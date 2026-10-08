package com.privee.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.privee.app.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privee.app.BuildConfig
import com.privee.app.data.AppContainer
import com.privee.net.ServerCheckException
import com.privee.net.ServerProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun serverProblemResource(problem: ServerProblem): Int = when (problem) {
    ServerProblem.InvalidUrl -> R.string.server_invalid_url
    ServerProblem.CleartextNotAllowed -> R.string.server_https_required
    ServerProblem.Unreachable -> R.string.server_unreachable
    ServerProblem.NotPrivee -> R.string.server_not_privee
    ServerProblem.UnsupportedVersion -> R.string.server_unsupported_version
}

class ServerViewModel(private val container: AppContainer) : ViewModel() {
    var address by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<ServerCheckException?>(null)
        private set

    val canConnect: Boolean
        get() = !busy && address.isNotBlank()

    fun connect() {
        if (!canConnect) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val config = container.checkServer(address)
                withContext(Dispatchers.IO) { container.selectServer(config) }
            } catch (e: ServerCheckException) {
                error = e
            } catch (_: Exception) {
                error = ServerCheckException(ServerProblem.Unreachable)
            } finally {
                busy = false
            }
        }
    }
}

/** The first screen: the Privee server to use. Nothing else is reachable until one is selected. */
@Composable
fun ServerScreen(container: AppContainer, onSettings: () -> Unit) {
    val vm: ServerViewModel = viewModel { ServerViewModel(container) }
    ServerContent(
        address = vm.address,
        onAddressChange = { vm.address = it },
        busy = vm.busy,
        error = vm.error,
        canConnect = vm.canConnect,
        suggestion = BuildConfig.DEV_SERVER_SUGGESTION,
        onConnect = vm::connect,
        onSettings = onSettings,
    )
}

/** [ServerScreen] without its view model, so it renders from plain state (store screenshots). */
@Composable
fun ServerContent(
    address: String,
    onAddressChange: (String) -> Unit,
    busy: Boolean,
    error: ServerCheckException?,
    canConnect: Boolean,
    suggestion: String,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onSettings, modifier = Modifier.testTag("settings")) { Text(stringResource(R.string.settings)) }
            Text(stringResource(R.string.choose_server), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.choose_server_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text(stringResource(R.string.server_address_example)) },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
                modifier = Modifier.fillMaxWidth().testTag("server-address"),
            )
            if (suggestion.isNotEmpty()) {
                AssistChip(
                    onClick = { onAddressChange(suggestion) },
                    label = { Text(stringResource(R.string.use_server, suggestion)) },
                    modifier = Modifier.testTag("server-suggestion"),
                )
            }
            error?.let {
                Text(
                    if (it.problem == ServerProblem.UnsupportedVersion) {
                        stringResource(R.string.server_unsupported_version, it.apiVersion?.toString() ?: stringResource(R.string.unknown))
                    } else {
                        stringResource(serverProblemResource(it.problem))
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("server-error"),
                )
            }
            Button(
                onClick = onConnect,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("server-connect"),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}