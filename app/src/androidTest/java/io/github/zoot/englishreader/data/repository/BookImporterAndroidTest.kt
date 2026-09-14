package io.github.zoot.englishreader.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.zoot.englishreader.data.importer.EpubBookParser
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.spike.ReadiumEpubFixtures
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生产 [BookImporter] 在真实跨进程 `content://` 上的设备验收。
 *
 * 为什么必须是设备测试而不是 JVM 测试：这条链路的每一环都只在真机成立 ——
 * `MediaStore` 是真实的系统 ContentProvider（不是 mock 的 ContentResolver）、
 * Readium 的 `AssetRetriever` 需要真实 `File` 随机访问、`android.util.Xml`
 * 的 pull parser 在裸 JVM 上返回 null。JVM 侧的
 * [io.github.zoot.englishreader.data.importer.EpubBookParserTest] 覆盖解析语义，
 * 这里覆盖的是「SAF URI → 有界复制 → 解析 → 临时文件清理」的端到端接线。
 *
 * 临时文件断言是本类的核心：临时文件是用户书籍的**明文副本**，
 * 无论成功、失败还是取消都不能留在 cacheDir。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BookImporterAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val importer = BookImporter(context, EpubBookParser(context))

    @Test
    fun importFromUri_realContentUri_parsesChaptersAndCleansTemp() = runBlocking {
        val baseline = tempFileCount()
        val uri = insertIntoMediaStore(ReadiumEpubFixtures.epub3WithNav())
        try {
            val book = importer.importFromUri(uri)

            assertEquals(2, book.chapters.size)
            // 序号必须是从 0 开始的连续重编号，不是原始 spine 下标。
            assertEquals(listOf(0, 1), book.chapters.map { it.chapterIndex })
            book.chapters.forEach { chapter ->
                assertTrue("chapter ${chapter.chapterIndex} body blank", chapter.content.isNotBlank())
                assertTrue("sourceHref missing", chapter.sourceHref.isNotBlank())
            }
            assertTrue("title blank", book.metadata.title.isNotBlank())
            assertTrue("fingerprint blank", book.metadata.contentFingerprint.isNotBlank())
            assertTrue("toc empty for a NAV book", book.toc.isNotEmpty())

            // 成功路径也必须清理：临时文件是用户书籍明文副本。
            assertEquals("temp file leaked on success", baseline, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun importFromUri_multiChapterBook_renumbersAndCleansTemp() = runBlocking {
        val baseline = tempFileCount()
        val uri = insertIntoMediaStore(ReadiumEpubFixtures.largeEpub(chapters = 40, sentencesPerChapter = 20))
        try {
            val book = importer.importFromUri(uri)

            assertEquals(40, book.chapters.size)
            assertEquals((0 until 40).toList(), book.chapters.map { it.chapterIndex })
            assertEquals("temp file leaked on multi-chapter success", baseline, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /**
     * 加密正文必须在 Readium 打开之前被拒绝。
     *
     * Phase 0 已实测：Readium 3.0.3 对声明了不支持算法的资源照样返回**明文字节**
     * （见 `ReadiumPublicationSpikeTest`）。所以这道 preflight 不是冗余的礼貌检查，
     * 它是唯一阻止把 DRM 书当普通书导入的闸门。
     */
    @Test
    fun importFromUri_encryptedPayload_failsTypedAndCleansTemp() = runBlocking {
        val baseline = tempFileCount()
        val uri = insertIntoMediaStore(ReadiumEpubFixtures.epubWithEncryptionDeclaration())
        try {
            val failure = runCatching { importer.importFromUri(uri) }.exceptionOrNull()

            assertTrue(
                "expected ImportException, got ${failure?.let { it::class.java.simpleName }}",
                failure is ImportException
            )
            assertSame(
                ImportFailure.EncryptedEpub,
                (failure as ImportException).failure
            )
            assertEquals("temp file leaked on typed failure", baseline, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun importFromUri_notAnArchive_failsTypedAndCleansTemp() = runBlocking {
        val baseline = tempFileCount()
        val uri = insertIntoMediaStore(ReadiumEpubFixtures.notAnArchive())
        try {
            val failure = runCatching { importer.importFromUri(uri) }.exceptionOrNull()

            assertTrue("expected ImportException", failure is ImportException)
            assertSame(ImportFailure.InvalidEpub, (failure as ImportException).failure)
            assertEquals("temp file leaked on invalid archive", baseline, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /**
     * 真实 coroutine 取消：不是让 stream 主动抛 [CancellationException]，
     * 而是 `job.cancel()`。
     *
     * 判据是三条同时成立：`CancellationException` 原样上抛（不被转成
     * `ImportFailure`）、复制在中途中止、临时文件被删除。缺任何一条，
     * 用户返回后后台仍在跑，或 cacheDir 里留下整本书的明文副本。
     */
    @Test
    fun importFromUri_jobCancelled_rethrowsAndCleansTemp() = runBlocking {
        val baseline = tempFileCount()
        // 40 章 × 200 句：足够大，保证取消发生在复制/解析中途而非之后。
        val uri = insertIntoMediaStore(ReadiumEpubFixtures.largeEpub(chapters = 40, sentencesPerChapter = 200))
        try {
            val started = CountDownLatch(1)
            var caught: Throwable? = null

            val job = launch(Dispatchers.IO) {
                try {
                    started.countDown()
                    importer.importFromUri(uri)
                } catch (e: Throwable) {
                    caught = e
                    throw e
                }
            }

            assertTrue("import never started", started.await(10, TimeUnit.SECONDS))
            job.cancel()
            job.join()

            assertTrue(
                "expected CancellationException, got ${caught?.let { it::class.java.simpleName }}",
                caught is CancellationException
            )
            // 取消后临时文件必须已删除——它含用户书籍明文。
            assertEquals("temp file leaked on cancellation", baseline, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun importFromUri_unreadableUri_failsSourceUnreadable() = runBlocking {
        val baseline = tempFileCount()
        // 一个已删除的 MediaStore 行：URI 语法合法但 openInputStream 会失败。
        val uri = insertIntoMediaStore(ReadiumEpubFixtures.epub3WithNav())
        context.contentResolver.delete(uri, null, null)

        val failure = runCatching { importer.importFromUri(uri) }.exceptionOrNull()

        assertTrue("expected ImportException", failure is ImportException)
        assertSame(
            ImportFailure.SourceUnreadable,
            (failure as ImportException).failure
        )
        assertEquals("temp file leaked on unreadable source", baseline, tempFileCount())
    }

    /**
     * 超过 [ImportBudget.MAX_EPUB_ARCHIVE_BYTES] 的文件必须在有界复制阶段被拒，
     * 而不是先落盘再判断。
     *
     * 这道闸在 [BookImporter.copyToTempFile] 里，**不在** `BookArchivePreflight` ——
     * preflight 从不读 `file.length()`。此前整本书路径上没有任何用例行使拒绝路径：
     * 基准测试只断言 fixture *不超过* 上限（fixture 自检），`SourceTooLarge` 的现有
     * 用例都在单篇路径与 `BoundedSourceReaderTest` 上。
     *
     * 判据是三条同时成立：typed failure 是 [ImportFailure.SourceTooLarge] 且带正确
     * 上限值、异常在复制阶段抛出（不是解析阶段的 `InvalidEpub`）、临时文件被删除。
     * 第三条最关键 —— 拒绝一个 33 MiB 的文件却把 32 MiB 的明文副本留在 cacheDir，
     * 比不拒绝更糟。
     */
    @Test
    fun importFromUri_oversizedArchive_failsSourceTooLargeAndCleansTemp() = runBlocking {
        val baseline = tempFileCount()
        // 全零字节：写入快，且不是合法 ZIP —— 若体量闸失效，会走到 InvalidEpub，
        // 从而暴露「拒绝发生在解析阶段而非复制阶段」。
        val oversized = ByteArray(ImportBudget.MAX_EPUB_ARCHIVE_BYTES + 1024)
        val uri = insertIntoMediaStore(oversized)
        try {
            val failure = runCatching { importer.importFromUri(uri) }.exceptionOrNull()

            assertTrue("expected ImportException, got \$failure", failure is ImportException)
            val typed = (failure as ImportException).failure
            assertTrue(
                "expected SourceTooLarge, got \$typed — 体量闸失效时会退化成解析阶段的 InvalidEpub",
                typed is ImportFailure.SourceTooLarge
            )
            assertEquals(
                ImportBudget.MAX_EPUB_ARCHIVE_BYTES,
                (typed as ImportFailure.SourceTooLarge).limitBytes
            )
            assertEquals("temp file leaked on oversized archive", baseline, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    // --- helpers ---

    /** 真实系统 ContentProvider，不是 mock 的 ContentResolver。 */
    private fun insertIntoMediaStore(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "book-import-test-${System.nanoTime()}.epub")
            put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/EnglishReaderBookImportTest"
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

    /** 与 [BookImporter] 的 `File.createTempFile("book-import-", ".epub", cacheDir)` 前缀一致。 */
    private fun tempFileCount(): Int =
        context.cacheDir.listFiles { file: File -> file.name.startsWith("book-import-") }?.size ?: 0
}
