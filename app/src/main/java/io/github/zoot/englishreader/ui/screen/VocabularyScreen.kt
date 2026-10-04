package io.github.zoot.englishreader.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.ui.component.messageRes
import io.github.zoot.englishreader.ui.screen.vocabulary.ContextCardsContent
import io.github.zoot.englishreader.ui.screen.vocabulary.ExcerptsContent
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.observeVocabularyTime
import io.github.zoot.englishreader.ui.screen.vocabulary.vocabularyTimeSignals
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.LocalSegmentedControlColors
import io.github.zoot.englishreader.ui.theme.NeutralIconGray
import io.github.zoot.englishreader.ui.theme.SegmentedControlColors
import io.github.zoot.englishreader.viewmodel.VocabularyUiEvent
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel

@Composable
fun VocabularyScreen(
    onOpenArticle: (articleId: Long, word: String) -> Unit,
    viewModel: VocabularyViewModel = hiltViewModel(),
    onOpenExcerpt: (articleId: Long, excerptId: Long) -> Unit = { _, _ -> },
    onStartContextReview: () -> Unit = {},
    onOpenContext: ((articleId: Long, contextCardId: Long) -> Unit)? = null,
    onStartStudy: (VocabularyStudyMode) -> Unit = { onStartContextReview() }
) = ArticleUiTheme {
    val groups by viewModel.studyGroups.collectAsStateWithLifecycle()
    val excerptPlayback by viewModel.excerptPlayback.collectAsStateWithLifecycle()
    val excerpts by viewModel.excerpts.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val library by viewModel.contextLibrary.collectAsStateWithLifecycle()
    val openContext by rememberUpdatedState(onOpenContext)
    val openExcerpt by rememberUpdatedState(onOpenExcerpt)
    val details by viewModel.studyDetails.collectAsStateWithLifecycle()
    val groupType by viewModel.groupType.collectAsStateWithLifecycle()
    val loadingAudioKey by viewModel.loadingAudioKey.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, context, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeVocabularyTime(vocabularyTimeSignals(context, lifecycleOwner.lifecycle)).collect(viewModel::updateGroupingTime)
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopAudio()
                viewModel.cancelExcerptNavigation()
                viewModel.cancelContextNavigation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAudio()
            viewModel.cancelExcerptNavigation()
            viewModel.cancelContextNavigation()
        }
    }
    LaunchedEffect(viewModel, lifecycleOwner, selectedTab) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (selectedTab == 1) {
                viewModel.excerptToOpen.collect { excerpt -> excerpt.articleId?.let { openExcerpt(it, excerpt.id) } }
            } else {
                viewModel.contextToOpen.collect { card -> card.articleId?.let { openContext?.invoke(it, card.id) } }
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is VocabularyUiEvent.ExcerptDeleted -> {
                    val result = snackbar.showSnackbar(context.getString(R.string.excerpt_deleted), context.getString(R.string.vocabulary_undo))
                    if (result == SnackbarResult.ActionPerformed) viewModel.restoreExcerpt(event.excerpt)
                }
                is VocabularyUiEvent.Deleted -> {
                    val result = snackbar.showSnackbar(
                        context.getString(R.string.vocabulary_deleted, event.vocabulary.word), context.getString(R.string.vocabulary_undo)
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restoreVocabulary(event.vocabulary)
                }
                else -> snackbar.showSnackbar(context.getString(when (event) {
                    VocabularyUiEvent.DictionaryLoadFailed -> R.string.study_dictionary_failed
                    VocabularyUiEvent.ContextDeleted -> R.string.study_deleted
                    VocabularyUiEvent.ContextDeleteFailed -> R.string.context_delete_failed
                    VocabularyUiEvent.ContextSourceUnavailable -> R.string.context_source_unavailable
                    is VocabularyUiEvent.ExcerptAudioFailed -> event.reason.messageRes()
                    VocabularyUiEvent.ExcerptLoadFailed -> R.string.excerpt_load_failed
                    VocabularyUiEvent.ExcerptDeleteFailed -> R.string.excerpt_delete_failed
                    VocabularyUiEvent.ExcerptRestoreFailed -> R.string.excerpt_restore_failed
                    VocabularyUiEvent.ExcerptSourceUnavailable -> R.string.excerpt_source_deleted
                    VocabularyUiEvent.DeleteFailed -> R.string.vocabulary_delete_failed
                    VocabularyUiEvent.RestoreFailed -> R.string.vocabulary_restore_failed
                    VocabularyUiEvent.AudioUnavailable -> R.string.tts_unavailable
                    is VocabularyUiEvent.Deleted, is VocabularyUiEvent.ExcerptDeleted -> error("Handled above")
                }))
            }
        }
    }
    val startStudy: (VocabularyStudyMode) -> Unit = { mode ->
        viewModel.stopAudio()
        viewModel.cancelContextNavigation()
        onStartStudy(mode)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VocabularyHeader(library.words.size, groupType, viewModel::switchGroupType, selectedTab) {
                viewModel.cancelExcerptNavigation()
                viewModel.cancelContextNavigation()
                viewModel.stopAudio()
                selectedTab = it
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (selectedTab == 1) {
                ExcerptsContent(
                    excerpts, onOpenSource = viewModel::openExcerpt, onDelete = viewModel::deleteExcerpt,
                    playback = excerptPlayback, onTogglePlayback = viewModel::toggleExcerptPlayback
                )
            } else {
                ContextCardsContent(
                    library = library,
                    onReview = { startStudy(VocabularyStudyMode.DUE) },
                    onLearn = { startStudy(VocabularyStudyMode.LEARN) },
                    onPlayAudio = viewModel::playStudyWordAudio,
                    onDelete = viewModel::deleteStudyWord,
                    onRetry = viewModel::retryContextLibrary,
                    onOpenSource = { word ->
                        viewModel.stopAudio()
                        when (word) {
                            is StudyWord.Context -> if (onOpenContext != null) viewModel.openContextSource(word.card)
                            is StudyWord.Legacy -> word.articleId?.let { onOpenArticle(it, word.word) }
                        }
                    },
                    details = details,
                    loadingAudioKey = loadingAudioKey,
                    groups = groups,
                    onToggleGroup = viewModel::toggleGroup
                )
            }
        }
    }
}

