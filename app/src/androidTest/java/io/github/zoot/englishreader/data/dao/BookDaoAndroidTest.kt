package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookSourceFormat
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.importer.ImportBudget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BookDao] 的真实 Room 事务验证。
 *
 * 必须用 androidTest：这里要证明的是真实 SQLite 的事务回滚与外键级联行为，
 * mock DAO 只能证明「方法被按顺序调用了」，证明不了「失败后库里没有残留」。
 */
@RunWith(AndroidJUnit4::class)
class BookDaoAndroidTest {

    private lateinit var db: EnglishReaderDatabase
    private lateinit var bookDao: BookDao
    private lateinit var articleDao: ArticleDao
    private lateinit var vocabularyDao: VocabularyDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .build()
        bookDao = db.bookDao()
        articleDao = db.articleDao()
        vocabularyDao = db.vocabularyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertBookWithChapters_writesBookChaptersAndInitialProgress() = runBlocking {
        val bookId = bookDao.insertBookWithChapters(book(chapterCount = 3), chapters(3))

        val stored = bookDao.getBookById(bookId)
        assertNotNull("book row must exist", stored)
        assertEquals(3, stored!!.chapterCount)

        val relations = bookDao.getChaptersOnce(bookId)
        assertEquals(3, relations.size)
        // chapterIndex 必须由 DAO 重新连续编号，不依赖调用方传入的值
        assertEquals(listOf(0, 1, 2), relations.map { it.chapterIndex })
        assertEquals(listOf("ch0.xhtml", "ch1.xhtml", "ch2.xhtml"), relations.map { it.sourceHref })

        val progress = bookDao.getProgress(bookId)
        assertNotNull("initial progress must be written", progress)
        assertEquals(relations.first().articleId, progress!!.chapterArticleId)
        assertEquals(0, progress.paragraphIndex)
        assertEquals(0, progress.paragraphOffset)
    }

    /**
     * DAO 必须忽略调用方传入的 chapterIndex。
     *
     * 空正文项在解析阶段被过滤后，原始 spine 下标必然有空洞；若 DAO 直接采用传入值，
     * 「上一章/下一章」就要处理不连续序号。这里传入乱序的 99/7/42 验证重编号真的生效。
     */
    @Test
    fun insertBookWithChapters_ignoresCallerSuppliedChapterIndex() = runBlocking {
        val messy = listOf(99, 7, 42).mapIndexed { i, idx ->
            article("Chapter $i") to relation(chapterIndex = idx, href = "ch$i.xhtml")
        }
        val bookId = bookDao.insertBookWithChapters(book(chapterCount = 3), messy)

        assertEquals(listOf(0, 1, 2), bookDao.getChaptersOnce(bookId).map { it.chapterIndex })
    }

    /**
     * 中途失败必须整体回滚，不留半本书。
     *
     * 用重复 article id 触发 ABORT：第 2 章插入时冲突，此时第 1 章、books 行都已写入，
     * 若事务边界不对就会留下「有书没章节」的状态。
     */
    @Test
    fun insertBookWithChapters_rollsBackEntirelyOnChapterFailure() = runBlocking {
        articleDao.insertArticle(article("Existing").copy(id = 500))

        val chapters = listOf(
            article("Chapter 0") to relation(0, "ch0.xhtml"),
            // 与既有 article 撞 id，@Insert(ABORT) 会抛
            article("Chapter 1").copy(id = 500) to relation(1, "ch1.xhtml"),
        )

        val threw = try {
            bookDao.insertBookWithChapters(book(chapterCount = 2), chapters)
            false
        } catch (_: android.database.SQLException) {
            true
        }

        assertTrue("conflicting chapter insert must throw", threw)
        assertEquals("no book row may remain", 0, countOf("books"))
        assertEquals("no chapter relation may remain", 0, countOf("book_chapters"))
        assertEquals("no progress row may remain", 0, countOf("book_reading_progress"))
        assertEquals("no chapter article may remain", 1, countOf("articles"))
        // 回滚不得波及事务前已存在的数据
        assertNotNull("pre-existing article must survive", articleDao.getArticleById(500))
    }

    /**
     * 删书必须保留用户生词。
     *
     * `vocabulary.articleId` 对 articles 是 CASCADE，所以删章节正文前必须先解绑。
     * 这是本模块最容易静默丢用户数据的一处。
     */
    @Test
    fun deleteBookCascade_preservesVocabularyAndRemovesBookRows() = runBlocking {
        val bookId = bookDao.insertBookWithChapters(book(chapterCount = 2), chapters(2))
        val articleIds = bookDao.getChapterArticleIds(bookId)

        vocabularyDao.insertVocabulary(vocabulary("alpha", articleIds[0]))
        vocabularyDao.insertVocabulary(vocabulary("beta", articleIds[1]))

        bookDao.deleteBookCascade(bookId, articleDao)

        assertNull("book row must be gone", bookDao.getBookById(bookId))
        assertEquals("chapter relations must be gone", 0, countOf("book_chapters"))
        assertEquals("progress must be gone", 0, countOf("book_reading_progress"))
        assertEquals("chapter articles must be deleted", 0, countOf("articles"))

        // 生词必须仍在，且已解绑
        assertEquals("both vocabulary rows must survive", 2, countOf("vocabulary"))
        assertEquals(
            "surviving vocabulary must be unbound",
            2,
            countWhere("vocabulary", "articleId IS NULL")
        )
    }

