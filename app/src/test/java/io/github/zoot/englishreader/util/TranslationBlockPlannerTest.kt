package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.model.TranslationBlockCoverage
import io.github.zoot.englishreader.model.TranslationBlockPlan
import io.github.zoot.englishreader.model.TranslationBlockRange
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块规划器的纯逻辑回归。
 *
 * 分句用本文件的确定性假实现注入，不依赖 ICU：真实 ICU 的边界随系统版本变化，用它做断言会让
 * 「块长符合预期」这类用例在不同机器上给出不同结果。真实 ICU 的接线由 Robolectric 用例覆盖。
 *
 * 覆盖的核心不变量是[assertCoversSource]：块必须逐字铺满每个原段落。丢字与重复覆盖都不会抛
 * 异常，只会让用户读到缺失或重复付费翻译的对照，所以每个分块用例都顺带断言它。
 */
class TranslationBlockPlannerTest {

    @Test
    fun plan_blankLineSeparatedParagraphs_keepsAuthorParagraphsAsBlocks() {
        val content = "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph here."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(3, plan.blocks.size)
        assertEquals(listOf(0, 1, 2), plan.blocks.map { it.sourceParagraphIndex })
        assertTrue(plan.blocks.all { it.startOffset == 0 })
        assertCoversSource(content, plan)
    }

    /**
     * 多段之间夹着一个超长段时，长段按句子细分成多块，短段仍整段成块，且没有任何块跨越
     * 空行边界（每块只属于一个源段落）。这是「一个很长的自然段不再原样堆成一整屏对照块」的契约。
     */
    @Test
    fun plan_longParagraphAmongShortOnes_subdividesOnlyTheLongParagraph() {
        val longParagraph = paragraphOf(sentenceCount = 20, sentenceChars = 50)
        val content = "Short intro paragraph.\n\n$longParagraph\n\nShort closing paragraph."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        val byParagraph = plan.blocks.groupBy { it.sourceParagraphIndex }
        assertEquals("应有三个源段落", listOf(0, 1, 2), byParagraph.keys.sorted())
        assertEquals("短的首段整段成一块", 1, byParagraph.getValue(0).size)
        assertEquals("短的尾段整段成一块", 1, byParagraph.getValue(2).size)
        assertTrue("超长的中间段应被细分", byParagraph.getValue(1).size > 1)
        byParagraph.getValue(1).dropLast(1).forEach {
            assertTrue("长段的非末块应达到目标长度：${it.text.length}", it.text.length >= TranslationBlockPlanner.AUTO_TARGET_CHARS)
        }
        assertCoversSource(content, plan)
    }

    /** 落在完整句尾的独立换行是作者的分段意图，自动模式必须采用它。 */
    @Test
    fun plan_newlinesAtSentenceEnds_splitsAtThoseNewlines() {
        val content = "First sentence.\nSecond sentence.\nThird sentence."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(3, plan.blocks.size)
        assertTrue(plan.blocks.all { it.sourceParagraphIndex == 0 })
        assertEquals(listOf("First sentence.\n", "Second sentence.\n", "Third sentence."), plan.blocks.map { it.text })
        assertCoversSource(content, plan)
    }

    /** PDF 复制产生的句中折行不是段落分隔，自动模式不得据此切块。 */
    @Test
    fun plan_hardWrappedSingleSentence_doesNotSplitAtWrap() {
        val content = "The quick brown\nfox jumps over the lazy dog."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(1, plan.blocks.size)
        assertEquals(content, plan.blocks.single().text)
    }

    /** 句中折行与句尾换行并存时，只有句尾的那个是边界。 */
    @Test
    fun plan_mixedWrapAndSentenceEndNewlines_splitsOnlyAtSentenceEnd() {
        val content = "The quick brown\nfox jumped.\nA new thought begins here."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(2, plan.blocks.size)
        assertEquals("The quick brown\nfox jumped.\n", plan.blocks.first().text)
        assertCoversSource(content, plan)
    }

    @Test
    fun plan_crlfAtSentenceEnd_splitsAtThatBoundary() {
        val content = "First sentence.\r\nSecond sentence."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(2, plan.blocks.size)
        assertEquals("First sentence.\r\n", plan.blocks.first().text)
        assertCoversSource(content, plan)
    }

