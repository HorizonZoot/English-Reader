package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportBudgetValidatorTest {

    private fun failureOf(content: String): ImportFailure {
        val error = runCatching { ImportBudgetValidator.validate(content) }.exceptionOrNull()
        assertTrue("expected ImportException but was $error", error is ImportException)
        return (error as ImportException).failure
    }

    @Test
    fun validate_normalContent_passes() {
        ImportBudgetValidator.validate("First paragraph.\n\nSecond paragraph.")
    }

    @Test
    fun validate_blankContent_throwsEmptyContent() {
        listOf("", "   \n\t \n  ").forEach { content ->
            assertEquals("length=${content.length}", ImportFailure.EmptyContent, failureOf(content))
        }
    }

    @Test
    fun validate_overCharLimit_throwsContentTooLong() {
        // 构造超字符上限但段落不超限的内容
        val paragraph = "x".repeat(ImportBudget.MAX_PARAGRAPH_CHARS - 1)
        val paragraphCount = ImportBudget.MAX_IMPORT_CHARS / paragraph.length + 2
        val content = List(paragraphCount) { paragraph }.joinToString("\n\n")

        val failure = failureOf(content)

        assertTrue("expected ContentTooLong but was $failure", failure is ImportFailure.ContentTooLong)
        assertEquals(ImportBudget.MAX_IMPORT_CHARS, (failure as ImportFailure.ContentTooLong).limitChars)
    }

    @Test
    fun validate_singleHugeParagraph_throwsParagraphTooLong() {
        // 「整篇没有空行」的退化输入：单个巨型段落，Lazy 化也救不了
        // （LazyColumn 的最小组合单位就是一个段落）
        val content = "x".repeat(ImportBudget.MAX_PARAGRAPH_CHARS + 1)

        val failure = failureOf(content)

        assertTrue("expected ParagraphTooLong but was $failure", failure is ImportFailure.ParagraphTooLong)
    }

    @Test
    fun validate_tooManyParagraphs_throwsTooManyParagraphs() {
        // 同字数下段落结构差异会让渲染成本差一个数量级，故段落数须独立设限
        val content = List(ImportBudget.MAX_IMPORT_PARAGRAPHS + 1) { "p" }.joinToString("\n\n")

        val failure = failureOf(content)

        assertTrue("expected TooManyParagraphs but was $failure", failure is ImportFailure.TooManyParagraphs)
    }

    @Test
    fun validate_paragraphCountMatchesParagraphAlignerRule() {
        // 契约：分段规则必须与 ParagraphAligner 一致，否则校验的段数与渲染段数不符，
        // 渲染上限形同虚设。这里用「多个空行 + 缩进空白」验证两者行为相同。
        val separator = "\n   \n\n  "
        val content = List(ImportBudget.MAX_IMPORT_PARAGRAPHS) { "p" }.joinToString(separator)

        // 恰好达到段落上限，额外空行不应被计为段落。
        ImportBudgetValidator.validate(content)
        assertEquals(
            ImportFailure.TooManyParagraphs(
                ImportBudget.MAX_IMPORT_PARAGRAPHS + 1,
                ImportBudget.MAX_IMPORT_PARAGRAPHS
            ),
            failureOf(content + separator + "p")
        )
    }

    @Test
    fun exceedsFullExplanationLimit_contentLengths_matchBudgetDecision() {
        listOf(
            "short text" to false,
            "x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1) to true
        ).forEach { (content, expected) ->
            assertEquals("length=" + content.length, expected, ImportBudgetValidator.exceedsFullExplanationLimit(content))
        }
    }

    // ---- 标题规范化：所有入口共用同一上限 ----

    @Test
    fun normalizeTitle_overLimit_isTruncated() {
        val long = "T".repeat(ImportBudget.MAX_TITLE_CHARS + 500)

        val result = ImportBudgetValidator.normalizeTitle(long, "Untitled")

        assertEquals(ImportBudget.MAX_TITLE_CHARS, result.length)
    }

    @Test
    fun normalizeTitle_shortInput_trimsOrUsesFallback() {
        listOf(
            "  My Doc  " to "My Doc",
            "   " to "Untitled",
            null to "Untitled",
            "Short" to "Short"
        ).forEach { (input, expected) ->
            assertEquals("input=$input", expected, ImportBudgetValidator.normalizeTitle(input, "Untitled"))
        }
    }

    @Test
    fun normalizeTitle_doesNotSplitSurrogatePair() {
        // emoji 占两个 UTF-16 单元。截断点正好落在配对中间时，按 char 截会留下孤立的
        // 高代理项，写进 Room 后列表页显示成乱码方块。
        val title = "T".repeat(ImportBudget.MAX_TITLE_CHARS - 1) + "😀"

        val result = ImportBudgetValidator.normalizeTitle(title, "Untitled")

        assertEquals(ImportBudget.MAX_TITLE_CHARS - 1, result.length)
        assertTrue(
            "不得留下孤立代理项，实际末字符: ${result.last().code}",
            result.none { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) }
        )
    }

    @Test
    fun normalizeTitle_keepsCompleteSurrogatePairWithinLimit() {
        // 配对完整落在上限内时须完整保留，不能误删
        val title = "T".repeat(ImportBudget.MAX_TITLE_CHARS - 2) + "😀"

        val result = ImportBudgetValidator.normalizeTitle(title, "Untitled")

        assertEquals(ImportBudget.MAX_TITLE_CHARS, result.length)
        assertTrue("完整 emoji 须保留", result.endsWith("😀"))
    }
}
