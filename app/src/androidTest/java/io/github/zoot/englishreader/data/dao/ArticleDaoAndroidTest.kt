package io.github.zoot.englishreader.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookReadingProgressEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ArticleDao in-memory Room 测试
 *
 * 用真实 SQLite 引擎（内存库）验证 [ArticleDao.refreshSampleArticles] 的核心不变量——
 * 这些行为依赖真实的 UNIQUE(word, articleId) 约束、ForeignKey CASCADE 与 @Transaction，
 * 无法用 MockK 假 DAO 覆盖，只能靠仪器测试真正跑一遍 SQL 才能证明。
 *
 * 覆盖此前两轮代码审查关注的关键点：
 *  - CASCADE 保护：刷新样本时用户生词不被连带删除（headline 数据丢失 bug）
 *  - 去重：同一 word 关联多篇样本，解绑后只剩一行 (word, NULL)，不违反唯一约束
 *  - 白名单：仅删除样本 source 的文章，用户导入/其他来源文章及其生词不受影响
 *  - 事务替换：旧样本被清、新样本被插
 *
 * 注意：Room 默认开启 PRAGMA foreign_keys=ON，故 CASCADE 在内存库中同样生效。
 */
@RunWith(AndroidJUnit4::class)
class ArticleDaoAndroidTest {

    private lateinit var db: EnglishReaderDatabase
    private lateinit var articleDao: ArticleDao
    private lateinit var vocabularyDao: VocabularyDao

    /** 测试用样本 source 白名单（DAO 与 source 值无关，此处自定义以便控制） */
    private val sampleSources = listOf("sampleA", "sampleB")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        articleDao = db.articleDao()
        vocabularyDao = db.vocabularyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleArticle(source: String, title: String = "t") =
        ArticleEntity(title = title, content = "c", source = source)

    private suspend fun allVocab(): List<VocabularyEntity> =
        vocabularyDao.getAllVocabulary().first()

    private fun freshSamples() = listOf(
        sampleArticle("sampleA", "newA"),
        sampleArticle("sampleB", "newB")
    )

    @Test
    fun readingPosition_staleSaveCannotRegressBookOrArticleAndDeletionCascades() = runBlocking {
        val (bookId, firstId, secondId) = bookWithTwoChapters()
        val latest = ReadingPositionEntity(secondId, 4, "TRANSLATION", 18, 300)
        articleDao.saveReadingPosition(latest)
        articleDao.saveReadingPosition(ReadingPositionEntity(firstId, 2, "ORIGINAL", 8, 200))
        articleDao.saveReadingPosition(latest.copy(characterOffset = 0, updatedAt = 100))

        assertEquals(latest, articleDao.getReadingPosition(secondId))
        assertEquals(8, articleDao.getReadingPosition(firstId)?.characterOffset)
        assertEquals(secondId, db.bookDao().getProgress(bookId)?.chapterArticleId)
        assertEquals(300L, db.bookDao().getBookById(bookId)?.lastReadAt)
        articleDao.deleteArticle(requireNotNull(articleDao.getArticleById(secondId)))
        assertNull(articleDao.getReadingPosition(secondId))
        assertTrue(articleDao.getReadingPosition(firstId) != null)
    }

    @Test
    fun readingPosition_bookWriteFails_rollsBackPositionAndChapterTogether() = runBlocking {
        val (bookId, firstId, secondId) = bookWithTwoChapters()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_reading_time BEFORE UPDATE OF lastReadAt ON books " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        var failed = false
        try {
            articleDao.saveReadingPosition(ReadingPositionEntity(secondId, 2, "ORIGINAL", 10, 200))
        } catch (_: android.database.SQLException) {
            failed = true
        }
        assertTrue(failed)
        assertNull(articleDao.getReadingPosition(secondId))
        assertEquals(firstId, db.bookDao().getProgress(bookId)?.chapterArticleId)
        assertNull(articleDao.getArticleById(secondId)?.lastReadAt)
    }

    private suspend fun bookWithTwoChapters(): Triple<Long, Long, Long> {
        val bookDao = db.bookDao()
        val bookId = bookDao.insertBookRow(
            BookEntity(title = "Book", contentFingerprint = "fp", sourceFormat = "epub3", chapterCount = 2, totalChars = 20)
        )
        val ids = (0..1).map { index ->
            val articleId = articleDao.insertArticle(ArticleEntity(title = "Chapter $index", content = "Body text."))
            bookDao.insertChapterRelation(
                BookChapterEntity(bookId = bookId, articleId = articleId, chapterIndex = index, sourceHref = "$index.xhtml")
            )
            articleId
        }
        bookDao.upsertProgress(BookReadingProgressEntity(bookId, ids[0], 0, 0, 100))
        return Triple(bookId, ids[0], ids[1])
    }