    /**
     * 同一单词出现在同一本书的多个章节时，解绑后不得产生重复生词。
     *
     * vocabulary 有 UNIQUE(word, articleId)，而 SQLite 认为 NULL 彼此不相等——
     * 不先去重就解绑会得到多行 (word, NULL) 且约束不拦截。一本书内多章出现同一单词
     * 是常态，所以这条对书籍比对样本文章更关键。
     */
    @Test
    fun deleteBookCascade_dedupesRepeatedWordAcrossChapters() = runBlocking {
        val bookId = bookDao.insertBookWithChapters(book(chapterCount = 3), chapters(3))
        val articleIds = bookDao.getChapterArticleIds(bookId)

        // 同一个词在三章里各出现一次
        articleIds.forEach { vocabularyDao.insertVocabulary(vocabulary("recurring", it)) }
        assertEquals(3, countOf("vocabulary"))

        bookDao.deleteBookCascade(bookId, articleDao)

        assertEquals(
            "repeated word must collapse to a single unbound row",
            1,
            countOf("vocabulary")
        )
        assertEquals(1, countWhere("vocabulary", "articleId IS NULL"))
    }

    @Test
    fun deleteBookCascade_atMaxChapterCount_batchesWithoutLosingDedup() = runBlocking {
        // 满章节上限：去重 SQL 把 :articleIds 绑两次，不分批就是 1000 个参数，
        // 在 API 24–30（SQLite < 3.32，上限 999）会抛 "too many SQL variables"
        // 并回滚整个事务——那本书就永久删不掉。
        //
        // 本用例在 API 36 上不会重现那个崩溃（新 SQLite 上限是 32766），
        // 它针对的是修复本身的风险点：分批后去重语义是否仍跨批成立。
        // 500 章 / 每批 200 → 3 批，重复词跨越两个批次边界。
        val chapterCount = ImportBudget.MAX_BOOK_CHAPTERS
        assertTrue(
            "fixture must span multiple batches",
            chapterCount > BookDao.VOCABULARY_BATCH
        )

        val bookId = bookDao.insertBookWithChapters(
            book(chapterCount = chapterCount),
            chapters(chapterCount)
        )
        val articleIds = bookDao.getChapterArticleIds(bookId)
        assertEquals(chapterCount, articleIds.size)

        // 同一个词出现在每一章（跨全部 3 批），再给首尾两章各一个唯一词。
        articleIds.forEach { vocabularyDao.insertVocabulary(vocabulary("recurring", it)) }
        vocabularyDao.insertVocabulary(vocabulary("first-only", articleIds.first()))
        vocabularyDao.insertVocabulary(vocabulary("last-only", articleIds.last()))
        assertEquals(chapterCount + 2, countOf("vocabulary"))

        bookDao.deleteBookCascade(bookId, articleDao)

        assertEquals("book row must be gone", 0, countOf("books"))
        assertEquals("all chapter articles must be deleted", 0, countOf("articles"))
        // recurring 塔缩为 1 行 + 首尾两个唯一词 = 3。
        // 若分批破坏了去重，这里会看到每批各留一行 recurring。
        assertEquals("repeated word must collapse across all batches", 3, countOf("vocabulary"))
        assertEquals(3, countWhere("vocabulary", "articleId IS NULL"))
        assertEquals(1, countWhere("vocabulary", "word = 'recurring'"))
    }

    @Test
    fun findByIdentifierAndFingerprint_locateExistingBook() = runBlocking {
        bookDao.insertBookWithChapters(
            book(chapterCount = 1).copy(identifier = "urn:isbn:123", contentFingerprint = "fp-1"),
            chapters(1)
        )

        assertNotNull(bookDao.findByIdentifier("urn:isbn:123"))
        assertNull(bookDao.findByIdentifier("urn:isbn:missing"))
        assertNotNull(bookDao.findByFingerprint("fp-1"))
        assertNull(bookDao.findByFingerprint("fp-missing"))
    }

    @Test
    fun findChapterByArticleId_distinguishesBookChaptersFromStandaloneArticles() = runBlocking {
        val standaloneId = articleDao.insertArticle(article("Standalone"))
        val bookId = bookDao.insertBookWithChapters(book(chapterCount = 1), chapters(1))
        val chapterArticleId = bookDao.getChapterArticleIds(bookId).single()

        assertNotNull(
            "chapter article must resolve to a book",
            bookDao.findChapterByArticleId(chapterArticleId)
        )
        assertNull(
            "standalone article must not resolve to a book",
            bookDao.findChapterByArticleId(standaloneId)
        )
    }

