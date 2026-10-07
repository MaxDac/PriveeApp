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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/** What to tell the user when [e] prevents using a server. */
fun serverProblemMessage(e: ServerCheckException): String = when (e.problem) {
    ServerProblem.InvalidUrl -> "This is not a valid server address."
    ServerProblem.CleartextNotAllowed -> "The server must use HTTPS (an address starting with https://)."
    ServerProblem.Unreachable -> "Cannot reach the server. Check the address and your connection."
    ServerProblem.NotPrivee -> "This is not a Privee server."
    ServerProblem.UnsupportedVersion ->
        "This Privee server uses API version ${e.apiVersion ?: "unknown"}, which this app does not support. " +
            "Update the app or ask the server administrator."
}

class ServerViewModel(private val container: AppContainer) : ViewModel() {
    var address by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
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
                error = serverProblemMessage(e)
            } catch (_: Exception) {
                error = "Cannot reach the server. Check the address and your connection."
            } finally {
                busy = false
            }
        }
    }
}

/** The first screen: the Privee server to use. Nothing else is reachable until one is selected. */
@Composable
fun ServerScreen(container: AppContainer) {
    val vm: ServerViewModel = viewModel { ServerViewModel(container) }

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
            Text("Choose your server", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Privee has no central server: anyone can run one. Enter the address of the Privee server you " +
                    "want to use, for example your own deployment. Your session and keys belong to that server.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = vm.address,
                onValueChange = { vm.address = it },
                label = { Text("Server address") },
                placeholder = { Text("https://privee.example.org") },
                isError = vm.error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { vm.connect() }),
                modifier = Modifier.fillMaxWidth().testTag("server-address"),
            )
            if (BuildConfig.DEV_SERVER_SUGGESTION.isNotEmpty()) {
                AssistChip(
                    onClick = { vm.address = BuildConfig.DEV_SERVER_SUGGESTION },
                    label = { Text("Use ${BuildConfig.DEV_SERVER_SUGGESTION}") },
                    modifier = Modifier.testTag("server-suggestion"),
                )
            }
            vm.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("server-error"),
                )
            }
            Button(
                onClick = vm::connect,
                enabled = vm.canConnect,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("server-connect"),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("Connect")
                }
            }
        }
    }
}
