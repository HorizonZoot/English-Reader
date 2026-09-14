package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 书架条目的排序与列表 key。
 *
 * 这两条都是**只有在书和文章混排时才会暴露**的性质，所以单独测：
 *  - id 撞号：两张表各自自增，`books.id == articles.id` 是常态而非边缘情况；
 *  - 排序语义：书用 `lastReadAt ?: createdAt`，文章用 `createdAt`。
 */
class LibraryItemTest {

    @Test
    fun listKey_sameNumericId_doesNotCollideAcrossTypes() {
        // 关键场景：两张表的自增序列必然产出相同数字 id。
        // 若 LazyColumn 的 key 只用数字，Compose 会把这两条当成同一项，
        // 复用错误条目的 composable 状态（例如删除确认对话框指向错的东西）。
        val article = LibraryItem.Article(article(id = 7L))
        val book = LibraryItem.Book(book(id = 7L))

        assertNotEquals(article.listKey, book.listKey)
        assertEquals("article-7", article.listKey)
        assertEquals("book-7", book.listKey)
    }

    @Test
    fun sortKey_readBook_usesLastReadAtSoItFloatsAboveNewerUnreadItems() {
        // 读过的书应该浮上来，即使它是更早导入的。
        val readBook = LibraryItem.Book(
            book(id = 1L, createdAt = 1_000L, lastReadAt = 9_000L)
        )
        val newerArticle = LibraryItem.Article(article(id = 2L, createdAt = 5_000L))

        val sorted = listOf(newerArticle, readBook).sortedByDescending { it.sortKey }

        assertEquals(listOf("book-1", "article-2"), sorted.map { it.listKey })
    }

    @Test
    fun sortKey_unreadBook_fallsBackToCreatedAt() {
        // lastReadAt 为 null 时必须退回 createdAt，否则未读书会因为 key 为 0
        // 永远沉到列表底部——刚导入的书反而看不见，这是最糟的首次体验。
        val unread = LibraryItem.Book(book(id = 3L, createdAt = 8_000L, lastReadAt = null))

        assertEquals(8_000L, unread.sortKey)
    }

    private fun article(id: Long, createdAt: Long = 0L) = ArticleEntity(
        id = id,
        title = "A$id",
        content = "body",
        createdAt = createdAt
    )

    private fun book(
        id: Long,
        createdAt: Long = 0L,
        lastReadAt: Long? = null
    ) = BookEntity(
        id = id,
        title = "B$id",
        contentFingerprint = "fp$id",
        sourceFormat = "epub3",
        chapterCount = 2,
        totalChars = 100,
        createdAt = createdAt,
        lastReadAt = lastReadAt
    )
}
