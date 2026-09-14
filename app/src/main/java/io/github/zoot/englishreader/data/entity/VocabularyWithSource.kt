package io.github.zoot.englishreader.data.entity

import androidx.room.Embedded

/**
 * 生词 + 来源文章标题的查询投影，**不是一张表**，仅供 [io.github.zoot.englishreader.data.dao.VocabularyDao] 返回。
 *
 * 界面要在每条生词下标注「来自《标题》」，用 LEFT JOIN 一次取回，而不是按 articleId
 * 逐条回查：[ArticleDao.getArticleById] 会把整篇正文（单篇上限 40,000 字符）一并读出，
 * 生词分散在几十篇文章时就是几十次全量正文读取。
 *
 * LEFT JOIN 而非 INNER JOIN：`articleId` 允许为 NULL（删书时生词会被解绑而不是删除），
 * 这类生词必须仍然出现在生词本里，此时 [articleTitle] 为 null。
 */
data class VocabularyWithSource(
    @Embedded val vocabulary: VocabularyEntity,

    /** 来源文章标题；生词未关联文章或文章已不存在时为 null。 */
    val articleTitle: String?
)
