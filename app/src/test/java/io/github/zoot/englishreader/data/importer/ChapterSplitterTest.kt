package io.github.zoot.englishreader.data.importer

import io.github.zoot.englishreader.core.SentenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterSplitter] 的纯 JVM 契约。
 *
 * 分句通过参数注入，所以这里用假实现而不是 `SentenceSplitter`——后者依赖
 * `android.icu.text.BreakIterator`，在纯 JVM 下不可用。假实现按句号切，偏移严格取自原文，
 * 与真实分句器的关键共同点是：**句间空白不属于任何一个 [SentenceRange]**。真实 ICU 行为
 * （缩写、省略号）不在本类职责内，由 `RealProseSentenceSplitTest` 在 Robolectric 下覆盖。
 */
class ChapterSplitterTest {

    /** 按句号切，偏移取自原文。句尾空白留在句子外，复现真实分句器的空白语义。 */
    private val fakeSplitter: (String) -> List<SentenceRange> = { text ->
        val ranges = mutableListOf<SentenceRange>()
        var index = 0
        var cursor = 0
        while (cursor < text.length) {
            val dot = text.indexOf('.', cursor)
            val end = if (dot < 0) text.length else dot + 1
            val slice = text.substring(cursor, end)
            if (slice.isNotBlank()) {
                ranges += SentenceRange(index++, slice, cursor, end)
            }
            // 跳过句间空白：它落在两个 SentenceRange 之间，谁都不拥有。
            cursor = end
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        }
        ranges
    }

    private fun split(
        content: String,
        maxChapterChars: Int = 100,
        maxParagraphChars: Int = 40
    ): List<String> = ChapterSplitter.split(
        content = content,
        maxChapterChars = maxChapterChars,
        maxParagraphChars = maxParagraphChars,
        sentenceSplitter = fakeSplitter
    )

    /**
     * 恒等：已在预算内的正文必须原样返回**同一个字符串实例**。
     *
     * 断言 `assertSame` 而不是 `assertEquals` 是刻意的：`BookMetadata.contentFingerprint`
     * 摘要的是正文字节，而 `ParagraphAligner.splitParagraphs` 会 trim 段落、丢弃空段、
     * 把连续空行吞成一个分隔符。任何「拆开再拼回」的实现都能通过 assertEquals 于大多数输入，
     * 却会在带缩进或多空行的正文上换掉指纹——那 11 本今天能导入的书会被判成「不是同一本书」。
     */
    @Test
    fun split_contentWithinBudget_returnsTheOriginalStringItself() {
        val content = "  First paragraph.\n\n\n\nSecond paragraph with trailing space.  "

        val parts = split(content)

        assertEquals(1, parts.size)
        assertSame(content, parts.single())
    }

    @Test
    fun split_chapterOverCeiling_packsWholeParagraphsWithoutReordering() {
        val paragraphs = (1..6).map { "Paragraph number $it." } // 各 20 字符左右
        val content = paragraphs.joinToString("\n\n")

        val parts = split(content, maxChapterChars = 50)

        assertTrue("expected several parts, got ${parts.size}", parts.size > 1)
        parts.forEach { assertTrue("part over ceiling: ${it.length}", it.length <= 50) }
        // 顺序即阅读顺序：把所有部分按序拼回，段落序列必须与原文完全一致。
        assertEquals(paragraphs, parts.flatMap { it.split("\n\n") })
    }

    @Test
    fun split_paragraphOverParagraphCeiling_dividesItAtSentenceBoundaries() {
        // 一段 5 句、每句 20 字符，共 100 字符，远超 maxParagraphChars = 40。
        val sentences = (1..5).map { "Sentence number $it xx." }
        val content = sentences.joinToString(" ")

        val parts = split(content, maxChapterChars = 1_000, maxParagraphChars = 40)

        val resultParagraphs = parts.flatMap { it.split("\n\n") }
        assertTrue("expected the paragraph to be divided", resultParagraphs.size > 1)
        resultParagraphs.forEach {
            assertTrue("chunk over paragraph ceiling: ${it.length}", it.length <= 40)
        }
        // 切分只在句子边界发生：每个产物都由完整句子构成，不得切在句中。
        resultParagraphs.forEach { chunk ->
            assertTrue("chunk does not end at a sentence boundary: $chunk", chunk.endsWith("."))
        }
    }