    /**
     * 用户直接粘贴、段间只有单换行的长文：`splitParagraphs` 把整篇当一个大段，段落换行是必切点，
     * 但两处换行之间仍是几百字的整段，必须在必切点之上再按句子细分，而不是一段一块。
     */
    @Test
    fun plan_singleNewlineSeparatedLongParagraphs_subdividesEachBeyondTarget() {
        val longFirst = paragraphOf(sentenceCount = 12, sentenceChars = 50)
        val longSecond = paragraphOf(sentenceCount = 12, sentenceChars = 50)
        val content = "$longFirst\n$longSecond"

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        // 单换行不产生空行，全篇是一个源段落。
        assertTrue(plan.blocks.all { it.sourceParagraphIndex == 0 })
        // 每段 ~600 字符，各自应被切成多块，而不是两块（一段一块）。
        assertTrue("长段必须被句子细分而非一段一块：${plan.blocks.size}", plan.blocks.size > 2)
        plan.blocks.dropLast(1).forEach {
            assertTrue("非末块应达到目标长度：${it.text.length}", it.text.length >= TranslationBlockPlanner.AUTO_TARGET_CHARS)
        }
        assertCoversSource(content, plan)
    }

    /** 没有任何可信分段线索时按完整句子累积，块界只能落在句子起点。 */
    @Test
    fun plan_longParagraphWithoutNewlines_groupsWholeSentencesOnly() {
        val content = paragraphOf(sentenceCount = 30, sentenceChars = 50)
        val sentenceStarts = splitSentences(content).map { it.startOffset }.toSet()

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertTrue("应当切成多块", plan.blocks.size > 1)
        plan.blocks.forEach {
            assertTrue("块界必须落在句子起点：${it.startOffset}", it.startOffset in sentenceStarts)
        }
        plan.blocks.dropLast(1).forEach {
            assertTrue("非末块应达到目标长度：${it.text.length}", it.text.length >= TranslationBlockPlanner.AUTO_TARGET_CHARS)
            assertTrue("非末块不应超过软上限：${it.text.length}", it.text.length <= TranslationBlockPlanner.AUTO_MAX_CHARS)
        }
        assertCoversSource(content, plan)
    }

    /** 单句超过软上限时原样保留：按长度硬切会让模型看不到完整结构。 */
    @Test
    fun plan_singleSentenceLongerThanSoftLimit_keepsItInOneBlock() {
        val content = "x".repeat(TranslationBlockPlanner.AUTO_MAX_CHARS + 500) + "."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(1, plan.blocks.size)
        assertEquals(content, plan.blocks.single().text)
    }

    /** 加入下一整句会超过软上限时提前收束，而不是把超长句拼进当前块。 */
    @Test
    fun plan_shortSentenceFollowedByOverlongSentence_closesBlockBeforeIt() {
        val short = "y".repeat(199) + "."
        val long = "z".repeat(TranslationBlockPlanner.AUTO_MAX_CHARS - 99) + "."
        val content = "$short $long"

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(2, plan.blocks.size)
        assertEquals("$short ", plan.blocks.first().text)
        assertEquals(long, plan.blocks.last().text)
        assertCoversSource(content, plan)
    }

    @Test
    fun plan_shortContent_staysSingleBlock() {
        val content = "Short."

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertEquals(1, plan.blocks.size)
        assertEquals(content, plan.blocks.single().text)
    }

    /** 缩写和引号不得凭空产生额外块：分句结果只有一句时就只有一块。 */
    @Test
    fun plan_abbreviationsAndQuotes_followSentenceSplitterOnly() {
        val content = "Mr. Smith said \"go now\" before leaving."

        val plan = planned(content, TranslationSegmentationMode.AUTO) { text ->
            listOf(SentenceRange(0, text, 0, text.length))
        }

        assertEquals(1, plan.blocks.size)
        assertEquals(content, plan.blocks.single().text)
    }

    /** 用户选按换行就是在纠正「这些换行是分隔」，代码不得再把句中换行合并回去。 */
    @Test
    fun plan_lineMode_splitsEveryNewlineIncludingMidSentence() {
        val content = "The quick brown\nfox jumps over\nthe lazy dog."

        val plan = planned(content, TranslationSegmentationMode.LINE)

        assertEquals(3, plan.blocks.size)
        assertEquals(listOf("The quick brown\n", "fox jumps over\n", "the lazy dog."), plan.blocks.map { it.text })
        assertCoversSource(content, plan)
    }

