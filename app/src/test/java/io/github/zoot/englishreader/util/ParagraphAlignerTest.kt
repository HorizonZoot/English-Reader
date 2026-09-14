package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.core.SentenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * ParagraphAligner 单元测试
 *
 * 覆盖分段对齐的关键逻辑：
 * - 英文段与译文段一一配对
 * - sentenceOffset 按前序段句子数累加，保证全局索引唯一
 * - 译文段数不足时对应段 chinese 为 null
 * - 译文段数过多时多余段被忽略
 * - 空行分隔、首尾空白被正确处理
 * - 每段只分句一次，分句结果随段落返回给渲染层复用（9.6）
 * - AlignedParagraph 在构造处拒绝与其文本不符的句子列表
 */
class ParagraphAlignerTest {

    /**
     * 以句号切分的假分句器，避免依赖 Android 的 SentenceSplitter。
     *
     * 产出真实的 [SentenceRange]：句号归属前一句、offset 连续覆盖整段、index 从 0 递增，
     * 与生产 SentenceSplitter 的坐标系契约一致。
     */
    private val dotSplitter: (String) -> List<SentenceRange> = { text ->
        val ranges = mutableListOf<SentenceRange>()
        var start = 0
        var index = 0
        text.forEachIndexed { position, char ->
            if (char == '.') {
                val end = position + 1
                ranges.add(SentenceRange(index++, text.substring(start, end), start, end))
                start = end
            }
        }
        if (start < text.length) {
            ranges.add(SentenceRange(index, text.substring(start), start, text.length))
        }
        ranges.ifEmpty { listOf(SentenceRange(0, text, 0, text.length)) }
    }

    @Test
    fun align_pairsParagraphsInOrder() {
        val content = "First para.\n\nSecond para."
        val translation = "第一段。\n\n第二段。"

        val result = ParagraphAligner.align(content, translation, dotSplitter)

        assertEquals(2, result.size)
        assertEquals("First para.", result[0].english)
        assertEquals("第一段。", result[0].chinese)
        assertEquals("Second para.", result[1].english)
        assertEquals("第二段。", result[1].chinese)
    }

    @Test
    fun align_accumulatesSentenceOffsetsIndependentlyOfTranslation() {
        data class Case(
            val name: String,
            val content: String,
            val translation: String?,
            val chinese: List<String?>,
            val offsets: List<Int>,
            val totalSentences: Int
        )
        val content = "A. B.\n\nC.\n\nD. E. F."
        val cases = listOf(
            Case("matched", content, "甲。\n\n乙。\n\n丙。", listOf("甲。", "乙。", "丙。"), listOf(0, 2, 3), 6),
            Case("short translation", content, "甲。", listOf("甲。", null, null), listOf(0, 2, 3), 6),
            Case("no translation", content, null, listOf(null, null, null), listOf(0, 2, 3), 6),
            Case("one sentence per paragraph", "One.\n\nTwo.\n\nThree.", "第一。", listOf("第一。", null, null), listOf(0, 1, 2), 3),
            Case("two untranslated paragraphs", "One.\n\nTwo.", null, listOf(null, null), listOf(0, 1), 2)
        )
        cases.forEach { case ->
            val result = ParagraphAligner.align(case.content, case.translation, dotSplitter)

            assertEquals(case.name, case.offsets, result.map { it.sentenceOffset })
            assertEquals(case.name, case.chinese, result.map { it.chinese })
            var expectedOffset = 0
            result.forEach { paragraph ->
                assertEquals(case.name, expectedOffset, paragraph.sentenceOffset)
                expectedOffset += paragraph.sentences.size
            }
            assertEquals(case.name, case.totalSentences, expectedOffset)
        }
    }

    @Test
    fun align_translationLongerThanContent_extraParagraphsIgnored() {
        val content = "Only one para."
        val translation = "第一段。\n\n多余的第二段。"

        val result = ParagraphAligner.align(content, translation, dotSplitter)

        assertEquals(1, result.size) // 英文段数决定渲染段数
        assertEquals("第一段。", result[0].chinese) // 只取第 1 段译文
    }

    @Test
    fun align_multipleBlankLinesAndWhitespace_trimmedAndSplit() {
        // 段落间有多个空行 + 缩进空白
        val content = "First.\n   \n\n  Second.  "
        val translation = "一。\n\n\n二。"

        val result = ParagraphAligner.align(content, translation, dotSplitter)

        assertEquals(2, result.size)
        assertEquals("First.", result[0].english)     // 首尾空白被 trim
        assertEquals("Second.", result[1].english)
        assertEquals("一。", result[0].chinese)
        assertEquals("二。", result[1].chinese)
    }

