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
}
