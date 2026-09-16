package io.github.zoot.englishreader.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.WordAudioUrl
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.ui.screen.vocabulary.AlphabetGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.TimeGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

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
    private val settingsPreferences: SettingsPreferences
) : ViewModel() {

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

    // 分组后的生词列表
    val groups: StateFlow<List<VocabularyGroup>> = combine(
        vocabulary,
        groupType,
        expandedGroups
    ) { words, type, expanded ->
        when (type) {
            GroupType.ByTime -> timeGrouper.group(words, expanded)
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
     * 缓存的 key 是 lowercase 后的词——词典查询契约要求已归一化的输入，且
     * `WordLemmatizer` 的候选也走 lowercase，同一词的任意大小写写法结果相同。
     */
    val details: StateFlow<Map<Long, VocabularyWordDetail>> = vocabularyWithSource
        .map { rows -> resolveDetails(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

    /**
     * 切换分组类型
     */
    fun switchGroupType(type: GroupType) {
        _groupType.value = type
        // 切换分组时重置展开状态
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

    /**
     * 正在准备读音的生词 id；null 表示当前没有在准备。
     *
     * 只覆盖「需要联网取音频」的那一段：命中本地缓存时 prepare() 是毫秒级，闪一下
     * 转圈比不转更难看。
     */
    private val _loadingAudioWordId = MutableStateFlow<Long?>(null)
    val loadingAudioWordId: StateFlow<Long?> = _loadingAudioWordId.asStateFlow()

    private var playAudioJob: Job? = null
    private var audioGeneration = 0

    /**
     * 播放生词读音。
     *
     * 顺序沿用阅读页的查词发音：**先查本地缓存，再判网络**。反过来的话，离线时明明
     * 缓存里已经有的词也用不上——而缓存存在的全部意义就是让这些词离线可用。
     *
     * 比阅读页那份简单的地方：这里没有「弹窗中途被关掉」这种失效场景，所以一个代次
     * 就够——连点两个词时，前一个的结果不得覆盖后一个的状态。
     */
    fun playWordAudio(vocabulary: VocabularyEntity) {
        val word = vocabulary.word.trim()
        if (word.isEmpty()) return
        val key = word.lowercase()
        val generation = ++audioGeneration
        playAudioJob?.cancel()
        playAudioJob = viewModelScope.launch {
            try {
                val cached = pronunciationAudioCache.get(key)
                if (generation != audioGeneration) return@launch

                if (cached == null && !networkChecker.isOnline()) {
                    speakViaTts(word)
                    return@launch
                }

                if (cached == null) _loadingAudioWordId.value = vocabulary.id

                val url = cached?.absolutePath ?: WordAudioUrl.forWord(word)
                audioPlayer.play(
                    url = url,
                    onError = { exception ->
                        // 回到主线程后再检查代次，旧回调不能打断后来开始的那次播放。
                        viewModelScope.launch playbackFailure@ {
                            if (generation != audioGeneration) return@playbackFailure
                            _loadingAudioWordId.value = null
                            // 只记类名：异常信息可能回显带单词的 URL。
                            Log.e(
                                "VocabularyViewModel",
                                "Audio playback failed: ${exception.javaClass.simpleName}"
                            )
                            // 本地文件不可播时失效它，让下次重走远端；远端失败不动缓存。
                            if (cached != null) pronunciationAudioCache.invalidate(key)
                            speakViaTts(word)
                        }
                    }
                )
                if (generation == audioGeneration) _loadingAudioWordId.value = null

                // 播放已经启动，另起协程把音频存入缓存，不阻塞本次播放。
                // 与阅读页同一取舍：首次发两个请求，换后续每次为零。
                if (cached == null && networkChecker.isOnline()) {
                    viewModelScope.launch {
                        runCatching { pronunciationAudioCache.download(key, url) }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                }
            } catch (e: CancellationException) {
                if (generation == audioGeneration) _loadingAudioWordId.value = null
                throw e
            } catch (e: Exception) {
                if (generation != audioGeneration) return@launch
                _loadingAudioWordId.value = null
                Log.e(
                    "VocabularyViewModel",
                    "Failed to start audio playback: ${e.javaClass.simpleName}"
                )
                speakViaTts(word)
            }
        }
    }

    /**
     * 用系统 TTS 兜底朗读。
     *
     * 联网语音的授权**每次现读**，不缓存：用户可能刚在设置里关掉它，而缓存值会让这一次朗读
     * 仍然走网络——那是把「已撤销的同意」当成有效同意。`allowNetworkTts` 默认 false，
     * 所以未表态的用户仍然只用本地语音。
     */
    private suspend fun speakViaTts(word: String) {
        val allowNetwork = settingsPreferences.allowNetworkTts.first()
        // trySend：Channel 有缓冲、永不阻塞，回调可能同步触发，无需起协程。
        ttsPlayer.speakWord(word, allowNetwork) {
            _uiEvent.trySend(VocabularyUiEvent.AudioUnavailable)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // AudioPlayer 是 @Singleton：离开生词本后不该继续出声。
        audioPlayer.stop()
    }

    /**
     * 解析整屏生词的补充信息。
     *
     * 逐词串行查询（词典查询本身会命中 `dictionary.word` 的主键索引，单次约微秒级），
     * 并用 [glossCacheMutex] 串行化：`WhileSubscribed` 重启时新旧收集协程可能短暂重叠，
     * 而 [glossCache] 是普通 MutableMap。
     */
    private suspend fun resolveDetails(
        rows: List<VocabularyWithSource>
    ): Map<Long, VocabularyWordDetail> = glossCacheMutex.withLock {
        rows.associate { row ->
            val word = row.vocabulary
            val key = word.word.lowercase()
            if (!glossCache.containsKey(key)) {
                glossCache[key] = lookupGloss(key)
            }
            val gloss = glossCache[key]
            word.id to VocabularyWordDetail(
                phonetic = gloss?.phonetic,
                chinese = gloss?.chinese,
                headword = gloss?.headword,
                sourceTitle = row.articleTitle
            )
        }
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

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5000L
    }
}
