package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.BookDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookReadingProgressEntity
import io.github.zoot.englishreader.data.entity.BookSourceFormat
import io.github.zoot.englishreader.data.importer.BookFormat
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.ImportedBook
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 书籍仓库：整本书的持久化 façade。
 *
 * 章节复用 [ArticleEntity]，所以 ReadingScreen / 词典 / TTS / AI 缓存 / 生词全部
 * 沿用 articleId，无需感知书籍概念。
 *
 * 重复检测策略：优先 `dc:identifier`（出版方分配，最可靠），缺失时回退正文指纹。
 * **不自动覆盖**——返回 typed [ImportFailure.DuplicateBook] 让用户决定，
 * 因为覆盖会连带删除该书章节上的生词。
 */
@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val articleDao: ArticleDao
) {

    fun getAllBooks(): Flow<List<BookEntity>> = bookDao.getAllBooks()

    fun getChapters(bookId: Long): Flow<List<BookChapterEntity>> = bookDao.getChapters(bookId)

    suspend fun getBookById(bookId: Long): BookEntity? = bookDao.getBookById(bookId)

    suspend fun getChaptersOnce(bookId: Long): List<BookChapterEntity> =
        bookDao.getChaptersOnce(bookId)

    /** 反查：阅读页只知道 articleId，需要据此判断当前是否在读某本书的某一章。 */
    suspend fun findChapterByArticleId(articleId: Long): BookChapterEntity? =
        bookDao.findChapterByArticleId(articleId)

    suspend fun getProgress(bookId: Long): BookReadingProgressEntity? = bookDao.getProgress(bookId)

    /**
     * 原子落库。
     *
     * @param allowDuplicate true 时跳过重复检测，用于用户明确选择「仍然导入」
     * @throws ImportException [ImportFailure.DuplicateBook] 当检测到同一本书且未允许重复
     */
    suspend fun persist(book: ImportedBook, allowDuplicate: Boolean = false): Long {
        if (!allowDuplicate) {
            findDuplicate(book)?.let { existing ->
                throw ImportException(
                    ImportFailure.DuplicateBook(
                        existingBookId = existing.id,
                        existingTitle = existing.title
                    )
                )
            }
        }

        val now = System.currentTimeMillis()
        return bookDao.insertBookWithChapters(
            book = BookEntity(
                title = book.metadata.title,
                author = book.metadata.author,
                identifier = book.metadata.identifier,
                language = book.metadata.language,
                sourceFormat = when (book.metadata.sourceFormat) {
                    BookFormat.EPUB2 -> BookSourceFormat.EPUB2
                    BookFormat.EPUB3 -> BookSourceFormat.EPUB3
                },
                contentFingerprint = book.metadata.contentFingerprint,
                chapterCount = book.chapters.size,
                totalChars = book.chapters.sumOf { it.content.length },
                createdAt = now,
                lastReadAt = null
            ),
            chapters = book.chapters.map { chapter ->
                ArticleEntity(
                    title = chapter.title,
                    content = chapter.content,
                    source = SOURCE_BOOK_CHAPTER,
                    createdAt = now,
                    lastReadAt = null
                ) to BookChapterEntity(
                    // bookId / articleId 由 DAO 在事务内回填：此处还拿不到自增 ID。
                    bookId = 0,
                    articleId = 0,
                    chapterIndex = chapter.chapterIndex,
                    sourceHref = chapter.sourceHref,
                    navigationTitle = chapter.navigationTitle
                )
            }
        )
    }

    /**
     * 删除整本书。
     *
     * 委托 [BookDao.deleteBookCascade]——生词解绑必须在删除章节文章**之前**完成，
     * 因为 vocabulary.articleId 是 ON DELETE CASCADE。
     */
    suspend fun deleteBook(bookId: Long) {
        bookDao.deleteBookCascade(bookId, articleDao)
    }

    /** 保存阅读位置。章节 ID + 段落序号比全书百分比稳定：字体或屏幕变化不影响它。 */
    suspend fun saveProgress(
        bookId: Long,
        chapterArticleId: Long,
        paragraphIndex: Int,
        paragraphOffset: Int
    ) {
        val now = System.currentTimeMillis()
        bookDao.upsertProgress(
            BookReadingProgressEntity(
                bookId = bookId,
                chapterArticleId = chapterArticleId,
                paragraphIndex = paragraphIndex,
                paragraphOffset = paragraphOffset,
                updatedAt = now
            )
        )
        bookDao.updateBookLastReadTime(bookId, now)
    }

    private suspend fun findDuplicate(book: ImportedBook): BookEntity? {
        val identifier = book.metadata.identifier?.takeIf { it.isNotBlank() }
        if (identifier != null) {
            bookDao.findByIdentifier(identifier)?.let { return it }
        }
        return bookDao.findByFingerprint(book.metadata.contentFingerprint)
    }

    companion object {
        /** 章节文章的 source 标记，用于与单篇导入/样例文章区分。 */
        const val SOURCE_BOOK_CHAPTER = "book-chapter"
    }
}
