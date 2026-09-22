package io.github.zoot.englishreader.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.model.ArticleEditorDialog
import io.github.zoot.englishreader.model.ArticleEditorError
import io.github.zoot.englishreader.model.ArticleEditorEvent
import io.github.zoot.englishreader.model.ArticleEditorState
import io.github.zoot.englishreader.util.ArticleParagraphFormatting
import io.github.zoot.englishreader.util.ErrorMessageMapper
import io.github.zoot.englishreader.viewmodel.ArticleEditorViewModel

@Composable
fun ArticleEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: ArticleEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnNavigateBack by rememberUpdatedState(onNavigateBack)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == ArticleEditorEvent.NAVIGATE_BACK) currentOnNavigateBack()
        }
    }
    BackHandler { viewModel.requestBack() }
    ArticleEditorContent(
        state = state,
        onBack = viewModel::requestBack,
        onTitleChange = viewModel::updateTitle,
        onContentChange = viewModel::updateContent,
        onSave = viewModel::requestSave,
        onConfirmContentSave = viewModel::confirmContentSave,
        onConfirmDiscard = viewModel::confirmDiscard,
        onPreviewParagraphs = viewModel::requestParagraphPreview,
        onApplyParagraphs = viewModel::applyParagraphPreview,
        onDismissDialog = viewModel::dismissDialog,
        onRetryLoad = viewModel::retryLoad
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleEditorContent(
    state: ArticleEditorState,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onConfirmContentSave: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onPreviewParagraphs: () -> Unit,
    onApplyParagraphs: () -> Unit,
    onDismissDialog: () -> Unit,
    onRetryLoad: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.article_editor_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !state.isSaving,
                        modifier = Modifier.size(48.dp).testTag("article-editor-back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = state.canSave,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-save")
                    ) {
                        Text(stringResource(if (state.isSaving) R.string.article_editor_saving else R.string.article_editor_save))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("article-editor-loading"))
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .testTag("article-editor-form"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    state.error?.let { error ->
                        Text(
                            text = editorErrorText(error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("article-editor-error")
                        )
                    }
                    if (state.original != null) {
                        OutlinedTextField(
                            value = state.title,
                            onValueChange = onTitleChange,
                            enabled = state.canEdit,
                            label = { Text(stringResource(R.string.article_editor_title_label)) },
                            singleLine = true,
                            isError = state.error == ArticleEditorError.TitleRequired,
                            modifier = Modifier.fillMaxWidth().testTag("article-editor-title-field")
                        )
                        OutlinedTextField(
                            value = state.content,
                            onValueChange = onContentChange,
                            enabled = state.canEdit,
                            label = { Text(stringResource(R.string.article_editor_content_label)) },
                            minLines = 10,
                            maxLines = 20,
                            isError = state.error is ArticleEditorError.InvalidContent,
                            supportingText = {
                                Text(stringResource(R.string.article_editor_character_count, state.content.length, ImportBudget.MAX_IMPORT_CHARS))
                            },
                            modifier = Modifier.fillMaxWidth().testTag("article-editor-content-field")
                        )
                        val hasUnseparatedLines = remember(state.content) {
                            ArticleParagraphFormatting.hasUnseparatedLines(state.content)
                        }
                        if (hasUnseparatedLines) {
                            Text(
                                stringResource(R.string.article_editor_unseparated_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("article-editor-paragraph-hint")
                            )
                        }
                        TextButton(
                            onClick = onPreviewParagraphs,
                            enabled = state.canEdit && state.content.isNotBlank() &&
                                ('\n' in state.content || '\r' in state.content),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-format")
                        ) {
                            Text(stringResource(R.string.article_editor_format))
                        }
                        Text(
                            stringResource(R.string.article_editor_format_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.error == ArticleEditorError.LoadFailed) {
                        TextButton(
                            onClick = onRetryLoad,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-retry")
                        ) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        ArticleEditorDialog.ConfirmContentSave -> AlertDialog(
            onDismissRequest = onDismissDialog,
            modifier = Modifier.imePadding().navigationBarsPadding(),
            title = { Text(stringResource(R.string.article_editor_content_save_title)) },
            text = { Text(stringResource(R.string.article_editor_content_save_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmContentSave, modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-confirm-save")) {
                    Text(stringResource(R.string.article_editor_confirm_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
        ArticleEditorDialog.ConfirmDiscard -> AlertDialog(
            onDismissRequest = onDismissDialog,
            modifier = Modifier.imePadding().navigationBarsPadding(),
            title = { Text(stringResource(R.string.article_editor_discard_title)) },
            text = { Text(stringResource(R.string.article_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard, modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-confirm-discard")) {
                    Text(stringResource(R.string.article_editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.article_editor_keep_editing))
                }
            }
        )
        is ArticleEditorDialog.ParagraphPreview -> ParagraphPreviewDialog(dialog, onApplyParagraphs, onDismissDialog)
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParagraphPreviewDialog(
    preview: ArticleEditorDialog.ParagraphPreview,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                        .testTag("article-editor-preview"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.article_editor_preview_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.article_editor_preview_count, preview.paragraphCount))
                    Text(stringResource(R.string.article_editor_preview_notice), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(preview.content, modifier = Modifier.testTag("article-editor-preview-content"))
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-preview-cancel")) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(onClick = onApply, modifier = Modifier.heightIn(min = 48.dp).testTag("article-editor-preview-apply")) {
                            Text(stringResource(R.string.article_editor_apply_to_draft))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun editorErrorText(error: ArticleEditorError): String = when (error) {
    ArticleEditorError.LoadFailed -> stringResource(R.string.article_editor_load_failed)
    ArticleEditorError.NotFound -> stringResource(R.string.article_editor_not_found)
    ArticleEditorError.NotStandalone -> stringResource(R.string.article_editor_not_standalone)
    ArticleEditorError.Conflict -> stringResource(R.string.article_editor_conflict)
    ArticleEditorError.SaveFailed -> stringResource(R.string.article_editor_save_failed)
    ArticleEditorError.TitleRequired -> stringResource(R.string.article_editor_title_required)
    is ArticleEditorError.InvalidContent -> if (error.failure == ImportFailure.EmptyContent) {
        stringResource(R.string.article_editor_content_required)
    } else {
        ErrorMessageMapper.mapImportFailure(LocalContext.current, error.failure)
    }
}
