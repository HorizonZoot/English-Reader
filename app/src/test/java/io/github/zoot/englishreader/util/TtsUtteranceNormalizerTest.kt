package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TtsUtteranceNormalizerTest {

    @Test
    fun normalize_leadingCapitalIt_lowercasesOnlyThatWord() {
        // 真机复现的原句：段中第二句，对引擎却是 utterance 开头。
        val text = "It expands our vocabulary, improves our focus, and enhances our imagination. "

        assertEquals(
            "it expands our vocabulary, improves our focus, and enhances our imagination. ",
            TtsUtteranceNormalizer.normalize(text)
        )
    }

    @Test
    fun normalize_leadingWhitespaceAndContractions_preservesWhitespaceAndApostrophe() {
        assertEquals("  it's fine.", TtsUtteranceNormalizer.normalize("  It's fine."))
        assertEquals("it’s fine.", TtsUtteranceNormalizer.normalize("It’s fine."))
        assertEquals("it'll do.", TtsUtteranceNormalizer.normalize("It'll do."))
        assertEquals("it’d work.", TtsUtteranceNormalizer.normalize("It’d work."))
        assertEquals("its color.", TtsUtteranceNormalizer.normalize("Its color."))
    }

    @Test
    fun normalize_leadingQuoteOrBracket_stillLowercasesTheFirstWord() {
        // 对话句：引号在最前面，`It` 对引擎来说仍是 utterance 的第一个词。
        assertEquals("“it was late,” she said.", TtsUtteranceNormalizer.normalize("“It was late,” she said."))
        assertEquals("\"it works.\"", TtsUtteranceNormalizer.normalize("\"It works.\""))
        assertEquals("(it rains.)", TtsUtteranceNormalizer.normalize("(It rains.)"))
    }

    @Test
    fun normalize_wordNotInAllowlist_returnsSameInstance() {
        // `Reading` 小写后读音会变（英国地名），`IT` 是真正的缩写，`Italy` 只是前缀相同。
        for (text in listOf("Reading is fun.", "IT department.", "Italy is warm.", "it already.", "", "   ")) {
            assertSame(text, TtsUtteranceNormalizer.normalize(text))
        }
    }

    @Test
    fun normalize_itInsideSentence_isLeftUntouched() {
        val text = "We read it. It helps."

        assertEquals("We read it. It helps.", TtsUtteranceNormalizer.normalize(text))
    }
}
