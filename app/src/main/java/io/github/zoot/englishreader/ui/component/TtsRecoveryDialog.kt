package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.model.ReadingTtsFailure
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.TtsFailureReason

@Composable
fun TtsRecoveryDialog(
    failure: ReadingTtsFailure,
    onRetry: (Boolean) -> Unit,
    onSystemAction: (TtsSystemAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tts_recovery_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(failure.reason.messageRes()))
                if (failure.reason == TtsFailureReason.NETWORK_VOICE_DISABLED) {
                    Text(stringResource(R.string.tts_network_once_disclosure))
                    TextButton(onClick = { onRetry(true) }, modifier = Modifier.testTag("tts-recovery-network")) {
                        Text(stringResource(R.string.tts_use_network_once))
                    }
                }
                TextButton(
                    onClick = { onSystemAction(TtsSystemAction.OPEN_SETTINGS) },
                    modifier = Modifier.testTag("tts-recovery-settings")
                ) { Text(stringResource(R.string.tts_system_settings)) }
                TextButton(
                    onClick = { onSystemAction(TtsSystemAction.INSTALL_DATA) },
                    modifier = Modifier.testTag("tts-recovery-install")
                ) { Text(stringResource(R.string.tts_install_data)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRetry(false) }, modifier = Modifier.testTag("tts-recovery-retry")) {
                Text(stringResource(R.string.action_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
