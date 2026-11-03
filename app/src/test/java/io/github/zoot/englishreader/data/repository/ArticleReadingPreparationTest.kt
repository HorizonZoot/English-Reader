package io.github.zoot.englishreader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.squareup.moshi.Moshi
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.model.AppliedTranslationLayoutCodec
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.util.SentenceSplitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读快照的分句预计算：真实 Room 与真实 ICU，证明仓库下发的段落与阅读页自行分句的结果一致，
 * 且只改阅读时间的重查不会重新分句。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticleReadingPreparationTest {

    private lateinit var db: EnglishReaderDatabase
    private val codec = AppliedTranslationLayoutCodec(Moshi.Builder().build())

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
    fun tearDown() = db.close()

    @Test
    fun getReadingArticle_preparesSameParagraphsAsReadingScreen() = runTest {
        val content = "Mr. Smith arrived. He sat down.\n\nThe second paragraph ends here."
        val translation = "史密斯先生到了。\n\n第二段。"
        val id = db.articleDao().insertArticle(ArticleEntity(title = "T", content = content, translation = translation))
        val repository = ArticleRepository(db.articleDao(), backgroundScope, codec, StandardTestDispatcher(testScheduler))

        val state = requireNotNull(repository.getReadingArticle(id))

        assertEquals(ParagraphAligner.align(content, translation, SentenceSplitter::split), state.paragraphs)
    }

    @Test
    fun observeReadingArticle_readingTimeOnlyChange_reusesParagraphs() = runTest {
        val id = db.articleDao().insertArticle(ArticleEntity(title = "T", content = "First.\n\nSecond."))
        val repository = ArticleRepository(db.articleDao(), backgroundScope, codec, StandardTestDispatcher(testScheduler))

        repository.observeReadingArticle(id).test {
            val first = requireNotNull(awaitItem())
            db.articleDao().updateLastReadTime(id, 100)
            val touched = requireNotNull(awaitItem())
            assertEquals(100L, touched.article.lastReadAt)
            assertSame(first.paragraphs, touched.paragraphs)

            db.articleDao().updateEditedContent(id, "T", "Edited.")
            val edited = requireNotNull(awaitItem())
            assertNotSame(first.paragraphs, edited.paragraphs)
            assertEquals(listOf("Edited."), edited.paragraphs?.map { it.english })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveReadingPositionIfContent_storesProgressFromCheckedContent() = runTest {
        val content = "abcd\n\nefgh"
        val id = db.articleDao().insertArticle(ArticleEntity(title = "T", content = content))
        val repository = ArticleRepository(db.articleDao(), backgroundScope, codec, StandardTestDispatcher(testScheduler))

        repository.saveReadingPosition(ReadingPosition(id, ReadingAnchor(1, ReadingTextKind.ORIGINAL, 2)), content)

        val stored: ReadingPositionEntity = requireNotNull(db.articleDao().getReadingPosition(id))
        assertEquals(0.75f, stored.progress, 0.0001f)
    }
}
