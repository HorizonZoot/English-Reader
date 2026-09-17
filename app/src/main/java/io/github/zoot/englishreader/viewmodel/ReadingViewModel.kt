package io.github.zoot.englishreader.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import io.github.zoot.englishreader.data.repository.AiExplanationStartResult
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.data.repository.VocabularyInsertResult
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.WholeTranslationRepository
import io.github.zoot.englishreader.data.repository.WholeTranslationStartResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.ScopeOption
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationScopeChoice
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiExplanationTextNormalizer
import io.github.zoot.englishreader.model.AiSheetRequestToken
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingTtsFailure
import io.github.zoot.englishreader.model.ReadingTtsPhase
import io.github.zoot.englishreader.model.ReadingTtsState
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.model.ReadingVoiceSettingsState
import io.github.zoot.englishreader.model.SelectedSentence
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsPlaybackResult
import io.github.zoot.englishreader.util.TtsVoiceSnapshot
import io.github.zoot.englishreader.util.failureReason
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.data.audio.WordAudioUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject

/**
 * 单词定义数据类（支持双语）
 */
data class WordDefinition(
    val word: String,
    val phonetic: String?,
    val chineseDefinitions: List<String>,  // 中文释义（优先显示）
    val englishDefinitions: List<String>,  // 英文释义（补充）
    val audioUrl: String? = null,
    val source: DefinitionSource = DefinitionSource.OFFLINE,
    // 非空表示 word（原形）是从该变形词还原来的（如 word="live", inflectedForm="lives"）；
    // 直接命中或在线结果时为 null。界面据此标注"xxx 的原形"。
    val inflectedForm: String? = null,
    // 歧义变形词（lives/leaves）的其余合法原形释义，与主释义并列展示；非歧义词为空。
    val alternates: List<AlternateDefinition> = emptyList()
)

/**
 * 歧义变形词的备选原形释义（如长按 lives 时，除 live 外并列展示的 life）。
 */
data class AlternateDefinition(
    val word: String,
    val phonetic: String?,
    val chineseDefinitions: List<String>,
    val englishDefinitions: List<String>
)

/**
 * 释义来源
 */
enum class DefinitionSource {
    OFFLINE,  // 离线词典
    ONLINE    // Free Dictionary API
}

private const val MAX_DEFINITIONS = 5
private const val READING_ROUTE_ARTICLE = "reading_route_article"
private const val READING_CURRENT_ARTICLE = "reading_current_article"

/**
 * 生词本「查看原文」带的词。既是导航参数名，也是 SavedStateHandle 的键。
 *
 * 只在 [ReadingViewModel.openReadingSession] 首次进入该路由时读取一次：用户从生词本
 * 跳过来是为了看这个词所在的段落，之后在原地切章不该再被拽回去。
 */
private const val READING_ROUTE_WORD = "word"

/** 跳转过来的段落亮多久。够看清位置，又不至于在随后的阅读里持续干扰。 */
private const val PARAGRAPH_HIGHLIGHT_MS = 3000L

/**
 * 词典错误类型（用于本地化错误消息）
 */
enum class DictionaryErrorType {
    WORD_NOT_FOUND,
    NETWORK_ERROR,
    UNKNOWN_ERROR
}

enum class VocabularySaveResult {
    SAVED,
    ALREADY_EXISTS,
    FAILED
}

enum class ReadingError { LOAD, SAVE_POSITION, SAVE_PREFERENCE }

/** 当前文章在书中的导航位置；独立文章没有章节上下文。 */

data class ChapterContext(
    val bookId: Long,
    /** 0 基。显示时 +1。 */
    val chapterIndex: Int,
    val chapterCount: Int,
    val navigationTitle: String?,
    val previousArticleId: Long?,
    val nextArticleId: Long?
)

