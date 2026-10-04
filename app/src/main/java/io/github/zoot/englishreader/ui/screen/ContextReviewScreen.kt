package io.github.zoot.englishreader.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.ui.component.ContextSentence
import io.github.zoot.englishreader.ui.screen.vocabulary.observeVocabularyTime
import io.github.zoot.englishreader.ui.screen.vocabulary.vocabularyTimeSignals
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.viewmodel.ContextReviewEvent
import io.github.zoot.englishreader.viewmodel.ContextReviewUiState
import io.github.zoot.englishreader.viewmodel.ContextReviewViewModel
import io.github.zoot.englishreader.viewmodel.StudyPronunciationDetails

@Composable
fun ContextReviewScreen(
    onBack: () -> Unit,
    viewModel: ContextReviewViewModel = hiltViewModel(),
    onOpenContext: ((articleId: Long, contextCardId: Long) -> Unit)? = null,
    onOpenArticle: ((articleId: Long, word: String) -> Unit)? = null
) = ArticleUiTheme {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pronunciationDetails by viewModel.pronunciationDetails.collectAsStateWithLifecycle()
    val loadingAudioKey by viewModel.loadingAudioKey.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val openContext by rememberUpdatedState(onOpenContext)
    val openArticle by rememberUpdatedState(onOpenArticle)
    val exitReview = {
        viewModel.cancelContextNavigation()
        viewModel.stopAudio()
        onBack()
    }
    BackHandler(onBack = exitReview)
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.cancelContextNavigation()
                viewModel.stopAudio()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.cancelContextNavigation()
            viewModel.stopAudio()
        }
    }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.contextToOpen.collect { source ->
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.consumeContextSource(source)?.let { word ->
                        word.articleId?.let { articleId ->
                            when (word) {
                                is StudyWord.Context -> openContext?.invoke(articleId, word.card.id)
                                is StudyWord.Legacy -> openArticle?.invoke(articleId, word.word)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(context, lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeVocabularyTime(vocabularyTimeSignals(context, lifecycleOwner.lifecycle)).collect { viewModel.refreshDate() }
        }
    }
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiEvent.collect { event ->
                snackbar.showSnackbar(context.getString(when (event) {
                    ContextReviewEvent.SAVE_FAILED -> R.string.context_review_save_failed
                    ContextReviewEvent.CARD_CHANGED -> R.string.context_card_changed
                    ContextReviewEvent.SOURCE_UNAVAILABLE -> R.string.context_source_unavailable
                    ContextReviewEvent.AUDIO_UNAVAILABLE -> R.string.tts_unavailable
                }))
            }
        }
    }
    ContextReviewContent(
        state, snackbar, exitReview, viewModel::reveal, viewModel::grade, viewModel::startNextRound, viewModel::retry,
        onOpenSource = if (onOpenContext == null && onOpenArticle == null) null else viewModel::openContextSource,
        pronunciationDetails = pronunciationDetails,
        loadingAudioKey = loadingAudioKey,
        onPlayAudio = viewModel::playWordAudio,
        onSkipMissingAnswer = viewModel::skipMissingAnswer
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
    onOpenSource: ((String) -> Unit)? = null,
    pronunciationDetails: StudyPronunciationDetails = StudyPronunciationDetails(),
    loadingAudioKey: String? = null,
    onPlayAudio: (String) -> Unit = {},
    onSkipMissingAnswer: (String) -> Unit = {}
) {
    val round = state.round
    val mode = round?.session?.mode ?: VocabularyStudyMode.DUE
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (mode == VocabularyStudyMode.DUE) R.string.context_review_title else R.string.study_title)) },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val entry = round?.current()
            val word = entry?.studyWord()
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
                    entry == null || word == null -> {
                        item {
                            Text(stringResource(when {
                                round.session.initialCount > 0 -> R.string.context_review_complete
                                mode == VocabularyStudyMode.LEARN -> R.string.study_words_empty
                                else -> R.string.context_review_empty
                            }), style = MaterialTheme.typography.headlineSmall)
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
                        item {
                            Text(stringResource(R.string.context_review_progress, round.answeredCount(), round.session.initialCount))
                            if (entry.item.isRetry) Text(stringResource(R.string.context_review_practice), style = MaterialTheme.typography.labelLarge)
                            else if (!entry.item.updatesSchedule) Text(stringResource(R.string.study_early_practice), style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(word.word, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif, modifier = Modifier.testTag("study-review-word"))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (pronunciationDetails.itemToken == entry.item.itemToken && !pronunciationDetails.phonetic.isNullOrBlank()) {
                                        Text(pronunciationDetails.phonetic, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleMedium)
                                    }
                                    IconButton(
                                        onClick = { onPlayAudio(entry.item.itemToken) }, enabled = !state.busy,
                                        modifier = Modifier.size(48.dp).testTag("study-review-audio")
                                    ) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.word_play_pronunciation, word.word)) }
                                    if (loadingAudioKey == word.key) CircularProgressIndicator(Modifier.size(16.dp))
                                }
                                if (pronunciationDetails.itemToken == entry.item.itemToken) {
                                    pronunciationDetails.headword?.let {
                                        Text(stringResource(R.string.vocabulary_word_headword, it), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (pronunciationDetails.itemToken == entry.item.itemToken && pronunciationDetails.failed) {
                                    Text(stringResource(R.string.study_dictionary_failed), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (word is StudyWord.Context) {
                            item { ContextSentence(word.card.sentence, word.card.targetStartOffset, word.card.targetEndOffset, Modifier.testTag("context-review-sentence")) }
                        }
                        if (!entry.item.revealed) {
                            item { Text(stringResource(R.string.study_recall_hint)) }
                            item { Button(
                                onClick = { onReveal(entry.item.itemToken) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-review-reveal")
                            ) { Text(stringResource(R.string.context_review_reveal)) } }
                        } else {
                            val hasAnswer = entry.item.meaningSnapshot.isNotBlank()
                            item {
                                Text(stringResource(R.string.study_meaning_label), style = MaterialTheme.typography.labelLarge)
                                Text(entry.item.meaningSnapshot.ifBlank { stringResource(R.string.study_answer_missing) },
                                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("context-review-answer"))
                            }
                            if (!hasAnswer) item { TextButton(
                                onClick = { onSkipMissingAnswer(entry.item.itemToken) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("study-review-skip")
                            ) { Text(stringResource(R.string.study_skip_missing_answer)) } }
                            word.sourceTitle?.takeIf(String::isNotBlank)?.let { title -> item { Text(title, style = MaterialTheme.typography.bodySmall) } }
                            if (word.articleId == null) item { Text(stringResource(R.string.study_source_missing), style = MaterialTheme.typography.bodySmall) }
                            if (onOpenSource != null && word.articleId != null) {
                                item { TextButton(
                                    onClick = { onOpenSource(entry.item.itemToken) }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-review-source")
                                ) { Text(stringResource(R.string.vocabulary_open_article)) } }
                            }
                            item { Text(stringResource(R.string.context_review_grade_hint)) }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ContextGrade.entries.forEach { grade ->
                                        OutlinedButton(
                                            onClick = { onGrade(entry.item.itemToken, grade) }, enabled = !state.busy && hasAnswer,
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
