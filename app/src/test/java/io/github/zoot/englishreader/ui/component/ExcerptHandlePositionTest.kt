package io.github.zoot.englishreader.ui.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptHandlePositionTest {
    @Test
    fun centers_shortSelectionNearEitherEdge_keepBothTargetsInsideAndSeparate() {
        val bounds = Rect(0f, 0f, 320f, 480f)
        for (x in listOf(0f, 300f)) {
            val glyph = Rect(x, 460f, x + 12f, 480f)
            val (start, end) = excerptHandleCenters(ExcerptSelectionGeometry(glyph, glyph, glyph), bounds, 24f, 8f)
            for (center in listOf(start, end)) {
                assertTrue(center.x >= 24f && center.x <= 296f)
                assertTrue(center.y >= 24f && center.y <= 456f)
            }
            assertTrue(end.x - start.x >= 48f)
        }
    }

    @Test
    fun centers_wrappedSelection_doNotOverlapWhenEndIsLeftOfStart() {
        val startGlyph = Rect(180f, 30f, 190f, 55f)
        val endGlyph = Rect(20f, 60f, 30f, 85f)
        val (start, end) = excerptHandleCenters(
            ExcerptSelectionGeometry(startGlyph, endGlyph, Rect(0f, 30f, 300f, 85f)),
            Rect(0f, 0f, 320f, 480f), 24f, 8f
        )
        assertEquals(156f, start.x, 0f)
        assertEquals(54f, end.x, 0f)
        assertTrue(kotlin.math.abs(end.x - start.x) >= 48f || kotlin.math.abs(end.y - start.y) >= 48f)
    }

    @Test
    fun popup_selectionAtViewportTop_placesBelowHandleTouchTargets() {
        val bounds = Rect(0f, 0f, 320f, 480f)
        val glyph = Rect(90f, 0f, 150f, 24f)
        val geometry = ExcerptSelectionGeometry(glyph, glyph, glyph)
        val centers = excerptHandleCenters(geometry, bounds, 24f, 8f)
        val anchor = excerptControlsBounds(geometry, centers, 24f)
        val position = SentencePopupPositionProvider(anchor, preferAbove = true, readingViewportBounds = bounds)
            .calculatePosition(IntRect(0, 0, 320, 480), IntSize(320, 480), LayoutDirection.Ltr, IntSize(160, 48))
        assertTrue(position.y >= maxOf(centers.first.y, centers.second.y) + 24f)
    }
}
