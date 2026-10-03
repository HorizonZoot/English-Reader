package io.github.zoot.englishreader.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.ui.component.ContextSentence
import io.github.zoot.englishreader.ui.screen.vocabulary.observeVocabularyTime
import io.github.zoot.englishreader.ui.screen.vocabulary.vocabularyTimeSignals
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.viewmodel.ContextReviewEvent
import io.github.zoot.englishreader.viewmodel.ContextReviewUiState
import io.github.zoot.englishreader.viewmodel.ContextReviewViewModel

@Composable
fun ContextReviewScreen(
    onBack: () -> Unit,
    viewModel: ContextReviewViewModel = hiltViewModel(),
    onOpenContext: ((articleId: Long, contextCardId: Long) -> Unit)? = null
) = ArticleUiTheme {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val openContext by rememberUpdatedState(onOpenContext)
    val exitReview = {
        viewModel.cancelContextNavigation()
        onBack()
    }
    BackHandler(onBack = exitReview)
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.cancelContextNavigation()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.cancelContextNavigation()
        }
    }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.contextToOpen.collect { source ->
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.consumeContextSource(source)?.let { card ->
                        card.articleId?.let { articleId -> openContext?.invoke(articleId, card.id) }
                    }
                }
            }
        }
    }
    LaunchedEffect(context, lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeVocabularyTime(vocabularyTimeSignals(context, lifecycleOwner.lifecycle)).collect {
                viewModel.refreshDate()
            }
        }
    }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiEvent.collect { event ->
                snackbar.showSnackbar(context.getString(when (event) {
                    ContextReviewEvent.SAVE_FAILED -> R.string.context_review_save_failed
                    ContextReviewEvent.CARD_CHANGED -> R.string.context_card_changed
                    ContextReviewEvent.SOURCE_UNAVAILABLE -> R.string.context_source_unavailable
                }))
            }
        }
    }
    ContextReviewContent(
        state, snackbar, exitReview, viewModel::reveal, viewModel::grade, viewModel::startNextRound, viewModel::retry,
        onOpenSource = if (onOpenContext == null) null else viewModel::openContextSource
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextReviewContent(
    state: ContextReviewUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onReveal: (String) -> Unit,
    onGrade: (String, ContextGrade) -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onOpenSource: ((String) -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.context_review_title)) },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val round = state.round
            val entry = round?.current()
            LazyColumn(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("context-review"),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                when {
                    state.failed -> item {
                        Text(stringResource(R.string.context_load_failed))
                        Button(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.action_retry)) }
                    }
                    round == null -> item { CircularProgressIndicator() }
                    entry == null -> {
                        item {
                            Text(
                                stringResource(if (round.session.initialCount == 0) R.string.context_review_empty else R.string.context_review_complete),
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        if (round.session.initialCount > 0) {
                            item { Text(stringResource(R.string.context_review_summary, round.answeredCount())) }
                            item { Button(onClick = onNext, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.context_review_next))
                            } }
                        }
                        item { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.context_review_exit))
                        } }
                    }
                    else -> {
                        val card = entry.card
                        item {
                            Text(stringResource(R.string.context_review_progress, round.answeredCount(), round.session.initialCount))
                            if (entry.item.isRetry) Text(stringResource(R.string.context_review_practice), style = MaterialTheme.typography.labelLarge)
                        }
                        item { Text(stringResource(R.string.context_target_word, card.word), style = MaterialTheme.typography.titleLarge) }
                        item { ContextSentence(card.sentence, card.targetStartOffset, card.targetEndOffset, Modifier.testTag("context-review-sentence")) }
                        item {
                            Text(card.sourceTitle, style = MaterialTheme.typography.bodySmall)
                            if (card.articleId == null) Text(stringResource(R.string.context_source_deleted), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!entry.item.revealed) {
                            item { Text(stringResource(R.string.context_review_recall_hint)) }
                            item { Button(
                                onClick = { onReveal(entry.item.itemToken) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-review-reveal")
                            ) { Text(stringResource(R.string.context_review_reveal)) } }
                        } else {
                            item {
                                Text(stringResource(R.string.context_meaning_label), style = MaterialTheme.typography.labelLarge)
                                Text(entry.item.meaningSnapshot, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("context-review-answer"))
                            }
                            if (onOpenSource != null) {
                                item { TextButton(
                                    onClick = { onOpenSource(entry.item.itemToken) },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-review-source")
                                ) { Text(stringResource(R.string.vocabulary_open_article)) } }
                            }
                            item { Text(stringResource(R.string.context_review_grade_hint)) }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ContextGrade.entries.forEach { grade ->
                                        OutlinedButton(
                                            onClick = { onGrade(entry.item.itemToken, grade) },
                                            enabled = !state.busy,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-grade-${grade.name}")
                                        ) { Text(stringResource(when (grade) {
                                            ContextGrade.FORGOTTEN -> R.string.context_grade_forgotten
                                            ContextGrade.FUZZY -> R.string.context_grade_fuzzy
                                            ContextGrade.REMEMBERED -> R.string.context_grade_remembered
                                        })) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