    @Test
    fun plan_preserveMode_producesOneBlockPerSourceParagraph() {
        val content = "One.\nStill one.\n\nTwo.\nStill two."

        val plan = planned(content, TranslationSegmentationMode.PRESERVE)

        assertEquals(2, plan.blocks.size)
        assertEquals(listOf("One.\nStill one.", "Two.\nStill two."), plan.blocks.map { it.text })
        assertCoversSource(content, plan)
    }

    @Test
    fun plan_everyMode_coversEachSourceCharacterExactlyOnce() {
        val fixtures = listOf(
            "Single.",
            "First.\nSecond.\nThird.",
            "Wrapped line\ncontinues here.",
            "Para one.\n\nPara two.\nWith a line.",
            "First.\r\nSecond.\r\n\r\nThird.",
            paragraphOf(sentenceCount = 12, sentenceChars = 90)
        )

        for (content in fixtures) {
            for (mode in TranslationSegmentationMode.entries) {
                assertCoversSource(content, planned(content, mode))
            }
        }
    }

    /** 块界只可能出现在换行后或句子起点，因此代理对不会被切断。 */
    @Test
    fun plan_surrogatePairs_neverSplitsInsideCodePoint() {
        val content = "Hello 🙂 world.\nNext 👍🏽 line here."

        for (mode in TranslationSegmentationMode.entries) {
            val plan = planned(content, mode)
            plan.blocks.forEach {
                assertFalse("块不得以低位代理开头", it.text.first().isLowSurrogate())
                assertFalse("块不得以高位代理结尾", it.text.last().isHighSurrogate())
            }
            assertCoversSource(content, plan)
        }
    }

    @Test
    fun plan_blankContent_returnsNoContent() {
        assertEquals(
            TranslationBlockPlanner.Result.NoContent,
            TranslationBlockPlanner.plan(1, "   \n\n  \t ", TranslationSegmentationMode.AUTO, ::splitSentences)
        )
    }

    /** 超限时明确报错，不静默换成保留原段落——用户会看到与所选不符的结果却无任何说明。 */
    @Test
    fun plan_lineModeExceedingBlockLimit_returnsTooManyBlocks() {
        val lines = TranslationBlockPlanner.MAX_TRANSLATION_BLOCKS_PER_ARTICLE + 1
        val content = (1..lines).joinToString("\n") { "Line $it." }

        val result = TranslationBlockPlanner.plan(1, content, TranslationSegmentationMode.LINE, ::splitSentences)

        val rejected = result as TranslationBlockPlanner.Result.TooManyBlocks
        assertEquals(lines, rejected.actualBlocks)
        assertEquals(TranslationBlockPlanner.MAX_TRANSLATION_BLOCKS_PER_ARTICLE, rejected.maxBlocks)
    }

    /** 保留原段落不加新门槛：导入已允许的文章不能在这里忽然翻不了。 */
    @Test
    fun plan_preserveModeAboveBlockLimit_isStillPlanned() {
        val paragraphs = TranslationBlockPlanner.MAX_TRANSLATION_BLOCKS_PER_ARTICLE + 100
        val content = (1..paragraphs).joinToString("\n\n") { "Paragraph $it." }

        val plan = planned(content, TranslationSegmentationMode.PRESERVE)

        assertEquals(paragraphs, plan.blocks.size)
    }

    @Test
    fun plan_anyMode_recordsBlockTextMatchingItsOwnRange() {
        val content = "First.\nSecond sentence here.\n\nAnother paragraph."
        val paragraphs = ParagraphAligner.splitParagraphs(content)

        for (mode in TranslationSegmentationMode.entries) {
            planned(content, mode).blocks.forEach { block ->
                assertEquals(
                    "块文本必须等于它声称的源区间切片",
                    paragraphs[block.sourceParagraphIndex].substring(block.startOffset, block.endOffset),
                    block.text
                )
            }
        }
    }

    @Test
    fun isComplete_contiguousRangesPerParagraph_returnsTrue() {
        val paragraphs = listOf("abcdef", "ghi")
        val ranges = listOf(
            TranslationBlockRange(0, 0, 0, 4),
            TranslationBlockRange(1, 0, 4, 6),
            TranslationBlockRange(2, 1, 0, 3)
        )

        assertTrue(TranslationBlockCoverage.isComplete(paragraphs, ranges))
    }

