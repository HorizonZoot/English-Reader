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
    fun fromDrag_invalidOffset_rejectsInsteadOfExpandingToAnotherParagraph() {
        assertNull(ExcerptRange.fromDrag("", 0, 0))
        assertNull(ExcerptRange.fromDrag("Alpha", -1, 2))
        assertNull(ExcerptRange.fromDrag("Alpha", 1, 5))
    }
}
