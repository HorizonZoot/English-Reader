package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookEntity

/**
 * 书架条目：一篇单独的文章，或一本书。
 *
 * ## 为什么需要这层封装
 *
 * 章节也是 [ArticleEntity]——阅读页、词典、TTS、AI 缓存、生词全都靠 `articleId` 工作，
 * 给章节另建一套正文表会把这些能力全部推翻重做。代价是「文章表里同时躺着单篇文章和
 * 章节」，若书架直接渲染 `articles`，导入一本 500 章的长篇会在列表里铺出 500 行，
 * 用户再也找不到自己原来的文章。
 *
 * 所以区分发生在两处，缺一不可：
 *  - 查询层：[io.github.zoot.englishreader.data.dao.ArticleDao.getStandaloneArticles]
 *    排除所有出现在 `book_chapters` 里的 articleId；
 *  - 展示层：本类型把「书」和「单篇」并列为同一列表的两种条目。
 *
 * 不用 `ArticleEntity?` + `BookEntity?` 这类可空字段的单一 data class：那样每个消费点
 * 都要自己判空并处理「两个都为 null」「两个都非 null」这些不可能状态。sealed interface
 * 让 `when` 穷尽，编译器替我们拦住遗漏分支。
 */
sealed interface LibraryItem {

    /** 排序键：书用 `lastReadAt ?: createdAt`，文章用 `createdAt`，取值统一为毫秒。 */
    val sortKey: Long

    /** 列表 key，必须在两类条目间全局唯一（见 [Book.listKey] 的说明）。 */
    val listKey: String

    /** 单篇导入或内置样本文章。 */
    data class Article(val article: ArticleEntity) : LibraryItem {
        override val sortKey: Long get() = article.createdAt
        override val listKey: String get() = "article-${article.id}"
    }

    /**
     * 一本书（多章）。
     *
     * [listKey] 带类型前缀是必需的：书 id 和文章 id 来自两张不同表的自增序列，
     * 必然出现 `books.id == articles.id`。若直接用数字当 `LazyColumn` 的 key，
     * Compose 会认为两条不同条目是同一项，复用错误的 composable 状态。
     */
    data class Book(val book: BookEntity) : LibraryItem {
        override val sortKey: Long get() = book.lastReadAt ?: book.createdAt
        override val listKey: String get() = "book-${book.id}"
    }
}
