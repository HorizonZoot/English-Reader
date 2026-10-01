package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.data.audio.WordAudioUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WordLookupKeyTest {
    @Test
    fun normalize_typographicApostrophe_matchesAsciiWithoutChangingSource() {
        val surface = "  Children’s  "
        assertEquals("children's", WordLookupKey.normalize(surface))
        assertEquals("  Children’s  ", surface)
        assertEquals("don't", WordLookupKey.normalize("DON’T"))
    }

    @Test
    fun foldApostrophe_preservesUtf16LengthAndOtherPunctuation() {
        val source = "😀 ‘DON’T’ – co-operate"
        val folded = WordLookupKey.foldApostrophes(source)
        assertEquals("😀 ‘DON'T' – co-operate", folded)
        assertEquals(source.length, folded.length)
    }

    @Test
    fun wordAudioUrl_typographicApostrophe_usesSameQueryAsAscii() {
        assertEquals(WordAudioUrl.forWord("don't"), WordAudioUrl.forWord("DON’T"))
    }
}