    /**
     * 内层优先：单段本身就超过**章节**上限时，必须先按句子切开才可能装进任何一个箱子。
     * 顺序反过来的话，装箱面对一个不可分割的超限单元，只能整段吐出去、于是仍然超限。
     */
    @Test
    fun split_singleParagraphOverChapterCeiling_isDividedBeforePacking() {
        val sentences = (1..10).map { "Sentence number $it xx." } // 约 220 字符
        val content = sentences.joinToString(" ")

        val parts = split(content, maxChapterChars = 60, maxParagraphChars = 40)

        parts.forEach { assertTrue("part over ceiling: ${it.length}", it.length <= 60) }
    }

    /**
     * 句子以下不切。单个句子超过段落上限时原样输出，让调用方的既有校验拒绝它。
     * 按字符硬切会在正文中间造出断句——用户读到坏文本且毫无提示，比拒绝更糟。
     */
    @Test
    fun split_singleSentenceOverParagraphCeiling_isEmittedIntactForTheCallerToReject() {
        val content = "A single very long sentence that has no interior boundary at all"

        val parts = split(content, maxChapterChars = 1_000, maxParagraphChars = 20)

        assertEquals(1, parts.size)
        assertEquals(content, parts.single())
        assertTrue("must stay over the ceiling", parts.single().length > 20)
    }

    /**
     * 分句器返回空列表时，整段必须原样输出——**不能被静默丢弃**。
     *
     * 这条守的是注入契约而不是真实 ICU 行为：语料里那几段无标点的独白，ICU 报的是
     * `sentences=1`（整段一句），不是零句，所以生产路径走不到这里。但分句器是公开参数，
     * 任何返回空列表的实现都会让切分器的游标停在初始值、chunks 为空——若无那条判断，
     * 一整段正文会凭空消失。这里用一个恒返回空列表的 splitter 直接打到那条分支。
     *
     * 上一版这个用例传的是默认 fake splitter 加 `"x".repeat(60)`，名字声称覆盖
     * 「无句子边界」，实际却拿到一个覆盖全文的 SentenceRange，走的是「单句超限」那条路
     * ——与 [split_singleSentenceOverParagraphCeiling_isEmittedIntactForTheCallerToReject]
     * 完全同路，这条分支从未被执行。
     */
    @Test
    fun split_splitterReturnsNoSentences_emitsTheParagraphIntactInsteadOfDroppingIt() {
        val content = "x".repeat(60)

        val parts = ChapterSplitter.split(
            content = content,
            maxChapterChars = 1_000,
            maxParagraphChars = 20,
            sentenceSplitter = { emptyList() }
        )

        assertEquals(listOf(content), parts)
    }

    /**
     * 切分不得丢字。断言全部可见字符逐字保留——空白会因段落 trim 与分隔符归一而变，
     * 但一个字母都不能少，也不能多。
     */
    @Test
    fun split_acrossBothLevels_preservesEveryNonWhitespaceCharacter() {
        val longParagraph = (1..12).joinToString(" ") { "Sentence number $it xx." }
        val content = listOf(
            "Short opening paragraph.",
            longParagraph,
            "Another short paragraph.",
            longParagraph
        ).joinToString("\n\n")

        val parts = split(content, maxChapterChars = 120, maxParagraphChars = 40)

        assertEquals(
            content.filterNot { it.isWhitespace() },
            parts.joinToString("").filterNot { it.isWhitespace() }
        )
    }

    /**
     * 全空白正文原样返回同一实例，不产出任何切片。
     *
     * 空白正文由 `ImportBudgetValidator` 按 `EmptyContent` 拒绝，切分器只需别在它上面自作主张。
     * 注意这里的输入**同时**超过两个上限（8 字符 > maxChapterChars = 5），所以恒等短路的
     * 长度条件不成立——真正让它走恒等路径的是 `splitParagraphs` 丢弃空段后 `paragraphs` 为空，
     * 于是 `all {}` 在空集合上为 true。这条用例因此也钉住了「空集合不得被当成有超限段落」。
     *
     * 上一版只断言 `parts.size == 1`，那是恒真的：任何返回单元素列表的实现都能通过，
     * 包括返回空字符串或返回 trim 后结果的实现——而后者会换掉 contentFingerprint。
     */
    @Test
    fun split_blankContent_returnsTheOriginalStringItself() {
        val content = "   \n\n   "

        val parts = split(content, maxChapterChars = 5, maxParagraphChars = 3)

        assertEquals(1, parts.size)
        assertSame(content, parts.single())
    }
}
