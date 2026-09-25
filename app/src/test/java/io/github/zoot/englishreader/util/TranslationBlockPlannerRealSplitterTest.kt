package io.github.zoot.englishreader.util

import android.app.Application
import io.github.zoot.englishreader.model.TranslationBlockCoverage
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用**真实 Android ICU** 驱动 [TranslationBlockPlanner]。
 *
 * [TranslationBlockPlannerTest] 注入假分句器，能精确钉住分块规则，但恰恰因此证明不了生产配置
 * 可用：自动模式的「句末换行」判定依赖 ICU 给出的句子终点，假分句器是照着期望结果写的。这里
 * 换成 `SentenceSplitter::split`，验证接线在真实断句下同样成立。
 *
 * 断言只针对**能从文本本身确定**的性质（块数、边界落在哪个词后、覆盖完整），不复述 ICU 的完整
 * 断句结果——那属于 `SentenceSplitterAndroidTest`，在这里重复一遍只会让 ICU 的任何版本差异
 * 同时弄红两个测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranslationBlockPlannerRealSplitterTest {

    private fun plan(content: String, mode: TranslationSegmentationMode) =
        TranslationBlockPlanner.plan(
            articleId = 1L,
            content = content,
            mode = mode,
            sentenceSplitter = SentenceSplitter::split
        )

    private fun planned(content: String, mode: TranslationSegmentationMode) =
        (plan(content, mode) as TranslationBlockPlanner.Result.Planned).plan

    @Test
    fun plan_realIcuSentenceEndNewlines_splitsAtEachOfThem() {
        val content = "Reading is useful.\nIt builds vocabulary.\nIt also builds patience."

        val blocks = planned(content, TranslationSegmentationMode.AUTO).blocks

        assertEquals(3, blocks.size)
        assertEquals("Reading is useful.\n", blocks[0].text)
        assertEquals("It builds vocabulary.\n", blocks[1].text)
        assertEquals("It also builds patience.", blocks[2].text)
        assertTrue(blocks.all { it.sourceParagraphIndex == 0 })
    }

    /**
     * 真实 ICU 下的硬折行。
     *
     * `the most` 之后的换行不在句子终点，因此不能成为块边界——否则 PDF 复制来的正文会被切成
     * 一堆半句，而每半句都会单独送去翻译。
     */
    @Test
    fun plan_realIcuMidSentenceWrap_keepsTheSentenceTogether() {
        val content = "Reading is one of the most\nbeneficial activities for our minds."

        val blocks = planned(content, TranslationSegmentationMode.AUTO).blocks

        assertEquals(1, blocks.size)
        assertEquals(content, blocks.single().text)
    }

    /** 称谓缩写由 `SentenceSplitter` 合并，其后的换行不是句末，不该成为边界。 */
    @Test
    fun plan_realIcuTitleAbbreviation_doesNotSplitAfterIt() {
        val content = "We met Mr.\nSmith yesterday.\nHe was late."

        val blocks = planned(content, TranslationSegmentationMode.AUTO).blocks

        assertEquals(2, blocks.size)
        assertEquals("We met Mr.\nSmith yesterday.\n", blocks[0].text)
        assertEquals("He was late.", blocks[1].text)
    }

    @Test
    fun plan_realIcuLongUnbrokenProse_groupsAtSentenceBoundariesAndCoversEverything() {
        val sentence = "The quiet library kept its old habits through every season of the year. "
        val content = sentence.repeat(30).trim()

        val plan = planned(content, TranslationSegmentationMode.AUTO)

        assertTrue("long prose must be grouped into several blocks", plan.blocks.size > 1)
        assertTrue(
            "no block may exceed the soft limit when whole sentences still fit under it",
            plan.blocks.all { it.text.length <= TranslationBlockPlanner.AUTO_MAX_CHARS }
        )
        // 每块都应当在句末收束：块尾去掉空白后必须是句号。
        assertTrue(
            "blocks must end on sentence boundaries",
            plan.blocks.all { it.text.trimEnd().endsWith('.') }
        )
        assertEquals(content, plan.blocks.joinToString("") { it.text })
        assertTrue(
            TranslationBlockCoverage.isComplete(
                ParagraphAligner.splitParagraphs(content),
                plan.ranges
            )
        )
    }

    /**
     * 真实 ICU 下的三种模式都必须逐字覆盖正文。
     *
     * 覆盖完整是本模块最不可让的性质：丢一个字符就是用户读不到的正文，重复一段就是重复付费。
     */
    @Test
    fun plan_realIcuEveryMode_reproducesSourceParagraphsExactly() {
        val content = buildString {
            append("First paragraph has two sentences. The second one ends here.\n")
            append("A wrapped line follows it.\n\n")
            append("Second paragraph is one long sentence that just keeps going without any stop")
        }
        val paragraphs = ParagraphAligner.splitParagraphs(content)

        for (mode in TranslationSegmentationMode.entries) {
            val plan = planned(content, mode)
            assertTrue(
                "coverage must be complete in $mode",
                TranslationBlockCoverage.isComplete(paragraphs, plan.ranges)
            )
            paragraphs.forEachIndexed { index, paragraph ->
                val rebuilt = plan.blocks
                    .filter { it.sourceParagraphIndex == index }
                    .joinToString("") { it.text }
                assertEquals("paragraph $index must survive $mode unchanged", paragraph, rebuilt)
            }
        }
    }
}
