package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleParagraphFormattingTest {
    @Test
    fun separateLines_singleNewlines_addsOnlyParagraphSeparators() {
        assertEquals(
            "  First line.\n\n\tSecond line.\n\n第三行 😀",
            ArticleParagraphFormatting.separateLines("  First line.\n\tSecond line.\n第三行 😀")
        )
    }

    @Test
    fun separateLines_existingBlankLinesAndWhitespace_preservesThem() {
        val original = "\n  First.\n \t\n\nSecond.\n\nThird.\n"
        assertEquals(original, ArticleParagraphFormatting.separateLines(original))
        assertFalse(ArticleParagraphFormatting.hasUnseparatedLines(original))
    }

    @Test
    fun separateLines_windowsAndOldMacNewlines_normalizesBeforeSeparating() {
        assertEquals("First.\n\nSecond.\n\nThird.", ArticleParagraphFormatting.separateLines("First.\r\nSecond.\rThird."))
        assertTrue(ArticleParagraphFormatting.hasUnseparatedLines("First.\r\nSecond."))
    }

    @Test
    fun separateLines_repeatedApplication_isIdempotent() {
        val once = ArticleParagraphFormatting.separateLines("First.\nSecond.\n\nThird.\nFourth.")
        assertEquals(once, ArticleParagraphFormatting.separateLines(once))
        assertEquals(listOf("First.", "Second.", "Third.", "Fourth."), ParagraphAligner.splitParagraphs(once))
    }

    @Test
    fun hasUnseparatedLines_warnsOnlyForOneLogicalParagraphWithMultipleNonblankLines() {
        assertTrue(ArticleParagraphFormatting.hasUnseparatedLines("First.\nSecond.\nThird."))
        for (content in listOf("", " \n \t", "Only one line.", "First.\n\nSecond.", "First.\nSecond.\n\nThird.")) {
            assertFalse("must not infer paragraph intent for this shape", ArticleParagraphFormatting.hasUnseparatedLines(content))
        }
    }
}
