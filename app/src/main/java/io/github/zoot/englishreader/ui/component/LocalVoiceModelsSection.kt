package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.data.tts.TtsModelFailure
import io.github.zoot.englishreader.data.tts.TtsModelState

@Composable
fun LocalVoiceModelsSection(
    states: Map<String, TtsModelState>,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var confirmation by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tts_models_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tts_models_description), style = MaterialTheme.typography.bodyMedium)
        TtsModelCatalog.entries.forEach { entry ->
            val state = states[entry.id] ?: TtsModelState.NotInstalled
            Card(Modifier.fillMaxWidth().testTag("tts-model-${entry.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(entry.nameRes), style = MaterialTheme.typography.titleMedium)
                    Text(entry.voices.map { stringResource(it.nameRes) }.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                    Text(if (entry.bundled) {
                        stringResource(R.string.tts_model_bundled_details, entry.unpackedBytes / 1_000_000.0, entry.license)
                    } else {
                        stringResource(R.string.tts_model_details, entry.archiveBytes / 1_000_000.0,
                            entry.unpackedBytes / 1_000_000.0, entry.license)
                    }, style = MaterialTheme.typography.bodySmall)
                    Text(when (state) {
                        TtsModelState.NotInstalled -> stringResource(if (entry.bundled) R.string.tts_model_bundled else R.string.tts_model_not_installed)
                        TtsModelState.Installed -> stringResource(R.string.tts_model_installed)
                        TtsModelState.Installing -> stringResource(R.string.tts_model_installing)
                        TtsModelState.Removing -> stringResource(R.string.tts_model_removing)
                        is TtsModelState.Downloading -> stringResource(R.string.tts_model_progress, state.bytes / 1_000_000.0, state.totalBytes / 1_000_000.0)
                        is TtsModelState.Failed -> stringResource(when (state.reason) {
                            TtsModelFailure.NETWORK -> R.string.tts_model_error_network
                            TtsModelFailure.STORAGE -> R.string.tts_model_error_storage
                            TtsModelFailure.CORRUPT -> R.string.tts_model_error_corrupt
                            TtsModelFailure.UNAVAILABLE -> R.string.tts_model_unavailable
                        })
                    })
                    when (state) {
                        is TtsModelState.Downloading -> {
                            LinearProgressIndicator(progress = (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                            ModelAction(R.string.tts_model_cancel) { onCancel(entry.id) }
                        }
                        TtsModelState.Installing -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (!entry.bundled) ModelAction(R.string.action_cancel) { onCancel(entry.id) }
                        }
                        TtsModelState.Removing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        TtsModelState.Installed -> if (!entry.bundled) {
                            ModelAction(R.string.tts_model_remove) { confirmation = entry.id to false }
                        }
                        else -> if (!entry.bundled) {
                            ModelAction(R.string.tts_model_download) { confirmation = entry.id to true }
                        } else if (state is TtsModelState.Failed) {
                            ModelAction(R.string.action_retry) { onInstall(entry.id) }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.tts_model_licenses), style = MaterialTheme.typography.bodySmall)
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
                ModelAction(if (install) R.string.tts_model_download else R.string.tts_model_remove) {
                    confirmation = null
                    if (install) onInstall(id) else onRemove(id)
                }
            },
            dismissButton = { ModelAction(R.string.action_cancel) { confirmation = null } }
        )
    }
}

@Composable
private fun ModelAction(@StringRes label: Int, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
}
