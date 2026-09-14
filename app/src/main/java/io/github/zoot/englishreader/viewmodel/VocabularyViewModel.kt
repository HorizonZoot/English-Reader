package io.github.zoot.englishreader.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.ui.screen.vocabulary.AlphabetGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.TimeGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val dictionaryRepository: DictionaryRepository
) : ViewModel() {

    private val timeGrouper = TimeGrouper()
    private val alphabetGrouper = AlphabetGrouper()

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

    fun deleteVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            try {
                vocabularyRepository.deleteVocabulary(vocabulary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VocabularyViewModel", "Vocabulary delete failed", e)
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
     * 静默忽略：用户在这期间又把同一个词加回了生词本，那正是他想要的结果。
     */
    fun restoreVocabulary(vocabulary: VocabularyEntity) {
        viewModelScope.launch {
            try {
                vocabularyRepository.insertVocabulary(vocabulary.copy(id = 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VocabularyViewModel", "Vocabulary restore failed", e)
            }
        }
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
