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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.zoot.englishreader.model.TranslationSegmentationMode
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
    onCancelTask: () -> Unit,
    onViewExistingTask: (Long) -> Unit = {},
    onCancelExistingTask: (Long) -> Unit = {},
    paragraphFormattingSuggested: Boolean = false,
    onEditArticle: (() -> Unit)? = null,
    onSelectMode: (TranslationSegmentationMode) -> Unit = {},
    onPreserveParagraphs: () -> Unit = {}
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
            onCancelTask = onCancelTask,
            onViewExistingTask = onViewExistingTask,
            onCancelExistingTask = onCancelExistingTask,
            paragraphFormattingSuggested = paragraphFormattingSuggested,
            onEditArticle = onEditArticle,
            onSelectMode = onSelectMode,
            onPreserveParagraphs = onPreserveParagraphs
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
    onCancelTask: () -> Unit,
    onViewExistingTask: (Long) -> Unit = {},
    onCancelExistingTask: (Long) -> Unit = {},
    paragraphFormattingSuggested: Boolean = false,
    onEditArticle: (() -> Unit)? = null,
    onSelectMode: (TranslationSegmentationMode) -> Unit = {},
    onPreserveParagraphs: () -> Unit = {}
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
                is WholeTranslationSheetState.Preparing -> Text(stringResource(R.string.whole_translation_previewing))
                is WholeTranslationSheetState.ChoosingScope -> ScopeChooser(
                    state = state,
                    onSelectScope = onSelectScope,
                    onStart = onStart,
                    onDismiss = onDismiss,
                    paragraphFormattingSuggested = paragraphFormattingSuggested,
                    onEditArticle = onEditArticle,
                    onSelectMode = onSelectMode
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
                                is AiError.InputTooLong -> R.string.settings_ai_error_input_too_long
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
                /*
                 * 冲突只给「去看既有任务」与「取消既有任务」两个动作，**没有**「忽略并新建」。
                 *
                 * 两个覆盖同一篇文章的任务都会跑到发布阶段，后提交的那个用自己的分块覆盖译文与
                 * 布局，另一个已发布的布局却还指向旧译文的坐标，用户看到一半新一半旧的对照，而
                 * 两边的请求都已计费。代码也不替用户取消既有任务：那等于让他为已经翻好的块白付
                 * 一次钱，这个决定必须由他自己做。
                 */
                is WholeTranslationSheetState.Conflict -> {
                    Text(
                        stringResource(
                            if (state.existingIsBook) {
                                R.string.whole_translation_conflict_book
                            } else {
                                R.string.whole_translation_conflict_article
                            }
                        ),
                        modifier = Modifier.testTag("whole-translation-conflict")
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = { onCancelExistingTask(state.existingTaskId) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whole-translation-conflict-cancel-existing")
                        ) {
                            Text(
                                stringResource(R.string.whole_translation_conflict_cancel_existing),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Button(
                            onClick = { onViewExistingTask(state.existingTaskId) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("whole-translation-primary")
                        ) { Text(stringResource(R.string.whole_translation_conflict_open_existing)) }
                    }
                }
                // 与 Rejected 分开显示：这不是「翻译不了」，而是当前分段方式切得太碎，
                // 用户改成保留原段落就能继续，所以文案要给出这条出路而不是一句失败。
                is WholeTranslationSheetState.TooManyBlocks -> {
                    Text(
                        stringResource(
                            R.string.whole_translation_too_many_blocks,
                            state.actualBlocks,
                            state.maxBlocks
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("whole-translation-too-many-blocks")
                    )
                    if (state.canPreserve) {
                        TextButton(onClick = onPreserveParagraphs, modifier = Modifier.heightIn(min = 48.dp).testTag("whole-translation-preserve")) {
                            Text(stringResource(R.string.whole_translation_mode_preserve))
                        }
                    } else Text(stringResource(R.string.whole_translation_other_chapter_limit))
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
    onDismiss: () -> Unit,
    paragraphFormattingSuggested: Boolean,
    onEditArticle: (() -> Unit)?,
    onSelectMode: (TranslationSegmentationMode) -> Unit
) {
    var confirmRegeneration by remember(state) { mutableStateOf(false) }
    if (confirmRegeneration) {
        AlertDialog(
            onDismissRequest = { confirmRegeneration = false },
            title = { Text(stringResource(R.string.whole_translation_regenerate)) },
            text = { Text(stringResource(R.string.whole_translation_regenerate_notice)) },
            confirmButton = {
                TextButton(onClick = { confirmRegeneration = false; onStart() }, modifier = Modifier.testTag("whole-translation-confirm-regenerate")) {
                    Text(stringResource(R.string.whole_translation_regenerate))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRegeneration = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    Text(stringResource(R.string.whole_translation_segmentation_title), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        TranslationSegmentationMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = state.segmentationMode == mode, enabled = !state.isStarting,
                        role = Role.RadioButton, onClick = { onSelectMode(mode) })
                    .testTag("whole-translation-mode-${mode.toStableToken()}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = state.segmentationMode == mode, onClick = null, enabled = !state.isStarting)
                Text(stringResource(segmentationLabel(mode)))
            }
        }
    }
    Text(stringResource(R.string.whole_translation_segmentation_notice), style = MaterialTheme.typography.bodySmall)
    // 分段方式切换要重算各范围的对照块数，是一次本地重新预览。刻意**不**在这里插入
    // 「正在计算…」占位行：那一行的出现与消失会顶动下方所有内容，正是用户看到的跳动。
    // 单选按钮已即时切换、范围行在预览期间置灰，反馈足够；块数就地更新，不改变行数。
    // previewChanged 是正文/范围变化后的持久重确认提示，与这一瞬时预览无关，保留。
    if (state.previewChanged) Text(stringResource(R.string.whole_translation_preview_changed))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScopeRow(
            title = stringResource(R.string.whole_translation_scope_current),
            description = stringResource(R.string.whole_translation_scope_current_description),
            option = state.currentArticleOption,
            selected = state.selected == WholeTranslationScopeChoice.CURRENT_ARTICLE,
            enabled = !state.isStarting && !state.isPreviewing,
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
                enabled = !state.isStarting && !state.isPreviewing,
                onClick = { onSelectScope(WholeTranslationScopeChoice.CHAPTER) },
                testTag = "whole-translation-scope-chapter"
            )
        }
    }
    if (paragraphFormattingSuggested) {
        Text(
            stringResource(R.string.whole_translation_paragraph_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("whole-translation-paragraph-hint")
        )
        if (onEditArticle != null) {
            TextButton(
                onClick = onEditArticle,
                enabled = !state.isStarting && !state.isPreviewing,
                modifier = Modifier.heightIn(min = 48.dp).testTag("whole-translation-edit-article")
            ) { Text(stringResource(R.string.action_edit_article)) }
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
            onClick = { if (state.hasTranslation) confirmRegeneration = true else onStart() },
            enabled = !state.isStarting && !state.isPreviewing,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("whole-translation-primary")
        ) {
            Text(stringResource(when {
                state.isStarting -> R.string.whole_translation_starting
                state.hasTranslation -> R.string.whole_translation_regenerate
                else -> R.string.whole_translation_start
            }))
        }
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
    val counts = stringResource(
        R.string.whole_translation_scope_counts,
        option.paragraphCount,
        option.blockCount,
        option.blockCount
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
    val modes = state.segmentationModes.map { stringResource(segmentationLabel(it)) }.joinToString(" / ")
    if (modes.isNotEmpty()) Text(stringResource(R.string.whole_translation_fixed_modes, modes))
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
            WholeTranslationPrimaryAction.CLOSE -> R.string.whole_translation_close to onDismiss
        }
        Button(
            onClick = action,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("whole-translation-primary")
        ) { Text(stringResource(label)) }
    }
}

private fun segmentationLabel(mode: TranslationSegmentationMode): Int = when (mode) {
    TranslationSegmentationMode.AUTO -> R.string.whole_translation_mode_auto
    TranslationSegmentationMode.LINE -> R.string.whole_translation_mode_line
    TranslationSegmentationMode.PRESERVE -> R.string.whole_translation_mode_preserve
}