    @Test
    fun isComplete_gapBetweenRanges_returnsFalse() {
        val ranges = listOf(
            TranslationBlockRange(0, 0, 0, 3),
            TranslationBlockRange(1, 0, 4, 6)
        )

        assertFalse(TranslationBlockCoverage.isComplete(listOf("abcdef"), ranges))
    }

    @Test
    fun isComplete_overlappingRanges_returnsFalse() {
        val ranges = listOf(
            TranslationBlockRange(0, 0, 0, 4),
            TranslationBlockRange(1, 0, 3, 6)
        )

        assertFalse(TranslationBlockCoverage.isComplete(listOf("abcdef"), ranges))
    }

    @Test
    fun isComplete_rangeBeyondParagraphLength_returnsFalse() {
        val ranges = listOf(TranslationBlockRange(0, 0, 0, 9))

        assertFalse(TranslationBlockCoverage.isComplete(listOf("abcdef"), ranges))
    }

    @Test
    fun isComplete_paragraphWithoutAnyRange_returnsFalse() {
        val ranges = listOf(TranslationBlockRange(0, 0, 0, 6))

        assertFalse(TranslationBlockCoverage.isComplete(listOf("abcdef", "ghi"), ranges))
    }

    @Test
    fun isComplete_nonSequentialBlockIndexes_returnsFalse() {
        val ranges = listOf(
            TranslationBlockRange(0, 0, 0, 4),
            TranslationBlockRange(2, 0, 4, 6)
        )

        assertFalse(TranslationBlockCoverage.isComplete(listOf("abcdef"), ranges))
    }

    @Test
    fun isComplete_emptyInputs_returnFalse() {
        assertFalse(TranslationBlockCoverage.isComplete(emptyList(), listOf(TranslationBlockRange(0, 0, 0, 1))))
        assertFalse(TranslationBlockCoverage.isComplete(listOf("abc"), emptyList()))
    }

    // ---- 辅助 ----

    private fun planned(
        content: String,
        mode: TranslationSegmentationMode,
        sentenceSplitter: (String) -> List<SentenceRange> = ::splitSentences
    ): TranslationBlockPlan {
        val result = TranslationBlockPlanner.plan(ARTICLE_ID, content, mode, sentenceSplitter)
        return (result as TranslationBlockPlanner.Result.Planned).plan
    }

    /** 块必须逐字铺满每个原段落，且块序号连续。 */
    private fun assertCoversSource(content: String, plan: TranslationBlockPlan) {
        val paragraphs = ParagraphAligner.splitParagraphs(content)
        assertEquals(plan.blocks.indices.toList(), plan.blocks.map { it.blockIndex })
        assertTrue(
            "范围必须完整覆盖原段落",
            TranslationBlockCoverage.isComplete(paragraphs, plan.ranges)
        )
        paragraphs.forEachIndexed { index, paragraph ->
            val rebuilt = plan.blocks.filter { it.sourceParagraphIndex == index }.joinToString("") { it.text }
            assertEquals("第 $index 段必须可由其块逐字拼回", paragraph, rebuilt)
        }
    }

    private fun paragraphOf(sentenceCount: Int, sentenceChars: Int): String =
        (1..sentenceCount).joinToString(" ") { "x".repeat(sentenceChars - 1) + "." }

    /**
     * 确定性分句：在 `.`/`!`/`?` 之后断句，并把句后空白（含换行）留在该句内。
     *
     * 句尾空白必须留在句内，规划器才能用 `trimEnd` 还原「最后一个非空白字符之后的位置」去判断
     * 一个换行是否恰好落在句尾。
     */
    private fun splitSentences(text: String): List<SentenceRange> {
        val ranges = mutableListOf<SentenceRange>()
        var start = 0
        var index = 0
        var position = 0
        while (position < text.length) {
            if (text[position] in TERMINATORS) {
                var end = position + 1
                while (end < text.length && text[end].isWhitespace()) end++
                ranges += SentenceRange(index++, text.substring(start, end), start, end)
                start = end
                position = end
            } else {
                position++
            }
        }
        if (start < text.length) {
            ranges += SentenceRange(index, text.substring(start), start, text.length)
        }
        return ranges
    }

    private companion object {
        const val ARTICLE_ID = 7L
        val TERMINATORS = charArrayOf('.', '!', '?')
    }
}
