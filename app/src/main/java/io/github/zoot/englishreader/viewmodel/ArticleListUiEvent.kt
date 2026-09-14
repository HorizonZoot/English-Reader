package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.importer.ImportFailure

/**
 * 文章列表页的一次性 UI 事件。
 *
 * ## 为什么合并成一个事件流
 *
 * 改造前界面有两个独立 collector（deleteError / importError），本次要加「导入成功」
 * 又不能再加第三个：三个 collector 会放大 Snackbar 的顺序竞争、让带动态参数的消息难以
 * 传递、把状态事件分散到多处、且测试须同时订阅多个 Flow。
 *
 * 界面只留一个 collector，事件类型由 sealed interface 区分。
 */
sealed interface ArticleListUiEvent {

    /**
     * 导入成功。
     *
     * 必须在 `insertArticle()` 返回 ID **之后**发出，不能在解析成功时提前发——
     * 写库仍可能失败。
     *
     * @param exceedsFullExplanationLimit 正文超过全文 AI 解释上限。
     *   仅作提示，**不拦截导入、不静默截断**。
     */
    data class ImportSucceeded(
        val articleId: Long,
        val title: String,
        val exceedsFullExplanationLimit: Boolean
    ) : ArticleListUiEvent

    /** 导入失败，[failure] 供界面映射为具体原因而非笼统的「导入失败」。 */
    data class ImportFailed(val failure: ImportFailure) : ArticleListUiEvent

    /**
     * 整本书导入成功。
     *
     * 与 [ImportSucceeded] 分开而不是给它加个可空 `bookId`：两者的后续动作不同
     * （书跳目录页，文章直接进阅读页），且书没有「超出全文 AI 解释上限」这个概念
     * ——AI 解释作用于单个章节。用可空字段合并会让每个消费点都得处理不可能状态。
     *
     * 必须在原子入库返回 `bookId` **之后**发出。
     */
    data class BookImportSucceeded(
        val bookId: Long,
        val title: String,
        val chapterCount: Int
    ) : ArticleListUiEvent

    data object DeleteFailed : ArticleListUiEvent
}
