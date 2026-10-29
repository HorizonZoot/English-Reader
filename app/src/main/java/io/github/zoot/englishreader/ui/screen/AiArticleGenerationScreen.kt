package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiArticleDifficulty
import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleLength
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticleRejection
import io.github.zoot.englishreader.data.repository.AiArticleGenerationStatus
import io.github.zoot.englishreader.data.repository.AiArticleSaveStatus
import io.github.zoot.englishreader.ui.theme.AiArticleGenerationStyle
import io.github.zoot.englishreader.ui.theme.AiArticleGenerationTheme
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.util.toUiMessage
import io.github.zoot.englishreader.viewmodel.AiArticleGenerationUiState
import io.github.zoot.englishreader.viewmodel.AiArticleGenerationViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun AiArticleGenerationScreen(
    onBack: () -> Unit,
    onOpenAiProfile: () -> Unit,
    onReadArticle: (Long) -> Unit,
    viewModel: AiArticleGenerationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val currentReadArticle by rememberUpdatedState(onReadArticle)
    val snackbarHostState = remember { SnackbarHostState() }
    DisposableEffect(lifecycleOwner, viewModel) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.setScreenActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        viewModel.setScreenActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setScreenActive(false)
        }
    }
    LaunchedEffect(lifecycleOwner, viewModel, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collectLatest { request ->
                val articleId = viewModel.consumeReadRequest(request) ?: return@collectLatest
                try {
                    currentReadArticle(articleId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    snackbarHostState.showSnackbar(context.getString(R.string.ai_article_navigation_failed))
                }
            }
        }
    }
    AiArticleGenerationContent(
        state = state,
        onBack = onBack,
        onOpenAiProfile = onOpenAiProfile,
        onReloadProfiles = viewModel::reloadProfiles,
        onSelectProfile = viewModel::selectProfile,
        onTopicChange = viewModel::updateTopic,
        onDifficultyChange = viewModel::updateDifficulty,
        onLengthChange = viewModel::updateLength,
        onGenerate = viewModel::generate,
        onSaveAndRead = viewModel::saveAndRead,
        onDiscard = viewModel::discardPreview,
        snackbarHostState = snackbarHostState
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiArticleGenerationContent(
    state: AiArticleGenerationUiState,
    onBack: () -> Unit,
    onOpenAiProfile: () -> Unit,
    onReloadProfiles: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onTopicChange: (String) -> Unit,
    onDifficultyChange: (AiArticleDifficulty) -> Unit,
    onLengthChange: (AiArticleLength) -> Unit,
    onGenerate: () -> Unit,
    onSaveAndRead: (String) -> Unit,
    onDiscard: (String) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val session = state.session
    val preview = session.preview
    var regenerateResultId by remember { mutableStateOf<String?>(null) }
    val paragraphs = remember(preview?.resultId) {
        preview?.article?.content?.let(ParagraphAligner::splitParagraphs).orEmpty()
    }

    AiArticleGenerationTheme {
        Scaffold(
            modifier = Modifier.testTag("ai_article_page"),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ai_article_title), style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                stringResource(R.string.nav_back),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("ai_article_content"),
                    verticalArrangement = Arrangement.spacedBy(AiArticleGenerationStyle.SectionGap),
                    contentPadding = PaddingValues(
                        horizontal = AiArticleGenerationStyle.PagePadding,
                        vertical = AiArticleGenerationStyle.SectionGap
                    )
                ) {
                    item(key = "form") {
                        Column(verticalArrangement = Arrangement.spacedBy(AiArticleGenerationStyle.ContentGap)) {
                            AiArticleGenerationForm(
                                state = state,
                                onOpenAiProfile = onOpenAiProfile,
                                onReloadProfiles = onReloadProfiles,
                                onSelectProfile = onSelectProfile,
                                onTopicChange = onTopicChange,
                                onDifficultyChange = onDifficultyChange,
                                onLengthChange = onLengthChange,
                                onGenerate = {
                                    if (preview == null) onGenerate() else regenerateResultId = preview.resultId
                                }
                            )
                            if (session.isGenerating) {
                                AiGenerationFootnote(stringResource(R.string.ai_article_generating))
                            }
                            when (val status = session.status) {
                                is AiArticleGenerationStatus.Failed -> {
                                    Text(generationFailureMessage(status.failure), color = MaterialTheme.colorScheme.error)
                                    AiGenerationFootnote(stringResource(R.string.ai_article_retry_cost))
                                }
                                is AiArticleGenerationStatus.Cancelled -> AiGenerationFootnote(stringResource(R.string.ai_article_cancelled))
                                else -> Unit
                            }
                            AiGenerationFootnote(stringResource(R.string.ai_article_temporary_notice))
                        }
                    }
                    if (preview != null) {
                        item(key = "preview_header") {
                            AiGenerationGroup(stringResource(R.string.ai_article_preview), modifier = Modifier.testTag("ai_article_preview")) {
                                Text(preview.article.title, style = MaterialTheme.typography.titleLarge)
                                val parameters = preview.article.parameters
                                Text(
                                    stringResource(
                                        R.string.ai_article_preview_parameters,
                                        stringResource(parameters.difficulty.labelResource()),
                                        stringResource(parameters.length.labelResource()),
                                        parameters.topic.ifBlank { stringResource(R.string.ai_article_topic_auto) }
                                    ),
                                    modifier = Modifier.testTag("ai_article_preview_parameters"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(stringResource(R.string.ai_article_word_count, preview.article.wordCount))
                                if (preview.article.isOutsideWordTarget) {
                                    AiGenerationFootnote(stringResource(R.string.ai_article_word_deviation, parameters.length.targetWords))
                                }
                                AiGenerationFootnote(stringResource(R.string.ai_article_content_notice))
                                AiGenerationFootnote(stringResource(R.string.ai_article_temporary_notice))
                            }
                        }
                        itemsIndexed(paragraphs, key = { index, _ -> "paragraph_$index" }) { _, paragraph ->
                            Text(
                                paragraph,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        item(key = "preview_actions") {
                            Column(verticalArrangement = Arrangement.spacedBy(AiArticleGenerationStyle.ContentGap)) {
                                when (preview.saveStatus) {
                                    AiArticleSaveStatus.Failed, AiArticleSaveStatus.Cancelled ->
                                        Text(stringResource(R.string.ai_article_save_failed), color = MaterialTheme.colorScheme.error)
                                    is AiArticleSaveStatus.Saved -> AiGenerationFootnote(stringResource(R.string.ai_article_saved))
                                    else -> Unit
                                }
                                Button(
                                    onClick = { onSaveAndRead(preview.resultId) },
                                    enabled = !state.isBusy,
                                    shape = AiArticleGenerationStyle.ButtonShape,
                                    colors = aiGenerationButtonColors(),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai_article_save")
                                ) {
                                    Text(stringResource(when (preview.saveStatus) {
                                        is AiArticleSaveStatus.Saved -> R.string.ai_article_read_saved
                                        AiArticleSaveStatus.Saving -> R.string.ai_article_saving
                                        AiArticleSaveStatus.Failed, AiArticleSaveStatus.Cancelled -> R.string.ai_article_retry_save
                                        AiArticleSaveStatus.NotSaved -> R.string.ai_article_save_and_read
                                    }))
                                }
                                TextButton(
                                    onClick = { onDiscard(preview.resultId) },
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_article_discard")
                                ) {
                                    Text(stringResource(R.string.ai_article_discard))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (regenerateResultId != null && regenerateResultId == preview?.resultId && !state.isBusy) {
            AlertDialog(
                onDismissRequest = { regenerateResultId = null },
                title = { Text(stringResource(R.string.ai_article_regenerate)) },
                text = { Text(stringResource(R.string.ai_article_regenerate_notice)) },
                confirmButton = {
                    TextButton(
                        onClick = { regenerateResultId = null; onGenerate() },
                        enabled = state.canGenerate,
                        modifier = Modifier.testTag("ai_article_confirm_regenerate")
                    ) { Text(stringResource(R.string.ai_article_confirm_regenerate)) }
                },
                dismissButton = {
                    TextButton(onClick = { regenerateResultId = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}

internal fun AiArticleDifficulty.labelResource(): Int = when (this) {
    AiArticleDifficulty.BEGINNER -> R.string.ai_article_beginner
    AiArticleDifficulty.INTERMEDIATE -> R.string.ai_article_intermediate
    AiArticleDifficulty.ADVANCED -> R.string.ai_article_advanced
}

internal fun AiArticleDifficulty.descriptionResource(): Int = when (this) {
    AiArticleDifficulty.BEGINNER -> R.string.ai_article_beginner_description
    AiArticleDifficulty.INTERMEDIATE -> R.string.ai_article_intermediate_description
    AiArticleDifficulty.ADVANCED -> R.string.ai_article_advanced_description
}

private fun AiArticleLength.labelResource(): Int = when (this) {
    AiArticleLength.SHORT -> R.string.ai_article_short
    AiArticleLength.MEDIUM -> R.string.ai_article_medium
    AiArticleLength.LONG -> R.string.ai_article_long
}

@Composable
private fun generationFailureMessage(failure: AiArticleGenerationFailure): String = when (failure) {
    is AiArticleGenerationFailure.Request -> {
        if (failure.error is AiError.InputTooLong) {
            stringResource(R.string.ai_article_topic_too_long)
        } else {
            val message = failure.error.toUiMessage()
            stringResource(message.resourceId, *message.formatArgs.toTypedArray())
        }
    }
    is AiArticleGenerationFailure.Output -> stringResource(when (failure.reason) {
        AiGeneratedArticleRejection.NO_CONTENT -> R.string.settings_ai_error_no_content
        AiGeneratedArticleRejection.TRUNCATED_RESPONSE -> R.string.settings_ai_error_truncated
        AiGeneratedArticleRejection.MALFORMED_RESPONSE, AiGeneratedArticleRejection.INVALID_TITLE ->
            R.string.ai_article_invalid_output
        AiGeneratedArticleRejection.TITLE_TOO_LONG, AiGeneratedArticleRejection.CONTENT_TOO_LONG,
        AiGeneratedArticleRejection.TOO_MANY_PARAGRAPHS, AiGeneratedArticleRejection.PARAGRAPH_TOO_LONG ->
            R.string.ai_article_output_too_long
    })
}
