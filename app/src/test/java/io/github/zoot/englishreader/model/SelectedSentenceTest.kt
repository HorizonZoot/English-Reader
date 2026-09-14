package io.github.zoot.englishreader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedSentenceTest {
    @Test
    fun constructor_validSnapshot_preservesAllFields() {
        val snapshot = SelectedSentence(4, 2, " Hello. ", "Hello.", 3, 11)
        assertEquals(4, snapshot.articleId)
        assertEquals(2, snapshot.sentenceIndex)
        assertEquals(" Hello. ", snapshot.rawText)
        assertEquals("Hello.", snapshot.normalizedText)
        assertEquals(3, snapshot.startOffset)
        assertEquals(11, snapshot.endOffset)
    }

    @Test
    fun constructor_invalidIdentityRangeOrText_rejects() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectedSentence(-1, 0, "A.", "A.", 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectedSentence(1, -1, "A.", "A.", 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectedSentence(1, 0, "A.", "A.", 2, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectedSentence(1, 0, "A", "A", 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectedSentence(1, 0, "  ", "", 0, 2)
        }
    }

    @Test
    fun toString_redactsUserDerivedText() {
        val rendered = SelectedSentence(1, 0, "secret raw", "secret raw", 0, 10).toString()
        assertFalse(rendered.contains("secret raw"))
        assertTrue(rendered.contains("[REDACTED]"))
    }
}