    /**
     * headline 数据丢失 bug：刷新样本时，样本关联的生词必须保留在生词本（articleId 解绑为 null），
     * 绝不能被 ForeignKey CASCADE 连带删除。
     */
    @Test
    fun refresh_keepsSampleVocabularyByUnbinding_notCascadeDeleted() = runBlocking {
        val aId = articleDao.insertArticle(sampleArticle("sampleA"))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "beautiful", articleId = aId))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val rows = allVocab().filter { it.word == "beautiful" }
        assertEquals("生词应保留，未被 CASCADE 删除", 1, rows.size)
        assertNull("生词应被解绑（articleId 置 null）", rows[0].articleId)
    }

    /**
     * 去重：同一 word 关联两篇样本文章（唯一约束因 articleId 不同而允许），
     * 刷新解绑后必须只剩一行 (word, NULL)，不得产生重复。
     */
    @Test
    fun refresh_sameWordInTwoSamples_collapsesToSingleNullRow() = runBlocking {
        val aId = articleDao.insertArticle(sampleArticle("sampleA"))
        val bId = articleDao.insertArticle(sampleArticle("sampleB"))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "habit", articleId = aId))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "habit", articleId = bId))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val rows = allVocab().filter { it.word == "habit" }
        assertEquals("重复生词应被去重为一行", 1, rows.size)
        assertNull(rows[0].articleId)
    }

    /**
     * 去重：生词本已存在 (word, NULL) 行，另有同 word 的样本关联行，
     * 刷新后必须仍只剩那一行既有的 (word, NULL)，样本关联行被清理。
     */
    @Test
    fun refresh_preexistingNullRow_sampleRowRemoved_singleNullRemains() = runBlocking {
        val aId = articleDao.insertArticle(sampleArticle("sampleA"))
        val preexistingId = vocabularyDao.insertVocabulary(
            VocabularyEntity(word = "reduce", articleId = null)
        )
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "reduce", articleId = aId))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val rows = allVocab().filter { it.word == "reduce" }
        assertEquals(1, rows.size)
        assertNull(rows[0].articleId)
        assertEquals("应保留既有的 NULL 行", preexistingId, rows[0].id)
    }

    /**
     * 白名单：用户导入文章（source 不在白名单）及其生词完全不受刷新影响。
     */
    @Test
    fun refresh_userArticleAndVocabulary_untouched() = runBlocking {
        val userId = articleDao.insertArticle(
            ArticleEntity(title = "u", content = "c", source = "file")
        )
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "custom", articleId = userId))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val userArticle = articleDao.getArticleById(userId)
        assertEquals("用户文章不应被删除", "u", userArticle?.title)
        val rows = allVocab().filter { it.word == "custom" }
        assertEquals(1, rows.size)
        assertEquals("用户生词的 articleId 不应被解绑", userId, rows[0].articleId)
    }

    /**
     * 同一 word 同时存在用户关联行与样本关联行：
     * 用户行保留其 articleId，样本行解绑为 (word, NULL)，两行 articleId 不同故不冲突。
     */
    @Test
    fun refresh_sameWordUserAndSample_bothSurviveDistinct() = runBlocking {
        val userId = articleDao.insertArticle(
            ArticleEntity(title = "u", content = "c", source = "file")
        )
        val sampleId = articleDao.insertArticle(sampleArticle("sampleA"))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "shared", articleId = userId))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "shared", articleId = sampleId))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val rows = allVocab().filter { it.word == "shared" }.sortedBy { it.articleId }
        assertEquals(2, rows.size)
        // 解绑后 articleId=null 的样本行排在前（null 视为最小），用户行保留 userId
        assertNull(rows[0].articleId)
        assertEquals(userId, rows[1].articleId)
    }

    /**
     * 事务替换：旧样本文章被清除，新样本被插入。
     */
    @Test
    fun refresh_replacesOldSamplesWithNew() = runBlocking {
        articleDao.insertArticle(sampleArticle("sampleA", "oldA"))
        articleDao.insertArticle(sampleArticle("sampleB", "oldB"))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val titles = articleDao.getAllArticles().first().map { it.title }.toSet()
        assertEquals(setOf("newA", "newB"), titles)
    }

    /**
     * 三篇样本关联同一 word：解绑后仅保留 id 最小的一行为 (word, NULL)，其余被删。
     */
    @Test
    fun refresh_threeSampleRowsSameWord_keepsOnlyMinId() = runBlocking {
        val aId = articleDao.insertArticle(sampleArticle("sampleA"))
        val bId = articleDao.insertArticle(sampleArticle("sampleB"))
        val cId = articleDao.insertArticle(sampleArticle("sampleA", "a2"))
        val minId = vocabularyDao.insertVocabulary(VocabularyEntity(word = "focus", articleId = aId))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "focus", articleId = bId))
        vocabularyDao.insertVocabulary(VocabularyEntity(word = "focus", articleId = cId))

        articleDao.refreshSampleArticles(sampleSources, freshSamples())

        val rows = allVocab().filter { it.word == "focus" }
        assertEquals(1, rows.size)
        assertEquals(minId, rows[0].id)
        assertNull(rows[0].articleId)
    }

    /**
     * 防御性契约：空 sampleSources / 空 samples 必须抛 IllegalArgumentException，
     * 避免"清空样本却不补"造成样本永久丢失。
     */
    @Test
    fun refresh_emptyArguments_throws() = runBlocking {
        var threwOnEmptySources = false
        try {
            articleDao.refreshSampleArticles(emptyList(), freshSamples())
        } catch (e: IllegalArgumentException) {
            threwOnEmptySources = true
        }
        assertTrue("空 sampleSources 应抛异常", threwOnEmptySources)

        var threwOnEmptySamples = false
        try {
            articleDao.refreshSampleArticles(sampleSources, emptyList())
        } catch (e: IllegalArgumentException) {
            threwOnEmptySamples = true
        }
        assertTrue("空 samples 应抛异常", threwOnEmptySamples)
    }
}
