package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.importer.ImportFailure

/** 编辑打开时的快照；阅读时间与后台生成的译文不属于编辑冲突字段。 */
data class ArticleEditSnapshot(
    val articleId: Long,
    val title: String,
    val content: String
) {
    override fun toString(): String =
        "ArticleEditSnapshot(articleId=$articleId, title=[REDACTED], content=[REDACTED])"
}

sealed interface ArticleEditResult {
    data object Saved : ArticleEditResult
    data object NotFound : ArticleEditResult
    data object NotStandalone : ArticleEditResult
    data object Conflict : ArticleEditResult
    data class InvalidContent(val failure: ImportFailure) : ArticleEditResult
}
