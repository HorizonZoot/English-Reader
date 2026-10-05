package io.github.zoot.englishreader.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.zoot.englishreader.ui.theme.FavoritesUiTheme
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
) = FavoritesUiTheme {
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
        pronunciationDetails = pronunciationDetails, loadingAudioKey = loadingAudioKey,
        onPlayAudio = viewModel::playWordAudio, onSkipMissingAnswer = viewModel::skipMissingAnswer
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
    val entry = round?.current()
    val word = entry?.studyWord()
    val completedCount = round?.entries?.count { it.item.grade != null || it.item.skipped } ?: 0
    val totalCount = round?.entries?.size ?: 0
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.study_title), style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null)
                        Text(stringResource(R.string.nav_back))
                    }
                }
            )
        },
        bottomBar = {
            if (!state.failed && entry != null && word != null) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            if (!entry.item.revealed) {
                                Button(onClick = { onReveal(entry.item.itemToken) }, enabled = !state.busy,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("context-review-reveal")) {
                                    Text(stringResource(R.string.context_review_reveal))
                                }
                            } else if (entry.item.meaningSnapshot.isBlank()) {
                                Button(onClick = { onSkipMissingAnswer(entry.item.itemToken) }, enabled = !state.busy,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("study-review-skip")) {
                                    Text(stringResource(R.string.study_skip_missing_answer))
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ContextGrade.entries.forEach { grade ->
                                        Button(onClick = { onGrade(entry.item.itemToken, grade) }, enabled = !state.busy,
                                            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (grade == ContextGrade.FORGOTTEN) MaterialTheme.colorScheme.errorContainer
                                                    else MaterialTheme.colorScheme.surface,
                                                contentColor = if (grade == ContextGrade.FORGOTTEN) MaterialTheme.colorScheme.error
                                                    else MaterialTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("context-grade-${grade.name}")) {
                                            Text(stringResource(when (grade) {
                                                ContextGrade.FORGOTTEN -> R.string.context_grade_forgotten
                                                ContextGrade.FUZZY -> R.string.context_grade_fuzzy
                                                ContextGrade.REMEMBERED -> R.string.context_grade_remembered
                                                ContextGrade.FAMILIAR -> R.string.context_grade_familiar
                                            }), style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("context-review"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
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
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LinearProgressIndicator(
                                        progress = completedCount.toFloat() / totalCount.coerceAtLeast(1),
                                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Text(stringResource(R.string.favorites_study_progress, completedCount, totalCount),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (entry.item.isRetry || !entry.item.updatesSchedule) Surface(
                                    shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface
                                ) {
                                    Text(stringResource(if (entry.item.isRetry) R.string.context_review_practice else R.string.study_early_practice),
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(word.word, style = MaterialTheme.typography.displaySmall, fontSize = 44.sp, fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Serif, modifier = Modifier.testTag("study-review-word"))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (pronunciationDetails.itemToken == entry.item.itemToken && !pronunciationDetails.phonetic.isNullOrBlank()) {
                                        Text(pronunciationDetails.phonetic, style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { onPlayAudio(entry.item.itemToken) }, enabled = !state.busy,
                                        modifier = Modifier.size(48.dp).testTag("study-review-audio")) {
                                        if (loadingAudioKey == word.key) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.word_play_pronunciation, word.word),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (pronunciationDetails.itemToken == entry.item.itemToken) {
                                    pronunciationDetails.headword?.let {
                                        Text(stringResource(R.string.vocabulary_word_headword, it), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (pronunciationDetails.failed) Text(stringResource(R.string.study_dictionary_failed),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (word is StudyWord.Context) item {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                                ContextSentence(word.card.sentence, word.card.targetStartOffset, word.card.targetEndOffset,
                                    Modifier.fillMaxWidth().padding(16.dp).testTag("context-review-sentence"))
                            }
                        }
                        if (!entry.item.revealed) item {
                            Text(stringResource(R.string.study_recall_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            item {
                                Text(stringResource(R.string.study_meaning_label), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(entry.item.meaningSnapshot.ifBlank { stringResource(R.string.study_answer_missing) },
                                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("context-review-answer"))
                            }
                            word.sourceTitle?.takeIf(String::isNotBlank)?.let { title -> item {
                                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            if (word.articleId == null) item { Text(stringResource(R.string.study_source_missing), style = MaterialTheme.typography.bodySmall) }
                            if (onOpenSource != null && word.articleId != null) item {
                                TextButton(onClick = { onOpenSource(entry.item.itemToken) }, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("context-review-source")) {
                                    Text(stringResource(R.string.vocabulary_open_article))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
