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
 * 分组头部在 LazyColumn 里用的 item key。
 *
 * 必须是能存进 Bundle 的类型：LazySaveableStateHolder 会把 item key 原样交给
 * SaveableStateProvider 用于保存列表项状态，直接用 [VocabularyGroupId] 实例会在
 * 列表首次测量时抛 IllegalArgumentException 崩掉整个界面。String 是安全的。
 *
 * 前缀用于区分分组维度，避免不同维度下取值相同的分组撞 key。
 */
val VocabularyGroupId.listKey: String
    get() = when (this) {
        VocabularyGroupId.Today -> "time:today"
        VocabularyGroupId.Yesterday -> "time:yesterday"
        VocabularyGroupId.ThisWeek -> "time:this-week"
        is VocabularyGroupId.OlderDate -> "date:$value"
        is VocabularyGroupId.Alphabet -> "alphabet:$value"
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
