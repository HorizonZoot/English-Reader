package io.github.zoot.englishreader.util

import android.app.Application
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SentenceSplitter 分句契约测试
 *
 * 被测对象依赖 `android.icu.text.BreakIterator`（Android ICU），需要 Android Runtime。
 * 使用 Robolectric 在 JVM 上提供该 Runtime，验证 ICU 断句行为 + `SentenceSplitter` 的
 * 称谓合并修正（Mr./Dr./Ms./Prof./U.S./e.g./i.e. 不应触发断句）。
 *
 * 同仓库 `ParagraphAlignerRealSplitterTest` 已在 Robolectric 下跑过更棘手的文本
 * （省略号、括号、引号、中英混排、多段），证明 Robolectric ICU 与真机行为一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SentenceSplitterAndroidTest {

    @Test
    fun split_commonSentencePatterns_splitCorrectly() {
        val cases = listOf(
            "Hello world. This is a test. How are you?" to listOf(
                "Hello world. ",
                "This is a test. ",
                "How are you?"
            ),
            "First sentence here. Second sentence here. Third one." to listOf(
                "First sentence here. ",
                "Second sentence here. ",
                "Third one."
            ),
            "What a day! How are you? I'm fine." to listOf(
                "What a day! ",
                "How are you? ",
                "I'm fine."
            )
        )

        cases.forEach { (text, expectedSentences) ->
            val result = SentenceSplitter.split(text)

            assertEquals(expectedSentences, result.map { it.text })
        }
    }

    @Test
    fun split_offsetsMatchSourceText() {
        // 准备
        val text = "First sentence. Second sentence."

        // 执行
        val result = SentenceSplitter.split(text)

        // 断言：验证每个 SentenceRange 的偏移匹配原文
        assertEquals(2, result.size)
        result.forEach { range ->
            val extracted = text.substring(range.startOffset, range.endOffset)
            assertEquals(range.text, extracted)
        }
    }

    @Test
    fun split_sentenceIndicesAreSequential() {
        // 准备
        val text = "One. Two. Three."

        // 执行
        val result = SentenceSplitter.split(text)

        // 断言
        assertEquals(3, result.size)
        assertEquals(0, result[0].index)
        assertEquals(1, result[1].index)
        assertEquals(2, result[2].index)
    }

    @Test
    fun split_emptyText_returnsEmptyList() {
        // 准备
        val text = ""

        // 执行
        val result = SentenceSplitter.split(text)

        // 断言
        assertTrue(result.isEmpty())
    }

    @Test
    fun split_singleSentence_returnsSingleResult() {
        // 准备
        val text = "This is a single sentence."

        // 执行
        val result = SentenceSplitter.split(text)

        // 断言
        assertEquals(1, result.size)
        assertEquals(text, result[0].text)
        assertEquals(0, result[0].startOffset)
        assertEquals(text.length, result[0].endOffset)
    }

    @Test
    fun split_abbreviations_keepsAbbreviationsWithSentence() {
        val cases = listOf(
            "Mr. Smith went home. He slept." to listOf("Mr. Smith went home. ", "He slept."),
            "Dr. Brown examined it. All was well." to listOf(
                "Dr. Brown examined it. ",
                "All was well."
            ),
            "Ms. Green arrived. We started." to listOf(
                "Ms. Green arrived. ",
                "We started."
            ),
            "Prof. Taylor spoke. Everyone listened." to listOf(
                "Prof. Taylor spoke. ",
                "Everyone listened."
            ),
            "I live in the U.S. It is large." to listOf("I live in the U.S. ", "It is large."),
            "Use examples, e.g. short notes. Then continue." to listOf(
                "Use examples, e.g. short notes. ",
                "Then continue."
            ),
            "It is concise, i.e. short. Then continue." to listOf(
                "It is concise, i.e. short. ",
                "Then continue."
            ),
        )

        cases.forEach { (text, expectedSentences) ->
            val result = SentenceSplitter.split(text)

            assertEquals(expectedSentences, result.map { it.text })
        }
    }

    @Test
    fun split_lowercaseMsMeansMilliseconds_doesNotMergeSentences() {
        // 称谓正则刻意大小写敏感：小写 ms. 是「毫秒」，不是「女士」。
        // 曾因 IGNORE_CASE 把下面这句与后一句强行合并，点第二句会高亮两句，
        // 发给 AI 的也是两句合体。技术类英文文章里 ms. 结句是现实输入。
        val result = SentenceSplitter.split("The delay was 20 ms. The next step ran.")

        assertEquals(
            listOf("The delay was 20 ms. ", "The next step ran."),
            result.map { it.text }
        )
    }

}