@Composable
private fun VocabularyHeader(
    wordCount: Int,
    groupType: GroupType,
    onTypeSelected: (GroupType) -> Unit,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.nav_vocabulary), style = MaterialTheme.typography.headlineLarge,
                fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp,
                modifier = Modifier.semantics { heading() }
            )
            TabRow(selectedTabIndex = selectedTab) {
                listOf(R.string.favorites_words, R.string.favorites_excerpts).forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { onTabSelected(index) }, text = { Text(stringResource(label)) })
                }
            }
            if (selectedTab == 0 && wordCount > 0) {
                Text(stringResource(R.string.vocabulary_total_count, wordCount), style = MaterialTheme.typography.bodyLarge)
                GroupTypeSegments(groupType, onTypeSelected, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GroupTypeSegments(selectedType: GroupType, onTypeSelected: (GroupType) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSegmentedControlColors.current
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(colors.track)
            .padding(2.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SegmentButton(stringResource(R.string.vocabulary_group_by_time), selectedType == GroupType.ByTime,
            { onTypeSelected(GroupType.ByTime) }, colors, Modifier.weight(1f))
        SegmentButton(stringResource(R.string.vocabulary_group_by_alphabet), selectedType == GroupType.ByAlphabet,
            { onTypeSelected(GroupType.ByAlphabet) }, colors, Modifier.weight(1f))
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit, colors: SegmentedControlColors, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.heightIn(min = 48.dp).then(if (selected) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape).background(if (selected) colors.thumb else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GroupHeader(title: String, wordCount: Int, isToday: Boolean, isExpanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (isExpanded) 0f else 180f, label = "group-chevron")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(stringResource(if (isToday) R.string.vocabulary_group_words_new else R.string.vocabulary_group_words, wordCount),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.KeyboardArrowUp,
            stringResource(if (isExpanded) R.string.vocabulary_group_collapse else R.string.vocabulary_group_expand),
            tint = NeutralIconGray, modifier = Modifier.size(20.dp).rotate(rotation))
    }
}

@Composable
internal fun vocabularyGroupTitle(id: VocabularyGroupId): String = when (id) {
    VocabularyGroupId.Today -> stringResource(R.string.vocabulary_group_today)
    VocabularyGroupId.Yesterday -> stringResource(R.string.vocabulary_group_yesterday)
    VocabularyGroupId.ThisWeek -> stringResource(R.string.vocabulary_group_this_week)
    is VocabularyGroupId.OlderDate -> id.value
    is VocabularyGroupId.Alphabet -> id.value
}
