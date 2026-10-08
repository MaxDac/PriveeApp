package com.privee.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.privee.app.AppAccent
import com.privee.app.AppLanguage
import com.privee.app.R

/** Device-local preferences: language and theme colour. */
@Composable
fun SettingsDialog(
    language: AppLanguage,
    accent: AppAccent,
    onLanguage: (AppLanguage) -> Unit,
    onAccent: (AppAccent) -> Unit,
    onDismiss: () -> Unit,
) {
    PriveeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle(stringResource(R.string.language))
                Column(Modifier.selectableGroup()) {
                    AppLanguage.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(language == option, role = Role.RadioButton, onClick = { onLanguage(option) })
                                .testTag("language-${option.tag}")
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(language == option, onClick = null)
                            Text(option.label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
                SectionTitle(stringResource(R.string.theme_colour), Modifier.padding(top = 16.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppAccent.entries.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { option -> AccentSwatch(option, accent == option) { onAccent(option) } }
                        }
                    }
                }
                Text(
                    stringResource(accent.label),
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AccentSwatch(accent: AppAccent, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(accent.label)
    Box(
        Modifier.size(44.dp)
            .clip(CircleShape)
            .background(accentSwatch(accent))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("accent-${accent.key}"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
    }
}
