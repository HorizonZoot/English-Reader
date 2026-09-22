package io.github.zoot.englishreader.model

import androidx.room.Embedded
import io.github.zoot.englishreader.data.entity.ArticleEntity

/** 单次 JOIN 的结果；正文、译文与已发布布局必须来自同一个数据库快照。 */
data class ReadingArticleRecord(
    @Embedded val article: ArticleEntity,
    val appliedPlan: String? = null,
    val appliedSourceFingerprint: String? = null,
    val appliedTranslationFingerprint: String? = null
) {
    override fun toString(): String = "ReadingArticleRecord(articleId=${article.id}, content=[REDACTED])"
}

/** 位置写入携带实际渲染的版本，而非回调执行时数据库的最新版本。 */
data class ReadingPublication(val translation: String?, val appliedPlan: String?) {
    override fun toString(): String = "ReadingPublication(content=[REDACTED])"
}

data class ReadingArticleState(
    val article: ArticleEntity,
    val publication: ReadingPublication = ReadingPublication(article.translation, null),
    val layout: AppliedTranslationLayout? = null
) {
    override fun toString(): String = "ReadingArticleState(articleId=${article.id}, content=[REDACTED])"
}
