package io.github.zoot.englishreader.ui.dialog

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.update.UpdateDownloadFailure
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import io.github.zoot.englishreader.data.update.UpdateReleasePolicy

@Composable
fun UpdateDownloadDialog(
    state: UpdateDownloadState,
    busy: Boolean,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRelease: () -> Unit
) {
    val context = LocalContext.current
    val verifying = state is UpdateDownloadState.Verifying
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (state is UpdateDownloadState.Ready) R.string.update_download_ready else R.string.update_download_title))
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    UpdateDownloadState.Idle -> {
                        Text(stringResource(R.string.update_download_preparing))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateDownloadState.Downloading -> {
                        Text(state.tag)
                        if (state.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = (state.bytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(stringResource(
                                R.string.update_download_progress,
                                Formatter.formatShortFileSize(context, state.bytes.coerceAtLeast(0)),
                                Formatter.formatShortFileSize(context, state.totalBytes)
                            ))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (state.paused) Text(stringResource(R.string.update_download_waiting))
                    }
                    is UpdateDownloadState.Verifying -> {
                        Text(stringResource(R.string.update_download_verifying))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateDownloadState.Ready -> Text(state.tag)
                    is UpdateDownloadState.Failed -> Text(
                        stringResource(state.reason.messageRes()), color = MaterialTheme.colorScheme.error
                    )
                }
                if (state != UpdateDownloadState.Idle) {
                    TextButton(onClick = onCancel, enabled = !busy && !verifying) {
                        Text(stringResource(if (state is UpdateDownloadState.Ready) R.string.update_download_remove else R.string.update_download_cancel))
                    }
                }
                if (state is UpdateDownloadState.Failed) {
                    TextButton(onClick = onOpenRelease) { Text(stringResource(R.string.update_download_browser)) }
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateDownloadState.Ready -> TextButton(
                    onClick = onInstall, enabled = !busy, modifier = Modifier.testTag("update-download-install")
                ) { Text(stringResource(R.string.update_download_install)) }
                is UpdateDownloadState.Failed -> if (UpdateReleasePolicy.isValidTag(state.tag)) {
                    TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag("update-download-retry")) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (state is UpdateDownloadState.Downloading) R.string.update_download_background else R.string.update_dialog_action_later))
            }
        }
    )
}

private fun UpdateDownloadFailure.messageRes(): Int = when (this) {
    UpdateDownloadFailure.NETWORK -> R.string.update_download_failed_network
    UpdateDownloadFailure.STORAGE -> R.string.update_download_failed_storage
    UpdateDownloadFailure.INVALID_PACKAGE -> R.string.update_download_failed_package
    UpdateDownloadFailure.WRONG_SIGNATURE -> R.string.update_download_failed_signature
    UpdateDownloadFailure.WRONG_VERSION -> R.string.update_download_failed_version
    UpdateDownloadFailure.UNSUPPORTED_DEVICE -> R.string.update_download_failed_device
    UpdateDownloadFailure.UNAVAILABLE -> R.string.update_download_failed_unavailable
}
