package io.github.zoot.englishreader.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.importer.EpubBookParser
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.spike.ReadiumEpubFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 长书基准：**生产** 导入链路的端到端耗时与内存观察。
 *
 * 与 Phase 0 的 `ReadiumSafSpikeAndroidTest` 的区别很重要 —— 那个测的是 spike probe，
 * 只到「Readium 打开 + 读资源」为止。这里测的是用户真正会走的路：
 * `content://` → [BookImporter]（有界复制 + preflight + 解析）→ [BookRepository.persist]
 * （原子入库）→ 打开章节 → 删除整本。落库和删除是 spike 完全没覆盖的部分，
 * 而它们恰好是长书最可能出问题的地方（500 章 = 500 个 article + 500 个 relation）。
 *
 * **测量环境是 AVD，不是物理设备。** 这些数字只能用于发现数量级问题
 * （例如 O(n²) 退化、章节数线性放大成平方级 SQL），不能作为抬高
 * [ImportBudget.MAX_BOOK_TEXT_CHARS] 的依据 —— 模拟器跑在宿主 CPU 和宿主
 * 文件系统上，I/O 和 GC 行为都不代表真机。抬上限仍需物理设备基线。
 *
 * Java heap 采样同样是有界观察：`Debug.getMemoryInfo` 给的是采样点的值，
 * 不是真正的峰值，GC 时机会让同一段代码的读数波动。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BookImportBenchmarkAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "benchmark-${System.nanoTime()}.db"
    private lateinit var db: EnglishReaderDatabase
    private lateinit var repository: BookRepository
    private lateinit var importer: BookImporter

    @Before
    fun setUp() {
        // 真实 SQLite 文件库，不是 inMemory —— 落盘 I/O 是本基准要测的东西之一。
        db = Room.databaseBuilder(
            context,
            EnglishReaderDatabase::class.java,
            databaseName
        ).build()
        repository = BookRepository(db.bookDao(), db.articleDao())
        importer = BookImporter(context, EpubBookParser(context))
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun benchmark_productionImportPath_shortMediumLong() = runBlocking {
        val samples = listOf(
            Triple("short", 10, 30),
            Triple("medium", 100, 30),
            // 500 章命中 MAX_BOOK_CHAPTERS 上限：批量删除的参数绑定分批逻辑
            // 只在这个规模下才真正被行使。
            Triple("long", ImportBudget.MAX_BOOK_CHAPTERS, 20),
            // 逼近 MAX_BOOK_TEXT_CHARS(4,000,000) 的样本。前三个样本最大只有
            // 25 万字符，是声明上限的 6% —— 用它们回答「4M 能不能撑住」等于没测。
            // 200 章 x 700 句 约 3.5M 字符，单章约 17,500 字符（仍在
            // MAX_CHAPTER_CHARS 40,000 之内），是当前预算下最坏的合法形状。
            Triple("near-book-limit", 200, 700)
        )

        val report = StringBuilder()
        report.append("=== BOOK-IMPORT-BENCHMARK model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ===\n")

        samples.forEach { (label, chapters, sentences) ->
            val bytes = ReadiumEpubFixtures.largeEpub(
                chapters = chapters,
                sentencesPerChapter = sentences
            )
            val uri = insertIntoMediaStore(bytes)
            try {
                // 预热一次：JIT、Readium 静态初始化、SQLite page cache 都需要预热，
                // 否则第一次的数字里混着一次性成本。预热结果立即删除。
                val warmBookId = repository.persist(importer.importFromUri(uri), allowDuplicate = true)
                repository.deleteBook(warmBookId)

                val importMs = mutableListOf<Long>()
                val persistMs = mutableListOf<Long>()
                val openMs = mutableListOf<Long>()
                val deleteMs = mutableListOf<Long>()
                var extractedChars = 0L
                var chapterCount = 0

                repeat(RUNS) {
                    val t0 = System.nanoTime()
                    val book = importer.importFromUri(uri)
                    val t1 = System.nanoTime()
                    // allowDuplicate: 同一 fingerprint 重复导入 RUNS 次是刻意的，
                    // 我们要测的是 persist 本身，不是去重分支。
                    val bookId = repository.persist(book, allowDuplicate = true)
                    val t2 = System.nanoTime()

                    val chapterList = repository.getChapters(bookId).first()
                    val firstArticleId = chapterList.first().articleId
                    val article = db.articleDao().getArticleById(firstArticleId)
                    val t3 = System.nanoTime()

                    repository.deleteBook(bookId)
                    val t4 = System.nanoTime()

                    importMs.add((t1 - t0) / 1_000_000)
                    persistMs.add((t2 - t1) / 1_000_000)
                    openMs.add((t3 - t2) / 1_000_000)
                    deleteMs.add((t4 - t3) / 1_000_000)

                    chapterCount = book.chapters.size
                    extractedChars = book.chapters.sumOf { it.content.length.toLong() }
                    requireNotNull(article) { "chapter article missing after persist" }
                }

                // 堆采样单独跑一次，不放进计时循环：`Debug.getMemoryInfo` 自身耗时毫秒级，
                // 插在 t1/t2 之间会把 persistMs 污染成「持久化 + 采样」。
                //
                // 采样点必须是**解析刚返回、全书章节仍被持有**的时刻。EpubBookParser
                // 把所有章节正文累积在一个 List 里再返回，这个瞬间才是整本导入的内存
                // 代价所在；在 deleteBook 之后采样只会测到已可回收的对象，那个数字
                // 看着漂亮但不回答任何问题。
                val probeBook = importer.importFromUri(uri)
                val probeRuntime = Runtime.getRuntime()
                val heapAfterParse = probeRuntime.totalMemory() - probeRuntime.freeMemory()
                val pssAfterParse = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
                // 显式读一次内容做保活：不读的话 probeBook 在采样点之后就不再被使用，
                // JIT 可以据此让它提前变得可回收，采样值会系统性偏低。
                val probeChars = probeBook.chapters.sumOf { it.content.length.toLong() }
                assertEquals("probe parse disagrees with timed runs for $label", extractedChars, probeChars)

                report.append(
                    "BOOK-BENCH sample=$label chapters=$chapterCount " +
                        "archiveBytes=${bytes.size} extractedChars=$extractedChars " +
                        "importMs=${stat(importMs)} persistMs=${stat(persistMs)} " +
                        "openChapterMs=${stat(openMs)} deleteMs=${stat(deleteMs)} " +
                        "heapAfterParseBytes=$heapAfterParse pssAfterParseKb=$pssAfterParse\n"
                )

                // 基准也必须是判据，不能只是打印。这两条断言的作用是防止
                // 「基准跑绿了但其实什么都没导入」。
                assertEquals("chapter count mismatch for $label", chapters, chapterCount)
                assertTrue("no text extracted for $label", extractedChars > 0)
                assertTrue(
                    "fixture for $label exceeded the archive ceiling; this is a fixture bug, not a perf result",
                    bytes.size <= ImportBudget.MAX_EPUB_ARCHIVE_BYTES
                )
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }

        println(report.toString())

        // 长样本跑完后数据库必须干净——删除路径若在 500 章规模下失败，
        // 这里会留下残留行。
        assertEquals("books leaked after benchmark", 0, repository.getAllBooks().first().size)
    }

    private fun stat(values: List<Long>): String =
        "${values.min()}/${values.sorted()[values.size / 2]}/${values.max()}"

    private fun insertIntoMediaStore(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "book-bench-${System.nanoTime()}.epub")
            put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/EnglishReaderBookBenchmark"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        )
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private companion object {
        const val RUNS = 5
    }
}