@HiltViewModel
class ReadingViewModel @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val audioPlayer: AudioPlayer,
    private val networkChecker: NetworkChecker,
    private val ttsPlayer: TtsPlayer,
    private val aiExplanationRepository: AiExplanationRepository,
    aiExplanationOperationRegistry: AiExplanationOperationRegistry,
    private val settingsPreferences: SettingsPreferences,
    private val bookRepository: BookRepository,
    private val pronunciationAudioCache: PronunciationAudioCache,
    private val wholeTranslationRepository: WholeTranslationRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /**
     * AI 解释面板协调器，每个 ViewModel 一份。
     *
     * observer 跑在 `viewModelScope`，付费操作由 registry 在进程级作用域启动，
     * 两者无父子关系——关闭面板只脱离 UI，不会取消已计费的请求。
     */
    private val aiSheetCoordinator = AiSheetCoordinator(
        registry = aiExplanationOperationRegistry,
        observerScope = viewModelScope
    )

    private val sentenceTranslationCoordinator = AiSheetCoordinator(
        registry = aiExplanationOperationRegistry,
        observerScope = viewModelScope
    )

    /**
     * AI 面板的**唯一**聚合状态。
     *
     * 刻意不拆成 `_isLoadingAi` / `_aiExplanation` / `_aiError` 等平行字段：代际语义
     * 一旦分散到多个 flow 就无法保证一致。现有单词详情状态与此完全独立，互不共用。
     *
     * 6.17 只建立所有权边界，不接 ReadingScreen；入口方法属于 6.6。
     */
    val aiSheetState: StateFlow<AiSheetState> = aiSheetCoordinator.state
    val sentenceTranslationState: StateFlow<AiSheetState> = sentenceTranslationCoordinator.state

    /** 只解除解释面板的观察者，不取消 application scope 持有的付费请求。 */
    fun dismissAiSheet() {
        explanationPreparationJob?.cancel()
        viewModelScope.launch { aiSheetCoordinator.dismiss() }
    }

    /** 只取消面板当前明确指向的那一个操作。 */
    fun cancelAiOperation(ref: AiOperationRef) {
        viewModelScope.launch { aiSheetCoordinator.cancel(ref) }
    }

    /** 只解除句子翻译的 UI 观察者；付费请求仍归注册表所有。 */
    fun dismissSentenceTranslation() {
        translationPreparationJob?.cancel()
        viewModelScope.launch { sentenceTranslationCoordinator.dismiss() }
    }

    /** 只取消弹层明确指向的那一个句子翻译操作。 */
    fun cancelSentenceTranslation(ref: AiOperationRef) {
        viewModelScope.launch { sentenceTranslationCoordinator.cancel(ref) }
    }

    // 阅读正文字号档位（来自设置），阅读页据此渲染正文字号
    val fontSizeOption: StateFlow<FontSizeOption> = settingsPreferences.fontSizeOption
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FontSizeOption.DEFAULT)

    /** 阅读页主题与应用级主题共用同一份偏好，仍由 DataStore 持久化。 */
    val themeOption: StateFlow<ThemeOption> = settingsPreferences.themeOption
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeOption.DEFAULT)

    val readingMode: StateFlow<ReadingMode> = settingsPreferences.readingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingMode.DEFAULT)

    private val _readingErrors = Channel<ReadingError>(Channel.CONFLATED)
    val readingErrors: Flow<ReadingError> = _readingErrors.receiveAsFlow()

    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch {
            try {
                settingsPreferences.setReadingMode(mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _readingErrors.trySend(ReadingError.SAVE_PREFERENCE)
            }
        }
    }

    fun setFontSizeOption(option: FontSizeOption) {
        viewModelScope.launch {
            try {
                settingsPreferences.setFontSizeOption(option)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _readingErrors.trySend(ReadingError.SAVE_PREFERENCE)
            }
        }
    }

    fun setThemeOption(option: ThemeOption) {
        viewModelScope.launch {
            try {
                settingsPreferences.setThemeOption(option)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _readingErrors.trySend(ReadingError.SAVE_PREFERENCE)
            }
        }
    }

    private val _article = MutableStateFlow<ArticleEntity?>(null)
    val article: StateFlow<ArticleEntity?> = _article.asStateFlow()

    private val _selectedSentence = MutableStateFlow<SelectedSentence?>(null)
    val selectedSentence: StateFlow<SelectedSentence?> = _selectedSentence.asStateFlow()

    private val _selectedWord = MutableStateFlow<String?>(null)
    val selectedWord: StateFlow<String?> = _selectedWord.asStateFlow()

    // 保存结果是 one-shot event。使用 buffered Channel 保留旋屏等 collector 空窗期间产生的
    // 结果；默认 MutableSharedFlow(replay = 0) 在没有订阅者时会直接丢弃事件。
    private val _vocabularySaved = Channel<VocabularySaveResult>(Channel.BUFFERED)
    val vocabularySaved: Flow<VocabularySaveResult> = _vocabularySaved.receiveAsFlow()

    private val _wordDefinition = MutableStateFlow<WordDefinition?>(null)
    val wordDefinition: StateFlow<WordDefinition?> = _wordDefinition.asStateFlow()

    private val _isLoadingDefinition = MutableStateFlow(false)
    val isLoadingDefinition: StateFlow<Boolean> = _isLoadingDefinition.asStateFlow()

    private val _isLoadingAudio = MutableStateFlow(false)
    val isLoadingAudio: StateFlow<Boolean> = _isLoadingAudio.asStateFlow()

    // 与保存结果相同，查词错误必须跨 collector 的短暂离场保留，且消费后不向未来页面重放。
    private val _definitionError = Channel<DictionaryErrorType>(Channel.BUFFERED)
    val definitionError: Flow<DictionaryErrorType> = _definitionError.receiveAsFlow()

    private val _isLoadingArticle = MutableStateFlow(false)
    val isLoadingArticle: StateFlow<Boolean> = _isLoadingArticle.asStateFlow()

    // 当前文章已收藏单词集合（小写）。歧义词卡片可同时展示主原形与备选原形（如 live / life），
    // 二者的"已收藏"状态需各自独立判断，故用集合而非单个布尔值。
    private val _vocabularyWords = MutableStateFlow<Set<String>>(emptySet())
    val vocabularyWords: StateFlow<Set<String>> = _vocabularyWords.asStateFlow()

    // TTS 引擎/英语语言包不可用的一次性事件（UI 映射为 R.string.tts_unavailable 提示）。
    // 用 Channel（而非 replay=0 的 SharedFlow）：TTS 初始化是异步的，其结果可能恰好落在
    // 旋屏导致 collector 短暂离场期间，SharedFlow 会直接丢弃该事件、用户得不到任何反馈。
    // 使用 Channel 缓冲一次性 UI 事件，避免短暂离开页面时丢失反馈。
    private val _ttsUnavailable = Channel<Unit>(Channel.CONFLATED)
    val ttsUnavailable: Flow<Unit> = _ttsUnavailable.receiveAsFlow()

    private val _readingTtsState = MutableStateFlow(ReadingTtsState())
    val readingTtsState = _readingTtsState.asStateFlow()
    private val _ttsPositionTarget = MutableStateFlow<ReadingPositionTarget?>(null)
    val ttsPositionTarget = _ttsPositionTarget.asStateFlow()
    private val _ttsFailures = Channel<ReadingTtsFailure>(Channel.CONFLATED)
    val ttsFailures = _ttsFailures.receiveAsFlow()
    private val _ttsSystemActions = Channel<TtsSystemAction>(Channel.BUFFERED)
    val ttsSystemActions = _ttsSystemActions.receiveAsFlow()
    private var ttsGeneration = 0L
    private var positionRequestId = 0L
    private var ttsPreparationJob: Job? = null
    private var ttsRefreshJob: Job? = null
    private var activeTtsUtterance: String? = null
    private var ttsSentences = emptyList<SpeechSentence>()
    private var sentenceRequest: SentenceSpeechRequest? = null
    private val _voiceSettings = MutableStateFlow(ReadingVoiceSettingsState())
    val voiceSettings = _voiceSettings.asStateFlow()
    private var voiceSettingsJob: Job? = null
    private var voiceInspectionGeneration = 0L
    private var previewGeneration = 0L
    private var previewJob: Job? = null
    private var previewUtteranceId: String? = null
    private val voicePreferenceMutex = Mutex()
    private var voicePreferenceWriteJob: Job? = null
    private var inspectedVoiceKey: VoiceInspectionKey? = null

    /**
     * 只有这两项会改变引擎解析出的 Voice，语速不影响。
     *
     * 没有这个判定时，改动语速也会走一次 recheckVoiceSettings()，而后者会重建
     * TextToSpeech，于是面板上的 Voice 列表整块消失、状态文案退回「正在检查」、
     * 试听按钮变灰 —— 调语速是本面板最高频的操作，必然复现。
     */
    private data class VoiceInspectionKey(val voiceId: String?, val allowNetwork: Boolean)

    private class SpeechSentence(val index: Int, val rawText: String, val anchor: ReadingAnchor?)

    private class SentenceSpeechRequest(
        val generation: Long,
        val articleId: Long,
        val sentence: SpeechSentence,
        val continuous: Boolean,
        val allowNetworkOnce: Boolean,
        val recheck: Boolean
    )

    /**
     * 当前文章在书中的位置。
     *
     * 独立文章为 null —— 界面据此决定是否显示上一章/下一章。用 null 而非
     * `chapterCount = 0` 之类的哨兵值：两种情况的行为完全不同，哨兵值会让
     * 「还没加载完」和「这不是书」变得无法区分。
     */
    private val _chapterContext = MutableStateFlow<ChapterContext?>(null)
    val chapterContext: StateFlow<ChapterContext?> = _chapterContext.asStateFlow()

    private val _pendingPositionTarget = MutableStateFlow<ReadingPositionTarget?>(null)
    val pendingPositionTarget: StateFlow<ReadingPositionTarget?> = _pendingPositionTarget.asStateFlow()
    private var loadArticleJob: Job? = null

    /**
     * 要临时高亮的段落序号（生词本跳转过来的那一段），不等同于阅读位置。
     *
     * 只在跳转后亮一段时间就自行熄灭：它是一个「就是这里」的提示，不是选中态——
     * 常亮的段落底色会变成阅读时的干扰。
     */
    private val _highlightedParagraph = MutableStateFlow<Int?>(null)
    val highlightedParagraph: StateFlow<Int?> = _highlightedParagraph.asStateFlow()
    private var highlightJob: Job? = null

    /** 从生词本跳转时携带的词，由 [loadArticle] 消费一次后置空。 */
    private var pendingHighlightWord: String? = null
    private var lookupWordJob: Job? = null
    private var wordSelectionGeneration = 0L
    private var explanationPreparationJob: Job? = null
    private var translationPreparationJob: Job? = null
    private var articleLoadGeneration = 0L

    /**
     * 发音请求的持有者与代次。
     *
     * 两者都必需，各挡一种情况：
     *  - `playWordAudioJob` 让 [stopAudio] 能真的取消在途请求。原来那个协程不被任何字段持有，
     *    关闭词义弹窗后它会照常恢复并开始播放 —— 用户已经离开却听到声音。
     *  - `audioRequestGeneration` 作废「已经越过挂起点、取消挡不住」的那次。协程可能正好在
     *    `cache.get()` 返回后、`play()` 之前，此时 cancel 不会阻止它调 player；代次不匹配才会。
     *
     * 加载态也按代次归属：旧请求返回时不得清掉新请求刚置上的转圈。
     */
    private var playWordAudioJob: Job? = null
    private var audioRequestGeneration = 0L
    private var requestedArticleId: Long? = null
    private var openedRouteArticleId: Long? = null

    /** 路由只初始化会话；原地切章后，重建页面不能再加载最初的路由章节。 */
    fun openReadingSession(articleId: Long) {
        if (openedRouteArticleId == articleId && (_article.value != null || _isLoadingArticle.value)) return
        openedRouteArticleId = articleId
        if (savedStateHandle.get<Long>(READING_ROUTE_ARTICLE) != articleId) {
            savedStateHandle[READING_CURRENT_ARTICLE] = articleId
        }
        savedStateHandle[READING_ROUTE_ARTICLE] = articleId
        // 生词本「查看原文」带来的词。只在进入该路由时取一次，原地切章不再复用。
        pendingHighlightWord = savedStateHandle.get<String>(READING_ROUTE_WORD)?.takeIf { it.isNotBlank() }
        loadArticle(savedStateHandle.get<Long>(READING_CURRENT_ARTICLE) ?: articleId)
    }

    fun loadArticle(articleId: Long, entry: ReadingEntry = ReadingEntry.RESUME) {
        if (entry == ReadingEntry.RESUME && requestedArticleId == articleId &&
            _article.value?.id == articleId && !_isLoadingArticle.value
        ) return
        val generation = ++articleLoadGeneration
        requestedArticleId = articleId
        loadArticleJob?.cancel()
        closeVoiceSettings()
        clearSelection()
        // 换文章时作废全文翻译 sheet：它的范围快照与段落计数属于旧文章。只靠 articleId
        // 比对不够——用户退出再进入同一篇时 id 相同，而那份 ChoosingScope 已经过期。
        dismissWholeTranslation()
        ttsSentences = emptyList()
        _vocabularyWords.value = emptySet()
        _isLoadingArticle.value = true
        val previousTarget = _pendingPositionTarget.value
        _pendingPositionTarget.value = null
        clearParagraphHighlight()
        loadArticleJob = viewModelScope.launch {
            try {
                val loaded = articleRepository.getArticleById(articleId)
                if (generation != articleLoadGeneration) return@launch
                if (loaded == null) {
                    requestedArticleId = _article.value?.id
                    _pendingPositionTarget.value = previousTarget
                    _readingErrors.trySend(ReadingError.LOAD)
                    return@launch
                }
                val stored = if (entry == ReadingEntry.RESUME) {
                    articleRepository.getReadingPosition(articleId)?.takeIf { it.articleId == articleId }
                } else null
                val context = chapterContextFor(articleId)
                if (generation != articleLoadGeneration || requestedArticleId != articleId) return@launch

                // 生词本跳转：定位词所在段落，既覆盖阅读位置（滚过去），又点亮那一段。
                // 词找不到时按 null 处理，退回正常的阅读位置恢复——生词可能是变形词，
                // 而正文里存的是它出现时的形态，理论上必然命中；找不到也不该让跳转失败。
                val highlightWord = pendingHighlightWord
                pendingHighlightWord = null
                val wordAnchor = highlightWord?.let { findWordAnchor(loaded.content, it) }
                wordAnchor?.let { highlightParagraph(it.paragraphIndex) }

                val position = wordAnchor?.let { ReadingPosition(articleId, it) }
                    ?: stored
                    ?: ReadingPosition(
                        articleId, ReadingAnchor(textKind = ReadingTextKind.TITLE)
                    )
                // 位置先发布，内容随后可见；首帧的临时位置不得冲掉已保存的锚点。
                _pendingPositionTarget.value = ReadingPositionTarget(
                    position = position,
                    entry = if (entry == ReadingEntry.RESUME && stored == null) ReadingEntry.START else entry,
                    requestId = ++positionRequestId
                )
                _chapterContext.value = context
                _article.value = loaded
                savedStateHandle[READING_CURRENT_ARTICLE] = articleId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == articleLoadGeneration) {
                    requestedArticleId = _article.value?.id
                    _pendingPositionTarget.value = previousTarget
                    _readingErrors.trySend(ReadingError.LOAD)
                }
            } finally {
                if (generation == articleLoadGeneration) _isLoadingArticle.value = false
            }
        }
    }

    private suspend fun chapterContextFor(articleId: Long): ChapterContext? {
        val relation = bookRepository.findChapterByArticleId(articleId) ?: return null
        val chapters = bookRepository.getChaptersOnce(relation.bookId)
        val position = chapters.indexOfFirst { it.articleId == articleId }
        if (position < 0) return null
        return ChapterContext(
            bookId = relation.bookId,
            chapterIndex = position,
            chapterCount = chapters.size,
            navigationTitle = relation.navigationTitle,
            previousArticleId = chapters.getOrNull(position - 1)?.articleId,
            nextArticleId = chapters.getOrNull(position + 1)?.articleId
        )
    }

    fun consumePositionTarget(target: ReadingPositionTarget) {
        if (_pendingPositionTarget.value == target && _article.value?.id == target.position.articleId) {
            _pendingPositionTarget.value = null
        }
    }

    fun saveReadingPosition(position: ReadingPosition) {
        if (_isLoadingArticle.value || _pendingPositionTarget.value != null ||
            position.articleId != requestedArticleId || position.articleId != _article.value?.id
        ) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                articleRepository.saveReadingPosition(position)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _readingErrors.trySend(ReadingError.SAVE_POSITION)
            }
        }
    }

    /** 仅为当前已加载的文章世代发布一份不可变快照。 */
    fun selectSentence(articleId: Long, globalIndex: Int, range: SentenceRange) {
        if (articleId != requestedArticleId || articleId != _article.value?.id) return

        val normalizedText = AiExplanationTextNormalizer.normalize(range.text)
        if (normalizedText.isBlank()) return

        val snapshot = SelectedSentence(
            articleId = articleId,
            sentenceIndex = globalIndex,
            rawText = range.text,
            normalizedText = normalizedText,
            startOffset = range.startOffset,
            endOffset = range.endOffset
        )
        if (_selectedSentence.value == snapshot) return

        _selectedSentence.value = snapshot
        dismissAiSheet()
        dismissSentenceTranslation()
        stopAudio()
        // 点击句子时清除单词高亮
        _selectedWord.value = null
    }

    fun clearSelection() {
        _wordDefinition.value = null
        _isLoadingDefinition.value = false
        wordSelectionGeneration++
        lookupWordJob?.cancel()
        dismissAiSheet()
        dismissSentenceTranslation()
        _selectedWord.value = null
        _selectedSentence.value = null
        stopAudio()
    }

    /** 只用不可变快照里的规范化文本发起句子解释。 */
    fun explainSelectedSentence() {
        val snapshot = _selectedSentence.value ?: return
        startExplanation(
            target = AiExplanationTarget.Sentence(snapshot),
            input = AiExplanationInput.Sentence(snapshot.normalizedText)
        )
    }

    /** 用不可变快照发起翻译请求，并使用与解释相互独立的缓存语义。 */
    fun translateSelectedSentence() {
        val snapshot = _selectedSentence.value ?: return
        translationPreparationJob?.cancel()
        translationPreparationJob = viewModelScope.launch {
            var token: AiSheetRequestToken? = null
            try {
                val requestToken = sentenceTranslationCoordinator.begin(
                    AiExplanationTarget.Sentence(snapshot)
                )
                token = requestToken
                when (
                    val result = aiExplanationRepository.start(
                        AiExplanationInput.SentenceTranslation(snapshot.normalizedText)
                    )
                ) {
                    is AiExplanationStartResult.Started -> {
                        sentenceTranslationCoordinator.attach(requestToken, result.handle)
                    }
                    is AiExplanationStartResult.Rejected -> {
                        sentenceTranslationCoordinator.reject(requestToken, result.error)
                    }
                }
            } catch (cancellation: CancellationException) {
                token?.let { sentenceTranslationCoordinator.invalidate(it) }
                throw cancellation
            }
        }
    }

    fun retrySelectedSentenceTranslation() {
        translateSelectedSentence()
    }

    // ---- 全文翻译 ----
    //
    // 付费任务归 WholeTranslationRepository（application scope）所有；这里只持有 sheet 状态
    // 与观察者。关闭 sheet 解除观察，不取消任务——与句子翻译的 coordinator 同一条边界。

    private val _wholeTranslation =
        MutableStateFlow<WholeTranslationSheetState>(WholeTranslationSheetState.Hidden)
    val wholeTranslationState: StateFlow<WholeTranslationSheetState> = _wholeTranslation.asStateFlow()

    private var wholeTranslationObserver: Job? = null
    private var wholeTranslationGeneration = 0L

    /** 从段落浮窗「更多 → 全文翻译」进入范围选择。 */
    fun openWholeTranslation() {
        val article = _article.value ?: return
        if (_isLoadingArticle.value || article.id != requestedArticleId) return
        val generation = ++wholeTranslationGeneration
        wholeTranslationObserver?.cancel()
        dismissSentenceActions()
        viewModelScope.launch {
            val chapter = _chapterContext.value
            val currentScope = WholeTranslationScope.CurrentArticle(article.id)
            val chapterScope = chapter?.let { ctx ->
                bookRepository.getChaptersOnce(ctx.bookId)
                    .map { it.articleId }
                    .takeIf { it.isNotEmpty() }
                    ?.let { WholeTranslationScope.Chapter(ctx.bookId, it) }
            }
            // 已有任务优先：同源任务可继续时直接进入跟踪态，用户不必重新选范围。
            val existingCurrent = wholeTranslationRepository.findResumable(currentScope)
            val existingChapter = chapterScope?.let { wholeTranslationRepository.findResumable(it) }
            if (generation != wholeTranslationGeneration || _article.value?.id != article.id) return@launch
            val existing = existingChapter ?: existingCurrent
            if (existing != null) {
                track(existing.taskId, generation)
                return@launch
            }

            val currentCount = ParagraphAligner.splitParagraphs(article.content).size
            val chapterCount = chapterScope?.let { scope ->
                scope.articleIds.sumOf { id ->
                    articleRepository.getArticleById(id)?.content
                        ?.let { ParagraphAligner.splitParagraphs(it).size } ?: 0
                }
            }
            if (generation != wholeTranslationGeneration || _article.value?.id != article.id) return@launch
            _wholeTranslation.value = WholeTranslationSheetState.ChoosingScope(
                articleId = article.id,
                selected = WholeTranslationScopeChoice.CURRENT_ARTICLE,
                currentArticleOption = ScopeOption(paragraphCount = currentCount, articleCount = 1),
                chapterOption = chapterScope?.let {
                    ScopeOption(paragraphCount = chapterCount ?: 0, articleCount = it.articleIds.size)
                },
                existing = null
            )
        }
    }

    fun selectWholeTranslationScope(choice: WholeTranslationScopeChoice) {
        val current = _wholeTranslation.value as? WholeTranslationSheetState.ChoosingScope ?: return
        if (choice == WholeTranslationScopeChoice.CHAPTER && current.chapterOption == null) return
        _wholeTranslation.value = current.copy(selected = choice)
    }

    /** 开始所选范围。开始后立刻切到跟踪态，进度由 repository 的 Flow 驱动。 */
    fun startWholeTranslation() {
        val choosing = _wholeTranslation.value as? WholeTranslationSheetState.ChoosingScope ?: return
        if (choosing.articleId != _article.value?.id) return
        val generation = ++wholeTranslationGeneration
        viewModelScope.launch {
            val scope = when (choosing.selected) {
                WholeTranslationScopeChoice.CURRENT_ARTICLE ->
                    WholeTranslationScope.CurrentArticle(choosing.articleId)
                WholeTranslationScopeChoice.CHAPTER -> {
                    val ctx = _chapterContext.value ?: return@launch
                    val ids = bookRepository.getChaptersOnce(ctx.bookId).map { it.articleId }
                    if (ids.isEmpty()) return@launch
                    WholeTranslationScope.Chapter(ctx.bookId, ids)
                }
            }
            val result = wholeTranslationRepository.start(scope)
            if (generation != wholeTranslationGeneration) return@launch
            when (result) {
                is WholeTranslationStartResult.Started -> track(result.taskId, generation)
                is WholeTranslationStartResult.Existing -> track(result.taskId, generation)
                WholeTranslationStartResult.NoContent ->
                    _wholeTranslation.value = WholeTranslationSheetState.Rejected(choosing.articleId, AiError.NoContent)
                is WholeTranslationStartResult.Rejected ->
                    _wholeTranslation.value = WholeTranslationSheetState.Rejected(choosing.articleId, result.error)
            }
        }
    }

    fun resumeWholeTranslation() {
        val tracking = _wholeTranslation.value as? WholeTranslationSheetState.Tracking ?: return
        viewModelScope.launch { wholeTranslationRepository.resume(tracking.taskId) }
    }

    fun retryFailedWholeTranslation() {
        val tracking = _wholeTranslation.value as? WholeTranslationSheetState.Tracking ?: return
        viewModelScope.launch { wholeTranslationRepository.retryFailed(tracking.taskId) }
    }

    /** 显式取消任务本身（付费工作停止）。与 [dismissWholeTranslation] 不同。 */
    fun cancelWholeTranslation() {
        val tracking = _wholeTranslation.value as? WholeTranslationSheetState.Tracking ?: return
        viewModelScope.launch { wholeTranslationRepository.cancel(tracking.taskId) }
    }

    /** 只关闭 sheet 并解除观察；任务在后台继续。 */
    fun dismissWholeTranslation() {
        ++wholeTranslationGeneration
        wholeTranslationObserver?.cancel()
        wholeTranslationObserver = null
        _wholeTranslation.value = WholeTranslationSheetState.Hidden
    }

    private fun track(taskId: Long, generation: Long) {
        wholeTranslationObserver?.cancel()
        wholeTranslationObserver = viewModelScope.launch {
            wholeTranslationRepository.observe(taskId).collect { view ->
                if (generation != wholeTranslationGeneration) return@collect
                if (view == null) {
                    _wholeTranslation.value = WholeTranslationSheetState.Hidden
                    return@collect
                }
                _wholeTranslation.value = WholeTranslationSheetState.Tracking(
                    taskId = view.taskId,
                    scopeKey = view.scopeKey,
                    status = view.status,
                    progress = view.progress,
                    failureReason = view.failureReason
                )
                // 完成后文章译文已写入 Room；重新读取让段落对照立即可见。
                if (view.status == WholeTranslationTaskStatus.COMPLETED) {
                    val current = _article.value ?: return@collect
                    articleRepository.getArticleById(current.id)?.let { refreshed ->
                        if (_article.value?.id == refreshed.id) _article.value = refreshed
                    }
                }
            }
        }
    }

    /** 通过 Android TextToSpeech 朗读整份不可变句子快照。 */
    fun playSelectedSentence() {
        val snapshot = _selectedSentence.value ?: return
        if (snapshot.articleId != _article.value?.id || _isLoadingArticle.value) return
        prepareSentenceSpeech(SpeechSentence(snapshot.sentenceIndex, snapshot.rawText, null), continuous = false)
    }

    fun startContinuousReading(
        articleId: Long,
        paragraphs: List<ParagraphAligner.AlignedParagraph>,
        anchor: ReadingAnchor
    ) {
        if (articleId != _article.value?.id || articleId != requestedArticleId || _isLoadingArticle.value) return
        ttsSentences = paragraphs.flatMapIndexed { paragraphIndex, paragraph ->
            paragraph.sentences.map { range ->
                SpeechSentence(
                    paragraph.sentenceOffset + range.index,
                    range.text,
                    ReadingAnchor(paragraphIndex, ReadingTextKind.ORIGINAL, range.startOffset)
                )
            }
        }
        val selected = _selectedSentence.value
        val start = ttsSentences.firstOrNull {
            selected != null && it.index == selected.sentenceIndex && it.rawText == selected.rawText &&
                it.anchor?.characterOffset == selected.startOffset
        } ?: ttsSentences.firstOrNull { sentence ->
            val location = requireNotNull(sentence.anchor)
            when {
                anchor.textKind == ReadingTextKind.TITLE || anchor.textKind == ReadingTextKind.SOURCE -> true
                location.paragraphIndex > anchor.paragraphIndex -> true
                location.paragraphIndex < anchor.paragraphIndex -> false
                anchor.textKind == ReadingTextKind.TRANSLATION -> true
                else -> location.characterOffset + sentence.rawText.length > anchor.characterOffset
            }
        } ?: ttsSentences.lastOrNull() ?: return
        clearSelection()
        prepareSentenceSpeech(start, continuous = true)
    }

    private fun prepareSentenceSpeech(
        sentence: SpeechSentence,
        continuous: Boolean,
        allowNetworkOnce: Boolean = false,
        recheck: Boolean = false
    ) {
        val articleId = _article.value?.id ?: return
        if (_isLoadingArticle.value || articleId != requestedArticleId) return
        stopAudio()
        val request = SentenceSpeechRequest(ttsGeneration, articleId, sentence, continuous, allowNetworkOnce, recheck)
        sentenceRequest = request
        _readingTtsState.value = ReadingTtsState(
            phase = ReadingTtsPhase.PREPARING,
            requestId = request.generation,
            sentenceIndex = sentence.index,
            sentenceCount = ttsSentences.size,
            continuous = continuous
        )
        if (continuous && sentence.anchor != null) {
            _ttsPositionTarget.value = ReadingPositionTarget(
                ReadingPosition(articleId, sentence.anchor), requestId = ++positionRequestId
            )
        } else {
            speakPreparedSentence(request)
        }
    }

    fun consumeTtsPositionTarget(target: ReadingPositionTarget) {
        val request = sentenceRequest ?: return
        if (_ttsPositionTarget.value != target || !isCurrentSentenceRequest(request)) return
        _ttsPositionTarget.value = null
        speakPreparedSentence(request)
    }

    private fun speakPreparedSentence(request: SentenceSpeechRequest) {
        ttsPreparationJob = viewModelScope.launch {
            val allowed = request.allowNetworkOnce || settingsPreferences.allowNetworkTts.first()
            val settings = settingsPreferences.ttsReadingSettings.first()
            if (!isCurrentSentenceRequest(request)) return@launch
            val speak = {
                ttsPlayer.speakReading(request.sentence.rawText, settings, allowed) { result ->
                    handleSentencePlayback(request, result)
                    if (isCurrentSentenceRequest(request)) {
                        clearMissingVoice(settings, ttsPlayer.currentVoiceSnapshot())
                    }
                }
            }
            if (request.recheck) {
                ttsPlayer.refreshVoices(settings, allowed) { snapshot ->
                    if (isCurrentSentenceRequest(request)) {
                        if (snapshot.capability is TtsCapability.Ready) speak()
                        else failSentenceSpeech(request, snapshot.capability.failureReason() ?: TtsFailureReason.INITIALIZATION_FAILED)
                    }
                }
            } else speak()
        }
    }

    private fun handleSentencePlayback(request: SentenceSpeechRequest, result: TtsPlaybackResult) {
        if (!isCurrentSentenceRequest(request)) return
        when (result) {
            is TtsPlaybackResult.Started -> {
                if (_readingTtsState.value.phase != ReadingTtsPhase.PREPARING) return
                activeTtsUtterance = result.utteranceId
                _readingTtsState.value = _readingTtsState.value.copy(phase = ReadingTtsPhase.PLAYING)
            }
            is TtsPlaybackResult.Finished -> {
                if (activeTtsUtterance != result.utteranceId || _readingTtsState.value.phase != ReadingTtsPhase.PLAYING) return
                activeTtsUtterance = null
                val next = ttsSentences.getOrNull(request.sentence.index + 1)
                if (request.continuous && next != null) prepareSentenceSpeech(next, continuous = true)
                else _readingTtsState.value = _readingTtsState.value.copy(phase = ReadingTtsPhase.COMPLETED)
            }
            is TtsPlaybackResult.Failed -> failSentenceSpeech(request, result.reason)
        }
    }

    private fun failSentenceSpeech(request: SentenceSpeechRequest, reason: TtsFailureReason) {
        if (!isCurrentSentenceRequest(request) || _readingTtsState.value.phase !in
            listOf(ReadingTtsPhase.PREPARING, ReadingTtsPhase.PLAYING)
        ) return
        activeTtsUtterance = null
        dismissAiSheet()
        dismissSentenceTranslation()
        _readingTtsState.value = _readingTtsState.value.copy(phase = ReadingTtsPhase.FAILED, failure = reason)
        _ttsFailures.trySend(ReadingTtsFailure(request.generation, reason))
    }

    private fun isCurrentSentenceRequest(request: SentenceSpeechRequest): Boolean =
        sentenceRequest === request && request.generation == ttsGeneration &&
            request.articleId == _article.value?.id && request.articleId == requestedArticleId &&
            !_isLoadingArticle.value

    fun pauseReadingTts() {
        val request = sentenceRequest ?: return
        val state = _readingTtsState.value
        if (state.phase != ReadingTtsPhase.PLAYING && state.phase != ReadingTtsPhase.PREPARING) return
        stopAudio()
        sentenceRequest = SentenceSpeechRequest(
            ttsGeneration, request.articleId, request.sentence, request.continuous, request.allowNetworkOnce, false
        )
        _readingTtsState.value = state.copy(phase = ReadingTtsPhase.PAUSED, requestId = ttsGeneration)
    }

    fun resumeReadingTts() {
        val request = sentenceRequest ?: return
        when (_readingTtsState.value.phase) {
            ReadingTtsPhase.PAUSED -> prepareSentenceSpeech(request.sentence, request.continuous, request.allowNetworkOnce)
            ReadingTtsPhase.FAILED -> retryReadingTts(request.generation)
            ReadingTtsPhase.COMPLETED -> {
                val first = if (request.continuous) ttsSentences.firstOrNull() else request.sentence
                first?.let { prepareSentenceSpeech(it, request.continuous) }
            }
            else -> Unit
        }
    }

    fun previousTtsSentence() = moveTtsSentence(-1)

    fun nextTtsSentence() = moveTtsSentence(1)

    private fun moveTtsSentence(delta: Int) {
        val request = sentenceRequest?.takeIf { it.continuous } ?: return
        val next = ttsSentences.getOrNull(request.sentence.index + delta) ?: return
        prepareSentenceSpeech(next, continuous = true)
    }

    fun retryReadingTts(requestId: Long, allowNetworkOnce: Boolean = false) {
        val request = sentenceRequest ?: return
        if (request.generation != requestId || _readingTtsState.value.phase != ReadingTtsPhase.FAILED) return
        if (allowNetworkOnce && _readingTtsState.value.failure != TtsFailureReason.NETWORK_VOICE_DISABLED) return
        // One-time consent covers only this sentence; continuous network use requires the setting.
        prepareSentenceSpeech(request.sentence, request.continuous && !allowNetworkOnce, allowNetworkOnce, recheck = true)
    }

    fun openTtsSystemAction(requestId: Long, action: TtsSystemAction) {
        if (_readingTtsState.value.requestId == requestId && _readingTtsState.value.phase == ReadingTtsPhase.FAILED) {
            _ttsSystemActions.trySend(action)
        }
    }

    fun openVoiceSettings() {
        if (_voiceSettings.value.isOpen) return
        if (_readingTtsState.value.continuous) pauseReadingTts() else stopAudio()
        // 带入已知偏好，避免打开瞬间闪回默认值；随后由首次发射校正。
        inspectedVoiceKey = null
        _voiceSettings.value = _voiceSettings.value.copy(isOpen = true)
        voiceSettingsJob = viewModelScope.launch {
            combine(settingsPreferences.ttsReadingSettings, settingsPreferences.allowNetworkTts) { settings, allowed ->
                settings to allowed
            }.collect { (settings, allowed) ->
                // 停试听只由 saveVoicePreference 负责：这里再停一次会杀掉「改完语速立刻试听」。
                _voiceSettings.value = _voiceSettings.value.copy(settings = settings, allowNetwork = allowed)
                val key = VoiceInspectionKey(settings.voiceId, allowed)
                if (key != inspectedVoiceKey) {
                    inspectedVoiceKey = key
                    recheckVoiceSettings()
                }
            }
        }
    }

    fun closeVoiceSettings() {
        voiceSettingsJob?.cancel()
        voiceSettingsJob = null
        voiceInspectionGeneration++
        stopVoicePreview()
        _voiceSettings.value = _voiceSettings.value.copy(isOpen = false)
    }

    fun setReadingVoice(voiceId: String?) {
        if (!_voiceSettings.value.isOpen) return
        if (voiceId != null && _voiceSettings.value.snapshot.voices.none { it.id == voiceId }) return
        saveVoicePreference { settingsPreferences.setTtsVoiceId(voiceId) }
    }

    fun setReadingSpeechRate(rate: Float) {
        if (_voiceSettings.value.isOpen) saveVoicePreference { settingsPreferences.setTtsSpeechRate(rate) }
    }

    fun setReadingNetworkVoiceAllowed(allowed: Boolean) {
        if (_voiceSettings.value.isOpen) saveVoicePreference { settingsPreferences.setAllowNetworkTts(allowed) }
    }

    fun resetReadingVoiceSettings() {
        if (_voiceSettings.value.isOpen) saveVoicePreference { settingsPreferences.resetReadingVoiceSettings() }
    }

    private fun saveVoicePreference(write: suspend () -> Unit) {
        stopVoicePreview()
        voicePreferenceWriteJob = viewModelScope.launch {
            try {
                voicePreferenceMutex.withLock { write() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _readingErrors.trySend(ReadingError.SAVE_PREFERENCE)
            }
        }
    }

    private fun clearMissingVoice(settings: TtsReadingSettings, snapshot: TtsVoiceSnapshot) {
        val id = settings.voiceId ?: return
        if (snapshot.catalogLoaded && snapshot.voices.none { it.id == id }) {
            saveVoicePreference { settingsPreferences.clearTtsVoiceIf(id) }
        }
    }

    fun recheckVoiceSettings() {
        if (!_voiceSettings.value.isOpen) return
        stopVoicePreview()
        val generation = ++voiceInspectionGeneration
        val state = _voiceSettings.value
        // 保留旧列表，只把能力标记为检查中，避免整块消失。
        _voiceSettings.value = state.copy(snapshot = state.snapshot.reinspecting())
        ttsPlayer.refreshVoices(state.settings, state.allowNetwork) { snapshot ->
            if (generation == voiceInspectionGeneration && _voiceSettings.value.isOpen) {
                _voiceSettings.value = _voiceSettings.value.copy(snapshot = snapshot)
                clearMissingVoice(state.settings, snapshot)
            }
        }
    }

    fun openVoiceSettingsSystemAction(action: TtsSystemAction) {
        if (!_voiceSettings.value.isOpen) return
        stopVoicePreview()
        _ttsSystemActions.trySend(action)
    }

    fun previewReadingVoice(sample: String) {
        if (!_voiceSettings.value.isOpen || sample.isBlank()) return
        stopVoicePreview()
        val generation = previewGeneration
        val articleId = _article.value?.id
        _voiceSettings.value = _voiceSettings.value.copy(previewing = true, previewFailure = null)
        previewJob = viewModelScope.launch {
            voicePreferenceWriteJob?.join()
            // Read persisted values after the latest preference write completes. A failed
            // consent write must never authorize network speech.
            val settings = settingsPreferences.ttsReadingSettings.first()
            val allowed = settingsPreferences.allowNetworkTts.first()
            if (generation != previewGeneration || !_voiceSettings.value.isOpen || _article.value?.id != articleId) return@launch
            ttsPlayer.speakReading(sample, settings, allowed) { result ->
                if (generation == previewGeneration && _voiceSettings.value.isOpen && _article.value?.id == articleId) {
                    when (result) {
                        is TtsPlaybackResult.Started -> previewUtteranceId = result.utteranceId
                        is TtsPlaybackResult.Finished -> if (previewUtteranceId == result.utteranceId) {
                            previewUtteranceId = null
                            _voiceSettings.value = _voiceSettings.value.copy(previewing = false)
                        }
                        is TtsPlaybackResult.Failed -> {
                            previewUtteranceId = null
                            _voiceSettings.value = _voiceSettings.value.copy(previewing = false, previewFailure = result.reason)
                        }
                    }
                }
            }
        }
    }

    fun stopVoicePreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        if (_voiceSettings.value.previewing) ttsPlayer.stop()
        previewUtteranceId = null
        _voiceSettings.value = _voiceSettings.value.copy(previewing = false, previewFailure = null)
    }

    fun refreshTtsCapability() {
        if (_voiceSettings.value.isOpen) {
            recheckVoiceSettings()
            return
        }
        ttsRefreshJob?.cancel()
        val generation = ttsGeneration
        ttsRefreshJob = viewModelScope.launch {
            val allowed = settingsPreferences.allowNetworkTts.first()
            if (generation == ttsGeneration) ttsPlayer.refresh(allowed) { }
        }
    }

    fun releaseTts() {
        closeVoiceSettings()
        stopAudio()
        ttsPlayer.shutdown()
    }

    /** 清掉句子弹层的全部临时状态，但不取消 application scope 持有的 AI 请求。 */
    fun dismissSentenceActions() {
        clearSelection()
    }

    /** 关闭句子弹层并解除两路结果观察，保留选句以便下次点击重新打开。 */
    fun dismissSentencePopup() {
        dismissAiSheet()
        dismissSentenceTranslation()
        stopAudio()
    }

    /** 只用已加载的文章正文发起有界的全文解释。 */
    fun explainArticle() {
        val currentArticle = _article.value ?: return
        if (_isLoadingArticle.value || currentArticle.id != requestedArticleId) return
        startExplanation(
            target = AiExplanationTarget.Article(currentArticle.id),
            input = AiExplanationInput.Article(currentArticle.content)
        )
    }

    private fun startExplanation(
        target: AiExplanationTarget,
        input: AiExplanationInput
    ) {
        explanationPreparationJob?.cancel()
        explanationPreparationJob = viewModelScope.launch {
            var token: AiSheetRequestToken? = null
            try {
                val requestToken = aiSheetCoordinator.begin(target)
                token = requestToken
                when (val result = aiExplanationRepository.start(input)) {
                    is AiExplanationStartResult.Started -> {
                        aiSheetCoordinator.attach(requestToken, result.handle)
                    }
                    is AiExplanationStartResult.Rejected -> {
                        aiSheetCoordinator.reject(requestToken, result.error)
                    }
                }
            } catch (cancellation: CancellationException) {
                token?.let { aiSheetCoordinator.invalidate(it) }
                throw cancellation
            }
        }
    }

    fun saveVocabulary(word: String) {
        viewModelScope.launch {
            val articleId = _article.value?.id ?: return@launch
            val vocabulary = VocabularyEntity(
                word = word,
                articleId = articleId
            )
            try {
                when (vocabularyRepository.insertVocabulary(vocabulary)) {
                    is VocabularyInsertResult.Inserted -> {
                        _vocabularySaved.send(VocabularySaveResult.SAVED)
                        // 保存成功后刷新集合，使卡片上该词的"添加"按钮即时切为"已收藏"状态
                        refreshVocabularyWords()
                    }

                    VocabularyInsertResult.AlreadyExists -> {
                        _vocabularySaved.send(VocabularySaveResult.ALREADY_EXISTS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ReadingViewModel", "Vocabulary save failed", e)
                _vocabularySaved.send(VocabularySaveResult.FAILED)
            }
        }
    }

    /**
     * 查询单词定义（离线优先）
     */
    fun lookupWord(word: String) {
        val generation = ++wordSelectionGeneration
        lookupWordJob?.cancel()
        lookupWordJob = viewModelScope.launch {
            _isLoadingDefinition.value = true
            try {
                val cleanWord = word.trim().lowercase()
                if (cleanWord.isBlank()) return@launch

                // 优先查询离线词典（含词形还原：lives→live）
                val offlineResult = dictionaryRepository.lookupOffline(cleanWord)
                if (offlineResult != null) {
                    val entry = offlineResult.entry
                    // 发音用用户长按的原词（cleanWord），而非还原后的原形：
                    // lives/live 发音不同，读用户实际看到的词更准。有道 dictvoice 亦基于原词。
                    val audioUrl = buildYoudaoAudioUrl(cleanWord)
                    if (generation != wordSelectionGeneration) return@launch
                    _wordDefinition.value = WordDefinition(
                        word = entry.word,  // 显示原形（词形还原命中时即 live）
                        phonetic = entry.phonetic,
                        chineseDefinitions = splitChineseDefinitions(entry.chinese),
                        englishDefinitions = entry.english?.let { listOf(it) } ?: emptyList(),
                        audioUrl = audioUrl,
                        source = DefinitionSource.OFFLINE,
                        inflectedForm = offlineResult.inflectedForm,
                        // 歧义变形词（lives/leaves）并列展示其余合法原形，由用户按上下文判断
                        alternates = offlineResult.alternateEntries.map {
                            AlternateDefinition(
                                word = it.word,
                                phonetic = it.phonetic,
                                chineseDefinitions = splitChineseDefinitions(it.chinese),
                                englishDefinitions = it.english?.let { e -> listOf(e) } ?: emptyList()
                            )
                        }
                    )

                    // 高亮正文中用户长按的词（变形词 lives，非原形）
                    _selectedWord.value = cleanWord
                    // 刷新已收藏集合：卡片主原形与备选原形各自据此判断是否已收藏
                    refreshVocabularyWords()
                    // 查词后自动播放读音：查词仅由长按触发，故恰好每次长按播一次；
                    // 放在 ViewModel 而非 UI 层，避免旋屏/进程恢复重组时重放。
                    // silent=true：自动播放失败（如无网）静默处理，不弹 Snackbar 污染离线阅读；
                    // 只有用户手动点播放按钮失败才提示。传原词供无网 TTS 朗读。
                    playWordAudio(cleanWord, audioUrl, silent = true)
                    return@launch
                }

                // 降级到在线 API
                val responses = dictionaryRepository.lookupOnline(cleanWord)
                if (responses.isNotEmpty()) {
                    val response = responses.first()
                    if (generation != wordSelectionGeneration) return@launch

                    // 提取音标和音频 URL
                    val phonetic = response.phonetic
                        ?: response.phonetics?.firstNotNullOfOrNull { it.text?.takeIf { t -> t.isNotBlank() } }
                    val audioUrl = response.phonetics
                        ?.firstNotNullOfOrNull { it.audio?.takeIf { a -> a.isNotBlank() } }
                        ?.let { normalizeAudioUrl(it) }

                    // 提取前 5 个释义
                    val definitions = response.meanings
                        .flatMap { meaning ->
                            meaning.definitions.map { def ->
                                "${meaning.partOfSpeech}. ${def.definition}"
                            }
                        }
                        .take(MAX_DEFINITIONS)

                    _wordDefinition.value = WordDefinition(
                        word = response.word,
                        phonetic = phonetic,
                        chineseDefinitions = emptyList(),  // 在线 API 无中文
                        englishDefinitions = definitions,
                        audioUrl = audioUrl,
                        source = DefinitionSource.ONLINE
                    )

                    // API 成功后才高亮单词
                    _selectedWord.value = cleanWord
                    refreshVocabularyWords()
                    // 同离线分支：查词成功后自动播放读音（在线音频可能缺失时传 null，
                    // 有网走真人音、无网静默）。silent=true：自动播放失败静默，不弹 Snackbar。
                    playWordAudio(cleanWord, audioUrl, silent = true)
                } else {
                    if (generation == wordSelectionGeneration) {
                        _definitionError.send(DictionaryErrorType.WORD_NOT_FOUND)
                        _selectedWord.value = null
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                // Retrofit 抛出的异常信息里可能带上被查询的单词或可配置的 endpoint，不能直接落日志。
                Log.e("ReadingViewModel", "Dictionary lookup failed")
                val errorType = if (e is IOException) {
                    DictionaryErrorType.NETWORK_ERROR
                } else {
                    DictionaryErrorType.UNKNOWN_ERROR
                }
                if (generation == wordSelectionGeneration) {
                    _definitionError.send(errorType)
                    _selectedWord.value = null
                }
            } finally {
                if (generation == wordSelectionGeneration) {
                    _isLoadingDefinition.value = false
                }
            }
        }
    }

    /** 进入单词长按的「按住」阶段：只高亮，不查词也不发音。 */
    fun beginWordSelection(word: String) {
        val cleanWord = word.trim().lowercase()
        if (cleanWord.isBlank()) return
        wordSelectionGeneration++
        lookupWordJob?.cancel()
        _isLoadingDefinition.value = false
        _wordDefinition.value = null
        _selectedWord.value = cleanWord
        _selectedSentence.value = null
        dismissAiSheet()
        dismissSentenceTranslation()
        stopAudio()
    }

    /** 拆分词库的中文释义字段（注意：释义内部含"；"会被一并拆开）。 */
    private fun splitChineseDefinitions(chinese: String): List<String> =
        chinese.split("；").map { it.trim() }.filter { it.isNotEmpty() }

    /** 刷新当前文章的已收藏单词集合（小写）。卡片据此判断主/备选原形各自是否已收藏。 */
    private fun refreshVocabularyWords() {
        val articleId = _article.value?.id ?: return
        val generation = articleLoadGeneration
        viewModelScope.launch {
            // 只取首次发射，避免这里挂上一个永不结束的 flow 收集
            val vocabList = vocabularyRepository.getVocabularyByArticle(articleId).first()
            if (generation == articleLoadGeneration && articleId == requestedArticleId) {
                _vocabularyWords.value = vocabList.mapTo(mutableSetOf()) { it.word.lowercase() }
            }
        }
    }

    /**
     * 播放单词发音（混合读音）。
     *
     * - 有网 + audioUrl 非空 → 有道真人发音；失败且非 silent → 降级系统 TTS。
     * - 无网（或 audioUrl 为空）→ 非 silent 用系统 TTS 机器音兜底；silent（自动播放）→ 静默不发声。
     *
     * @param word 用户长按的原词（如 "lives"），供无网/降级时 TTS 朗读——发音以实际看到的词为准。
     * @param audioUrl 有道真人发音 URL，可空（在线结果无音频时）。
     * @param silent 为 true 时（查词后的自动播放）任何失败/无网都静默：既不走 TTS、也不提示，
     *   避免离线阅读时每长按一个词都弹错误提示或响机器音。用户手动点播放按钮时为 false，明确反馈。
     */
    fun playWordAudio(word: String, audioUrl: String?, silent: Boolean = false) {
        if (_readingTtsState.value.phase != ReadingTtsPhase.IDLE) stopAudio()
        // audioUrl 为空表示这条结果没有真人音可播（在线词典缺 audio 字段），与网络无关，
        // 也没有可缓存的来源，所以直接兜底。
        if (audioUrl == null) {
            if (!silent) speakViaTts(word)
            return
        }
        val generation = ++audioRequestGeneration
        playWordAudioJob?.cancel()
        playWordAudioJob = viewModelScope.launch {
            try {
                // **先查缓存，再判网络。** 顺序是审计纠正过的：原来离线检查在最前面，于是
                // 明明已经缓存过的词在离线时也用不上 —— 手动点击直接走 TTS 机器音，自动播放
                // 静默。而缓存的全部意义就是让这些词离线可用。
                //
                // 「无网时不必发起网络请求空等」这个收益并没有丢：未命中且离线时下面立刻兜底，
                // 一样不发请求。
                val cached = pronunciationAudioCache.get(word.lowercase())

                // 缓存查询回来时这次请求可能已经作废（用户关了弹窗、或换了词）。
                //
                // 这一行与 `playWordAudioJob?.cancel()` 各挡一格，两者不可互相替代：
                //  - 协程**挂在** `cache.get()` 上时被作废 → cancel 让它直接结束，走不到这里
                //  - 协程**已越过** `cache.get()`、还没调 `play()` 时被作废 → 取消只在挂起点
                //    投递，此时 cancel 只是标记 Job，不中断当前执行；只有代次能作废它
                //
                // 判据：`playWordAudio_invalidatedAfterCacheLookupButBeforePlay_doesNotPlay`
                // 用不挂起的 `cache.get` 桩（在返回前内联调 `stopAudio()`）复现第二格。
                // 去掉这一行那条会红。
                //
                // 我原先在这里写过「那个窗口在单测里无法确定性复现，所以没有判据」——
                // 那是错的，复审给出了上面这个构造。留着一句错误的「测不了」比没有注释更糟：
                // 它会让下一个人以为这行不可验证，从而不敢改也不敢删。
                if (generation != audioRequestGeneration) return@launch

                if (cached == null && !networkChecker.isOnline()) {
                    if (!silent) speakViaTts(word)
                    return@launch
                }

                // 命中时**不置加载态** —— 本地文件的 prepare() 是毫秒级，闪一下转圈比不转
                // 更难看。这也让「命中不转圈」成为可测性质：测试断言传给 player 的是本地路径。
                if (cached == null) _isLoadingAudio.value = true

                audioPlayer.play(
                    url = cached?.absolutePath ?: audioUrl,
                    onComplete = {
                        Log.d("ReadingViewModel", "Audio playback completed")
                    },
                    onError = { exception ->
                        // 回到主线程后再检查代次，旧回调不能打断后来开始的句子朗读。
                        viewModelScope.launch playbackFailure@ {
                            if (generation != audioRequestGeneration) return@playbackFailure
                            _isLoadingAudio.value = false
                            Log.e("ReadingViewModel", "Audio playback failed: ${exception.javaClass.simpleName}")
                            // 本地文件不可播时清掉坏缓存；远端失败不删除缓存。
                            if (cached != null) {
                                viewModelScope.launch {
                                    pronunciationAudioCache.invalidate(word.lowercase())
                                }
                            }
                            if (!silent) speakViaTts(word)
                        }
                    }
                )

                // 播放已经启动，转圈到此为止。
                //
                // **不能**放在 `onComplete` 里：那个回调挂的是 `MediaPlayer` 的
                // `setOnCompletionListener`，音频**播完**才触发。放在那里的话转圈时长
                // 变成「网络等待 + 整段音频播放」——声音已经响了还在转，与「正在加载」
                // 这个语义自相矛盾。实测确认过：`play` 返回后 `isLoadingAudio` 仍为 true。
                //
                // 还有一个更隐蔽的后果：连续查两个词时，A 的 `onComplete` 会把 B 刚置上的
                // 转圈关掉。`play` 是 suspend 且在 `start()` 之后才返回，所以在这里置 false
                // 既准确又不会跨请求串台。
                if (generation == audioRequestGeneration) _isLoadingAudio.value = false

                // 未命中：播放已经启动，另起协程把音频下载入缓存，不阻塞本次播放。
                //
                // 这让首次播放发**两个**请求（一个给 MediaPlayer 流式播，一个给缓存下载），
                // 多约 19 KB 流量。这是刻意的，不是 bug：改成「先下完再播」会让首次延迟变长，
                // 因为 prepare() 本来是流式的、拿到足够缓冲就开始播。用一次 19 KB 换后续每次
                // 为零。详见 design.md「为什么不边播边缓存」。
                if (cached == null && networkChecker.isOnline()) {
                    viewModelScope.launch {
                        runCatching { pronunciationAudioCache.download(word.lowercase(), audioUrl) }
                            .onFailure {
                                if (it is CancellationException) throw it
                                // 缓存失败不影响发音：本次播的是远端，下次查词会再试一次下载。
                                Log.w("ReadingViewModel", "Failed to cache pronunciation audio")
                            }
                    }
                }
            } catch (e: CancellationException) {
                if (generation == audioRequestGeneration) _isLoadingAudio.value = false
                throw e
            } catch (e: Exception) {
                if (generation != audioRequestGeneration) return@launch
                _isLoadingAudio.value = false
                // 同上，只记类名：这层包住的是 audioPlayer.play(url = audioUrl, ...)，
                // 同步抛出的异常同样可能回显带单词的 URL。
                Log.e("ReadingViewModel", "Failed to start audio playback: ${e.javaClass.simpleName}")
                if (!silent) speakViaTts(word)
            }
        }
    }

    /**
     * 用系统 TTS 朗读，语言包/引擎不可用时发 [ttsUnavailable] 事件（UI 映射为本地化提示）。
     *
     * 联网授权从设置读取，与整句朗读同一个开关：单词兜底原先硬编码「只用本地语音」，于是
     * 即便用户已经开启联网 TTS，查词兜底也只能用那套机械的离线拼接音。授权仍然由用户掌握，
     * 这里只是不再替他否决。
     *
     * 语音同样现读：原先单词发音传 `TtsReadingSettings()`，用户在设置里挑的语音对它完全无效。
     * 叠加「离线优先」后这会变成听得见的割裂——好语音基本都是网络语音，于是已授权且挑了网络
     * 神经语音的用户，整句是神经音、点单词却掉回本地拼接音。语速**不**跟随（见
     * [TtsPlayer.speakWord]）。
     *
     * 两个偏好各读一次而不是 `combine`：它们只喂给同一次 `speakWord` 调用，没有跨字段一致性
     * 要求，而下面那道代次校验已经挡住「读偏好期间这次请求作废」这一格。
     *
     * ## 为什么要占用 [playWordAudioJob] 与代次
     *
     * 读偏好是 suspend，所以本函数必须起协程；而**游离的协程会绕开 [stopAudio]**——它靠
     * `playWordAudioJob?.cancel()` 取消在途请求。用户关掉弹窗后，一个没人持有的协程仍会恢复
     * 并调 `speakWord`，于是机器音在弹窗消失之后才响。这正是本文件 `playWordAudioJob` 声明处
     * 记着的那个坑（「原来那个协程不被任何字段持有」），不能重新引入。
     *
     * 代次校验与 cancel 各挡一格，理由同 [playWordAudio]：cancel 只在挂起点生效，已越过
     * `first()` 的那次只能靠代次作废。
     */
    private fun speakViaTts(word: String) {
        val generation = ++audioRequestGeneration
        playWordAudioJob?.cancel()
        playWordAudioJob = viewModelScope.launch {
            val allowNetwork = settingsPreferences.allowNetworkTts.first()
            val voiceId = settingsPreferences.ttsReadingSettings.first().voiceId
            if (generation != audioRequestGeneration) return@launch
            ttsPlayer.speakWord(word, voiceId, allowNetwork) {
                // trySend：CONFLATED channel 永不阻塞，回调可能在主线程同步触发，无需起协程。
                _ttsUnavailable.trySend(Unit)
            }
        }
    }

    /**
     * 规范化音频 URL
     * Free Dictionary API 的 audio 字段常为协议相对 URL（以 // 开头），
     * MediaPlayer 无法识别，需补全为 https。
     */
    private fun normalizeAudioUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    /** 构造有道 dictvoice 发音 URL（离线词典命中时补音频）。格式见 [WordAudioUrl]。 */
    private fun buildYoudaoAudioUrl(word: String): String = WordAudioUrl.forWord(word)

    /**
     * 在正文里定位生词所在的段落。
     *
     * 先按整词匹配（`\b`），找不到再退化成子串匹配：生词就是从正文里选出来的，理论上
     * 必然命中，但 `\b` 会把带撇号的词（`don't`）在中间断开，也可能因为词尾标点而落空。
     * 两级都找不到时返回 null，调用方退回正常的阅读位置恢复。
     *
     * 段落切分用 [ParagraphAligner.splitParagraphs]，与阅读页渲染时的分段同源——
     * 各自切一遍就会得到不同的段落序号。
     */
    private fun findWordAnchor(content: String, word: String): ReadingAnchor? {
        val needle = word.trim()
        if (needle.isEmpty()) return null
        val paragraphs = ParagraphAligner.splitParagraphs(content)

        val wholeWord = Regex("\\b${Regex.escape(needle)}\\b", RegexOption.IGNORE_CASE)
        paragraphs.forEachIndexed { index, paragraph ->
            wholeWord.find(paragraph)?.let {
                return ReadingAnchor(index, ReadingTextKind.ORIGINAL, it.range.first)
            }
        }
        paragraphs.forEachIndexed { index, paragraph ->
            val at = paragraph.indexOf(needle, ignoreCase = true)
            if (at >= 0) return ReadingAnchor(index, ReadingTextKind.ORIGINAL, at)
        }
        return null
    }

    private fun highlightParagraph(index: Int) {
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            _highlightedParagraph.value = index
            delay(PARAGRAPH_HIGHLIGHT_MS)
            _highlightedParagraph.value = null
        }
    }

    private fun clearParagraphHighlight() {
        highlightJob?.cancel()
        highlightJob = null
        _highlightedParagraph.value = null
    }

    /**
     * 停止播放
     */
    fun stopAudio() {
        stopVoicePreview()
        ttsGeneration++
        ttsPreparationJob?.cancel()
        ttsPreparationJob = null
        ttsRefreshJob?.cancel()
        ttsRefreshJob = null
        activeTtsUtterance = null
        sentenceRequest = null
        _ttsPositionTarget.value = null
        _readingTtsState.value = ReadingTtsState(requestId = ttsGeneration)
        // 作废在途请求并取消它。两件事都要做：
        //  - 递增代次让「已越过挂起点、取消挡不住」的那次放弃写状态与调 player
        //  - 取消 job 让还挂在缓存查询里的那次直接结束，不必等它恢复
        audioRequestGeneration++
        playWordAudioJob?.cancel()
        playWordAudioJob = null
        _isLoadingAudio.value = false
        audioPlayer.stop()
        // 混合读音下发声可能来自 TTS 而非 MediaPlayer，两者都要停，
        // 否则关闭弹窗后机器音仍会继续读完。
        ttsPlayer.stop()
    }

    /**
     * 清除单词定义（关闭 BottomSheet 时调用）
     */
    fun clearWordDefinition() {
        _isLoadingAudio.value = false
        wordSelectionGeneration++
        lookupWordJob?.cancel()
        _wordDefinition.value = null
        // 同时清除单词高亮
        _selectedWord.value = null
        _vocabularyWords.value = emptySet()
        // 停止播放音频
        stopAudio()
    }

    override fun onCleared() {
        translationPreparationJob?.cancel()
        super.onCleared()
        releaseTts()
    }
}
