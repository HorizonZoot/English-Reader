package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.ExcerptRange
import io.github.zoot.englishreader.ui.textLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExcerptGestureAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun longPress_thenDrag_finishesExcerptWithoutReleasingWordCard() {
        val finished = mutableListOf<Boolean>()
        val anchors = mutableListOf<Int>()
        var wordReleases = 0
        composeRule.setContent {
            InteractiveText(
                text = "Alpha beta gamma.", fontSize = 18.sp, modifier = Modifier.width(300.dp),
                onSentenceClick = { _, _ -> }, onWordLongPress = {},
                onWordPressRelease = { wordReleases++ },
                onExcerptDrag = { anchor, _, done -> anchors += anchor; finished += done }
            )
        }
        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        val layout = node.textLayoutResult()
        node.performTouchInput {
            down(layout.getBoundingBox(1).center)
            advanceEventTime(700)
            moveTo(layout.getBoundingBox(12).center)
            up()
        }
        composeRule.runOnIdle {
            assertTrue(finished.isNotEmpty())
            assertEquals(true, finished.last())
            assertTrue(anchors.all { it == 1 })
            assertEquals(0, wordReleases)
        }
    }

    @Test
    fun longPressDrag_secondPointer_clearsPendingExcerptWithoutCompleting() {
        val finished = mutableListOf<Boolean>()
        var clears = 0
        composeRule.setContent {
            InteractiveText(
                text = "Alpha beta gamma.", fontSize = 18.sp, modifier = Modifier.width(300.dp),
                onSentenceClick = { _, _ -> }, onWordLongPress = {},
                onClearSelection = { clears++ },
                onExcerptDrag = { _, _, done -> finished += done }
            )
        }
        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        val layout = node.textLayoutResult()
        node.performTouchInput {
            down(0, layout.getBoundingBox(1).center)
            advanceEventTime(700)
            moveTo(0, layout.getBoundingBox(12).center)
            down(1, layout.getBoundingBox(15).center)
            up(1)
            up(0)
        }
        composeRule.runOnIdle {
            assertTrue(finished.isNotEmpty())
            assertFalse(finished.any { it })
            assertTrue(clears >= 2)
        }
    }

    @Test
    fun visibleExcerptRange_scrollClipAndPagedClip_keepOnlyVisibleOriginalOffsets() {
        var coordinates: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
        composeRule.setContent {
            InteractiveText(
                text = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu.",
                fontSize = 18.sp, modifier = Modifier.width(100.dp),
                onSentenceClick = { _, _ -> }, onWordLongPress = {},
                onTextCoordinates = { coordinates = it }, onTextLayout = { layout = it }
            )
        }
        composeRule.runOnIdle {
            val text = requireNotNull(layout)
            val coords = requireNotNull(coordinates)
            assertTrue(text.lineCount > 3)
            val origin = coords.localToWindow(Offset.Zero)
            val clip = Rect(origin.x, origin.y + text.getLineTop(1) + 0.1f,
                origin.x + text.size.width, origin.y + text.getLineBottom(2) - 0.1f)
            val geometry = ExcerptTextGeometry(3, 100, text, coords, null)
            val expected = ExcerptRange(100 + text.getLineStart(1), 100 + text.getLineEnd(2))
            assertEquals(expected, geometry.visibleExcerptRange(clip))
            assertNull(excerptFocusOffset(4, clip.center, clip, listOf(geometry)))
            val clamped = requireNotNull(excerptFocusOffset(3, clip.bottomRight + Offset(0f, 1000f), clip, listOf(geometry)))
            assertTrue(clamped in expected.startOffset until expected.endOffset)
            val page = geometry.copy(viewport = ReadingTextViewport(
                text.getLineStart(2), text.getLineEnd(2), text.getLineTop(2).toInt(),
                (text.getLineBottom(2) - text.getLineTop(2)).toInt()
            ))
            assertEquals(ExcerptRange(100 + text.getLineStart(2), 100 + text.getLineEnd(2)),
                page.visibleExcerptRange(clip))
            val pageFocus = requireNotNull(excerptFocusOffset(3, clip.topLeft, clip, listOf(page)))
            assertTrue(pageFocus >= 100 + text.getLineStart(2))
        }
    }
}
