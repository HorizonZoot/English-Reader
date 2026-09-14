package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.BookDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookSourceFormat
import io.github.zoot.englishreader.data.importer.BookFormat
import io.github.zoot.englishreader.data.importer.BookMetadata
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.ImportedBook
import io.github.zoot.englishreader.data.importer.ImportedChapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [BookRepository] 的映射与去重策略测试。
 *
 * 真实 SQLite 的原子性与生词保护由 `BookDaoAndroidTest` 覆盖；这里只钉住
 * 仓库层自己的决策：去重优先级、typed 失败、以及 domain → entity 映射不丢字段。
 */
class BookRepositoryTest {

    private lateinit var bookDao: BookDao
    private lateinit var articleDao: ArticleDao
    private lateinit var repository: BookRepository

    @Before
    fun setup() {
        bookDao = mockk(relaxed = true)
        articleDao = mockk(relaxed = true)
        repository = BookRepository(bookDao, articleDao)
    }

    @Test
    fun persist_identifierMatch_throwsDuplicateBook() = runTest {
        val existing = BookEntity(
            id = 11,
            title = "Existing",
            contentFingerprint = "other-fp",
            sourceFormat = BookSourceFormat.EPUB3,
            chapterCount = 3,
            totalChars = 300
        )
        coEvery { bookDao.findByIdentifier("urn:isbn:1") } returns existing

        try {
            repository.persist(book(identifier = "urn:isbn:1"))
            fail("expected ImportException")
        } catch (e: ImportException) {
            val failure = e.failure as ImportFailure.DuplicateBook
            assertEquals(11L, failure.existingBookId)
            assertEquals("Existing", failure.existingTitle)
        }
        // 命中 identifier 后不应再查指纹：identifier 是出版方分配的强标识
        coVerify(exactly = 0) { bookDao.findByFingerprint(any()) }
    }

    @Test
    fun persist_noIdentifier_fallsBackToFingerprint() = runTest {
        val existing = BookEntity(
            id = 22,
            title = "Same Content",
            contentFingerprint = "fp-1",
            sourceFormat = BookSourceFormat.EPUB2,
            chapterCount = 2,
            totalChars = 200
        )
        coEvery { bookDao.findByFingerprint("fp-1") } returns existing

        try {
            repository.persist(book(identifier = null, fingerprint = "fp-1"))
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertEquals(22L, (e.failure as ImportFailure.DuplicateBook).existingBookId)
        }
    }

    @Test
    fun persist_blankIdentifier_skipsIdentifierLookupAndUsesFingerprint() = runTest {
        coEvery { bookDao.findByFingerprint(any()) } returns null
        coEvery { bookDao.insertBookWithChapters(any(), any()) } returns 5L

        assertEquals(5L, repository.persist(book(identifier = "   ")))

        coVerify(exactly = 0) { bookDao.findByIdentifier(any()) }
        coVerify(exactly = 1) { bookDao.findByFingerprint("fp-default") }
    }

    @Test
    fun persist_allowDuplicate_skipsBothLookups() = runTest {
        coEvery { bookDao.insertBookWithChapters(any(), any()) } returns 9L

        assertEquals(9L, repository.persist(book(identifier = "urn:isbn:1"), allowDuplicate = true))

        coVerify(exactly = 0) { bookDao.findByIdentifier(any()) }
        coVerify(exactly = 0) { bookDao.findByFingerprint(any()) }
    }

    @Test
    fun persist_mapsMetadataAndChapterOrderWithoutLoss() = runTest {
        coEvery { bookDao.findByIdentifier(any()) } returns null
        coEvery { bookDao.findByFingerprint(any()) } returns null
        val bookSlot = slot<BookEntity>()
        val chaptersSlot = slot<List<Pair<ArticleEntity, BookChapterEntity>>>()
        coEvery {
            bookDao.insertBookWithChapters(capture(bookSlot), capture(chaptersSlot))
        } returns 7L

        repository.persist(
            ImportedBook(
                metadata = BookMetadata(
                    title = "Pride",
                    author = "Austen",
                    language = "en",
                    identifier = "urn:isbn:9",
                    contentFingerprint = "fp-x",
                    sourceFormat = BookFormat.EPUB2
                ),
                chapters = listOf(
                    chapter(0, "One", "c1.xhtml", "Nav One", "AAAA"),
                    chapter(1, "Two", "c2.xhtml", null, "BBBBBB")
                ),
                toc = emptyList()
            )
        )

        val saved = bookSlot.captured
        assertEquals("Pride", saved.title)
        assertEquals("Austen", saved.author)
        assertEquals("en", saved.language)
        assertEquals("urn:isbn:9", saved.identifier)
        assertEquals("fp-x", saved.contentFingerprint)
        // BookFormat.EPUB2 必须映射成持久化常量，而不是 enum.name（"EPUB2"）
        assertEquals(BookSourceFormat.EPUB2, saved.sourceFormat)
        assertEquals(2, saved.chapterCount)
        assertEquals(10, saved.totalChars)
        assertNull(saved.lastReadAt)

        val chapters = chaptersSlot.captured
        assertEquals(2, chapters.size)
        assertEquals(listOf(0, 1), chapters.map { it.second.chapterIndex })
        assertEquals(listOf("c1.xhtml", "c2.xhtml"), chapters.map { it.second.sourceHref })
        assertEquals(listOf("Nav One", null), chapters.map { it.second.navigationTitle })
        assertEquals(listOf("One", "Two"), chapters.map { it.first.title })
        assertEquals(listOf("AAAA", "BBBBBB"), chapters.map { it.first.content })
        // 章节文章必须带 source 标记，否则书架会把它们当成独立单篇文章
        assertTrue(chapters.all { it.first.source == BookRepository.SOURCE_BOOK_CHAPTER })
    }

    @Test
    fun saveProgress_writesProgressAndTouchesBook() = runTest {
        repository.saveProgress(bookId = 3, chapterArticleId = 41, paragraphIndex = 12, paragraphOffset = 240)

        coVerify(exactly = 1) {
            bookDao.upsertProgress(
                match {
                    it.bookId == 3L &&
                        it.chapterArticleId == 41L &&
                        it.paragraphIndex == 12 &&
                        it.paragraphOffset == 240
                }
            )
        }
        coVerify(exactly = 1) { bookDao.updateBookLastReadTime(3L, any()) }
    }

    @Test
    fun deleteBook_delegatesToCascadeWithArticleDao() = runTest {
        repository.deleteBook(4)

        // 必须走 cascade 版本：直接删 books 行会让 CASCADE 静默带走生词
        coVerify(exactly = 1) { bookDao.deleteBookCascade(4L, articleDao) }
        coVerify(exactly = 0) { bookDao.deleteBookRow(any()) }
    }

    private fun book(
        identifier: String?,
        fingerprint: String = "fp-default"
    ) = ImportedBook(
        metadata = BookMetadata(
            title = "Book",
            author = null,
            language = null,
            identifier = identifier,
            contentFingerprint = fingerprint,
            sourceFormat = BookFormat.EPUB3
        ),
        chapters = listOf(chapter(0, "Ch", "c.xhtml", null, "text")),
        toc = emptyList()
    )

    private fun chapter(
        index: Int,
        title: String,
        href: String,
        navTitle: String?,
        content: String
    ) = ImportedChapter(
        chapterIndex = index,
        title = title,
        sourceHref = href,
        navigationTitle = navTitle,
        content = content
    )
}