    @Test
    fun align_singleParagraphNoBlankLine_returnsOneParagraph() {
        val content = "Just one paragraph with. multiple sentences."

        val result = ParagraphAligner.align(content, null, dotSplitter)

        assertEquals(1, result.size)
        assertEquals(0, result[0].sentenceOffset)
    }

    @Test
    fun align_blankContent_returnsEmpty() {
        listOf("", "   ", "\n\n\n", "  \n \n  ").forEachIndexed { index, content ->
            assertEquals("blank case $index", 0, ParagraphAligner.align(content, null, dotSplitter).size)
        }
    }

    @Test
    fun align_splitsEachParagraphExactlyOnce() {
        // 9.6 的核心回归：对齐层与渲染层曾各分句一次，同一段文本被分两遍。
        // 现在每段只分一次，结果随段落返回给渲染层复用。
        val splitCalls = mutableListOf<String>()
        val countingSplitter: (String) -> List<SentenceRange> = { text ->
            splitCalls.add(text)
            dotSplitter(text)
        }
        val content = "A. B.\n\nC.\n\nD. E. F."

        val result = ParagraphAligner.align(content, null, countingSplitter)

        assertEquals(3, result.size)
        assertEquals(listOf("A. B.", "C.", "D. E. F."), splitCalls)
    }

    @Test
    fun align_returnsParagraphLocalSentenceOffsets() {
        // 句子 offset 是段落局部坐标（相对本段文本），不是文章全局坐标。
        // 跨段落的身份由 index + sentenceOffset 换算，渲染层依赖这一点定位高亮。
        val content = "A. B.\n\nCharlie."

        val result = ParagraphAligner.align(content, null, dotSplitter)

        val first = result[0].sentences
        assertEquals(2, first.size)
        assertEquals(0, first[0].startOffset)
        assertEquals("A.", first[0].text)
        assertEquals(2, first[1].startOffset)
        assertEquals(" B.", first[1].text)

        // 第 2 段的 offset 从 0 重新起算，而非延续第 1 段
        val second = result[1].sentences
        assertEquals(1, second.size)
        assertEquals(0, second[0].startOffset)
        assertEquals(0, second[0].index)
        assertEquals("Charlie.", second[0].text)
        assertEquals(2, result[1].sentenceOffset) // 全局身份靠段落偏移换算
    }

    @Test
    fun align_returnsSplitterListUnmodified() {
        // 渲染层复用的就是分句器产出的那个列表，中间没有再包装或重算。
        val produced = mutableListOf<List<SentenceRange>>()
        val recordingSplitter: (String) -> List<SentenceRange> = { text ->
            dotSplitter(text).also { produced.add(it) }
        }

        val result = ParagraphAligner.align("A. B.\n\nC.", null, recordingSplitter)

        assertEquals(2, result.size)
        assertSame(produced[0], result[0].sentences)
        assertSame(produced[1], result[1].sentences)
    }

    @Test
    fun alignedParagraph_mismatchedSourceRange_throws() {
        val cases = listOf(
            "text from another paragraph" to SentenceRange(0, "Gamma.", 0, 6),
            "shifted offset" to SentenceRange(0, "Alpha.", 1, 7)
        )
        cases.forEach { (name, sentence) ->
            val error = assertThrows(name, IllegalArgumentException::class.java) {
                ParagraphAligner.AlignedParagraph(
                    english = "Alpha. Beta.",
                    chinese = null,
                    sentenceOffset = 0,
                    sentences = listOf(sentence)
                )
            }
            assertEquals(name, true, error.message?.contains("does not occur at offset"))
        }
    }

    @Test
    fun alignedParagraph_nonSequentialSentenceIndex_throws() {
        // index 跳号会让不同段落换算出的全局句子身份撞车或空缺。
        val error = assertThrows(IllegalArgumentException::class.java) {
            ParagraphAligner.AlignedParagraph(
                english = "Alpha. Beta.",
                chinese = null,
                sentenceOffset = 0,
                sentences = listOf(
                    SentenceRange(0, "Alpha.", 0, 6),
                    SentenceRange(5, " Beta.", 6, 12)
                )
            )
        }
        assertEquals(true, error.message?.contains("sequential"))
    }

    @Test
    fun alignedParagraph_emptySentences_isAllowed() {
        // 空句子列表本身不违反坐标系不变量（例如分句器对某段无输出），不应在构造处拒绝。
        val paragraph = ParagraphAligner.AlignedParagraph(
            english = "Alpha.",
            chinese = null,
            sentenceOffset = 0,
            sentences = emptyList()
        )
        assertEquals(0, paragraph.sentences.size)
    }
}
