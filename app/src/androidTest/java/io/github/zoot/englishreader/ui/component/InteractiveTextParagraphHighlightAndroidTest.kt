package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.zoot.englishreader.ui.textLayoutResult
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 整段高亮的**真实渲染**契约：多行几何与像素结果。
 *
 * 这两件事在 JVM 侧测不到——Robolectric 的字形宽度是桩数据，同一个长段落在窄宽度下也报
 * `lineCount == 1`，而且没有像素。文本层契约（强度不重建 AnnotatedString 等）见
 * `InteractiveTextParagraphHighlightTest`。
 *
 * 为什么要验像素：本次把底纹从 `SpanStyle(background = ...)` 挪到了绘制阶段，几何算错或
 * 提前 return 都会让高亮**静默消失**，而文本层断言察觉不到。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class InteractiveTextParagraphHighlightAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val description = "Interactive reading text"

    private val paragraph =
        "Small details reward noticing, and noticing is the whole of the practice. " +
            "A second sentence follows it, then a third one arrives to push the text " +
            "well past the width of the viewport and onto several laid-out lines."

    /**
     * 渲染一次，并通过 [highlight] 在同一个 composition 内改强度。
     *
     * `setContent` 每个测试只能调用一次，所以不能靠「渲染两次对比」——必须让强度可变。
     */
    private fun render(highlight: MutableFloatState) {
        composeRule.setContent {
            ArticleUiTheme {
                InteractiveText(
                    text = paragraph,
                    fontSize = 16.sp,
                    modifier = Modifier.width(220.dp),
                    paragraphHighlight = highlight.floatValue,
                    onSentenceClick = { _, _ -> },
                    onWordLongPress = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun node() = composeRule.onNodeWithContentDescription(description)

    private fun pixels(): PixelMap = node().captureToImage().toPixelMap()

    private fun PixelMap.differsAt(other: PixelMap, x: Int, y: Int) = this[x, y] != other[x, y]

    private fun Rect.midY() = (top + (bottom - top) / 2f).toInt()

    /** 取样行：矩形中线，夹到两张图的公共范围内。 */
    private fun sampleY(rect: Rect, off: PixelMap, on: PixelMap) =
        rect.midY().coerceIn(0, minOf(off.height, on.height) - 1)

    @Test
    fun paragraphBackground_coversEveryWrappedLine() {
        render(mutableFloatStateOf(1f))
        val layout = node().textLayoutResult()

        // 前提自证：单行布局下「每行一个矩形」会退化成空洞的断言。
        assertTrue(
            "fixture must wrap onto several lines, got ${layout.lineCount}",
            layout.lineCount > 1
        )

        val rects = textRangeBackgroundRects(layout, 0, paragraph.length)
        assertEquals(layout.lineCount, rects.size)
        rects.forEachIndexed { line, rect ->
            assertEquals("rect $line top", layout.getLineTop(line), rect.top, 1f)
            assertEquals("rect $line bottom", layout.getLineBottom(line), rect.bottom, 1f)
            assertTrue("rect $line must have positive width", rect.width > 0f)
        }
    }

    @Test
    fun paragraphBackground_isActuallyPaintedInsideTheTextBounds() {
        val highlight = mutableFloatStateOf(0f)
        render(highlight)
        val layout = node().textLayoutResult()
        val off = pixels()

        composeRule.runOnIdle { highlight.floatValue = 1f }
        composeRule.waitForIdle()
        val on = pixels()

        val rect = textRangeBackgroundRects(layout, 0, paragraph.length).first()
        val y = sampleY(rect, off, on)
        val xs = (rect.left.toInt() + 2 until rect.right.toInt() - 2)
            .filter { it in 0 until minOf(off.width, on.width) }

        val changed = xs.count { x -> on.differsAt(off, x, y) }
        assertTrue("paragraph fill must paint pixels inside the text bounds", changed > 0)
    }

    @Test
    fun paragraphBackground_stopsAtTheLastGlyphOfTheLine() {
        val highlight = mutableFloatStateOf(0f)
        render(highlight)
        val layout = node().textLayoutResult()
        val off = pixels()

        composeRule.runOnIdle { highlight.floatValue = 1f }
        composeRule.waitForIdle()
        val on = pixels()

        // 底纹是贴字形的：矩形右缘之外（同一行）不该被涂色，
        // 否则行尾会拖出一条到容器右边缘的色块。
        val rect = textRangeBackgroundRects(layout, 0, paragraph.length).first()
        val y = sampleY(rect, off, on)
        val rightOfText = (rect.right.toInt() + 2 until minOf(off.width, on.width)).take(32)

        val painted = rightOfText.count { x -> on.differsAt(off, x, y) }
        assertEquals("fill must stop at the last glyph of the line", 0, painted)
    }

    @Test
    fun paragraphBackground_keepsTheTextReadable() {
        render(mutableFloatStateOf(1f))
        val layout = node().textLayoutResult()
        val on = pixels()

        // 底纹若盖在字上，矩形内就只剩一种颜色。字形墨色 + 底纹至少是两种。
        val rect = textRangeBackgroundRects(layout, 0, paragraph.length).first()
        val y = rect.midY().coerceIn(0, on.height - 1)
        val xs = (rect.left.toInt() until rect.right.toInt()).filter { it in 0 until on.width }
        val distinctColors = xs.map { x -> on[x, y] }.toSet()

        assertTrue(
            "text ink must still be visible over the paragraph fill",
            distinctColors.size > 1
        )
    }
}
