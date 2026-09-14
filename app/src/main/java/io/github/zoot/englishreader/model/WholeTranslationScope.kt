package io.github.zoot.englishreader.model

/**
 * 一次全文翻译任务的目标范围。
 *
 * 两个变体互斥，且**不是**同一个东西的参数化：当前文章是用户正在读的这一篇，章节范围是
 * 整本书按阅读顺序的全部章节。两者的请求量差一到两个数量级，UI 必须让用户在开始前看清
 * 自己选的是哪个，所以类型上就分开，不用一个 `articleIds: List<Long>` 混同。
 */
sealed interface WholeTranslationScope {

    /** 参与本次任务的 article，顺序即处理顺序。 */
    val articleIds: List<Long>

    /** 稳定的范围标识，用于识别「同源任务」；不含任何正文或用户内容。 */
    val scopeKey: String

    data class CurrentArticle(val articleId: Long) : WholeTranslationScope {
        init {
            require(articleId > 0) { "articleId must be positive" }
        }

        override val articleIds: List<Long> get() = listOf(articleId)

        override val scopeKey: String get() = "article:$articleId"
    }

    /**
     * 整本书的章节范围。
     *
     * [articleIds] 必须已按 `chapterIndex` 升序排列且去重：任务的段落快照按这个顺序展开，
     * 顺序不稳定会让「预计请求数」与实际处理顺序对不上，恢复时也无法判断进度。
     */
    data class Chapter(
        val bookId: Long,
        override val articleIds: List<Long>
    ) : WholeTranslationScope {
        init {
            require(bookId > 0) { "bookId must be positive" }
            require(articleIds.isNotEmpty()) { "chapter scope must contain at least one article" }
            require(articleIds.all { it > 0 }) { "articleIds must be positive" }
            require(articleIds.distinct().size == articleIds.size) {
                "articleIds must not contain duplicates"
            }
        }

        override val scopeKey: String get() = "book:$bookId"
    }
}
