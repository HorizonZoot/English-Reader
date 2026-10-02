package io.github.zoot.englishreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExcerptRangeTest {
    @Test
    fun fromDrag_forwardAndReverse_keepExactWhitespaceAndOffsets() {
        val text = "Alpha  beta, gamma."
        val expected = ExcerptRange(7, 18)
        assertEquals(expected, ExcerptRange.fromDrag(text, 7, 17))
        assertEquals(expected, ExcerptRange.fromDrag(text, 17, 7))
        assertEquals("beta, gamma", text.substring(expected.startOffset, expected.endOffset))
    }

    @Test
    fun fromDrag_surrogatePair_neverCutsCodePoint() {
        val text = "A😀B"
        assertEquals(ExcerptRange(1, 3), ExcerptRange.fromDrag(text, 1, 1))
        assertEquals(ExcerptRange(1, 3), ExcerptRange.fromDrag(text, 2, 2))
        assertEquals(ExcerptRange(1, 4), ExcerptRange.fromDrag(text, 3, 2))
    }

    @Test
    fun adjustHandle_clampsToVisibleRangeAndNeverCrossesOtherHandle() {
        val text = "0123456789"
        val visible = ExcerptRange(2, 8)
        val selection = ExcerptRange(3, 7)
        assertEquals(ExcerptRange(2, 7), selection.adjustHandle(text, -99, true, visible))
        assertEquals(ExcerptRange(6, 7), selection.adjustHandle(text, 99, true, visible))
        assertEquals(ExcerptRange(3, 4), selection.adjustHandle(text, -99, false, visible))
        assertEquals(ExcerptRange(3, 8), selection.adjustHandle(text, 99, false, visible))
    }

    @Test
    fun adjustHandle_surrogatePair_keepsBothCodeUnits() {
        val text = "A😀BC"
        val visible = ExcerptRange(0, text.length)
        assertEquals(ExcerptRange(1, 5), visible.adjustHandle(text, 2, true, visible))
        assertEquals(ExcerptRange(0, 3), visible.adjustHandle(text, 2, false, visible))
        val emoji = ExcerptRange(1, 3)
        assertEquals(emoji, emoji.adjustHandle(text, 3, true, visible))
        assertEquals(emoji, emoji.adjustHandle(text, 1, false, visible))
    }

    @Test
    fun fromDrag_invalidOffset_rejectsInsteadOfExpandingToAnotherParagraph() {
        assertNull(ExcerptRange.fromDrag("", 0, 0))
        assertNull(ExcerptRange.fromDrag("Alpha", -1, 2))
        assertNull(ExcerptRange.fromDrag("Alpha", 1, 5))
    }
}
