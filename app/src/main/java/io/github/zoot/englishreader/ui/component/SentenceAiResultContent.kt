package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.util.toUiMessage

/** 标题关闭入口固定可达，所有结果、披露和操作在父 Popup 提供的高度内滚动。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SentenceAiResultContent(
    mode: SentencePopupMode,
    state: AiSheetState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    val isTranslation = mode == SentencePopupMode.TRANSLATION
    val title = stringResource(
        if (isTranslation) R.string.reading_sentence_translation_title
        else R.string.reading_sentence_explanation_title
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.reading_sentence_popup_close)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .testTag("sentence-result-scroll")
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val target = when (state) {
                is AiSheetState.Loading -> state.target
                is AiSheetState.Visible -> state.target
                is AiSheetState.Rejected -> state.target
                AiSheetState.Hidden -> null
            }
            (target as? AiExplanationTarget.Sentence)?.snapshot?.rawText?.let { source ->
                Text(
                    stringResource(R.string.reading_sentence_source),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    source,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("sentence-result-source")
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            when (state) {
                is AiSheetState.Loading -> ResultLoading(isTranslation, onCancel)
                is AiSheetState.Rejected -> ResultError(
                    error = state.error,
                    onRetry = onRetry.takeIf { isTranslation },
                    onDismiss = onDismiss
                )
                is AiSheetState.Visible -> when (val outcome = state.outcome) {
                    null -> ResultLoading(isTranslation, onCancel)
                    is AiOperationOutcome.Success -> {
                        if (!isTranslation && target !is AiExplanationTarget.Sentence) {
                            Text(
                                stringResource(R.string.reading_sentence_explanation_detail),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = outcome.explanation,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("sentence-result-explanation")
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResultCloseButton(onDismiss)
                            PopupAction(
                                icon = Icons.Default.PlayArrow,
                                label = stringResource(R.string.reading_sentence_play),
                                contentDescription = stringResource(R.string.reading_sentence_play_content_description),
                                onClick = onPlay
                            )
                        }
                    }
                    is AiOperationOutcome.Failure -> ResultError(
                        error = outcome.error,
                        onRetry = onRetry.takeIf { isTranslation },
                        onDismiss = onDismiss
                    )
                    AiOperationOutcome.Cancelled -> Unit
                }
                AiSheetState.Hidden -> {
                    Text(
                        stringResource(
                            if (isTranslation) R.string.reading_sentence_translation_unavailable
                            else R.string.reading_sentence_explanation_unavailable
                        )
                    )
                    ResultCloseButton(onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ResultLoading(isTranslation: Boolean, onCancel: () -> Unit) {
    val loadingDescription = stringResource(
        if (isTranslation) R.string.reading_sentence_translation_loading
        else R.string.ai_explanation_loading
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = loadingDescription },
            strokeWidth = 2.dp
        )
        Text(text = loadingDescription, style = MaterialTheme.typography.bodyLarge)
    }
    if (!isTranslation) {
        Text(
            text = stringResource(R.string.settings_ai_third_party_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.action_cancel))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultError(error: AiError, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    val message = error.toUiMessage()
    Text(
        text = stringResource(message.resourceId, *message.formatArgs.toTypedArray()),
        color = MaterialTheme.colorScheme.error
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        }
        ResultCloseButton(onDismiss)
    }
}

@Composable
private fun ResultCloseButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.reading_sentence_popup_close))
    }
}
