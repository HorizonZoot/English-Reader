package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ParagraphAligner × 真实 SentenceSplitter 的联合契约（9.6）
 *
 * [ParagraphAligner.AlignedParagraph] 在构造处 `require` 句子索引连续、且句子文本落在其声称
 * 的偏移上。生产路径传入的就是 `SentenceSplitter::split` 的输出，所以这里用真实分句器跑各种
 * 棘手文本，证明该校验**不会**在合法输入上崩掉阅读页。
 *
 * 需要 ICU（`android.icu.text.BreakIterator`），故走 Robolectric 而非纯 JVM。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParagraphAlignerRealSplitterTest {

    private val trickyContents = listOf(
        "Simple sentence.",
        "First sentence. Second sentence. Third one.",
        // ICU 不抑制英文缩写，靠 SentenceSplitter 的称谓正则兜底
        "Mr. Smith arrived late. Dr. Jones followed.",
        // 小写 ms. 是毫秒，不应被当成称谓合并
        "The delay was 20 ms. The next step ran.",
        // 省略号按 ICU 规则断句，没有专门处理
        "Wait... I think so.",
        "U.S. GDP grew. Analysts e.g. Smith disagreed.",
        // 段内硬折行不是段落分隔，会留在段落文本里
        "Line one\nstill same paragraph. Next sentence.",
        // 句间多空格 / 制表符
        "Alpha.   Beta.\tGamma.",
        "Quoted \"speech ends here.\" Then narration.",
        "Parenthetical (with an aside.) And more.",
        "中文句子。第二个中文句子。",
        "Mixed 中文 and English. Second sentence 有中文。",
        "No terminal punctuation at all",
        "Multiple!!! Exclamations??? Here.",
        "Numbers 3.14 and 2.5 stay inline. Done.",
        "a.b.c. compressed initials. Next.",
        // 多段：走 align 的分段路径
        "First para one. First para two.\n\nSecond para only.",
        "Para with title Mr. Smith.\n\nAnother para. With two sentences.",
        // 长文本（多段 + 多句）
        (1..40).joinToString(" ") { "Sentence number $it." },
        (1..15).joinToString("\n\n") { "Paragraph $it has one sentence. And a second." }
    )

    @Test
    fun align_realSplitterOutput_neverViolatesAlignedParagraphInvariant() {
        // 构造本身就是断言：任何一段违反不变量都会抛 IllegalArgumentException 让测试失败。
        trickyContents.forEach { content ->
            val paragraphs = ParagraphAligner.align(content, null, SentenceSplitter::split)

            paragraphs.forEach { paragraph ->
                paragraph.sentences.forEachIndexed { position, sentence ->
                    assertEquals(
                        "index must be sequential for: $content",
                        position,
                        sentence.index
                    )
                    assertTrue(
                        "sentence must occur at its offset for: $content",
                        paragraph.english.startsWith(sentence.text, sentence.startOffset)
                    )
                }
            }
        }
    }

    @Test
    fun align_realSplitter_offsetAccumulatesAcrossParagraphs() {
        val content = "First para one. First para two.\n\nSecond para only.\n\nThird. Para. Here."

        val paragraphs = ParagraphAligner.align(content, null, SentenceSplitter::split)

        assertEquals(3, paragraphs.size)
        var expected = 0
        paragraphs.forEach { paragraph ->
            assertEquals(expected, paragraph.sentenceOffset)
            expected += paragraph.sentences.size
        }
    }

    @Test
    fun align_realSplitter_paragraphOffsetsAreParagraphLocal() {
        // 第 2 段的句子偏移必须相对本段文本重新起算，否则渲染层按段落局部坐标绘制会错位。
        val content = "Alpha one. Alpha two.\n\nBeta one. Beta two."

        val paragraphs = ParagraphAligner.align(content, null, SentenceSplitter::split)

        assertEquals(2, paragraphs.size)
        paragraphs.forEach { paragraph ->
            assertEquals(0, paragraph.sentences.first().startOffset)
            assertEquals(0, paragraph.sentences.first().index)
            // 末句的 endOffset 不超出本段文本长度
            assertTrue(paragraph.sentences.last().endOffset <= paragraph.english.length)
        }
        assertEquals(0, paragraphs[0].sentenceOffset)
        assertEquals(paragraphs[0].sentences.size, paragraphs[1].sentenceOffset)
    }
}
