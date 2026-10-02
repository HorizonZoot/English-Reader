package io.github.zoot.englishreader.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptHandlePositionTest {
    @Test
    fun handleGeometry_wrappedSelection_waterdropsTouchExactGlyphCorners() {
        val bounds = Rect(0f, 0f, 320f, 480f)
        val start = excerptHandleGeometry(Rect(180f, 30f, 190f, 55f), true, bounds, 24f, 7f)
        val end = excerptHandleGeometry(Rect(20f, 60f, 30f, 85f), false, bounds, 24f, 7f)
        assertEquals(Rect(166f, 55f, 180f, 69f), start.knob)
        assertEquals(Offset(180f, 55f), start.tip)
        assertEquals(Rect(30f, 85f, 44f, 99f), end.knob)
        assertEquals(Offset(30f, 85f), end.tip)
        for (handle in listOf(start, end)) {
            assertEquals(48f, handle.touchBounds.width, 0f)
            assertEquals(48f, handle.touchBounds.height, 0f)
        }
    }

    @Test
    fun handleGeometry_largerTouchTarget_doesNotMoveOrEnlargeVisibleHandle() {
        val bounds = Rect(0f, 0f, 320f, 480f)
        val glyph = Rect(90f, 80f, 110f, 104f)
        for (start in listOf(true, false)) {
            val regular = excerptHandleGeometry(glyph, start, bounds, 24f, 7f)
            val larger = excerptHandleGeometry(glyph, start, bounds, 32f, 7f)
            assertEquals(regular.tip, larger.tip)
            assertEquals(regular.knob, larger.knob)
            assertEquals(64f, larger.touchBounds.width, 0f)
        }
    }

    @Test
    fun handleGeometry_viewportCorners_keepsSmallWaterdropAttachedAndTargetInside() {
        val bounds = Rect(30f, 60f, 350f, 540f)
        for (x in listOf(30f, 338f)) {
            for (y in listOf(60f, 516f)) {
                val glyph = Rect(x, y, x + 12f, y + 24f)
                for (start in listOf(true, false)) {
                    val handle = excerptHandleGeometry(glyph, start, bounds, 24f, 7f)
                    assertEquals(14f, handle.knob.width, 0f)
                    assertEquals(14f, handle.knob.height, 0f)
                    assertEquals(if (start) glyph.left else glyph.right, handle.tip.x, 0f)
                    assertEquals(if (y == 60f) glyph.bottom else glyph.top, handle.tip.y, 0f)
                    assertTrue(handle.tip.x == handle.knob.left || handle.tip.x == handle.knob.right)
                    assertTrue(handle.tip.y == handle.knob.top || handle.tip.y == handle.knob.bottom)
                    assertEquals(handle.knob, handle.knob.intersect(handle.touchBounds))
                    assertEquals(handle.touchBounds, handle.touchBounds.intersect(bounds))
                }
            }
        }
    }

    @Test
    fun handleAt_shortSelectionWithOverlappingTargets_bothVisibleKnobsRemainDraggable() {
        val bounds = Rect(0f, 0f, 320f, 480f)
        for (x in listOf(0f, 140f, 308f)) {
            val glyph = Rect(x, 200f, x + 12f, 224f)
            val start = excerptHandleGeometry(glyph, true, bounds, 24f, 7f)
            val end = excerptHandleGeometry(glyph, false, bounds, 24f, 7f)
            assertTrue(start.touchBounds.overlaps(end.touchBounds))
            for ((front, other) in listOf(start to end, end to start)) {
                assertEquals(start, excerptHandleAt(start.knob.center, front, other))
                assertEquals(end, excerptHandleAt(end.knob.center, front, other))
            }
        }
    }

    @Test
    fun popup_selectionAtViewportTop_placesBelowHandleTouchTargets() {
        val bounds = Rect(0f, 0f, 320f, 480f)
        val glyph = Rect(90f, 0f, 150f, 24f)
        val geometry = ExcerptSelectionGeometry(glyph, glyph, glyph)
        val start = excerptHandleGeometry(glyph, true, bounds, 24f, 7f)
        val end = excerptHandleGeometry(glyph, false, bounds, 24f, 7f)
        val anchor = excerptControlsBounds(geometry, start, end)
        val position = SentencePopupPositionProvider(anchor, preferAbove = true, readingViewportBounds = bounds)
            .calculatePosition(IntRect(0, 0, 320, 480), IntSize(320, 480), LayoutDirection.Ltr, IntSize(160, 48))
        assertTrue(position.y >= maxOf(start.touchBounds.bottom, end.touchBounds.bottom))
    }
}
