package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.ExcerptRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExcerptHandleAndroidTest {
    @get:Rule val composeRule = createComposeRule()
    private val text = "Alpha beta gamma delta epsilon."
    private var selected = ExcerptRange(6, 16)
    private var saved: ExcerptRange? = null
    private lateinit var layout: TextLayoutResult
    private lateinit var textCoordinates: LayoutCoordinates
    private lateinit var readingBounds: Rect
    private var density = 1f

    private fun show() {
        composeRule.setContent {
            var selection by remember { mutableStateOf<ExcerptRange?>(selected) }
            var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var bounds by remember { mutableStateOf<Rect?>(null) }
            density = LocalDensity.current.density
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("excerpt-reading-area").onGloballyPositioned {
                    bounds = it.boundsInWindow()
                    readingBounds = it.boundsInWindow()
                }) {
                    InteractiveText(
                        text = text, fontSize = 18.sp, modifier = Modifier.width(280.dp),
                        onSentenceClick = { _, _ -> }, onWordLongPress = {}, excerptRange = selection,
                        onTextLayout = { textLayout = it; layout = it },
                        onTextCoordinates = { coordinates = it; textCoordinates = it }
                    )
                    val current = selection
                    val readingBounds = bounds
                    val geometry = if (textLayout != null && coordinates != null) {
                        ExcerptTextGeometry(0, 0, textLayout!!, coordinates!!, null)
                    } else null
                    if (current != null && readingBounds != null && geometry != null) {
                        excerptSelectionGeometry(0, current, readingBounds, listOf(geometry))?.let { handles ->
                            ExcerptSelectionOverlay(
                                geometry = handles, readingBounds = readingBounds,
                                safeInsets = SentencePopupSafeInsets(),
                                onMove = { isStart, point ->
                                    val offset = excerptFocusOffset(0, point, readingBounds, listOf(geometry), caret = true)
                                    if (offset != null) {
                                        selection = current.adjustHandle(text, offset, isStart, ExcerptRange(0, text.length))
                                        selected = requireNotNull(selection)
                                    }
                                },
                                onNudge = { _, _ -> false }, onCancel = { selection = null },
                                onSave = { saved = selection; selection = null }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun visibleKnob(start: Boolean): Offset {
        val offset = if (start) selected.startOffset else selected.endOffset - 1
        val glyph = layout.getBoundingBox(offset).translate(textCoordinates.localToWindow(Offset.Zero))
        return excerptHandleGeometry(glyph, start, readingBounds, 24f * density, 7f * density)
            .knob.center - readingBounds.topLeft
    }

    @Test
    fun startHandle_overlappingTargets_dragVisibleKnobAdjustsStartNotEnd() {
        selected = ExcerptRange(0, 2)
        show()
        composeRule.waitForIdle()
        val start = composeRule.onNodeWithTag("excerpt-start-handle").fetchSemanticsNode().boundsInRoot
        val end = composeRule.onNodeWithTag("excerpt-end-handle").fetchSemanticsNode().boundsInRoot
        assertTrue(start.overlaps(end))
        assertEquals(48f * density, start.width, 1f)
        val knob = visibleKnob(true)
        val delta = layout.getBoundingBox(1).topLeft - layout.getBoundingBox(0).topLeft
        composeRule.onNodeWithTag("excerpt-reading-area").performTouchInput {
            down(knob)
            moveBy(delta)
            up()
        }
        composeRule.runOnIdle { assertEquals(ExcerptRange(1, 2), selected) }
    }

    @Test
    fun endHandle_overlappingTargets_dragVisibleKnobAdjustsEndNotStart() {
        selected = ExcerptRange(0, 1)
        show()
        composeRule.waitForIdle()
        val knob = visibleKnob(false)
        val delta = layout.getBoundingBox(2).bottomRight - layout.getBoundingBox(0).bottomRight
        composeRule.onNodeWithTag("excerpt-reading-area").performTouchInput {
            down(knob)
            moveBy(delta)
            up()
        }
        composeRule.runOnIdle { assertEquals(ExcerptRange(0, 3), selected) }
    }

    @Test
    fun startHandle_multipleMovesAcrossRecomposition_preservesEndAndSavesAdjustedRange() {
        show()
        val handle = composeRule.onNodeWithTag("excerpt-start-handle")
        val initial = handle.fetchSemanticsNode().boundsInRoot.center
        val firstDelta = layout.getBoundingBox(3).topLeft - layout.getBoundingBox(6).topLeft
        val secondDelta = layout.getBoundingBox(1).topLeft - layout.getBoundingBox(6).topLeft
        handle.performTouchInput { down(center); moveBy(firstDelta) }
        composeRule.runOnIdle {
            assertEquals(3, selected.startOffset)
            assertEquals(16, selected.endOffset)
        }
        composeRule.onNodeWithTag("excerpt-actions").assertDoesNotExist()
        val movedBounds = handle.fetchSemanticsNode().boundsInRoot
        handle.performTouchInput {
            moveTo(initial + secondDelta - movedBounds.topLeft)
            up()
        }
        composeRule.runOnIdle { assertEquals(ExcerptRange(1, 16), selected) }
        composeRule.onNodeWithTag("excerpt-actions").assertExists()
        composeRule.onNodeWithTag("excerpt-save").performClick()
        composeRule.runOnIdle { assertEquals(ExcerptRange(1, 16), saved) }
    }

    @Test
    fun endHandle_dragBeyondViewport_clampsAndMultiTouchCancelsSelection() {
        show()
        val handle = composeRule.onNodeWithTag("excerpt-end-handle")
        handle.performTouchInput { down(center); moveBy(Offset(2_000f, 2_000f)); up() }
        composeRule.runOnIdle {
            assertEquals(6, selected.startOffset)
            assertEquals(text.length, selected.endOffset)
        }
        handle.performTouchInput {
            down(0, center)
            moveBy(Offset(-10f, 0f))
            down(1, center + Offset(1f, 1f))
            up(1)
            up(0)
        }
        composeRule.onNodeWithTag("excerpt-start-handle").assertDoesNotExist()
        composeRule.onNodeWithTag("excerpt-actions").assertDoesNotExist()
        assertTrue(saved == null)
    }
}
