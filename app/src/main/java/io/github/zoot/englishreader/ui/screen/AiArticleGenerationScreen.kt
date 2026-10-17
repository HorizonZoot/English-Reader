package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import io.github.zoot.englishreader.data.ai.AiArticlePromptPolicy
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticleRejection
import io.github.zoot.englishreader.data.repository.AiArticleGenerationStatus
import io.github.zoot.englishreader.data.repository.AiArticleSaveStatus
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val draft = session.draft.parameters
    val preview = session.preview
    var profileMenuOpen by remember { mutableStateOf(false) }
    var regenerateResultId by remember { mutableStateOf<String?>(null) }
    val paragraphs = remember(preview?.resultId) {
        preview?.article?.content?.let(ParagraphAligner::splitParagraphs).orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_article_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .imePadding().testTag("ai_article_content"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            item(key = "form") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ai_article_cost_notice), style = MaterialTheme.typography.bodySmall)
                    Box {
                        OutlinedButton(
                            onClick = { profileMenuOpen = true },
                            enabled = !state.isBusy && state.profilesLoaded && state.profiles.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_article_profile")
                        ) {
                            Text(state.selectedProfile?.displayName ?: stringResource(R.string.ai_article_select_profile))
                        }
                        DropdownMenu(
                            expanded = profileMenuOpen && !state.isBusy,
                            onDismissRequest = { profileMenuOpen = false }
                        ) {
                            state.profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.displayName) },
                                    onClick = {
                                        profileMenuOpen = false
                                        onSelectProfile(profile.profileId)
                                    }
                                )
                            }
                        }
                    }
                    when {
                        !state.profilesLoaded -> Text(stringResource(R.string.ai_article_loading_profiles))
                        state.profilesLoadFailed -> {
                            Text(stringResource(R.string.ai_article_profiles_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onReloadProfiles, enabled = !state.isBusy) {
                                Text(stringResource(R.string.ai_article_reload_profiles))
                            }
                        }
                        state.selectedProfile == null -> Text(stringResource(R.string.ai_article_profile_required))
                    }
                    Text(stringResource(R.string.ai_article_profile_local), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenAiProfile) { Text(stringResource(R.string.ai_article_open_settings)) }
                    Text(stringResource(R.string.ai_article_difficulty), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiArticleDifficulty.entries.forEach { difficulty ->
                            FilterChip(
                                selected = draft.difficulty == difficulty,
                                onClick = { onDifficultyChange(difficulty) },
                                enabled = !state.isBusy,
                                label = { Text(stringResource(difficulty.labelResource())) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("ai_article_difficulty_${difficulty.name}")
                            )
                        }
                    }
                    Text(stringResource(draft.difficulty.descriptionResource()), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.ai_article_length), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiArticleLength.entries.forEach { length ->
                            FilterChip(
                                selected = draft.length == length,
                                onClick = { onLengthChange(length) },
                                enabled = !state.isBusy,
                                label = { Text(stringResource(length.labelResource())) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("ai_article_length_${length.name}")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.topic,
                        onValueChange = onTopicChange,
                        enabled = !state.isBusy,
                        label = { Text(stringResource(R.string.ai_article_topic)) },
                        placeholder = { Text(stringResource(R.string.ai_article_topic_example)) },
                        supportingText = {
                            Text(stringResource(R.string.ai_article_topic_count, draft.topic.length, AiArticlePromptPolicy.MAX_TOPIC_CHARS))
                        },
                        isError = state.topicTooLong,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("ai_article_topic")
                    )
                    if (state.topicTooLong) {
                        Text(stringResource(R.string.ai_article_topic_too_long), color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            R.string.ai_article_topic_daily,
                            R.string.ai_article_topic_travel,
                            R.string.ai_article_topic_work,
                            R.string.ai_article_topic_technology,
                            R.string.ai_article_topic_story
                        ).forEach { resource ->
                            val topic = stringResource(resource)
                            OutlinedButton(onClick = { onTopicChange(topic) }, enabled = !state.isBusy) { Text(topic) }
                        }
                        TextButton(onClick = { onTopicChange("") }, enabled = !state.isBusy) {
                            Text(stringResource(R.string.ai_article_clear_topic))
                        }
                    }
                    Text(stringResource(R.string.ai_article_topic_hint), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            if (preview == null) onGenerate() else regenerateResultId = preview.resultId
                        },
                        enabled = state.canGenerate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_article_generate")
                    ) {
                        Text(stringResource(if (preview == null) R.string.ai_article_generate else R.string.ai_article_regenerate))
                    }
                    if (session.isGenerating) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.ai_article_generating))
                    }
                    when (val status = session.status) {
                        is AiArticleGenerationStatus.Failed -> {
                            Text(generationFailureMessage(status.failure), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.ai_article_retry_cost), style = MaterialTheme.typography.bodySmall)
                        }
                        is AiArticleGenerationStatus.Cancelled -> Text(stringResource(R.string.ai_article_cancelled))
                        else -> Unit
                    }
                    Text(stringResource(R.string.ai_article_temporary_notice), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preview != null) {
                item(key = "preview_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("ai_article_preview")) {
                        Text(stringResource(R.string.ai_article_preview), style = MaterialTheme.typography.titleLarge)
                        Text(preview.article.title, style = MaterialTheme.typography.headlineSmall)
                        val parameters = preview.article.parameters
                        Text(
                            stringResource(
                                R.string.ai_article_preview_parameters,
                                stringResource(parameters.difficulty.labelResource()),
                                stringResource(parameters.length.labelResource()),
                                parameters.topic.ifBlank { stringResource(R.string.ai_article_topic_auto) }
                            ),
                            modifier = Modifier.testTag("ai_article_preview_parameters"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(stringResource(R.string.ai_article_word_count, preview.article.wordCount))
                        if (preview.article.isOutsideWordTarget) {
                            Text(stringResource(R.string.ai_article_word_deviation, parameters.length.targetWords))
                        }
                        Text(stringResource(R.string.ai_article_content_notice), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ai_article_temporary_notice), style = MaterialTheme.typography.bodySmall)
                    }
                }
                itemsIndexed(paragraphs, key = { index, _ -> "paragraph_$index" }) { _, paragraph ->
                    Text(paragraph, style = MaterialTheme.typography.bodyLarge)
                }
                item(key = "preview_actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (preview.saveStatus) {
                            AiArticleSaveStatus.Failed, AiArticleSaveStatus.Cancelled ->
                                Text(stringResource(R.string.ai_article_save_failed), color = MaterialTheme.colorScheme.error)
                            is AiArticleSaveStatus.Saved -> Text(stringResource(R.string.ai_article_saved))
                            else -> Unit
                        }
                        Button(
                            onClick = { onSaveAndRead(preview.resultId) },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_article_save")
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
                            modifier = Modifier.fillMaxWidth().testTag("ai_article_discard")
                        ) {
                            Text(stringResource(R.string.ai_article_discard))
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

private fun AiArticleDifficulty.labelResource(): Int = when (this) {
    AiArticleDifficulty.BEGINNER -> R.string.ai_article_beginner
    AiArticleDifficulty.INTERMEDIATE -> R.string.ai_article_intermediate
    AiArticleDifficulty.ADVANCED -> R.string.ai_article_advanced
}

private fun AiArticleDifficulty.descriptionResource(): Int = when (this) {
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
