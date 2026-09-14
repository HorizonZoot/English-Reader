package io.github.zoot.englishreader.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * ArticleImporter 单元测试
 *
 * 验证 IO façade 的边界行为：
 * - UTF-8 / GBK 文本读取
 * - openInputStream 返回 null → SourceUnreadable
 * - 空内容 / 全空白 → EmptyContent
 * - 标题优先 DISPLAY_NAME，失败 fallback lastPathSegment，各格式扩展名都要剥
 * - Markdown 按扩展名走剥离管线
 *
 * 注意：importer 会开两次流（先探头部判格式，再完整读取），故 mock 须每次返回新流。
 */
class ArticleImporterTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var importer: ArticleImporter

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { contentResolver.getType(any()) } returns null
        importer = ArticleImporter(context)
    }

    /** 每次 openInputStream 都返回一个新的流——importer 会开两次（探测 + 全读）。 */
    private fun stubContent(uri: Uri, bytes: ByteArray) {
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
    }

    private fun stubContent(uri: Uri, text: String, charset: Charset = Charsets.UTF_8) {
        stubContent(uri, text.toByteArray(charset))
    }

    private suspend fun failureOf(uri: Uri): ImportFailure {
        val error = runCatching { importer.importFromUri(uri) }.exceptionOrNull()
        assertTrue("expected ImportException but was $error", error is ImportException)
        return (error as ImportException).failure
    }

    /** 构造一个返回指定 DISPLAY_NAME 的 mock Cursor */
    private fun mockDisplayNameCursor(displayName: String?): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns (displayName != null)
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getString(0) } returns displayName
        every { cursor.close() } returns Unit
        return cursor
    }

    private fun stubDisplayName(uri: Uri, displayName: String?) {
        every {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        } returns mockDisplayNameCursor(displayName)
    }

    private fun mockUri(lastPathSegment: String?): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns lastPathSegment
        return uri
    }

    @Test
    fun importFromUri_validUtf8File_returnsTrimmedContentAndDisplayName() = runTest {
        val uri = mockUri("doc-id-123")
        stubContent(uri, "  Hello world 你好  ")
        stubDisplayName(uri, "my-article.txt")

        val result = importer.importFromUri(uri)

        assertEquals("Hello world 你好", result.content) // 已 trim
        assertEquals("my-article", result.title)          // DISPLAY_NAME 去掉 .txt
    }

    @Test
    fun importFromUri_gbkFile_decodesWithoutMojibake() = runTest {
        // 改造前硬编码 UTF-8：Windows 记事本存的 GBK 文本会整篇乱码且静默入库
        val uri = mockUri("gbk.txt")
        stubContent(uri, "这是一段中文正文", Charset.forName("GBK"))
        stubDisplayName(uri, "gbk.txt")

        val result = importer.importFromUri(uri)

        assertEquals("这是一段中文正文", result.content)
    }

    @Test
    fun importFromUri_markdownExtension_stripsMarkers() = runTest {
        val uri = mockUri("notes.md")
        stubContent(uri, "# Title\n\nSome **bold** text.")
        stubDisplayName(uri, "notes.md")

        val result = importer.importFromUri(uri)

        assertEquals("Title\n\nSome bold text.", result.content)
        assertEquals("notes", result.title)   // .md 也要被剥掉
    }

    @Test
    fun importFromUri_nullInputStream_failsWithSourceUnreadable() = runTest {
        val uri = mockUri("doc-id-123")
        every { contentResolver.openInputStream(uri) } returns null

        assertEquals(ImportFailure.SourceUnreadable, failureOf(uri))
    }

    @Test
    fun importFromUri_emptyOrBlankContent_failsWithEmptyContent() = runTest {
        listOf(
            "empty" to "",
            "blank" to "   \n\t  "
        ).forEach { (case, content) ->
            val uri = mockUri("$case.txt")
            stubContent(uri, content)

            assertEquals(case, ImportFailure.EmptyContent, failureOf(uri))
        }
    }

    @Test
    fun importFromUri_displayNameQueryReturnsNull_fallsBackToLastPathSegment() = runTest {
        val uri = mockUri("fallback-name.txt")
        stubContent(uri, "content")
        // query 返回 null（provider 不支持 DISPLAY_NAME）
        every {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        } returns null

        val result = importer.importFromUri(uri)

        assertEquals("fallback-name", result.title) // fallback 到 lastPathSegment，去掉 .txt
    }

    @Test
    fun importFromUri_bothNameSourcesMissing_usesUntitled() = runTest {
        val uri = mockUri(null)
        stubContent(uri, "content")
        every {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        } returns null

        val result = importer.importFromUri(uri)

        assertEquals("Untitled", result.title)
    }

    @Test
    fun importFromUri_unknownExtension_keepsNameIntact() = runTest {
        // 未知扩展名可能是标题的一部分，不该被剥掉
        val uri = mockUri("v1.2")
        stubContent(uri, "content")
        stubDisplayName(uri, "release v1.2")

        val result = importer.importFromUri(uri)

        assertEquals("release v1.2", result.title)
    }
}
