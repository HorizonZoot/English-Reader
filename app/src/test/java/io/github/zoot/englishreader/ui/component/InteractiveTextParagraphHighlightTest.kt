package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.SentenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 整段高亮（生词本「查看原文」点亮所在段落）的**文本层**契约。
 *
 * 高亮强度由 `animateFloatAsState` 驱动，渐隐期间每帧都是一个新值。这里锁定的核心性质是
 * **强度不参与文本内容**：底纹画在绘制阶段，所以强度变化只重绘，不重建 AnnotatedString、
 * 不触发重新布局。写成 `SpanStyle(background = ...)` 时每帧都要重排整段文本，而这是
 * 阅读页的热路径。
 *
 * 这里**不**验证多行几何，也**不**验证底纹真的被画出来：Robolectric 的字形宽度是桩数据
 * （214 个字符在 200px 宽里报 `lineCount == 1`），真实换行与像素结果只有真机文本测量才
 * 有意义。那两项在 `InteractiveTextParagraphHighlightAndroidTest` 里。
 * 本文件里的几何断言因此只覆盖单行布局下仍成立的性质（例如底纹止于最后一个字形、
 * 而不是拖到容器右边缘）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractiveTextParagraphHighlightTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val text = "Small details reward noticing. A second sentence follows it."
    private val sentences = listOf(
        SentenceRange(0, "Small details reward noticing.", 0, 30),
        SentenceRange(1, " A second sentence follows it.", 30, text.length)
    )

    private fun render(
        highlight: Float = 1f,
        highlightedSentenceIndex: Int? = null,
        onLayout: (TextLayoutResult) -> Unit = {}
    ) {
        composeRule.setContent {
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                modifier = Modifier.width(200.dp),
                precomputedSentences = sentences,
                highlightedSentenceIndex = highlightedSentenceIndex,
                paragraphHighlight = highlight,
                onSentenceClick = { _, _ -> },
                onWordLongPress = {},
                onTextLayout = onLayout
            )
        }
        composeRule.waitForIdle()
    }

    private fun layout(highlightedSentenceIndex: Int? = null): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        render(highlightedSentenceIndex = highlightedSentenceIndex) { layouts += it }
        return layouts.last()
    }

    @Test
    fun paragraphHighlightStrength_doesNotChangeTheRenderedText() {
        val highlight = mutableFloatStateOf(0f)
        val layouts = mutableListOf<TextLayoutResult>()
        val appliedHighlights = mutableListOf<Float>()
        composeRule.setContent {
            val currentHighlight = highlight.floatValue
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                modifier = Modifier.width(200.dp),
                precomputedSentences = sentences,
                paragraphHighlight = currentHighlight,
                onSentenceClick = { _, _ -> },
                onWordLongPress = {},
                onTextLayout = { layouts += it }
            )
            SideEffect { appliedHighlights += currentHighlight }
        }
        composeRule.waitForIdle()

        val before = layouts.last().layoutInput.text
        assertEquals(0f, appliedHighlights.last(), 0f)

        // 走几帧不同的强度，模拟渐隐过程。
        listOf(1f, 0.6f, 0.25f, 0f).forEach { value ->
            composeRule.runOnIdle { highlight.floatValue = value }
            composeRule.waitForIdle()
            assertEquals("highlight=$value must reach composition", value, appliedHighlights.last(), 0f)

            // 同一个实例：强度变化连 AnnotatedString 的重建都不该触发，更不必重新布局。
            assertSame(
                "paragraph highlight must not rebuild the AnnotatedString at $value",
                before,
                layouts.last().layoutInput.text
            )
        }
    }

    @Test
    fun paragraphHighlight_addsNoFullRangeBackgroundSpan() {
        // 整段底纹若还写成 SpanStyle，就会出现一个覆盖全文的 background span。
        val fullRange = layout().layoutInput.text.spanStyles
            .filter { it.start == 0 && it.end == text.length }
        assertTrue("full-range background span found: $fullRange", fullRange.isEmpty())
    }

    @Test
    fun highlightedSentence_stillCarriesItsBackgroundSpan() {
        // 句子选中仍走 SpanStyle（它不参与动画），不能被上面的改动一起搬走。
        val spans = layout(highlightedSentenceIndex = 0).layoutInput.text.spanStyles
        assertEquals(1, spans.size)
        assertEquals(0, spans.single().start)
        assertEquals(30, spans.single().end)
    }

    @Test
    fun textRangeBackgroundRects_spanEachLaidOutLineWithItsFullHeight() {
        // 本层能观察到的行数受限于桩字形：这个 fixture 在 Robolectric 下报
        // `lineCount == 1`，所以「多行各自成一个矩形」在这里只被验到 1 行，
        // **真实多行**由 InteractiveTextParagraphHighlightAndroidTest 的
        // paragraphBackground_coversEveryWrappedLine 断言。
        //
        // 这条在 JVM 侧仍然有效的是：矩形数 == 行数、矩形贴合行高、宽度为正。
        val layout = layout()
        val rects = textRangeBackgroundRects(layout, 0, text.length)

        // 空列表就是「高亮根本画不出来」。
        assertEquals(layout.lineCount, rects.size)
        rects.forEachIndexed { line, rect ->
            assertEquals(
                "rect $line must span its full line height",
                layout.getLineTop(line),
                rect.top,
                0.5f
            )
            assertEquals(layout.getLineBottom(line), rect.bottom, 0.5f)
            assertTrue("rect $line must have positive width", rect.width > 0f)
        }
    }

    @Test
    fun textRangeBackgroundRects_stopAtTheLastGlyphInsteadOfTheContainerEdge() {
        // 贴字形的底纹不能拖到容器右边缘：行尾空白不该被涂上颜色。
        // 这条与「单行还是多行」无关，故在桩字形下依然成立。
        val layout = layout()
        val rects = textRangeBackgroundRects(layout, 0, text.length)

        rects.forEachIndexed { line, rect ->
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            val lastGlyph = (lineEnd - 1 downTo layout.getLineStart(line))
                .firstOrNull { !layout.layoutInput.text[it].isWhitespace() }
            if (lastGlyph != null) {
                assertEquals(
                    "rect $line must end at its last non-whitespace glyph",
                    layout.getBoundingBox(lastGlyph).right,
                    rect.right,
                    0.5f
                )
            }
        }
    }

    @Test
    fun textRangeBackgroundRects_emptyOrInvertedRange_yieldsNothing() {
        val layout = layout()

        assertTrue(textRangeBackgroundRects(layout, 10, 10).isEmpty())
        assertTrue(textRangeBackgroundRects(layout, 20, 5).isEmpty())
        assertTrue(textRangeBackgroundRects(layout, 0, 0).isEmpty())
    }

    @Test
    fun textRangeBackgroundRects_partialRange_fillsOnlyThatRange() {
        val layout = layout()

        val rects = textRangeBackgroundRects(layout, 0, 5)
        assertEquals(1, rects.size)
        assertEquals(layout.getLineTop(0), rects.single().top, 0.5f)
        // 右边界是第 5 个字符的右缘，而不是整行末尾。
        assertEquals(layout.getBoundingBox(4).right, rects.single().right, 0.5f)
    }
}
