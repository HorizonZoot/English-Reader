package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.entity.VocabularyEntity

/**
 * 生词本的一次性 UI 事件。
 *
 * 合并成单个事件流的理由与 [ArticleListUiEvent] 相同：界面只有一个 Snackbar 宿主，
 * 多个 collector 会让「删除成功」「删除失败」「发音不可用」三条消息互相抢占，
 * 而在一个 collector 里顺序 `showSnackbar` 天然串行。
 */
sealed interface VocabularyUiEvent {

    /**
     * 删除已经落库。
     *
     * 必须在 `deleteVocabulary()` 返回**之后**才发：界面靠这条事件弹「已删除 + 撤销」，
     * 在派发删除时就提前弹会在写库失败时谎报成功。带上被删的实体，撤销要用它重新插入。
     */
    data class Deleted(val vocabulary: VocabularyEntity) : VocabularyUiEvent

    /** 删除没有落库。行仍在列表里，用户需要知道这次左滑没生效。 */
    data object DeleteFailed : VocabularyUiEvent

    /** 撤销删除没有落库。这条不能静默——用户以为词回来了，实际没有。 */
    data object RestoreFailed : VocabularyUiEvent

    /** 真人音、缓存、系统 TTS 全都不可用。用户是主动点的，静默无声会被当成功能没做。 */
    data object AudioUnavailable : VocabularyUiEvent
}
