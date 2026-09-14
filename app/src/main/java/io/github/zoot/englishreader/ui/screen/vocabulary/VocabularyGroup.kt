package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.data.entity.VocabularyEntity

/**
 * 生词分组类型
 */
sealed class GroupType {
    /** 按时间分组（默认） */
    object ByTime : GroupType()

    /** 按文章分组 */
    object ByArticle : GroupType()

    /** 按字母分组 */
    object ByAlphabet : GroupType()
}

sealed interface VocabularyGroupId {
    data object Today : VocabularyGroupId
    data object Yesterday : VocabularyGroupId
    data object ThisWeek : VocabularyGroupId
    data class OlderDate(val value: String) : VocabularyGroupId
    data class Alphabet(val value: String) : VocabularyGroupId
}

/**
 * 生词分组
 *
 * @param id 稳定的分组标识，显示文案由 Compose 根据 locale 映射
 * @param words 该分组下的单词列表
 * @param isExpanded 是否展开
 */
data class VocabularyGroup(
    val id: VocabularyGroupId,
    val words: List<VocabularyEntity>,
    val isExpanded: Boolean = false
)
