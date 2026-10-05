package io.github.zoot.englishreader.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.data.tts.TtsModelEntry
import io.github.zoot.englishreader.data.tts.TtsModelFailure
import io.github.zoot.englishreader.data.tts.TtsModelState
import io.github.zoot.englishreader.ui.screen.SettingsDivider
import io.github.zoot.englishreader.ui.screen.SettingsFootnote
import io.github.zoot.englishreader.ui.screen.SettingsGroup

@Composable
fun LocalVoiceModelsSection(
    states: Map<String, TtsModelState>,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var confirmation by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var detailsId by remember { mutableStateOf<String?>(null) }
    SettingsGroup(stringResource(R.string.settings_voice_packages)) {
        TtsModelCatalog.entries.forEachIndexed { index, entry ->
            val state = states[entry.id] ?: TtsModelState.NotInstalled
            if (index > 0) SettingsDivider(16.dp)
            ListItem(
                modifier = Modifier.heightIn(min = 52.dp).testTag("tts-model-${entry.id}")
                    .clickable { detailsId = entry.id },
                headlineContent = { Text(stringResource(entry.nameRes), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_voice_summary,
                            entry.unpackedBytes / 1_000_000.0,
                            entry.voices.take(3).map { stringResource(it.nameRes).substringBefore(" · ") }.joinToString("、")),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (state) {
                            is TtsModelState.Downloading -> Text(stringResource(R.string.tts_model_progress,
                                state.bytes / 1_000_000.0, state.totalBytes / 1_000_000.0), style = MaterialTheme.typography.bodySmall)
                            is TtsModelState.Failed -> Text(stringResource(state.reason.messageRes()),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TtsModelState.Installing -> Text(stringResource(R.string.tts_model_installing), style = MaterialTheme.typography.bodySmall)
                            TtsModelState.Removing -> Text(stringResource(R.string.tts_model_removing), style = MaterialTheme.typography.bodySmall)
                            else -> Unit
                        }
                    }
                },
                trailingContent = {
                    when (state) {
                        TtsModelState.Installed -> Text(stringResource(R.string.settings_voice_installed), style = MaterialTheme.typography.bodySmall)
                        is TtsModelState.Downloading -> ModelProgress(
                            progress = if (state.totalBytes > 0) (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else null,
                            onCancel = { onCancel(entry.id) }
                        )
                        TtsModelState.Installing -> ModelProgress(onCancel = if (entry.bundled) null else { { onCancel(entry.id) } })
                        TtsModelState.Removing -> ModelProgress()
                        else -> FilledTonalButton(
                            onClick = {
                                if (entry.bundled) onInstall(entry.id) else confirmation = entry.id to true
                            },
                            modifier = Modifier.heightIn(min = 30.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(stringResource(if (state is TtsModelState.Failed) R.string.action_retry else R.string.settings_voice_get))
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
    SettingsFootnote(stringResource(R.string.settings_voice_footnote))
    SettingsFootnote(stringResource(R.string.settings_voice_details_hint))

    detailsId?.let { id ->
        val entry = TtsModelCatalog.entries.first { it.id == id }
        AlertDialog(
            onDismissRequest = { detailsId = null },
            title = { Text(stringResource(entry.nameRes)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.voices.map { stringResource(it.nameRes) }.joinToString("、"))
                    Text(modelDetails(entry))
                    Text(stringResource(R.string.tts_models_description), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { ModelAction(R.string.action_close) { detailsId = null } },
            dismissButton = if (!entry.bundled && states[id] == TtsModelState.Installed) {
                { ModelAction(R.string.tts_model_remove, destructive = true) {
                    detailsId = null
                    confirmation = id to false
                } }
            } else null
        )
    }
    confirmation?.let { (id, install) ->
        val entry = TtsModelCatalog.entries.first { it.id == id }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (install) R.string.tts_model_download_title else R.string.tts_model_remove_title, stringResource(entry.nameRes))) },
            text = { Text(if (install) stringResource(R.string.tts_model_download_message,
                entry.archiveBytes / 1_000_000.0, entry.unpackedBytes / 1_000_000.0, entry.license)
                else stringResource(R.string.tts_model_remove_message)) },
            confirmButton = {
                ModelAction(if (install) R.string.tts_model_download else R.string.tts_model_remove, destructive = !install) {
                    if (confirmation != null) {
                        confirmation = null
                        if (install) onInstall(id) else onRemove(id)
                    }
                }
            },
            dismissButton = { ModelAction(R.string.action_cancel) { confirmation = null } }
        )
    }
}

@Composable
private fun ModelProgress(progress: Float? = null, onCancel: (() -> Unit)? = null) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (progress == null) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(progress = progress, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        if (onCancel != null) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, stringResource(R.string.tts_model_cancel), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun modelDetails(entry: TtsModelEntry): String = if (entry.bundled) {
    stringResource(R.string.tts_model_bundled_details, entry.unpackedBytes / 1_000_000.0, entry.license)
} else {
    stringResource(R.string.tts_model_details, entry.archiveBytes / 1_000_000.0,
        entry.unpackedBytes / 1_000_000.0, entry.license)
}

private fun TtsModelFailure.messageRes() = when (this) {
    TtsModelFailure.NETWORK -> R.string.tts_model_error_network
    TtsModelFailure.STORAGE -> R.string.tts_model_error_storage
    TtsModelFailure.CORRUPT -> R.string.tts_model_error_corrupt
    TtsModelFailure.UNAVAILABLE -> R.string.tts_model_unavailable
}

@Composable
private fun ModelAction(@StringRes label: Int, destructive: Boolean = false, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(label), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}
