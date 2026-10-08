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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.privee.app.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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

enum class AuthProblem { InvalidCredentials, Http, Unreachable, Validation }

fun authProblemResource(problem: AuthProblem): Int = when (problem) {
    AuthProblem.InvalidCredentials -> R.string.auth_invalid
    AuthProblem.Http -> R.string.http_error
    AuthProblem.Unreachable -> R.string.auth_unreachable
    AuthProblem.Validation -> R.string.validation_error
}

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
    var error by mutableStateOf<AuthProblem?>(null)
        private set
    var errorStatus by mutableIntStateOf(0)
        private set
    var fieldErrors by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    private var validationLanguage = container.languageTag
    private var validationGeneration = 0

    fun languageChanged(tag: String) {
        if (validationLanguage == tag) return
        validationLanguage = tag
        validationGeneration++
        fieldErrors = emptyMap()
        if (error == AuthProblem.Validation) error = null
    }

    val canSubmit: Boolean
        get() = !busy && (mode == AuthMode.Register || sessionName.isNotBlank()) && (quick || phrase.isNotBlank())

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        fieldErrors = emptyMap()
        val requestLanguage = container.languageTag
        val generation = validationGeneration
        viewModelScope.launch {
            try {
                val result = when (mode) {
                    AuthMode.Register -> server.api.register(sessionName, phrase.takeUnless { quick }, quick)
                    AuthMode.LogIn -> server.api.logIn(sessionName, phrase.takeUnless { quick }, quick)
                }
                container.signedIn(server, result)
            } catch (e: ApiException) {
                if (e.errors.isNotEmpty() &&
                    (requestLanguage != container.languageTag || generation != validationGeneration)
                ) return@launch
                fieldErrors = e.errors.filterKeys { it == "session_name" || it == "recovery_phrase" }
                errorStatus = e.status
                error = when {
                    fieldErrors.isNotEmpty() -> null
                    e.errors.isNotEmpty() -> AuthProblem.Validation
                    e.status == 401 || e.status == 404 -> AuthProblem.InvalidCredentials
                    else -> AuthProblem.Http
                }
            } catch (_: Exception) {
                error = AuthProblem.Unreachable
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(container: AppContainer, server: ActiveServer, mode: AuthMode, onSettings: () -> Unit, onBack: () -> Unit) {
    val vm: AuthViewModel = viewModel(key = "${server.config.url}:${mode.name}") { AuthViewModel(container, server, mode) }
    val register = mode == AuthMode.Register
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    LaunchedEffect(language) { vm.languageChanged(language) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (register) R.string.create_session else R.string.log_in)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onSettings, modifier = Modifier.testTag("settings")) { Text(stringResource(R.string.settings)) }
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
                stringResource(if (register) R.string.register_description else R.string.login_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = vm.sessionName,
                onValueChange = { vm.sessionName = it.trim() },
                label = { Text(stringResource(if (register) R.string.session_name_optional else R.string.session_name)) },
                supportingText = {
                    val errors = vm.fieldErrors["session_name"]
                    Text(
                        errors?.joinToString() ?: if (register) stringResource(R.string.session_name_hint) else "",
                    )
                },
                isError = vm.fieldErrors["session_name"] != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("session-name"),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.quick_session), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.quick_session_hint),
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
                    label = { Text(stringResource(R.string.recovery_phrase)) },
                    supportingText = {
                        Text(vm.fieldErrors["recovery_phrase"]?.joinToString() ?: stringResource(R.string.recovery_phrase_hint))
                    },
                    isError = vm.fieldErrors["recovery_phrase"] != null,
                    visualTransformation = PasswordVisualTransformation(),
                    // Also marks the field as sensitive to keyboards: no suggestions or learning.
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("recovery-phrase"),
                )
            }

            vm.error?.let {
                Text(
                    if (it == AuthProblem.Http) stringResource(R.string.http_error, vm.errorStatus) else stringResource(authProblemResource(it)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                    Text(stringResource(if (register) R.string.create_session_button else R.string.log_in))
                }
            }
        }
    }
}
