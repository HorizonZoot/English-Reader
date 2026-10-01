package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordBoundaryDetectorTest {

    @Test
    fun getWordAtOffset_wordOrTrailingCursor_returnsTrimmedWord() {
        listOf(
            Triple("The quick brown fox", 5, "quick"),
            Triple("The dog wondered.", 12, "wondered"),
            Triple("I don't know", 3, "don't"),
            Triple("I don’t know", 5, "don’t"),
            Triple("‘children’s’ books", 6, "children’s"),
            Triple("’word’", 2, "word"),
            Triple("A well-known fact", 5, "well-known"),
            Triple("test", 4, "test"), // 光标恰好位于文本末尾。
            Triple("-word", 1, "word"),
            Triple("'word'", 2, "word")
        ).forEach { (text, offset, expected) ->
            assertEquals("text=$text offset=$offset", expected, WordBoundaryDetector.getWordAtOffset(text, offset))
        }
    }

    @Test
    fun getWordAtOffset_outsideWord_returnsNull() {
        listOf(
            "Hello" to 100,
            "Hello" to -1,
            "Hello, world" to 5,
            "Hello world" to 5,
            "test - word" to 5
        ).forEach { (text, offset) ->
            assertNull("text=$text offset=$offset", WordBoundaryDetector.getWordAtOffset(text, offset))
        }
    }

    @Test
    fun getWordRangeAtOffset_trimsEdgesAndPreservesInternalPunctuation() {
        val text = "'word' -word- --can't-- 'well-known'"
        val expected = listOf("word", "word", "can't", "well-known")
        var from = 0
        expected.forEach { word ->
            val start = text.indexOf(word, from)
            for (offset in start until start + word.length) {
                val range = WordBoundaryDetector.getWordRangeAtOffset(text, offset)
                assertEquals(WordBoundaryDetector.WordRange(word, start, start + word.length), range)
                assertEquals(word, text.substring(range!!.startOffset, range.endOffset))
                assertEquals(word, WordBoundaryDetector.getWordAtOffset(text, offset))
            }
            from = start + word.length
        }
    }

    @Test
    fun findWordRanges_curlyApostrophe_keepsSourceOffsetsAndWholeWordMatching() {
        val text = "‘DON’T’ don’t don’tstop don't."
        val ranges = WordBoundaryDetector.findWordRanges(text, "don't")
        assertEquals(listOf("DON’T", "don’t", "don't"), ranges.map { it.word })
        assertEquals(listOf(1, 8, 24), ranges.map { it.startOffset })
        ranges.forEach { assertEquals(it.word, text.substring(it.startOffset, it.endOffset)) }
        assertEquals(emptyList<WordBoundaryDetector.WordRange>(), WordBoundaryDetector.findWordRanges(text, "don"))
    }

    @Test
    fun getWordRangeAtOffset_curlyApostrophe_everyInternalOffsetKeepsSurface() {
        val text = "‘children’s’ books"
        for (offset in 1..10) {
            assertEquals(
                WordBoundaryDetector.WordRange("children’s", 1, 11),
                WordBoundaryDetector.getWordRangeAtOffset(text, offset)
            )
        }
        listOf(0, 11).forEach { assertNull(WordBoundaryDetector.getWordRangeAtOffset(text, it)) }
    }

    @Test
    fun getWordRangeAtOffset_edgeAndStandalonePunctuation_returnsNull() {
        listOf("'word'" to 0, "'word'" to 5, "-word-" to 0, "-word-" to 5,
            "---" to 1, "''" to 0, "" to 0).forEach { (text, offset) ->
            assertNull("text=$text offset=$offset", WordBoundaryDetector.getWordRangeAtOffset(text, offset))
        }
    }
}
