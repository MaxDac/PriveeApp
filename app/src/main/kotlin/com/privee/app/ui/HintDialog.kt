package com.privee.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import com.privee.app.R
import com.privee.signal.SignalClient

/**
 * Edits the local hint about who [peerName] is. The advice not to write their name
 * is shown every time, because anyone who unlocks the device can read hints.
 */
@Composable
fun HintDialog(peerName: String, current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(peerName) { mutableStateOf(current.orEmpty()) }
    PriveeAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("hint-dialog"),
        title = { Text(stringResource(R.string.hint_title, peerName)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.hint_advice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("hint-advice"),
                )
                Spacer(Modifier.height(12.dp))
                NoPersonalizedLearning {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value.replace('\n', ' ').let {
                                if (it.codePointCount(0, it.length) > SignalClient.MAX_HINT_LENGTH) {
                                    it.substring(0, it.offsetByCodePoints(0, SignalClient.MAX_HINT_LENGTH))
                                } else {
                                    it
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.hint_label)) },
                        placeholder = { Text(stringResource(R.string.hint_placeholder)) },
                        supportingText = {
                            Text("${text.codePointCount(0, text.length)}/${SignalClient.MAX_HINT_LENGTH}")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onSave(SignalClient.normalizeHint(text)) }),
                        modifier = Modifier.fillMaxWidth().testTag("hint-input"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(SignalClient.normalizeHint(text)) },
                modifier = Modifier.testTag("hint-save"),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { onSave(null) }, modifier = Modifier.testTag("hint-remove")) {
                    Text(stringResource(R.string.remove))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
