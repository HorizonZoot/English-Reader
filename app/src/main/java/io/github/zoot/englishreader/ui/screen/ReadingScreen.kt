package io.github.zoot.englishreader.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingTtsFailure
import io.github.zoot.englishreader.model.ReadingTtsPhase
import io.github.zoot.englishreader.model.ReadingTtsState
import io.github.zoot.englishreader.model.SelectedSentence
import io.github.zoot.englishreader.ui.component.PagedReadingContent
import io.github.zoot.englishreader.ui.component.ReadingPageControls
import io.github.zoot.englishreader.ui.component.ReadingBlockKey
import io.github.zoot.englishreader.ui.component.ReadingLayoutKey
import io.github.zoot.englishreader.ui.component.ReadingTextViewport
import io.github.zoot.englishreader.ui.component.ScrollReadingContent
import io.github.zoot.englishreader.ui.component.recordLayout
import io.github.zoot.englishreader.ui.component.rememberReadingTextBlocks
import io.github.zoot.englishreader.ui.component.ReadingAppearanceSheet
import io.github.zoot.englishreader.ui.component.ReadingVoiceSettingsSheet
import io.github.zoot.englishreader.ui.component.WholeTranslationSheet
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.ui.component.InteractiveText
import io.github.zoot.englishreader.ui.component.InteractiveTextLongPressTarget
import io.github.zoot.englishreader.ui.component.SentenceActionAnchor
import io.github.zoot.englishreader.ui.component.SentenceActionPopup
import io.github.zoot.englishreader.ui.component.SentencePopupMode
import io.github.zoot.englishreader.ui.component.SentencePopupSafeInsets
import io.github.zoot.englishreader.ui.component.sentencePopupSafeBounds
import io.github.zoot.englishreader.ui.component.WordDetailsBottomSheet
import io.github.zoot.englishreader.ui.component.ReadingTtsControls
import io.github.zoot.englishreader.ui.component.TtsRecoveryDialog
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.util.SentenceSplitter
import io.github.zoot.englishreader.viewmodel.DictionaryErrorType
import io.github.zoot.englishreader.viewmodel.ChapterContext
import io.github.zoot.englishreader.viewmodel.ReadingError
import io.github.zoot.englishreader.viewmodel.ReadingViewModel
import io.github.zoot.englishreader.viewmodel.VocabularySaveResult
import kotlin.math.roundToInt

