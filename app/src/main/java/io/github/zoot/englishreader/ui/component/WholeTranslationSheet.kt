package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.ScopeOption
import io.github.zoot.englishreader.model.TranslationFailureReason
import io.github.zoot.englishreader.model.WholeTranslationPrimaryAction
import io.github.zoot.englishreader.model.WholeTranslationScopeChoice
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WholeTranslationSheet(
    state: WholeTranslationSheetState,
    onDismiss: () -> Unit,
    onSelectScope: (WholeTranslationScopeChoice) -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancelTask: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        WholeTranslationContent(
            state = state,
            onDismiss = onDismiss,
            onSelectScope = onSelectScope,
            onStart = onStart,
            onResume = onResume,
            onRetryFailed = onRetryFailed,
            onCancelTask = onCancelTask
        )
    }
}

/**
 * Sheet 主体，与 [ModalBottomSheet] 分离以便 Compose 测试直接渲染。
 *
 * 关闭（[onDismiss]）与取消任务（[onCancelTask]）是两个不同的动作，按钮文案与位置都分开：
 * 前者只收起面板，付费任务在后台继续；后者才停止任务。合并会让用户以为「关掉面板 = 停止
 * 花钱」，或反过来以为「点了取消只是收起面板」。
 */
@Composable
internal fun WholeTranslationContent(
    state: WholeTranslationSheetState,
    onDismiss: () -> Unit,
    onSelectScope: (WholeTranslationScopeChoice) -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancelTask: () -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag("whole-translation-sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.whole_translation_title),
                style = MaterialTheme.typography.titleLarge
            )
            when (state) {
                WholeTranslationSheetState.Hidden -> Unit
                is WholeTranslationSheetState.ChoosingScope -> ScopeChooser(
                    state = state,
                    onSelectScope = onSelectScope,
                    onStart = onStart,
                    onDismiss = onDismiss
                )
                is WholeTranslationSheetState.Tracking -> TaskTracker(
                    state = state,
                    onResume = onResume,
                    onRetryFailed = onRetryFailed,
                    onCancelTask = onCancelTask,
                    onDismiss = onDismiss
                )
                is WholeTranslationSheetState.Rejected -> {
                    Text(
                        stringResource(
                            when (state.error) {
                                AiError.NoContent -> R.string.whole_translation_rejected_no_content
                                else -> R.string.whole_translation_rejected_generic
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("whole-translation-rejected")
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("whole-translation-close")
                    ) { Text(stringResource(R.string.whole_translation_close)) }
                }
            }
        }
    }
}

@Composable
private fun ScopeChooser(
    state: WholeTranslationSheetState.ChoosingScope,
    onSelectScope: (WholeTranslationScopeChoice) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScopeRow(
            title = stringResource(R.string.whole_translation_scope_current),
            description = stringResource(R.string.whole_translation_scope_current_description),
            option = state.currentArticleOption,
            selected = state.selected == WholeTranslationScopeChoice.CURRENT_ARTICLE,
            enabled = true,
            onClick = { onSelectScope(WholeTranslationScopeChoice.CURRENT_ARTICLE) },
            testTag = "whole-translation-scope-current"
        )
        val chapter = state.chapterOption
        if (chapter != null) {
            ScopeRow(
                title = stringResource(R.string.whole_translation_scope_chapter),
                description = stringResource(
                    R.string.whole_translation_scope_chapter_description,
                    chapter.articleCount
                ),
                option = chapter,
                selected = state.selected == WholeTranslationScopeChoice.CHAPTER,
                enabled = true,
                onClick = { onSelectScope(WholeTranslationScopeChoice.CHAPTER) },
                testTag = "whole-translation-scope-chapter"
            )
        }
    }
    Text(
        stringResource(R.string.whole_translation_cost_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = 48.dp).testTag("whole-translation-close")
        ) { Text(stringResource(R.string.whole_translation_close)) }
        Button(
            onClick = onStart,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("whole-translation-primary")
        ) { Text(stringResource(R.string.whole_translation_start)) }
    }
}

/** 整行可点、48dp 触控、Role.RadioButton 语义；TalkBack 读出标题 + 计数。 */
@Composable
private fun ScopeRow(
    title: String,
    description: String,
    option: ScopeOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    // 每个段落独立一次请求（PRD 约束），所以预计请求数就是段落数。若将来引入批量，
    // 这里必须改为从 ScopeOption 读取独立的 requestCount，而不是继续复用段落数。
    val counts = stringResource(
        R.string.whole_translation_scope_counts,
        option.paragraphCount,
        option.paragraphCount
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$title, $counts" }
            .padding(vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskTracker(
    state: WholeTranslationSheetState.Tracking,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onCancelTask: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = state.progress
    val progressText = if (p.failed > 0) {
        stringResource(R.string.whole_translation_progress_failed, p.translated, p.total, p.failed)
    } else {
        stringResource(R.string.whole_translation_progress, p.translated, p.total)
    }
    Text(progressText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("whole-translation-progress"))
    LinearProgressIndicator(
        progress = { if (p.total == 0) 0f else p.translated.toFloat() / p.total },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        stringResource(
            when (state.status) {
                WholeTranslationTaskStatus.RUNNING -> R.string.whole_translation_status_running
                WholeTranslationTaskStatus.PAUSED -> R.string.whole_translation_status_paused
                WholeTranslationTaskStatus.COMPLETED -> R.string.whole_translation_status_completed
                WholeTranslationTaskStatus.FAILED ->
                    if (state.failureReason == TranslationFailureReason.CONFIGURATION) {
                        R.string.whole_translation_status_failed_configuration
                    } else {
                        R.string.whole_translation_status_failed_generic
                    }
                WholeTranslationTaskStatus.CANCELLED -> R.string.whole_translation_status_failed_generic
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.status == WholeTranslationTaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("whole-translation-status")
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.status != WholeTranslationTaskStatus.COMPLETED && state.status != WholeTranslationTaskStatus.CANCELLED) {
            TextButton(
                onClick = onCancelTask,
                modifier = Modifier.heightIn(min = 48.dp).testTag("whole-translation-cancel-task")
            ) { Text(stringResource(R.string.whole_translation_cancel_task), color = MaterialTheme.colorScheme.error) }
        }
        val (label, action) = when (state.primaryAction) {
            WholeTranslationPrimaryAction.RESUME -> R.string.whole_translation_resume to onResume
            WholeTranslationPrimaryAction.RETRY_FAILED -> R.string.whole_translation_retry_failed to onRetryFailed
            WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND -> R.string.whole_translation_continue_background to onDismiss
            WholeTranslationPrimaryAction.DONE -> R.string.whole_translation_done to onDismiss
        }
        Button(
            onClick = action,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("whole-translation-primary")
        ) { Text(stringResource(label)) }
    }
}
