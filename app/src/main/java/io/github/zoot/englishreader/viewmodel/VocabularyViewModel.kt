package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.ui.screen.vocabulary.AlphabetGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.TimeGrouper
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 生词本 ViewModel
 */
@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository
) : ViewModel() {

    private val timeGrouper = TimeGrouper()
    private val alphabetGrouper = AlphabetGrouper()

    // 原始生词列表
    val vocabulary: StateFlow<List<VocabularyEntity>> = vocabularyRepository.getAllVocabulary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            vocabularyRepository.deleteVocabulary(vocabulary)
        }
    }
}
