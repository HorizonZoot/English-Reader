package io.github.zoot.englishreader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookSourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 阅读位置跨进程重建的持久性验证。
 *
 * 为什么必须 androidTest 且必须**落盘**数据库：进程重建的本质是「所有内存状态消失，
 * 只剩磁盘」。用 `inMemoryDatabaseBuilder` 证明不了这一点——它一 close 数据就没了，
 * 无论生产代码写没写盘都会「测试通过」（因为两边都是空）。所以这里用
 * [Room.databaseBuilder] 指向真实文件，close 后重新打开，模拟冷启动读盘。
 *
 * 这条用例回答的是 implement.md「明确未完成」里的一半：
 * 「进程重建恢复未纳入基准（需跨进程重启用例）」。它不需要物理设备——
 * 需要物理设备的是滚动掉帧那一半（Macrobenchmark）。
 *
 * 覆盖的是 [BookRepository] 层，不是 ViewModel：ViewModel 的发布顺序已由
 * `ReadingViewModelTest.loadArticle_publishesScrollTargetBeforeChapterContext` 钉住，
 * 这里要证明的是它读到的那个值确实来自磁盘。
 */
@RunWith(AndroidJUnit4::class)
class BookProgressDurabilityAndroidTest {

    private lateinit var dbFile: File
    private lateinit var db: EnglishReaderDatabase
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = File(context.cacheDir, "durability-${System.nanoTime()}.db")
        openDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        // Room 会连带产生 -wal / -shm，逐一清理，避免污染后续用例的 cacheDir 计数断言。
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach {
            if (it.exists()) it.delete()
        }
    }

    @Test
    fun savedProgress_survivesDatabaseCloseAndReopen() = runBlocking {
        val bookId = db.bookDao().insertBookWithChapters(book(chapterCount = 3), chapters(3))
        val chapters = db.bookDao().getChapters(bookId).first()
        val secondChapterArticleId = chapters[1].articleId

        repository.saveProgress(
            bookId = bookId,
            chapterArticleId = secondChapterArticleId,
            paragraphIndex = 17,
            paragraphOffset = 42
        )

        // 模拟进程被杀：所有内存状态（Room 的连接池、缓存、repository 实例）全部丢弃。
        db.close()
        openDatabase()

        val restored = repository.getProgress(bookId)
        assertNotNull("progress must survive a cold reopen", restored)
        assertEquals(
            "chapter must be restored, not reset to the first chapter",
            secondChapterArticleId,
            restored!!.chapterArticleId
        )
        assertEquals("paragraph index must survive", 17, restored.paragraphIndex)
        assertEquals("paragraph offset must survive", 42, restored.paragraphOffset)
    }

    /**
     * 位置更新是覆盖而非追加。
     *
     * 若 upsert 退化成 insert-only，重建后读到的会是**第一次**保存的位置，
     * 用户的现象是「进度回退到很久以前」——比完全不保存更难察觉。
     */
    @Test
    fun latestProgress_replacesEarlierProgressAcrossReopen() = runBlocking {
        val bookId = db.bookDao().insertBookWithChapters(book(chapterCount = 3), chapters(3))
        val chapters = db.bookDao().getChapters(bookId).first()

        repository.saveProgress(bookId, chapters[0].articleId, paragraphIndex = 2, paragraphOffset = 0)
        repository.saveProgress(bookId, chapters[2].articleId, paragraphIndex = 99, paragraphOffset = 8)

        db.close()
        openDatabase()

        val restored = repository.getProgress(bookId)!!
        assertEquals(chapters[2].articleId, restored.chapterArticleId)
        assertEquals(99, restored.paragraphIndex)
        assertEquals(8, restored.paragraphOffset)
    }

    private fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(context, EnglishReaderDatabase::class.java, dbFile.path).build()
        repository = BookRepository(db.bookDao(), db.articleDao())
    }

    private fun book(chapterCount: Int) = BookEntity(
        title = "Durability Book",
        author = "Author",
        language = "en",
        identifier = null,
        contentFingerprint = "durability-fingerprint",
        sourceFormat = BookSourceFormat.EPUB3,
        chapterCount = chapterCount,
        totalChars = chapterCount * 10,
        createdAt = 1_000L
    )

    private fun chapters(count: Int) = (0 until count).map { i ->
        ArticleEntity(title = "Chapter $i", content = "Body of chapter $i.", createdAt = 1_000L) to
            BookChapterEntity(
                bookId = 0,
                articleId = 0,
                chapterIndex = i,
                sourceHref = "ch$i.xhtml",
                navigationTitle = "Nav $i"
            )
    }
}