/** 阅读页的路由层：负责收集状态并接线各类副作用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    articleId: Long,
    onBack: () -> Unit,
    /**
     * 打开当前章节所属书的目录。
     *
     * 独立文章不属于任何书，所以这个回调只会从章节导航栏触发，而那条栏在
     * `chapterContext == null` 时整条不出现。`bookId` 取自 [ChapterContext]，
     * 不需要额外的数据流。
     */
    onOpenToc: (Long) -> Unit = {},
    viewModel: ReadingViewModel = hiltViewModel()
) {
    val article by viewModel.article.collectAsStateWithLifecycle()
    val selectedSentence by viewModel.selectedSentence.collectAsStateWithLifecycle()
    val selectedWord by viewModel.selectedWord.collectAsStateWithLifecycle()
    val wordDefinition by viewModel.wordDefinition.collectAsStateWithLifecycle()
    val isLoadingDefinition by viewModel.isLoadingDefinition.collectAsStateWithLifecycle()
    val isLoadingAudio by viewModel.isLoadingAudio.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingArticle.collectAsStateWithLifecycle()
    val vocabularyWords by viewModel.vocabularyWords.collectAsStateWithLifecycle()
    val fontSizeOption by viewModel.fontSizeOption.collectAsStateWithLifecycle()
    val themeOption by viewModel.themeOption.collectAsStateWithLifecycle()
    val aiSheetState by viewModel.aiSheetState.collectAsStateWithLifecycle()
    val sentenceTranslationState by viewModel.sentenceTranslationState.collectAsStateWithLifecycle()
    val chapterContext by viewModel.chapterContext.collectAsStateWithLifecycle()
    val pendingPositionTarget by viewModel.pendingPositionTarget.collectAsStateWithLifecycle()
    val highlightedParagraph by viewModel.highlightedParagraph.collectAsStateWithLifecycle()
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle()
    val ttsState by viewModel.readingTtsState.collectAsStateWithLifecycle()
    val voiceSettings by viewModel.voiceSettings.collectAsStateWithLifecycle()
    val wholeTranslationState by viewModel.wholeTranslationState.collectAsStateWithLifecycle()
    val voiceSample = stringResource(R.string.reading_voice_sample)
    val ttsPositionTarget by viewModel.ttsPositionTarget.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showTranslation by rememberSaveable { mutableStateOf(false) }
    var showReadingSettings by rememberSaveable { mutableStateOf(false) }
    var wordSheetTopOnScreen by remember(articleId) { mutableStateOf<Float?>(null) }
    var ttsFailure by remember(viewModel) { mutableStateOf<ReadingTtsFailure?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshTtsCapability()
                Lifecycle.Event.ON_STOP -> viewModel.releaseTts()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releaseTts()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.ttsFailures.collect { failure -> ttsFailure = failure }
    }

    LaunchedEffect(articleId) {
        viewModel.openReadingSession(articleId)
    }

    LaunchedEffect(wordDefinition) {
        if (wordDefinition == null) wordSheetTopOnScreen = null
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.dismissSentenceActions() }
    }

    LaunchedEffect(Unit) {
        viewModel.vocabularySaved.collect { result ->
            val message = when (result) {
                VocabularySaveResult.SAVED -> context.getString(R.string.vocabulary_saved)
                VocabularySaveResult.ALREADY_EXISTS -> context.getString(R.string.word_already_saved)
                VocabularySaveResult.FAILED -> context.getString(R.string.vocabulary_save_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.definitionError.collect { errorType ->
            val message = when (errorType) {
                DictionaryErrorType.WORD_NOT_FOUND -> context.getString(R.string.word_not_found)
                DictionaryErrorType.NETWORK_ERROR -> context.getString(
                    R.string.word_lookup_failed,
                    context.getString(R.string.error_network)
                )
                DictionaryErrorType.UNKNOWN_ERROR -> context.getString(
                    R.string.word_lookup_failed,
                    context.getString(R.string.error_unknown)
                )
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ttsUnavailable.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.tts_unavailable))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.readingErrors.collect { error ->
            snackbarHostState.showSnackbar(context.getString(when (error) {
                ReadingError.LOAD -> R.string.reading_load_failed
                ReadingError.SAVE_POSITION -> R.string.reading_position_save_failed
                ReadingError.SAVE_PREFERENCE -> R.string.reading_preference_save_failed
            }))
        }
    }

    ArticleUiTheme {
        if (wholeTranslationState != WholeTranslationSheetState.Hidden) {
            WholeTranslationSheet(
                state = wholeTranslationState,
                onDismiss = viewModel::dismissWholeTranslation,
                onSelectScope = viewModel::selectWholeTranslationScope,
                onStart = viewModel::startWholeTranslation,
                onResume = viewModel::resumeWholeTranslation,
                onRetryFailed = viewModel::retryFailedWholeTranslation,
                onCancelTask = viewModel::cancelWholeTranslation
            )
        }
        if (voiceSettings.isOpen) {
            ReadingVoiceSettingsSheet(
                state = voiceSettings,
                onDismiss = viewModel::closeVoiceSettings,
                onVoiceChange = viewModel::setReadingVoice,
                onRateChange = viewModel::setReadingSpeechRate,
                onNetworkAllowedChange = viewModel::setReadingNetworkVoiceAllowed,
                onPreview = { viewModel.previewReadingVoice(voiceSample) },
                onStopPreview = viewModel::stopVoicePreview,
                onReset = viewModel::resetReadingVoiceSettings,
                onRecheck = viewModel::recheckVoiceSettings,
                onSystemAction = viewModel::openVoiceSettingsSystemAction
            )
        }
        ttsFailure?.takeIf {
            it.requestId == ttsState.requestId && ttsState.phase == ReadingTtsPhase.FAILED
        }?.let { failure ->
            TtsRecoveryDialog(
                failure = failure,
                onRetry = { allowNetworkOnce ->
                    ttsFailure = null
                    viewModel.retryReadingTts(failure.requestId, allowNetworkOnce)
                },
                onSystemAction = { action ->
                    ttsFailure = null
                    viewModel.openTtsSystemAction(failure.requestId, action)
                },
                onDismiss = { ttsFailure = null; viewModel.stopAudio() }
            )
        }
        wordDefinition?.let { definition ->
            WordDetailsBottomSheet(
                word = definition.word,
                phonetic = definition.phonetic,
                chineseDefinitions = definition.chineseDefinitions,
                englishDefinitions = definition.englishDefinitions,
                inflectedForm = definition.inflectedForm,
                alternates = definition.alternates,
                onDismiss = { viewModel.clearWordDefinition() },
                onAddToVocabulary = { form -> viewModel.saveVocabulary(form) },
                onPlayAudio = {
                    viewModel.playWordAudio(definition.inflectedForm ?: definition.word, definition.audioUrl)
                },
                isLoadingAudio = isLoadingAudio,
                isInVocabulary = { form -> vocabularyWords.contains(form.lowercase()) },
                onVisibleTopChanged = { top -> wordSheetTopOnScreen = top }
            )
        }

        ReadingScreenContent(
            article = article,
            selectedSentence = selectedSentence,
            selectedWord = selectedWord,
            wordDetailsVisible = wordDefinition != null,
            wordSheetTopOnScreen = wordSheetTopOnScreen,
            isLoadingDefinition = isLoadingDefinition,
            isLoading = isLoading,
            fontSizeOption = fontSizeOption,
            themeOption = themeOption,
            showTranslation = showTranslation,
            snackbarHostState = snackbarHostState,
            sentenceTranslationState = sentenceTranslationState,
            sentenceExplanationState = aiSheetState,
            onToggleTranslation = { showTranslation = !showTranslation },
            onBack = {
                viewModel.dismissSentenceActions()
                onBack()
            },
            onExplainSentence = viewModel::explainSelectedSentence,
            onCancelExplanation = {
                val visible = aiSheetState as? AiSheetState.Visible
                if (visible != null) viewModel.cancelAiOperation(visible.attachment.operationRef)
                else viewModel.dismissAiSheet()
            },
            onSentenceSelected = viewModel::selectSentence,
            onWordLongPress = viewModel::lookupWord,
            onWordPressStart = viewModel::beginWordSelection,
            onWordPressRelease = viewModel::lookupWord,
            onClearSelection = viewModel::clearSelection,
            // 保留给 TalkBack 的句子动作；指针长按已专用于查词。
            onSentenceAccessibilityTarget = { _, target ->
                viewModel.selectSentence(article?.id ?: articleId, target.sentenceIndex, target.sentenceRange)
            },
            // 普通点击已经由 onSentenceSelected 完成选中。
            // 这个回调只负责提供 UI 锚点，因此绝不能再提交第二次选中。
            onSentenceTapTarget = { _, _ -> },
            onPlaySentence = { viewModel.playSelectedSentence() },
            onTranslateSentence = viewModel::translateSelectedSentence,
            onWholeTranslation = viewModel::openWholeTranslation,
            onCancelTranslation = {
                val visible = sentenceTranslationState as? AiSheetState.Visible
                if (visible != null) viewModel.cancelSentenceTranslation(visible.attachment.operationRef)
                else viewModel.dismissSentenceTranslation()
            },
            onRetryTranslation = viewModel::retrySelectedSentenceTranslation,
            onDismissSentencePopup = viewModel::dismissSentencePopup,
            showReadingSettings = showReadingSettings,
            onToggleReadingSettings = { showReadingSettings = !showReadingSettings },
            onOpenVoiceSettings = { showReadingSettings = false; viewModel.openVoiceSettings() },
            onFontSizeChange = viewModel::setFontSizeOption,
            onThemeChange = viewModel::setThemeOption,
            chapterContext = chapterContext,
            readingMode = readingMode,
            onReadingModeChange = viewModel::setReadingMode,
            pendingPositionTarget = pendingPositionTarget,
            onPositionTargetConsumed = viewModel::consumePositionTarget,
            highlightedParagraph = highlightedParagraph,
            onSaveReadingPosition = viewModel::saveReadingPosition,
            ttsState = ttsState,
            ttsPositionTarget = ttsPositionTarget,
            onTtsPositioned = viewModel::consumeTtsPositionTarget,
            onStartContinuousReading = viewModel::startContinuousReading,
            onPauseTts = viewModel::pauseReadingTts,
            onResumeTts = viewModel::resumeReadingTts,
            onPreviousTtsSentence = viewModel::previousTtsSentence,
            onNextTtsSentence = viewModel::nextTtsSentence,
            onStopTts = viewModel::stopAudio,
            // 切章走 loadArticle 而不是 navigate：navigate 会在返回栈上堆叠章节，
            // 读完 20 章后按 20 次返回才能回到目录。原地换文章让返回键始终回目录。
            onNavigateChapter = { targetArticleId ->
                viewModel.loadArticle(targetArticleId)
            },
            onNavigatePageBoundary = viewModel::loadArticle,
            onOpenToc = onOpenToc
        )
    }
}

/** 无状态的生产 Scaffold，供路由层和 Compose 入口测试共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreenContent(
    article: ArticleEntity?,
    selectedSentence: SelectedSentence?,
    selectedWord: String?,
    wordDetailsVisible: Boolean = false,
    wordSheetTopOnScreen: Float? = null,
    isLoadingDefinition: Boolean,
    isLoading: Boolean,
    fontSizeOption: FontSizeOption,
    themeOption: ThemeOption = ThemeOption.DEFAULT,
    showTranslation: Boolean,
    snackbarHostState: SnackbarHostState,
    sentenceTranslationState: AiSheetState = AiSheetState.Hidden,
    sentenceExplanationState: AiSheetState = AiSheetState.Hidden,
    onToggleTranslation: () -> Unit,
    onBack: () -> Unit,
    onExplainSentence: () -> Unit,
    onCancelExplanation: () -> Unit = {},
    onSentenceSelected: (Long, Int, SentenceRange) -> Unit,
    onWordLongPress: (String) -> Unit,
    onWordPressStart: ((String) -> Unit)? = null,
    onWordPressRelease: ((String) -> Unit)? = null,
    onClearSelection: () -> Unit,
    onSentenceAccessibilityTarget: ((Int, InteractiveTextLongPressTarget) -> Unit)? = null,
    onSentenceTapTarget: ((Int, InteractiveTextLongPressTarget) -> Unit)? = null,
    onPlaySentence: (() -> Unit)? = null,
    onTranslateSentence: (() -> Unit)? = null,
    onWholeTranslation: (() -> Unit)? = null,
    onCancelTranslation: () -> Unit = {},
    onRetryTranslation: () -> Unit = {},
    onDismissSentencePopup: () -> Unit = {},
    showReadingSettings: Boolean = false,
    onToggleReadingSettings: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
    onFontSizeChange: (FontSizeOption) -> Unit = {},
    onThemeChange: (ThemeOption) -> Unit = {},
    chapterContext: ChapterContext? = null,
    readingMode: ReadingMode = ReadingMode.DEFAULT,
    onReadingModeChange: (ReadingMode) -> Unit = {},
    pendingPositionTarget: ReadingPositionTarget? = null,
    onPositionTargetConsumed: (ReadingPositionTarget) -> Unit = {},
    /** 从生词本跳转过来时要临时点亮的段落序号；null 表示没有。 */
    highlightedParagraph: Int? = null,
    onSaveReadingPosition: (ReadingPosition) -> Unit = {},
    onNavigateChapter: (Long) -> Unit = {},
    onNavigatePageBoundary: (Long, ReadingEntry) -> Unit = { id, _ -> onNavigateChapter(id) },
    /** 打开目录。默认空实现，因为独立文章没有目录可开。 */
    onOpenToc: (Long) -> Unit = {},
    ttsState: ReadingTtsState = ReadingTtsState(),
    ttsPositionTarget: ReadingPositionTarget? = null,
    onTtsPositioned: (ReadingPositionTarget) -> Unit = {},
    onStartContinuousReading: (Long, List<ParagraphAligner.AlignedParagraph>, ReadingAnchor) -> Unit = { _, _, _ -> },
    onPauseTts: () -> Unit = {},
    onResumeTts: () -> Unit = {},
    onPreviousTtsSentence: () -> Unit = {},
    onNextTtsSentence: () -> Unit = {},
    onStopTts: () -> Unit = {}
) {
    val hasTranslation = !article?.translation.isNullOrBlank()
    val currentSelection = selectedSentence?.takeIf { it.articleId == article?.id }
    val paragraphs = remember(article?.content, article?.translation) {
        article?.let {
            ParagraphAligner.align(it.content, it.translation, SentenceSplitter::split)
        }.orEmpty()
    }
    var readingAnchor by rememberSaveable(article?.id, stateSaver = ReadingAnchorSaver) {
        mutableStateOf(ReadingAnchor(textKind = ReadingTextKind.TITLE))
    }
    var sentenceActionAnchor by remember(article?.id) { mutableStateOf<SentenceActionAnchor?>(null) }
    var popupMode by remember(article?.id) { mutableStateOf(SentencePopupMode.ACTIONS) }
    var sentencePopupHeightPx by remember(article?.id) { mutableIntStateOf(0) }
    var sentencePopupAutoScrollConsumed by remember(article?.id) { mutableStateOf(false) }
    var wordSelectionAnchor by remember(article?.id) { mutableStateOf<WordSelectionAnchor?>(null) }
    var wordSheetCorrectionConsumed by remember(article?.id) { mutableStateOf(false) }
    var readingProgress by remember(article?.id) { mutableFloatStateOf(0f) }
    var pageControls by remember(article?.id) { mutableStateOf<ReadingPageControls?>(null) }
    var readingViewportBounds by remember { mutableStateOf<Rect?>(null) }
    var readingViewportBottomOnScreen by remember { mutableStateOf<Float?>(null) }
    var readingWindowSize by remember { mutableStateOf(IntSize.Zero) }
    var readingViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val paragraphBounds = remember(article?.id) { mutableMapOf<Int, Rect>() }
    val currentSentenceActionAnchor by rememberUpdatedState(sentenceActionAnchor)
    fun dismissSentencePopup() {
        if (sentenceActionAnchor == null) return
        sentenceActionAnchor = null
        popupMode = SentencePopupMode.ACTIONS
        sentencePopupHeightPx = 0
        sentencePopupAutoScrollConsumed = false
        onDismissSentencePopup()
    }
    val currentDismissSentencePopup by rememberUpdatedState(::dismissSentencePopup)
    val readingView = LocalView.current
    val readingViewLocation = remember(readingView) { IntArray(2) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = SentencePopupSafeInsets(
        left = WindowInsets.safeDrawing.getLeft(density, layoutDirection),
        top = WindowInsets.safeDrawing.getTop(density),
        right = WindowInsets.safeDrawing.getRight(density, layoutDirection),
        bottom = WindowInsets.safeDrawing.getBottom(density)
    )
    val readingSafeBounds = readingViewportBounds?.let {
        sentencePopupSafeBounds(readingWindowSize, safeInsets, it)
    }
    val popupGapPx = with(density) { 8.dp.roundToPx() }
    val wordSheetObstructionHeightPx = readingViewportBottomOnScreen?.let { bottom ->
        wordSheetTopOnScreen?.takeIf { wordDetailsVisible }?.let { top ->
            (bottom - top).coerceIn(0f, readingViewportBounds?.height ?: 0f).roundToInt()
        }
    } ?: 0

    LaunchedEffect(selectedWord) {
        if (selectedWord == null) wordSelectionAnchor = null
        wordSheetCorrectionConsumed = false
    }

    LaunchedEffect(currentSelection) {
        val anchor = sentenceActionAnchor
        if (anchor != null && (currentSelection == null || !anchor.target.matches(currentSelection))) {
            sentenceActionAnchor = null
            onDismissSentencePopup()
        }
    }

    LaunchedEffect(showReadingSettings, wordDetailsVisible) {
        if (showReadingSettings || wordDetailsVisible) dismissSentencePopup()
    }

    LaunchedEffect(ttsState.requestId, ttsState.phase) {
        if (ttsState.phase == ReadingTtsPhase.FAILED) {
            sentenceActionAnchor = null
            popupMode = SentencePopupMode.ACTIONS
            sentencePopupHeightPx = 0
        }
    }

    if (showReadingSettings) {
        ReadingAppearanceSheet(
            currentFontSize = fontSizeOption,
            currentTheme = themeOption,
            hasTranslation = hasTranslation,
            showTranslation = showTranslation,
            onDismiss = onToggleReadingSettings,
            onFontSizeChange = onFontSizeChange,
            onThemeChange = onThemeChange,
            onToggleTranslation = onToggleTranslation,
            currentReadingMode = readingMode,
            onReadingModeChange = onReadingModeChange
        )
    }

    Scaffold(
        modifier = Modifier
            .testTag("reading-screen")
            .onGloballyPositioned { screenOrigin = it.positionInWindow() }
            .pointerInput(article?.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val owner = currentSentenceActionAnchor?.paragraphIndex ?: return@awaitEachGesture
                    val bounds = paragraphBounds[owner]
                    if (bounds == null || !bounds.contains(down.position + screenOrigin)) {
                        currentDismissSentencePopup()
                        return@awaitEachGesture
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                            event.changes.any { it.id != down.id && it.pressed }
                        ) {
                            currentDismissSentencePopup()
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { dismissSentencePopup(); onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            dismissSentencePopup()
                            article?.let { onStartContinuousReading(it.id, paragraphs, readingAnchor) }
                        },
                        enabled = article != null && !isLoading && paragraphs.isNotEmpty() && pendingPositionTarget == null,
                        modifier = Modifier.size(48.dp).testTag("reading-tts-start")
                    ) {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.reading_tts_start))
                    }
                    IconButton(
                        onClick = {
                            // The ViewModel pauses speech; dismissing the popup through its stop
                            // callback here would discard the continuous-reading position.
                            sentenceActionAnchor = null
                            popupMode = SentencePopupMode.ACTIONS
                            onOpenVoiceSettings()
                        },
                        modifier = Modifier.size(48.dp).testTag("reading-voice-settings")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.reading_voice_settings))
                    }
                    val appearanceDescription = stringResource(R.string.reading_settings)
                    FilledTonalIconButton(
                        onClick = {
                            dismissSentencePopup()
                            onToggleReadingSettings()
                        },
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(48.dp)
                            .semantics { contentDescription = appearanceDescription }
                    ) {
                        Text(
                            text = stringResource(R.string.reading_appearance_sample),
                            fontFamily = FontFamily.Serif,
                            fontSize = 22.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                if (ttsState.continuous && ttsState.phase != ReadingTtsPhase.IDLE) {
                    ReadingTtsControls(
                        ttsState, onPauseTts, onResumeTts, onPreviousTtsSentence, onNextTtsSentence, onStopTts
                    )
                }
                if (article != null && readingMode == ReadingMode.PAGED) {
                    ReadingPageNavigationBar(pageControls)
                } else if (article != null && !isLoading) {
                    val readingProgressLabel = stringResource(R.string.reading_progress)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { readingProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .semantics { contentDescription = readingProgressLabel },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.reading_progress_percent,
                                (readingProgress * 100).roundToInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
                chapterContext?.let { context ->
                    ChapterNavigationBar(
                        context = context,
                        onNavigateChapter = { dismissSentencePopup(); onNavigateChapter(it) },
                        onOpenToc = { dismissSentencePopup(); onOpenToc(context.bookId) },
                        enabled = !isLoading
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("reading-content")
                .onGloballyPositioned { coordinates ->
                    readingViewportBounds = coordinates.boundsInWindow()
                    readingViewportSize = coordinates.size
                    readingWindowSize = coordinates.findRootCoordinates().size
                    readingView.getLocationOnScreen(readingViewLocation)
                    readingViewportBottomOnScreen = coordinates.positionInRoot().y +
                        coordinates.size.height + readingViewLocation[1]
                }
        ) {
            when {
                isLoading && article == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                article != null -> {
                    val currentArticle = article
                    val highlightedIndex = if (ttsState.phase in listOf(
                        ReadingTtsPhase.PREPARING, ReadingTtsPhase.PLAYING, ReadingTtsPhase.PAUSED
                    )) ttsState.sentenceIndex else currentSelection?.sentenceIndex
                    if (paragraphs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.article_empty_content),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        val listState = rememberLazyListState()
                        val paragraphItemOffset = 1
                        val layouts = remember(currentArticle.id) { mutableStateMapOf<ReadingBlockKey, TextLayoutResult>() }
                        val blocks = rememberReadingTextBlocks(currentArticle, paragraphs, fontSizeOption, showTranslation)
                        val layoutKey = ReadingLayoutKey(
                            viewport = readingViewportSize,
                            fontSize = fontSizeOption,
                            density = density.density,
                            fontScale = density.fontScale,
                            showTranslation = showTranslation
                        )
                        var readingLayoutReady by remember(layoutKey, readingMode) { mutableStateOf(false) }
                        val selectionAnchor = sentenceActionAnchor?.takeIf { currentSelection != null }?.let {
                            ReadingAnchor(it.paragraphIndex, ReadingTextKind.ORIGINAL, it.target.glyphOffset ?: it.target.sentenceRange.startOffset)
                        } ?: wordSelectionAnchor?.takeIf { selectedWord != null }?.let {
                            ReadingAnchor(it.paragraphIndex, ReadingTextKind.ORIGINAL, it.glyphOffset)
                        }
                        val speechTarget = ttsPositionTarget?.takeIf { it.position.articleId == currentArticle.id }
                        val restoreTarget = speechTarget ?: pendingPositionTarget?.takeIf { it.position.articleId == currentArticle.id }
                        val restorePosition: (ReadingAnchor, ReadingPositionTarget?) -> Unit = { anchor, target ->
                            readingAnchor = anchor
                            if (target != null) {
                                if (target == speechTarget) onTtsPositioned(target)
                                else onPositionTargetConsumed(target)
                            }
                            readingLayoutReady = true
                            onSaveReadingPosition(ReadingPosition(currentArticle.id, anchor))
                        }
                        val savePosition: (ReadingAnchor) -> Unit = { anchor ->
                            onSaveReadingPosition(ReadingPosition(currentArticle.id, anchor))
                        }
                        LaunchedEffect(listState, sentenceActionAnchor, readingMode, layoutKey, readingLayoutReady) {
                            if (readingMode != ReadingMode.SCROLL) return@LaunchedEffect
                            val owner = sentenceActionAnchor?.paragraphIndex ?: return@LaunchedEffect
                            snapshotFlow {
                                listState.layoutInfo.visibleItemsInfo.any { it.index == owner + 1 }
                            }.collect { visible ->
                                if (!visible && readingLayoutReady && sentenceActionAnchor?.paragraphIndex == owner) {
                                    sentenceActionAnchor = null
                                    onDismissSentencePopup()
                                }
                            }
                        }
                        val renderOriginal: @Composable (Int, ReadingTextViewport?, Boolean) -> Unit =
                            { paragraphIndex, visibleViewport, interactiveEnabled ->
                                val paragraph = paragraphs[paragraphIndex]
                                // 生词本跳过来的那一段临时点亮。强度交给 InteractiveText 内部
                                // 与高亮色相乘，所以这里不需要知道高亮色是什么。
                                // 用动画而非布尔：到期熄灭时是渐隐，硬切会像页面闪了一下。
                                val paragraphHighlight by animateFloatAsState(
                                    targetValue = if (paragraphIndex == highlightedParagraph) 1f else 0f,
                                    label = "paragraph-highlight"
                                )
                                val ownsResultPopup = popupMode != SentencePopupMode.ACTIONS &&
                                    sentenceActionAnchor?.paragraphIndex == paragraphIndex
                                if (readingMode == ReadingMode.SCROLL && readingLayoutReady && ownsResultPopup) {
                                    LaunchedEffect(
                                        sentenceActionAnchor,
                                        popupMode,
                                        sentencePopupHeightPx,
                                        readingViewportBounds,
                                        readingSafeBounds
                                    ) {
                                        val anchor = sentenceActionAnchor ?: return@LaunchedEffect
                                        withFrameNanos { }
                                        val viewport = readingViewportBounds ?: return@LaunchedEffect
                                        val safeBounds = readingSafeBounds ?: return@LaunchedEffect
                                        val layoutInfo = listState.layoutInfo
                                        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull {
                                            it.index == paragraphIndex + paragraphItemOffset
                                        } ?: return@LaunchedEffect
                                        val delta = sentencePopupSafeScrollDelta(
                                            itemTop = itemInfo.offset,
                                            sentenceBounds = anchor.target.sentenceBounds,
                                            viewportStart = layoutInfo.viewportStartOffset +
                                                (safeBounds.top - viewport.top).roundToInt(),
                                            viewportEnd = layoutInfo.viewportStartOffset +
                                                (safeBounds.bottom - viewport.top).roundToInt(),
                                            popupHeightPx = sentencePopupHeightPx,
                                            gapPx = popupGapPx,
                                            preferAbove = false
                                        )
                                        runSentencePopupScrollCorrectionOnce(
                                            correctionConsumed = sentencePopupAutoScrollConsumed,
                                            delta = delta,
                                            markConsumed = { sentencePopupAutoScrollConsumed = true },
                                            scroll = { measuredDelta ->
                                                listState.scrollBy(measuredDelta)
                                            }
                                        )
                                    }
                                }
                                DisposableEffect(paragraphIndex, interactiveEnabled) {
                                    onDispose { if (interactiveEnabled) paragraphBounds.remove(paragraphIndex) }
                                }
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 600.dp)
                                        .fillMaxWidth()
                                        .onGloballyPositioned {
                                            if (interactiveEnabled) paragraphBounds[paragraphIndex] = it.boundsInWindow()
                                        }
                                ) {
                                    val selectedWordAnchor = wordSelectionAnchor
                                        ?.takeIf { it.paragraphIndex == paragraphIndex && (visibleViewport == null || visibleViewport.contains(it.glyphOffset)) }
                                    val speakingDescription = if (ttsState.phase == ReadingTtsPhase.PLAYING &&
                                        ttsState.sentenceIndex in paragraph.sentenceOffset until
                                        paragraph.sentenceOffset + paragraph.sentences.size
                                    ) stringResource(R.string.reading_tts_current_sentence, ttsState.sentenceIndex + 1) else null
                                    InteractiveText(
                                        text = paragraph.english,
                                        fontSize = fontSizeOption.sizeSp.sp,
                                        fontFamily = FontFamily.Serif,
                                        visibleViewport = visibleViewport,
                                        enabled = interactiveEnabled,
                                        onTextLayout = { layout ->
                                            if (visibleViewport == null) layouts.recordLayout(ReadingBlockKey(paragraphIndex, ReadingTextKind.ORIGINAL), layout)
                                        },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            if (speakingDescription != null) stateDescription = speakingDescription
                                        },
                                        highlightedSentenceIndex = highlightedIndex,
                                        selectedWord = selectedWord.takeIf {
                                            wordSelectionAnchor == null || selectedWordAnchor != null
                                        },
                                        selectedWordStartOffset = selectedWordAnchor?.startOffset,
                                        selectedWordEndOffset = selectedWordAnchor?.endOffset,
                                        sentenceIndexOffset = paragraph.sentenceOffset,
                                        precomputedSentences = paragraph.sentences,
                                        paragraphHighlight = paragraphHighlight,
                                        selectedSentenceTarget = sentenceActionAnchor
                                            ?.takeIf {
                                                it.paragraphIndex == paragraphIndex && (visibleViewport == null ||
                                                    visibleViewport.contains(it.target.glyphOffset ?: it.target.sentenceRange.startOffset))
                                            }
                                            ?.target,
                                        onSentenceTargetLayoutChanged = { target ->
                                            val anchor = sentenceActionAnchor
                                            if (anchor?.paragraphIndex == paragraphIndex &&
                                                anchor.target.sentenceIndex == target.sentenceIndex &&
                                                anchor.target.sentenceRange == target.sentenceRange
                                            ) {
                                                sentenceActionAnchor = anchor.copy(target = target)
                                            }
                                        },
                                        onSentenceClick = { globalIndex, range ->
                                            // InteractiveText 会先发布新的 UI 锚点。
                                            // 若锚点属于本次同一次点击就保留，否则关掉上一句的弹层。
                                            val anchorBelongsToTap = sentenceActionAnchor?.let { anchor ->
                                                anchor.paragraphIndex == paragraphIndex &&
                                                    anchor.target.sentenceIndex == globalIndex &&
                                                    anchor.target.sentenceRange == range
                                            } == true
                                            if (!anchorBelongsToTap) {
                                                sentenceActionAnchor = null
                                                onDismissSentencePopup()
                                            }
                                            onSentenceSelected(currentArticle.id, globalIndex, range)
                                        },
                                        onWordLongPress = onWordLongPress,
                                        onWordPressStart = onWordPressStart?.let { begin ->
                                            { target ->
                                                val word = target.word
                                                val startOffset = target.wordStartOffset
                                                val endOffset = target.wordEndOffset
                                                if (word != null && startOffset != null && endOffset != null) {
                                                    wordSelectionAnchor = WordSelectionAnchor(
                                                        paragraphIndex = paragraphIndex,
                                                        startOffset = startOffset,
                                                        endOffset = endOffset,
                                                        anchorBounds = target.anchorBounds,
                                                        glyphOffset = target.glyphOffset ?: startOffset
                                                    )
                                                    wordSheetCorrectionConsumed = false
                                                    sentenceActionAnchor = null
                                                    popupMode = SentencePopupMode.ACTIONS
                                                    onDismissSentencePopup()
                                                    begin(word)
                                                }
                                            }
                                        },
                                        onWordPressRelease = onWordPressRelease?.let { release ->
                                            { target -> target.word?.let(release) }
                                        },
                                        onClearSelection = {
                                            sentenceActionAnchor = null
                                            onDismissSentencePopup()
                                            onClearSelection()
                                        },
                                        onSentenceTapTarget = onSentenceTapTarget?.let { callback ->
                                            { target ->
                                                val currentAnchor = sentenceActionAnchor
                                                val sameSentence = currentAnchor?.let { anchor ->
                                                    anchor.paragraphIndex == paragraphIndex &&
                                                        anchor.target.sentenceIndex == target.sentenceIndex &&
                                                        anchor.target.sentenceRange == target.sentenceRange
                                                } == true
                                                if (!sameSentence) {
                                                    sentenceActionAnchor = SentenceActionAnchor(paragraphIndex, target)
                                                    sentencePopupHeightPx = 0
                                                    sentencePopupAutoScrollConsumed = false
                                                    popupMode = SentencePopupMode.ACTIONS
                                                }
                                                callback(paragraphIndex, target)
                                            }
                                        },
                                        onSentencePlay = onSentenceAccessibilityTarget?.let { select ->
                                            onPlaySentence?.let { play ->
                                                { target ->
                                                    sentenceActionAnchor = SentenceActionAnchor(paragraphIndex, target)
                                                    sentencePopupHeightPx = 0
                                                    sentencePopupAutoScrollConsumed = false
                                                    popupMode = SentencePopupMode.ACTIONS
                                                    select(paragraphIndex, target)
                                                    play()
                                                }
                                            }
                                        },
                                        onSentenceTranslate = onSentenceAccessibilityTarget?.let { select ->
                                            onTranslateSentence?.let { translate ->
                                                { target ->
                                                    sentenceActionAnchor = SentenceActionAnchor(paragraphIndex, target)
                                                    sentencePopupHeightPx = 0
                                                    sentencePopupAutoScrollConsumed = false
                                                    popupMode = SentencePopupMode.TRANSLATION
                                                    select(paragraphIndex, target)
                                                    translate()
                                                }
                                            }
                                        },
                                        onSentenceExplain = onSentenceAccessibilityTarget?.let { select ->
                                            { target ->
                                                sentenceActionAnchor = SentenceActionAnchor(paragraphIndex, target)
                                                sentencePopupHeightPx = 0
                                                sentencePopupAutoScrollConsumed = false
                                                popupMode = SentencePopupMode.EXPLANATION
                                                select(paragraphIndex, target)
                                                onExplainSentence()
                                            }
                                        }
                                    )

                                    if (readingMode == ReadingMode.SCROLL && readingLayoutReady && wordDetailsVisible && selectedWordAnchor != null) {
                                        LaunchedEffect(
                                            wordSheetObstructionHeightPx,
                                            selectedWordAnchor
                                        ) {
                                            withFrameNanos { }
                                            val layoutInfo = listState.layoutInfo
                                            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull {
                                                it.index == paragraphIndex + 1
                                            } ?: return@LaunchedEffect
                                            val delta = wordSheetSafeScrollDelta(
                                                itemTop = itemInfo.offset,
                                                wordBounds = selectedWordAnchor.anchorBounds,
                                                viewportStart = layoutInfo.viewportStartOffset,
                                                viewportEnd = layoutInfo.viewportEndOffset,
                                                obstructionHeightPx = wordSheetObstructionHeightPx,
                                                gapPx = popupGapPx
                                            )
                                            runWordSheetScrollCorrectionOnce(
                                                correctionConsumed = wordSheetCorrectionConsumed,
                                                delta = delta,
                                                markConsumed = { wordSheetCorrectionConsumed = true },
                                                scroll = listState::scrollBy
                                            )
                                        }
                                    }

                                    val anchor = sentenceActionAnchor
                                    if (!showReadingSettings && !wordDetailsVisible && anchor != null &&
                                        anchor.paragraphIndex == paragraphIndex &&
                                        currentSelection != null &&
                                        (readingMode == ReadingMode.SCROLL || interactiveEnabled) &&
                                        (visibleViewport == null || visibleViewport.contains(anchor.target.glyphOffset ?: anchor.target.sentenceRange.startOffset)) &&
                                        anchor.target.matches(currentSelection)
                                    ) {
                                        SentenceActionPopup(
                                            target = anchor.target,
                                            mode = popupMode,
                                            translationState = sentenceTranslationState.forSelection(
                                                currentSelection
                                            ),
                                            explanationState = sentenceExplanationState.forSelection(
                                                currentSelection
                                            ),
                                            readingViewportBounds = readingViewportBounds,
                                            onPlay = { onPlaySentence?.invoke() },
                                            onTranslate = {
                                                sentencePopupHeightPx = 0
                                                sentencePopupAutoScrollConsumed = false
                                                popupMode = SentencePopupMode.TRANSLATION
                                                onTranslateSentence?.invoke()
                                            },
                                            onExplain = {
                                                sentencePopupHeightPx = 0
                                                sentencePopupAutoScrollConsumed = false
                                                popupMode = SentencePopupMode.EXPLANATION
                                                onExplainSentence()
                                            },
                                            onWholeTranslation = onWholeTranslation,
                                            onCancelExplanation = onCancelExplanation,
                                            onCancelTranslation = onCancelTranslation,
                                            onRetryTranslation = onRetryTranslation,
                                            onDismiss = {
                                                sentenceActionAnchor = null
                                                sentencePopupHeightPx = 0
                                                onDismissSentencePopup()
                                            },
                                            onPopupSizeChanged = { size ->
                                                if (popupMode != SentencePopupMode.ACTIONS &&
                                                    sentencePopupHeightPx != size.height
                                                ) {
                                                    sentencePopupHeightPx = size.height
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        if (readingMode == ReadingMode.SCROLL) {
                            ScrollReadingContent(
                                paragraphs = paragraphs,
                                blocks = blocks,
                                listState = listState,
                                layouts = layouts,
                                layoutKey = layoutKey,
                                anchor = readingAnchor,
                                selectedAnchor = selectionAnchor,
                                target = restoreTarget,
                                enabled = !isLoading && !showReadingSettings,
                                bottomInset = with(density) { if (wordDetailsVisible) wordSheetObstructionHeightPx.toDp() else 0.dp },
                                onRestored = restorePosition,
                                onPositionChanged = { readingAnchor = it },
                                onPositionSettled = savePosition,
                                onProgressChanged = { readingProgress = it },
                                originalContent = { index, active -> renderOriginal(index, null, active) }
                            )
                        } else {
                            PagedReadingContent(
                                blocks = blocks,
                                anchor = readingAnchor,
                                selectedAnchor = selectionAnchor,
                                target = restoreTarget,
                                enabled = !isLoading && !showReadingSettings,
                                hasPreviousChapter = chapterContext?.previousArticleId != null,
                                hasNextChapter = chapterContext?.nextArticleId != null,
                                onBoundary = { forward ->
                                    val nextArticleId = if (forward) chapterContext?.nextArticleId else chapterContext?.previousArticleId
                                    if (nextArticleId != null) {
                                        onNavigatePageBoundary(nextArticleId, if (forward) ReadingEntry.START else ReadingEntry.END)
                                    }
                                },
                                onRestored = restorePosition,
                                onPageChanged = { anchor ->
                                    sentenceActionAnchor = null
                                    wordSelectionAnchor = null
                                    popupMode = SentencePopupMode.ACTIONS
                                    onClearSelection()
                                    readingAnchor = anchor
                                    savePosition(anchor)
                                },
                                onControlsChanged = { pageControls = it },
                                onModeChange = onReadingModeChange,
                                originalContent = { index, viewport, active -> renderOriginal(index, viewport, active) }
                            )
                        }
                    }
                }
                else -> Text(
                    text = stringResource(R.string.article_not_found),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (isLoadingDefinition) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
            }
        }
    }
}

internal fun AiSheetState.forSelection(selection: SelectedSentence): AiSheetState {
    val target = when (this) {
        is AiSheetState.Loading -> target
        is AiSheetState.Visible -> target
        is AiSheetState.Rejected -> target
        AiSheetState.Hidden -> return AiSheetState.Hidden
    }
    val snapshot = (target as? AiExplanationTarget.Sentence)?.snapshot
        ?: return AiSheetState.Hidden
    val matches = snapshot.articleId == selection.articleId &&
        snapshot.sentenceIndex == selection.sentenceIndex &&
        snapshot.startOffset == selection.startOffset &&
        snapshot.endOffset == selection.endOffset &&
        snapshot.normalizedText == selection.normalizedText
    return if (matches) this else AiSheetState.Hidden
}

private fun InteractiveTextLongPressTarget.matches(selection: SelectedSentence): Boolean =
    sentenceIndex == selection.sentenceIndex &&
        sentenceRange.startOffset == selection.startOffset &&
        sentenceRange.endOffset == selection.endOffset &&
        sentenceRange.text == selection.rawText

private data class WordSelectionAnchor(
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val anchorBounds: Rect,
    val glyphOffset: Int
)

/** 返回把已测量的单词所在行顶到 BottomSheet 上方所需的最小上滚量。 */
internal fun wordSheetSafeScrollDelta(
    itemTop: Int,
    wordBounds: Rect,
    viewportStart: Int,
    viewportEnd: Int,
    obstructionHeightPx: Int,
    gapPx: Int = 0
): Float {
    if (obstructionHeightPx <= 0 || viewportEnd <= viewportStart) return 0f
    val unobstructedEnd = (viewportEnd - obstructionHeightPx).coerceAtLeast(viewportStart)
    if (unobstructedEnd - viewportStart < wordBounds.height + gapPx) return 0f
    return (itemTop + wordBounds.bottom + gapPx - unobstructedEnd).coerceAtLeast(0f)
}

/** 对当前单词快照，最多只消费一次已测量的 word sheet 滚动校正。 */
internal suspend fun runWordSheetScrollCorrectionOnce(
    correctionConsumed: Boolean,
    delta: Float,
    markConsumed: () -> Unit,
    scroll: suspend (Float) -> Unit
) {
    if (correctionConsumed || delta <= 0f) return
    markConsumed()
    scroll(delta)
}

/**
 * 当结果窗口在选句上下两侧都放不下时，返回一个最小校正量。
 * 校正量只依据已测量的实际边界计算。
 *
 * 只要弹层在上方或下方任一侧能放得下，句子就留在用户原本看到的位置不动。
 * 这样才不会让每次翻译都显得句子被强行固定到某个槽位上。
 */
internal fun sentencePopupSafeScrollDelta(
    itemTop: Int,
    sentenceBounds: Rect,
    viewportStart: Int,
    viewportEnd: Int,
    popupHeightPx: Int,
    gapPx: Int = 8,
    preferAbove: Boolean = false
): Float {
    if (popupHeightPx <= 0) return 0f
    val anchorTop = itemTop + sentenceBounds.top
    val anchorBottom = itemTop + sentenceBounds.bottom
    if (viewportEnd <= viewportStart) return 0f
    // 整句和结果无法同时容纳时交给 Popup 钳制，避免把所属段落滚出视口后误关闭。
    if (sentenceBounds.height + gapPx + popupHeightPx > viewportEnd - viewportStart) return 0f

    val belowOverflow = anchorBottom + gapPx + popupHeightPx - viewportEnd
    val aboveOverflow = viewportStart - (anchorTop - gapPx - popupHeightPx)
    if (belowOverflow <= 0f || aboveOverflow <= 0f) return 0f

    // 正值滚动把内容往上推、在下方腾空间；负值往下推、在上方腾空间。
    // 取两者中较小的校正量，而不是把句子钉死在视口的某个固定比例位置。
    return when {
        belowOverflow < aboveOverflow -> belowOverflow
        aboveOverflow < belowOverflow -> -aboveOverflow
        preferAbove -> -aboveOverflow
        else -> belowOverflow
    }
}

/**
 * 在当前弹层的整个生命周期内，最多只执行一次已测量的滚动校正。
 * 所有权在挂起的滚动动作之前就被消费掉，因此重新测量或取消都无法
 * 再拿第二个 delta 重启这段位移。
 */
internal suspend fun runSentencePopupScrollCorrectionOnce(
    correctionConsumed: Boolean,
    delta: Float,
    markConsumed: () -> Unit,
    scroll: suspend (Float) -> Unit
) {
    if (correctionConsumed || delta == 0f) return
    markConsumed()
    scroll(delta)
}

/**
 * 底部章节导航栏。
 *
 * 中间显示「第 N 章 / 共 M 章」：一本 500 章的书里，用户需要知道自己在哪，
 * 否则上下章按钮只是两个没有方位感的箭头。
 *
 * 首末章禁用对应按钮而非隐藏：按钮位置固定，用户不会因为按钮消失而误触另一个。
 */
@Composable
private fun ChapterNavigationBar(
    context: ChapterContext,
    onNavigateChapter: (Long) -> Unit,
    onOpenToc: () -> Unit,
    enabled: Boolean = true
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { context.previousArticleId?.let(onNavigateChapter) },
                enabled = enabled && context.previousArticleId != null
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateBefore,
                    contentDescription = stringResource(R.string.chapter_previous)
                )
            }
            // 「第 N/M 章」是打开目录的入口。此前它是纯 Text —— 用户点它没有任何反应，
            // 而那个位置在任何电子书阅读器里都是章节选择器，所以「点了没反应」读起来像坏了。
            // 目录页 (BookTocScreen) 一直存在，只是阅读页没有通往它的入口。
            //
            // 用 TextButton 而不是给 Text 加 clickable：前者自带 44dp 触摸目标与按压反馈，
            // 后者点击区域只有文字本身，在这个 4dp padding 的栏里会非常难点中。
            TextButton(
                onClick = onOpenToc,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.chapter_position,
                        context.chapterIndex + 1,
                        context.chapterCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
            }
            IconButton(
                onClick = { context.nextArticleId?.let(onNavigateChapter) },
                enabled = enabled && context.nextArticleId != null
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = stringResource(R.string.chapter_next)
                )
            }
        }
    }
}

private val ReadingAnchorSaver = listSaver<ReadingAnchor, Any>(
    save = { listOf(it.paragraphIndex, it.textKind.name, it.characterOffset) },
    restore = { ReadingAnchor(it[0] as Int, ReadingTextKind.fromName(it[1] as String), it[2] as Int) }
)

@Composable
private fun ReadingPageNavigationBar(controls: ReadingPageControls?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { controls?.previous?.invoke() },
            enabled = controls?.canPrevious == true,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.reading_page_previous))
        }
        Text(
            text = if (controls == null) stringResource(R.string.reading_paginating)
                else stringResource(R.string.reading_page_position, controls.page + 1, controls.count),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { controls?.next?.invoke() },
            enabled = controls?.canNext == true,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.reading_page_next))
        }
    }
}
