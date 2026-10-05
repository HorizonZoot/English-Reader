package io.github.zoot.englishreader.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.WordPronunciation
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.ui.screen.vocabulary.AlphabetGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.groupStudyWords
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.TimeGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupingTime
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import io.github.zoot.englishreader.util.WordLookupKey
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.core.excerptSpeechChunks
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsPlaybackResult
import kotlinx.coroutines.CompletableDeferred
import io.github.zoot.englishreader.util.TtsPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.TimeZone
import javax.inject.Inject

data class ExcerptPlaybackState(val excerptId: Long, val loading: Boolean)

/**
 * 生词本 ViewModel
 */
@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val audioPlayer: AudioPlayer,
    private val networkChecker: NetworkChecker,
    private val ttsPlayer: TtsPlayer,
    private val pronunciationAudioCache: PronunciationAudioCache,
    private val settingsPreferences: SettingsPreferences,
    private val excerptRepository: ExcerptRepository
) : ViewModel() {

    private val _excerptPlayback = MutableStateFlow<ExcerptPlaybackState?>(null)
    val excerptPlayback: StateFlow<ExcerptPlaybackState?> = _excerptPlayback.asStateFlow()
    private var excerptPlaybackJob: Job? = null

    val excerpts = excerptRepository.observeAll()
        .catch { error ->
            if (error is CancellationException) throw error
            _uiEvent.send(VocabularyUiEvent.ExcerptLoadFailed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    private val excerptDeletesInFlight = mutableSetOf<Long>()
    private var sourceRequest: Job? = null
    private val _excerptToOpen = MutableSharedFlow<ExcerptEntity>()
    val excerptToOpen: SharedFlow<ExcerptEntity> = _excerptToOpen.asSharedFlow()

    fun cancelExcerptNavigation() {
        sourceRequest?.cancel()
        sourceRequest = null
    }

    fun deleteExcerpt(excerpt: ExcerptEntity) {
        if (_excerptPlayback.value?.excerptId == excerpt.id) stopAudio()
        if (!excerptDeletesInFlight.add(excerpt.id)) return
        viewModelScope.launch {
            try {
                excerptRepository.deleteForUndo(excerpt.id)?.let { deleted ->
                    _uiEvent.send(VocabularyUiEvent.ExcerptDeleted(deleted))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiEvent.send(VocabularyUiEvent.ExcerptDeleteFailed)
            } finally {
                excerptDeletesInFlight.remove(excerpt.id)
            }
        }
    }

    fun restoreExcerpt(excerpt: ExcerptEntity) {
        viewModelScope.launch {
            try {
                excerptRepository.restore(excerpt)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiEvent.send(VocabularyUiEvent.ExcerptRestoreFailed)
            }
        }
    }

    fun openExcerpt(excerpt: ExcerptEntity) {
        stopAudio()
        cancelExcerptNavigation()
        sourceRequest = viewModelScope.launch {
            try {
                val available = excerptRepository.hasSource(excerpt)
                currentCoroutineContext().ensureActive()
                if (available) _excerptToOpen.emit(excerpt)
                else _uiEvent.send(VocabularyUiEvent.ExcerptSourceUnavailable)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiEvent.send(VocabularyUiEvent.ExcerptLoadFailed)
            }
        }
    }

    private val timeGrouper = TimeGrouper()
    private val alphabetGrouper = AlphabetGrouper()

    // 单一事件流，界面只需一个 collector（详见 VocabularyUiEvent 的说明）。
    private val _uiEvent = Channel<VocabularyUiEvent>(Channel.BUFFERED)
    val uiEvent: Flow<VocabularyUiEvent> = _uiEvent.receiveAsFlow()

    /**
     * 正在删除的生词 id。
     *
     * 只在主线程访问（[deleteVocabulary] 同步登记、协程 `finally` 里移除，
     * `viewModelScope` 默认 `Dispatchers.Main.immediate`），故用普通 MutableSet。
     */
    private val deletesInFlight = mutableSetOf<Long>()

    // 生词 + 来源文章标题。标题由 SQL LEFT JOIN 一次取回，界面不必再逐条回查文章。
    private val vocabularyWithSource: StateFlow<List<VocabularyWithSource>> =
        vocabularyRepository.getAllVocabularyWithSource()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    // 原始生词列表
    val vocabulary: StateFlow<List<VocabularyEntity>> = vocabularyWithSource
        .map { rows -> rows.map { it.vocabulary } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    // 当前分组类型
    private val _groupType = MutableStateFlow<GroupType>(GroupType.ByTime)
    val groupType: StateFlow<GroupType> = _groupType.asStateFlow()

    // 展开的稳定分组标识集合，不依赖当前 locale 的显示文案
    private val _expandedGroups = MutableStateFlow<Set<VocabularyGroupId>>(
        setOf(VocabularyGroupId.Today)
    )
    val expandedGroups: StateFlow<Set<VocabularyGroupId>> = _expandedGroups.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab = _selectedTab.asStateFlow()
    private val _sortMenuExpanded = MutableStateFlow(false)
    val sortMenuExpanded = _sortMenuExpanded.asStateFlow()
    private val _expandedExcerpts = MutableStateFlow<Set<Long>>(emptySet())
    val expandedExcerpts = _expandedExcerpts.asStateFlow()
    private val _showStudyInfo = MutableStateFlow(false)
    val showStudyInfo = _showStudyInfo.asStateFlow()
    private val _excerptMenuId = MutableStateFlow<Long?>(null)
    val excerptMenuId = _excerptMenuId.asStateFlow()
    private val _wordMenuKey = MutableStateFlow<String?>(null)
    val wordMenuKey = _wordMenuKey.asStateFlow()
    private val _wordToDelete = MutableStateFlow<StudyWord?>(null)
    val wordToDelete = _wordToDelete.asStateFlow()

    fun setExcerptMenu(id: Long?) { _excerptMenuId.value = id }
    fun setWordMenu(key: String?) { _wordMenuKey.value = key }
    fun confirmWordDeletion(word: StudyWord?) { _wordToDelete.value = word }

    fun selectTab(index: Int) {
        cancelExcerptNavigation()
        cancelContextNavigation()
        stopAudio()
        _sortMenuExpanded.value = false
        _excerptMenuId.value = null
        _wordMenuKey.value = null
        _wordToDelete.value = null
        _selectedTab.value = index.coerceIn(0, 1)
    }

    fun setSortMenuExpanded(expanded: Boolean) { _sortMenuExpanded.value = expanded }
    fun setStudyInfoVisible(visible: Boolean) { _showStudyInfo.value = visible }
    fun toggleExcerptExpanded(id: Long) {
        _expandedExcerpts.update { if (id in it) it - id else it + id }
    }

    private val sortWrites = Mutex()
    private var sortRequest = 0L
    private var committedGroupType: GroupType = GroupType.ByTime

    init {
        viewModelScope.launch {
            try {
                val request = sortRequest
                val alphabetical = settingsPreferences.favoritesAlphabetical.first()
                if (request == sortRequest) {
                    committedGroupType = if (alphabetical) GroupType.ByAlphabet else GroupType.ByTime
                    applyGroupType(committedGroupType)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiEvent.send(VocabularyUiEvent.SortPreferenceFailed)
            }
        }
    }

    private val groupingTime = MutableStateFlow(VocabularyGroupingTime.current())
    val favoritesTime = groupingTime.asStateFlow()
    private val contextRefresh = MutableStateFlow(0)
    private val contextDeletesInFlight = mutableSetOf<String>()
    private var contextSourceRequest: Job? = null
    private val _contextToOpen = MutableSharedFlow<ContextCardEntity>()
    val contextToOpen = _contextToOpen.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val contextLibrary = contextRefresh.flatMapLatest {
        combine(vocabularyRepository.observeStudyWords(), vocabularyRepository.observeContextReview()) { words, round ->
            ContextLibraryState(words = words, round = round, loading = false)
        }.onStart { emit(ContextLibraryState()) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(ContextLibraryState(loading = false, failed = true))
            }
    }.combine(groupingTime) { library, time ->
        library.copy(today = Instant.ofEpochMilli(time.nowMillis).atZone(time.zoneId).toLocalDate().toEpochDay())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ContextLibraryState())

    val studyGroups = combine(contextLibrary, groupType, expandedGroups, groupingTime) { library, type, expanded, time ->
        groupStudyWords(library.words, type, expanded, time)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    fun retryContextLibrary() { contextRefresh.value++ }

    fun deleteStudyWord(word: StudyWord) {
        if (!contextDeletesInFlight.add(word.key)) return
        stopAudio()
        viewModelScope.launch {
            try {
                if (vocabularyRepository.deleteStudyWord(word)) {
                    _uiEvent.send(when (word) {
                        is StudyWord.Legacy -> VocabularyUiEvent.Deleted(word.vocabulary)
                        is StudyWord.Context -> VocabularyUiEvent.ContextDeleted
                    })
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiEvent.send(VocabularyUiEvent.ContextDeleteFailed)
            } finally {
                contextDeletesInFlight.remove(word.key)
            }
        }
    }

    fun cancelContextNavigation() {
        contextSourceRequest?.cancel()
        contextSourceRequest = null
    }

    fun openContextSource(card: ContextCardEntity) {
        stopAudio()
        cancelContextNavigation()
        contextSourceRequest = viewModelScope.launch {
            try {
                val available = vocabularyRepository.hasContextSource(card)
                currentCoroutineContext().ensureActive()
                if (available) _contextToOpen.emit(card)
                else _uiEvent.send(VocabularyUiEvent.ContextSourceUnavailable)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiEvent.send(VocabularyUiEvent.ContextSourceUnavailable)
            }
        }
    }

    fun updateGroupingTime(time: VocabularyGroupingTime) {
        groupingTime.value = time
    }

    // 分组后的生词列表
    val groups: StateFlow<List<VocabularyGroup>> = combine(
        vocabulary,
        groupType,
        expandedGroups,
        groupingTime
    ) { words, type, expanded, time ->
        when (type) {
            GroupType.ByTime -> timeGrouper.group(
                words, expanded, time.nowMillis, TimeZone.getTimeZone(time.zoneId)
            )
            GroupType.ByAlphabet -> alphabetGrouper.group(words, expanded)
            // ByArticle 已从 UI 中移除，如果意外触发则返回空列表
            GroupType.ByArticle -> emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /**
     * 卡片补充信息，按生词 id 索引。
     *
     * 释义要在打开生词本时逐词查库（[DictionaryRepository.lookupOffline]），所以结果按词缓存：
     * 生词列表每次变动只补查新出现的词，删除/撤销不会让整屏重新查一遍。
     * 缓存 key 统一大小写与内部排版撇号；不更改生词的显示文本。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val details: StateFlow<Map<Long, VocabularyWordDetail>> = combine(
        vocabularyWithSource,
        dictionaryRepository.revision
    ) { rows, revision -> rows to revision }
        .mapLatest { (rows, revision) -> resolveDetails(rows, revision) }
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

    /**
     * 切换分组类型
     */
    fun switchGroupType(type: GroupType) {
        if (type == GroupType.ByArticle) {
            return
        }
        sortRequest += 1
        val request = sortRequest
        applyGroupType(type)
        viewModelScope.launch {
            sortWrites.withLock {
                try {
                    settingsPreferences.setFavoritesAlphabetical(type == GroupType.ByAlphabet)
                    committedGroupType = type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (request == sortRequest) {
                        applyGroupType(committedGroupType)
                    }
                    _uiEvent.send(VocabularyUiEvent.SortPreferenceFailed)
                }
            }
        }
    }

    private fun applyGroupType(type: GroupType) {
        _groupType.value = type
        _expandedGroups.value = when (type) {
            GroupType.ByTime -> setOf(VocabularyGroupId.Today)
            else -> emptySet()
        }
    }

    /**
     * 切换分组展开/收起
     */
    fun toggleGroup(id: VocabularyGroupId) {
        _expandedGroups.value = if (id in _expandedGroups.value) {
            _expandedGroups.value - id
        } else {
            _expandedGroups.value + id
        }
    }

    /**
     * 删除一条生词。
     *
     * [VocabularyUiEvent.Deleted] 在写库返回**之后**才发，界面据此才弹「已删除 + 撤销」：
     * 提前弹会在失败时谎报成功，而生词没有别的找回途径。失败发
     * [VocabularyUiEvent.DeleteFailed]，行留在列表里。
     *
     * [deletesInFlight] 按 id 去重，检查与登记都在**调用方线程**上同步完成：
     * `viewModelScope.launch` 的协程体要等调度，放在体内检查时同一帧的两次调用会都通过。
     * 左滑手势的 `confirmValueChange` 可能在目标值来回穿越阈值时被反复调用，去重放在
     * 这里而不是界面，是因为它跨重组存活，也能被单测直接盯住。
     */
    fun deleteVocabulary(vocabulary: VocabularyEntity) {
        if (!deletesInFlight.add(vocabulary.id)) return
        viewModelScope.launch {
            try {
                vocabularyRepository.deleteVocabulary(vocabulary)
                _uiEvent.trySend(VocabularyUiEvent.Deleted(vocabulary))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VocabularyViewModel", "Vocabulary delete failed", e)
                _uiEvent.trySend(VocabularyUiEvent.DeleteFailed)
            } finally {
                deletesInFlight.remove(vocabulary.id)
            }
        }
    }

    /**
     * 恢复被撤销删除的生词。
     *
     * 必须把 id 归零再插入：`id` 是 autoGenerate，带着原 id 插入会写成显式主键，
     * 而 SQLite 不带 AUTOINCREMENT 时下一个 id 取 `max(id)+1`——删掉最后一行后，
     * 新保存的生词会复用同一个 id，撤销删除就会主键冲突或覆盖掉那行新词。
     *
     * `UNIQUE(word, articleId)` 冲突（[io.github.zoot.englishreader.data.repository.VocabularyInsertResult.AlreadyExists]）
     * 不算失败：用户在这期间又把同一个词加回了生词本，那正是他想要的结果，
     * 词确实在生词本里，不该报错。只有抛异常（真的没写进去）才发
     * [VocabularyUiEvent.RestoreFailed]——撤销失败必须说出来，否则用户以为词回来了。
     */
    fun restoreVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            try {
                vocabularyRepository.insertVocabulary(vocabulary.copy(id = 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VocabularyViewModel", "Vocabulary restore failed", e)
                _uiEvent.trySend(VocabularyUiEvent.RestoreFailed)
            }
        }
    }

    private val pronunciation = WordPronunciation(
        viewModelScope, audioPlayer, networkChecker, ttsPlayer, pronunciationAudioCache,
        settingsPreferences, onUnavailable = { _uiEvent.trySend(VocabularyUiEvent.AudioUnavailable) }
    )
    val loadingAudioKey = pronunciation.loadingKey
    val loadingAudioWordId = loadingAudioKey.map { it?.removePrefix("vocabulary:")?.toLongOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var audioGeneration = 0L

    fun playWordAudio(vocabulary: VocabularyEntity) = playStudyWordAudio(StudyWord.Legacy(vocabulary))

    fun playStudyWordAudio(word: StudyWord) {
        if (word.word.isBlank()) return
        stopExcerptPlayback()
        pronunciation.play(word.key, word.word)
    }

    fun toggleExcerptPlayback(excerpt: ExcerptEntity) {
        val wasPlaying = _excerptPlayback.value?.excerptId == excerpt.id
        stopAudio(preserveTtsPreparation = true)
        if (wasPlaying) return
        val generation = audioGeneration
        _excerptPlayback.value = ExcerptPlaybackState(excerpt.id, loading = true)
        excerptPlaybackJob = viewModelScope.launch {
            try {
                val settings = settingsPreferences.ttsReadingSettings.first()
                val allowNetwork = settingsPreferences.allowNetworkTts.first()
                currentCoroutineContext().ensureActive()
                for (chunk in excerptSpeechChunks(excerpt.text)) {
                    if (chunk.isBlank()) continue
                    val completed = CompletableDeferred<TtsFailureReason?>()
                    ttsPlayer.speakReading(chunk, settings, allowNetwork) { result ->
                        if (generation == audioGeneration && !completed.isCompleted) {
                            when (result) {
                                is TtsPlaybackResult.Started -> _excerptPlayback.value = ExcerptPlaybackState(excerpt.id, loading = false)
                                is TtsPlaybackResult.Finished -> completed.complete(null)
                                is TtsPlaybackResult.Failed -> completed.complete(result.reason)
                            }
                        }
                    }
                    val failure = completed.await()
                    if (failure != null) {
                        _uiEvent.send(VocabularyUiEvent.ExcerptAudioFailed(failure))
                        break
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == audioGeneration) _uiEvent.send(VocabularyUiEvent.AudioUnavailable)
            } finally {
                if (generation == audioGeneration) {
                    _excerptPlayback.value = null
                    ttsPlayer.stop()
                }
            }
        }
    }

    fun stopAudio() = stopAudio(preserveTtsPreparation = false)

    private fun stopAudio(preserveTtsPreparation: Boolean) {
        stopExcerptPlayback()
        pronunciation.stop(preserveTtsPreparation)
    }

    private fun stopExcerptPlayback() {
        audioGeneration++
        excerptPlaybackJob?.cancel()
        excerptPlaybackJob = null
        _excerptPlayback.value = null
    }

    override fun onCleared() {
        stopAudio()
        ttsPlayer.shutdown()
        super.onCleared()
    }

    /**
     * 解析整屏生词的补充信息。
     *
     * 逐词串行查询（词典查询本身会命中 `dictionary.word` 的主键索引，单次约微秒级），
     * 并用 [glossCacheMutex] 串行化：`WhileSubscribed` 重启时新旧收集协程可能短暂重叠，
     * 而 [glossCache] 是普通 MutableMap。
     */
    private suspend fun resolveDetails(
        rows: List<VocabularyWithSource>,
        revision: Long
    ): Map<Long, VocabularyWordDetail>? = glossCacheMutex.withLock {
        if (revision != dictionaryRepository.revision.value) return@withLock null
        if (glossCacheRevision != revision) {
            glossCache.clear()
            glossCacheRevision = revision
        }
        val details = rows.associate { row ->
            val word = row.vocabulary
            val key = WordLookupKey.normalize(word.word)
            if (!glossCache.containsKey(key)) {
                val gloss = lookupGloss(key)
                currentCoroutineContext().ensureActive()
                if (revision != dictionaryRepository.revision.value) return@withLock null
                glossCache[key] = gloss
            }
            val gloss = glossCache[key]
            word.id to VocabularyWordDetail(
                phonetic = gloss?.phonetic,
                chinese = gloss?.chinese,
                headword = gloss?.headword,
                sourceTitle = row.articleTitle
            )
        }
        details.takeIf { revision == dictionaryRepository.revision.value }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val studyDetails: StateFlow<Map<String, VocabularyWordDetail>> = combine(
        contextLibrary.map { it.words }.distinctUntilChanged(), dictionaryRepository.revision
    ) { words, revision -> words to revision }
        .mapLatest { (words, revision) -> resolveStudyDetails(words, revision) }
        .filterNotNull()
        .catch { error ->
            if (error is CancellationException) throw error
            _uiEvent.send(VocabularyUiEvent.DictionaryLoadFailed)
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

    private suspend fun resolveStudyDetails(
        words: List<StudyWord>,
        revision: Long
    ): Map<String, VocabularyWordDetail>? = glossCacheMutex.withLock {
        if (revision != dictionaryRepository.revision.value) return@withLock null
        if (glossCacheRevision != revision) {
            glossCache.clear()
            glossCacheRevision = revision
        }
        val details = words.associate { word ->
            val key = WordLookupKey.normalize(word.word)
            if (!glossCache.containsKey(key)) {
                val gloss = lookupGloss(key)
                currentCoroutineContext().ensureActive()
                if (revision != dictionaryRepository.revision.value) return@withLock null
                glossCache[key] = gloss
            }
            val gloss = glossCache[key]
            val dictionaryPhonetic = gloss?.phonetic?.takeIf(String::isNotBlank)
            word.key to VocabularyWordDetail(
                phonetic = dictionaryPhonetic ?: (word as? StudyWord.Legacy)?.vocabulary?.phonetic,
                chinese = gloss?.chinese,
                headword = gloss?.headword?.takeIf { dictionaryPhonetic != null },
                sourceTitle = word.sourceTitle
            )
        }
        details.takeIf { revision == dictionaryRepository.revision.value }
    }

    /** 离线词典查询；词库未收录时返回 null。 */
    private suspend fun lookupGloss(word: String): GlossLookup? {
        val hit = dictionaryRepository.lookupOffline(word) ?: return null
        return GlossLookup(
            phonetic = hit.entry.phonetic,
            chinese = hit.entry.chinese,
            // 变形词（lives）收回原形（live）时标注；直接命中时两者相同，不标注。
            headword = hit.entry.word.takeIf { it != word }
        )
    }

    /** 词典命中的最小信息集，界面只需要这三项。 */
    private data class GlossLookup(
        val phonetic: String?,
        val chinese: String,
        val headword: String?
    )

    /** 词（小写）→ 词典结果；值为 null 表示「查过但词库未收录」，用于跳过重复查询。 */
    private val glossCache = mutableMapOf<String, GlossLookup?>()

    private val glossCacheMutex = Mutex()
    private var glossCacheRevision = -1L

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5000L
    }
}
