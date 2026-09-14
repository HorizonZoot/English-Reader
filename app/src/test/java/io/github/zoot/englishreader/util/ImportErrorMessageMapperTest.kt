package io.github.zoot.englishreader.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ErrorMessageMapper.mapImportFailure] 的判据。
 *
 * 之前完全没有：`AiErrorMessageMapper` 返回 `resourceId + formatArgs`，可以纯 JVM 断言，
 * 但导入 mapper 直接调 `Context.getString(id, args)`，于是**占位符个数或类型不匹配只在运行时
 * 抛异常** —— 而且恰好抛在用户已经导入失败的那一刻，把一句可读的错误变成崩溃。
 * 所以这里用 Robolectric 真解析资源，让格式化真的执行一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportErrorMessageMapperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * 每一个 [ImportFailure] variant 都必须映射出非空文案，且格式化不抛异常。
     *
     * 用实际 sealed 类型集合核对手写样本，新增 variant 漏加样本时测试必须失败。
     */
    @Test
    fun mapImportFailure_everyVariant_formatsWithoutThrowing() {
        val failures = listOf(
            ImportFailure.EmptyContent,
            ImportFailure.SourceTooLarge(limitBytes = ImportBudget.MAX_TEXT_SOURCE_BYTES),
            ImportFailure.ContentTooLong(actualChars = 40_001, limitChars = 40_000),
            ImportFailure.ChapterTooLong(
                chapterTitle = "Chapter VII",
                actualChars = 63_822,
                limitChars = 40_000
            ),
            ImportFailure.TooManyParagraphs(actualParagraphs = 1_201, limitParagraphs = 1_200),
            ImportFailure.ParagraphTooLong(actualChars = 14_053, limitChars = 8_000),
            ImportFailure.UnsupportedEncoding,
            ImportFailure.UnsupportedFormat,
            ImportFailure.InvalidEpub,
            ImportFailure.EncryptedEpub,
            ImportFailure.SourceUnreadable,
            ImportFailure.StorageFailed,
            ImportFailure.BookTooManyChapters(actualChapters = 501, limitChapters = 500),
            ImportFailure.BookTooLong(actualChars = 4_000_001, limitChars = 4_000_000),
            ImportFailure.BookArchiveTooLarge(limitBytes = ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES),
            ImportFailure.BookArchiveTooManyEntries(limitEntries = ImportBudget.MAX_ZIP_ENTRIES),
            ImportFailure.DuplicateBook(existingBookId = 7L, existingTitle = "Pride and Prejudice"),
            ImportFailure.NoReadableChapters
        )

        assertEquals(
            "a new ImportFailure variant must be added to this list",
            ImportFailure::class.sealedSubclasses.toSet(),
            failures.map { it::class }.toSet()
        )

        failures.forEach { failure ->
            val message = ErrorMessageMapper.mapImportFailure(context, failure)
            assertTrue("blank message for $failure", message.isNotBlank())
            // 未被替换的占位符是最常见的失败形态：参数漏传时 getString 会原样留下 %1$d。
            assertFalse("unsubstituted placeholder in '$message' for $failure", message.contains('%'))
        }
    }

    /**
     * `ChapterTooLong` 的文案必须点名章节、且不能沿用单篇「正文过长」的说法。
     *
     * 这是本条失败存在的全部理由：章节边界由制作方的分章决定，用户唯一可行动作是换一个版本。
     * 若文案退回「正文过长」，用户会去找更短的文章 —— 而那不是这里出的问题。
     */
    @Test
    fun mapImportFailure_chapterTooLong_namesChapterAndPointsAtEdition() {
        val message = ErrorMessageMapper.mapImportFailure(
            context,
            ImportFailure.ChapterTooLong(
                chapterTitle = "The Oversized Chapter",
                actualChars = 63_822,
                limitChars = 40_000
            )
        )

        assertTrue("must name the chapter: $message", message.contains("The Oversized Chapter"))
        assertTrue("must report the actual size: $message", message.contains("63822"))
        assertTrue("must report the limit: $message", message.contains("40000"))
        assertTrue("must point at the edition, not the text: $message", message.contains("分章"))
    }

    /** 单篇与章节必须给出不同文案，否则拆分这个失败类型没有意义。 */
    @Test
    fun mapImportFailure_chapterAndArticleOverflow_produceDifferentMessages() {
        val article = ErrorMessageMapper.mapImportFailure(
            context,
            ImportFailure.ContentTooLong(actualChars = 63_822, limitChars = 40_000)
        )
        val chapter = ErrorMessageMapper.mapImportFailure(
            context,
            ImportFailure.ChapterTooLong(
                chapterTitle = "Chapter VII",
                actualChars = 63_822,
                limitChars = 40_000
            )
        )

        assertTrue("article message must not mention chapter splitting: $article", !article.contains("分章"))
        assertTrue(article != chapter)
    }
}