    // ---- helpers ----


    /**
     * 书架的核心机制：章节文章不得出现在文章列表。
     *
     * 这是 Phase 2 存在的理由 —— 导入一本 500 章的书，若列表用 [ArticleDao.getAllArticles]，
     * 用户会看到 500 行章节把自己的文章冲到看不见的地方。该过滤此前**只有 MockK stub**
     * （`returns flowOf(emptyList())`，不执行任何 SQL），因此这条 SQL 从未被真实 SQLite 验证过。
     *
     * 必须 androidTest：`NOT IN (SELECT articleId FROM book_chapters)` 的正确性只能由真实
     * SQLite 回答，mock 无论怎么写都会返回你让它返回的东西。
     */
    @Test
    fun getStandaloneArticles_excludesBookChaptersAndKeepsOwnArticles() = runBlocking {
        // 用户自己导入的单篇
        val mineA = articleDao.insertArticle(article("My Own Article A"))
        val mineB = articleDao.insertArticle(article("My Own Article B"))

        // 一本 3 章的书：这 3 篇 article 是章节载体，不该出现在书架的文章区
        val bookId = bookDao.insertBookWithChapters(book(chapterCount = 3), chapters(3))
        val chapterIds = bookDao.getChapters(bookId).first().map { it.articleId }
        assertEquals("fixture sanity: 3 chapters", 3, chapterIds.size)

        val standalone = articleDao.getStandaloneArticles().first().map { it.id }.sorted()

        assertEquals(
            "only the user's own articles may appear on the shelf",
            listOf(mineA, mineB).sorted(),
            standalone
        )
        chapterIds.forEach { chapterArticleId ->
            assertTrue(
                "chapter article $chapterArticleId leaked into the article list",
                chapterArticleId !in standalone
            )
        }
        // 对照：getAllArticles 必须仍看得见全部 5 篇，证明过滤发生在查询层而非插入层
        assertEquals(5, articleDao.getAllArticles().first().size)
    }

    /**
     * 删书之后，原属该书的章节文章消失，用户自己的文章一篇不少。
     *
     * 与上一条互补：上一条证明「书在时章节不露出」，这一条证明「书没了不会连坐」。
     *
     * 必须插**两本**书：cascade 会物理删除被删那本的章节 article，如果只有一本，
     * 删完后表里只剩用户那一篇，`NOT IN (...)` 与无 WHERE 返回同一结果 ——
     * 用例就退化成只验 cascade，对过滤子变成空转。留下的第二本书让表里仍有活章节，
     * 过滤子因此仍然是必要的。
     */
    @Test
    fun getStandaloneArticles_afterDeleteBook_stillListsOnlyUserArticles() = runBlocking {
        val mine = articleDao.insertArticle(article("Survivor"))
        val deletedBookId = bookDao.insertBookWithChapters(book(chapterCount = 2), chapters(2))
        // 第二本书不被删：它的章节 article 必须仍由过滤子挡住。
        bookDao.insertBookWithChapters(book(chapterCount = 3), chapters(3))

        bookDao.deleteBookCascade(deletedBookId, articleDao)

        val standalone = articleDao.getStandaloneArticles().first().map { it.id }
        assertEquals(listOf(mine), standalone)
        // 1 用户文章 + 存活书的 3 章 = 4；被删那本的 2 章已物理消失。
        assertEquals("deleted book chapters must be gone entirely", 4, articleDao.getAllArticles().first().size)
    }

    private fun book(chapterCount: Int) = BookEntity(
        title = "Test Book",
        author = "Author",
        language = "en",
        identifier = null,
        contentFingerprint = "fingerprint",
        sourceFormat = BookSourceFormat.EPUB3,
        chapterCount = chapterCount,
        totalChars = chapterCount * 10,
        createdAt = 1_000L
    )

    private fun article(title: String) = ArticleEntity(
        title = title,
        content = "Body of $title.",
        createdAt = 1_000L
    )

    private fun relation(chapterIndex: Int, href: String) = BookChapterEntity(
        bookId = 0,
        articleId = 0,
        chapterIndex = chapterIndex,
        sourceHref = href,
        navigationTitle = "Nav $chapterIndex"
    )

    private fun chapters(count: Int) = (0 until count).map { i ->
        article("Chapter $i") to relation(i, "ch$i.xhtml")
    }

    private fun vocabulary(word: String, articleId: Long) = VocabularyEntity(
        word = word,
        articleId = articleId,
        createdAt = 1_000L
    )

    private suspend fun countOf(table: String): Int = countWhere(table, "1")

    private suspend fun countWhere(table: String, predicate: String): Int {
        // 直接查 SQLite 而非经 DAO：要验证的是库里真实剩了什么，
        // 经 DAO 查会把「DAO 语义正确」和「数据真的没了」混成一件事。
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM $table WHERE $predicate")
                .use { c ->
                    c.moveToFirst()
                    c.getInt(0)
                }
        }
    }
}
