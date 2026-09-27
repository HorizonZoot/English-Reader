package io.github.zoot.englishreader.data.entity

/** 书架需要的单篇文章字段，不携带正文或译文。 */
data class ArticleSummary(
    val id: Long,
    val title: String,
    val createdAt: Long
)
