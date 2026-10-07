package com.privee.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privee.app.data.ActiveServer
import com.privee.app.data.AppContainer
import com.privee.net.ApiException
import kotlinx.coroutines.launch

enum class AuthMode { Register, LogIn }

class AuthViewModel(
    private val container: AppContainer,
    private val server: ActiveServer,
    val mode: AuthMode,
) : ViewModel() {
    var sessionName by mutableStateOf("")
    var phrase by mutableStateOf("")
    var quick by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var fieldErrors by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    val canSubmit: Boolean
        get() = !busy && (mode == AuthMode.Register || sessionName.isNotBlank()) && (quick || phrase.isNotBlank())

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        fieldErrors = emptyMap()
        viewModelScope.launch {
            try {
                val result = when (mode) {
                    AuthMode.Register -> server.api.register(sessionName, phrase.takeUnless { quick }, quick)
                    AuthMode.LogIn -> server.api.logIn(sessionName, phrase.takeUnless { quick }, quick)
                }
                container.signedIn(server, result)
            } catch (e: ApiException) {
                fieldErrors = e.errors
                error = when {
                    e.errors.isNotEmpty() -> null
                    e.status == 401 || e.status == 404 -> "Session name or recovery phrase is not correct."
                    else -> e.error ?: "Something went wrong (HTTP ${e.status})."
                }
            } catch (_: Exception) {
                error = "Cannot reach the server. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(container: AppContainer, server: ActiveServer, mode: AuthMode, onBack: () -> Unit) {
    val vm: AuthViewModel = viewModel(key = "${server.config.url}:${mode.name}") { AuthViewModel(container, server, mode) }
    val register = mode == AuthMode.Register

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (register) "Create a session" else "Log in") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (register) {
                    "A session is your anonymous identity. Share its name with the people you want to talk to."
                } else {
                    "Use the session name and recovery phrase you chose when creating the session."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = vm.sessionName,
                onValueChange = { vm.sessionName = it.trim() },
                label = { Text(if (register) "Session name (optional)" else "Session name") },
                supportingText = {
                    val errors = vm.fieldErrors["session_name"]
                    Text(
                        errors?.joinToString() ?: if (register) "24-72 letters, digits or dashes. Leave empty for a random one." else "",
                    )
                },
                isError = vm.fieldErrors["session_name"] != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("session-name"),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quick session", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No recovery phrase: anyone who knows the name can log in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.quick, onCheckedChange = { vm.quick = it }, modifier = Modifier.testTag("quick"))
            }

            AnimatedVisibility(visible = !vm.quick) {
                OutlinedTextField(
                    value = vm.phrase,
                    onValueChange = { vm.phrase = it },
                    label = { Text("Recovery phrase") },
                    supportingText = {
                        Text(vm.fieldErrors["recovery_phrase"]?.joinToString() ?: "24-160 letters, spaces and punctuation.")
                    },
                    isError = vm.fieldErrors["recovery_phrase"] != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("recovery-phrase"),
                )
            }

            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::submit,
                enabled = vm.canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("submit"),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (register) "Create session" else "Log in")
                }
            }
        }
    }
}
