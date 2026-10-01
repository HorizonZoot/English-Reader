package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.util.TestDataGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 首次引导用 [ArticleDao.hasUserData] 区分新装与升级：内置样本不算用户数据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticleDaoUserDataTest {

    private lateinit var db: EnglishReaderDatabase
    private val dao get() = db.articleDao()
    private val samples = TestDataGenerator.SAMPLE_SOURCES

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun hasUserData_emptyOrSamplesOnly_returnsFalse() = runBlocking {
        assertFalse(dao.hasUserData(samples))
        dao.refreshSampleArticles(samples, TestDataGenerator.getSampleArticles())
        assertFalse(dao.hasUserData(samples))
    }

    @Test
    fun hasUserData_importedOrSourcelessArticle_returnsTrue() = runBlocking {
        dao.insertArticle(ArticleEntity(title = "Imported", content = "Text.", source = "paste"))
        assertTrue(dao.hasUserData(samples))
        db.clearAllTables()
        dao.insertArticle(ArticleEntity(title = "Legacy", content = "Text.", source = null))
        assertTrue(dao.hasUserData(samples))
    }

    @Test
    fun hasUserData_vocabularyOrReadingOnSample_returnsTrue() = runBlocking {
        dao.refreshSampleArticles(samples, TestDataGenerator.getSampleArticles())
        val sampleId = dao.getStandaloneArticles().first().first().id
        db.vocabularyDao().insertVocabulary(VocabularyEntity(word = "habit", articleId = sampleId))
        assertTrue(dao.hasUserData(samples))
        db.vocabularyDao().deleteVocabularyByArticle(sampleId)
        assertFalse(dao.hasUserData(samples))
        dao.upsertReadingPosition(
            ReadingPositionEntity(sampleId, paragraphIndex = 1, textKind = "ORIGINAL", characterOffset = 0, updatedAt = 1)
        )
        assertTrue(dao.hasUserData(samples))
    }

    @Test
    fun hasUserData_book_returnsTrue() = runBlocking {
        db.bookDao().insertBookRow(
            BookEntity(
                title = "Book", contentFingerprint = "fp", sourceFormat = "epub3",
                chapterCount = 0, totalChars = 0
            )
        )
        assertTrue(dao.hasUserData(samples))
    }
}
