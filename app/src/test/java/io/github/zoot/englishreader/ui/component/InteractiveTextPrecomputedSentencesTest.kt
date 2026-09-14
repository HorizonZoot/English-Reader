package io.github.zoot.englishreader.ui.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.SentenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * InteractiveText 的 precomputedSentences 契约（9.6）
 *
 * 断言的是**语义层**（TalkBack 句子动作逐句生成），不是文本布局几何——后者依赖真实
 * 文本测量，仍留在 `InteractiveTextAndroidTest`。
 *
 * 用「不可能由分句器自然产出的句子形状」来证明复用：给两句文本只传一个覆盖全文的区间，
 * 若组件仍自行分句就会产出 2 个动作而不是 1 个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractiveTextPrecomputedSentencesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun precomputedSentences_replaceInternalSplit() {
        val text = "Alpha one. Beta two."
        // 覆盖全文的单个区间：任何分句器都会把这段切成 2 句，因此动作数能区分两条路径。
        val precomputed = listOf(SentenceRange(0, text, 0, text.length))
        var clickedIndex: Int? = null
        var clickedRange: SentenceRange? = null

        composeRule.setContent {
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                precomputedSentences = precomputed,
                onSentenceClick = { index, range ->
                    clickedIndex = index
                    clickedRange = range
                },
                onWordLongPress = {}
            )
        }

        assertEquals(listOf("Highlight sentence 1"), sentenceActionLabels())

        performCustomAction("Highlight sentence 1")

        composeRule.runOnIdle {
            assertEquals(0, clickedIndex)
            // 回调拿到的就是传入的那个区间实例，未被重新分句替换。
            assertSame(precomputed[0], clickedRange)
        }
    }

    @Test
    fun precomputedSentences_driveActionCountAndOrder() {
        // 验证动作逐句、按序生成，并用引用相等证明回调拿到的是传入的那个区间。
        // （分句器对这段文本也会得出 3 句，故动作数本身不区分两条路径——靠 assertSame 区分。）
        val text = "Alpha. Beta. Gamma."
        val precomputed = listOf(
            SentenceRange(0, "Alpha.", 0, 6),
            SentenceRange(1, " Beta.", 6, 12),
            SentenceRange(2, " Gamma.", 12, 19)
        )
        val clicked = mutableListOf<Int>()
        var clickedRange: SentenceRange? = null

        composeRule.setContent {
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                precomputedSentences = precomputed,
                onSentenceClick = { index, range ->
                    clicked.add(index)
                    clickedRange = range
                },
                onWordLongPress = {}
            )
        }

        assertEquals(
            listOf("Highlight sentence 1", "Highlight sentence 2", "Highlight sentence 3"),
            sentenceActionLabels()
        )

        performCustomAction("Highlight sentence 3")
        composeRule.runOnIdle {
            assertEquals(listOf(2), clicked)
            assertSame(precomputed[2], clickedRange)
        }
    }

    @Test
    fun precomputedSentences_appliesSentenceIndexOffset() {
        // 分段渲染时全局身份 = 段落局部 index + sentenceOffset。复用列表不能绕过这个换算。
        val text = "Alpha one. Beta two."
        val precomputed = listOf(SentenceRange(0, text, 0, text.length))
        var clickedIndex: Int? = null

        composeRule.setContent {
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                sentenceIndexOffset = 10,
                precomputedSentences = precomputed,
                onSentenceClick = { index, _ -> clickedIndex = index },
                onWordLongPress = {}
            )
        }

        assertEquals(listOf("Highlight sentence 11"), sentenceActionLabels())

        performCustomAction("Highlight sentence 11")
        composeRule.runOnIdle { assertEquals(10, clickedIndex) }
    }

    @Test
    fun omittedPrecomputedSentences_fallsBackToInternalSplit() {
        // 不传时组件自行分句，独立复用者（含现有测试）行为不变。
        var clickedRange: SentenceRange? = null

        composeRule.setContent {
            InteractiveText(
                text = "Single sentence without internal breaks",
                fontSize = 16.sp,
                onSentenceClick = { _, range -> clickedRange = range },
                onWordLongPress = {}
            )
        }

        assertEquals(listOf("Highlight sentence 1"), sentenceActionLabels())

        performCustomAction("Highlight sentence 1")
        composeRule.runOnIdle {
            assertEquals("Single sentence without internal breaks", clickedRange?.text)
        }
    }

    @Test
    fun sameText_withoutPrecomputed_splitsIntoTwoSentences() {
        // 对照组：钉住 precomputedSentences_replaceInternalSplit 的对比基线。
        // 若未来分句行为变化使这段文本也只得 1 句，那条测试就不再能证明复用，
        // 本用例会先失败并指出原因，而不是让前者退化成永远通过的空断言。
        val text = "Alpha one. Beta two."

        composeRule.setContent {
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                onSentenceClick = { _, _ -> },
                onWordLongPress = {}
            )
        }

        assertEquals(
            listOf("Highlight sentence 1", "Highlight sentence 2"),
            sentenceActionLabels()
        )
    }

    private fun sentenceActionLabels(): List<String> =
        composeRule.onNodeWithContentDescription("Interactive reading text")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .map { it.label }
            .filter { it.startsWith("Highlight sentence") }

    @Test
    fun precomputedSentences_withWhitespaceGaps_preserveOriginalDisplayOffsets() {
        val original = "Alpha.\u2028\u2028Beta."
        val secondStart = original.indexOf("Beta")
        val sentences = listOf(
            SentenceRange(0, "Alpha.", 0, 6),
            SentenceRange(1, "Beta.", secondStart, original.length)
        )
        composeRule.setContent {
            InteractiveText(
                text = original,
                fontSize = 16.sp,
                precomputedSentences = sentences,
                onSentenceClick = { _, _ -> },
                onWordLongPress = {}
            )
        }
        val node = composeRule.onNodeWithContentDescription("Interactive reading text").fetchSemanticsNode()
        assertEquals(original, node.config[SemanticsProperties.Text].single().text)
    }

    @Test
    fun pageFragment_exposesVisibleTextButSentenceActionRetainsCompleteOriginal() {
        val original = "Alpha one and beta two form one sentence."
        val sentence = SentenceRange(0, original, 0, original.length)
        val start = original.indexOf("beta")
        val end = original.indexOf(" form")
        var selected: SentenceRange? = null
        composeRule.setContent {
            InteractiveText(
                text = original,
                fontSize = 16.sp,
                precomputedSentences = listOf(sentence),
                visibleViewport = ReadingTextViewport(start, end, 0, 100),
                onSentenceClick = { _, range -> selected = range },
                onWordLongPress = {}
            )
        }
        val node = composeRule.onNodeWithContentDescription("Interactive reading text").fetchSemanticsNode()
        assertEquals("beta two", node.config[SemanticsProperties.Text].single().text)
        performCustomAction("Highlight sentence 1")
        composeRule.runOnIdle { assertSame(sentence, selected) }
    }

    private fun performCustomAction(label: String) {
        val action = composeRule.onNodeWithContentDescription("Interactive reading text")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            ?.firstOrNull { it.label == label }
        assertNotNull("custom action not found: $label", action)
        composeRule.runOnUiThread { action!!.action() }
    }
}
