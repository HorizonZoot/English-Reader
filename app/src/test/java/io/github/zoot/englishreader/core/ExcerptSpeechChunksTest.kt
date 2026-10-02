package io.github.zoot.englishreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptSpeechChunksTest {
    @Test
    fun chunks_longSnapshot_preservesEveryCharacterAndRespectsUtteranceLimit() {
        val text = "Alpha beta 😀 gamma. ".repeat(900)
        val chunks = excerptSpeechChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 4_000 })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test
    fun chunks_pairAtHardBoundary_movesWholePairToNextChunk() {
        val text = "abcdefg😀abcdefghijk"
        val chunks = excerptSpeechChunks(text, maxLength = 8)
        assertEquals(text, chunks.joinToString(""))
        assertEquals("abcdefg", chunks.first())
        assertFalse(chunks[1].first().isLowSurrogate())
        assertTrue(chunks.all { it.length <= 8 })
    }

    @Test
    fun chunks_blankSnapshot_hasNoUtterances() {
        assertTrue(excerptSpeechChunks(" \n \t").isEmpty())
    }
}
