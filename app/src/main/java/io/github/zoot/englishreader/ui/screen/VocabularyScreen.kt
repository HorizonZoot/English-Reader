package io.github.zoot.englishreader.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.ui.component.SortMenuButton
import io.github.zoot.englishreader.ui.component.messageRes
import io.github.zoot.englishreader.ui.screen.vocabulary.ContextCardsContent
import io.github.zoot.englishreader.ui.screen.vocabulary.ExcerptsContent
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.observeVocabularyTime
import io.github.zoot.englishreader.ui.screen.vocabulary.vocabularyTimeSignals
import io.github.zoot.englishreader.ui.theme.FavoritesUiTheme
import io.github.zoot.englishreader.viewmodel.VocabularyUiEvent
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel
import java.util.Locale

@Composable
fun VocabularyScreen(
    onOpenArticle: (articleId: Long, word: String) -> Unit,
    viewModel: VocabularyViewModel = hiltViewModel(),
    onOpenExcerpt: (articleId: Long, excerptId: Long) -> Unit = { _, _ -> },
    onStartContextReview: () -> Unit = {},
    onOpenContext: ((articleId: Long, contextCardId: Long) -> Unit)? = null,
    onStartStudy: (VocabularyStudyMode) -> Unit = { onStartContextReview() }
) = FavoritesUiTheme {
    val groups by viewModel.studyGroups.collectAsStateWithLifecycle()
    val excerptPlayback by viewModel.excerptPlayback.collectAsStateWithLifecycle()
    val excerpts by viewModel.excerpts.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val expandedExcerpts by viewModel.expandedExcerpts.collectAsStateWithLifecycle()
    val sortExpanded by viewModel.sortMenuExpanded.collectAsStateWithLifecycle()
    val showInfo by viewModel.showStudyInfo.collectAsStateWithLifecycle()
    val excerptMenuId by viewModel.excerptMenuId.collectAsStateWithLifecycle()
    val wordMenuKey by viewModel.wordMenuKey.collectAsStateWithLifecycle()
    val wordToDelete by viewModel.wordToDelete.collectAsStateWithLifecycle()
    val favoritesTime by viewModel.favoritesTime.collectAsStateWithLifecycle()
    val library by viewModel.contextLibrary.collectAsStateWithLifecycle()
    val openContext by rememberUpdatedState(onOpenContext)
    val openExcerpt by rememberUpdatedState(onOpenExcerpt)
    val details by viewModel.studyDetails.collectAsStateWithLifecycle()
    val groupType by viewModel.groupType.collectAsStateWithLifecycle()
    val loadingAudioKey by viewModel.loadingAudioKey.collectAsStateWithLifecycle()
    val sortedExcerpts = remember(excerpts, groupType) {
        if (groupType == GroupType.ByAlphabet) excerpts.sortedWith(
            compareBy<io.github.zoot.englishreader.data.entity.ExcerptEntity> { it.text.lowercase(Locale.ROOT) }.thenBy { it.id }
        ) else excerpts.sortedWith(compareByDescending<io.github.zoot.englishreader.data.entity.ExcerptEntity> { it.createdAt }.thenByDescending { it.id })
    }
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
                    VocabularyUiEvent.SortPreferenceFailed -> R.string.favorites_sort_save_failed
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
    val sortBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (selectedTab == 0) R.string.favorites_count_words else R.string.favorites_count_excerpts,
                    if (selectedTab == 0) library.words.size else excerpts.size),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val time = stringResource(R.string.favorites_sort_time)
            val alphabet = stringResource(R.string.favorites_sort_alphabet)
            SortMenuButton(
                label = if (groupType == GroupType.ByAlphabet) alphabet else time,
                options = listOf(GroupType.ByTime to time, GroupType.ByAlphabet to alphabet),
                selectedField = groupType, ascending = groupType == GroupType.ByAlphabet,
                expanded = sortExpanded, onExpandedChange = viewModel::setSortMenuExpanded,
                onFieldSelected = viewModel::switchGroupType, onAscendingSelected = {}, showDirection = false
            )
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VocabularyHeader(selectedTab, viewModel::selectTab) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (selectedTab == 1) {
                ExcerptsContent(
                    sortedExcerpts, onOpenSource = viewModel::openExcerpt, onDelete = viewModel::deleteExcerpt,
                    playback = excerptPlayback, onTogglePlayback = viewModel::toggleExcerptPlayback,
                    expandedIds = expandedExcerpts, onToggleExpanded = viewModel::toggleExcerptExpanded,
                    sortBar = sortBar, menuId = excerptMenuId, onMenuChange = viewModel::setExcerptMenu, time = favoritesTime
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
                    details = details, loadingAudioKey = loadingAudioKey,
                    groups = groups, onToggleGroup = viewModel::toggleGroup,
                    onShowInfo = { viewModel.setStudyInfoVisible(true) }, sortBar = sortBar,
                    deleting = wordToDelete, onDeleteConfirmation = viewModel::confirmWordDeletion,
                    menuKey = wordMenuKey, onMenuChange = viewModel::setWordMenu
                )
            }
        }
    }
    if (showInfo) AlertDialog(
        onDismissRequest = { viewModel.setStudyInfoVisible(false) },
        title = { Text(stringResource(R.string.favorites_study_info)) },
        text = { Text(stringResource(R.string.favorites_study_help)) },
        confirmButton = { TextButton(onClick = { viewModel.setStudyInfoVisible(false) }) { Text(stringResource(R.string.favorites_got_it)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VocabularyHeader(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.nav_vocabulary), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() })
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surface), space = 0.dp
                ) {
                    listOf(R.string.favorites_words, R.string.favorites_excerpts).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = selectedTab == index, onClick = { onTabSelected(index) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2, RoundedCornerShape(9.dp)),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                activeBorderColor = Color.Transparent, inactiveBorderColor = Color.Transparent
                            ), icon = {}, label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupHeader(title: String, wordCount: Int, isToday: Boolean, isExpanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (isExpanded) 0f else 180f, label = "group-chevron")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(if (isToday) R.string.favorites_group_words else R.string.favorites_group_letters, title, wordCount),
            Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Icon(Icons.Default.KeyboardArrowUp,
            stringResource(if (isExpanded) R.string.vocabulary_group_collapse else R.string.vocabulary_group_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp).rotate(rotation))
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
