package io.github.zoot.englishreader.data.importer

import kotlin.system.measureNanoTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class XhtmlTextExtractorTest {
    @Before
    fun setUp() = XmlParsersTestSupport.install()

    @After
    fun tearDown() = XmlParsersTestSupport.reset()

    @Test
    fun extract_textBreaksAndSkippedSubtrees_preservesParagraphBoundaries() {
        val cases = listOf(
            "<p>Alpha<br/>Beta</p><p>Gamma</p>" to "Alpha\nBeta\n\nGamma",
            "<p>A</p><div> \t</div><p>B</p>" to "A\n\n \tB",
            "<p>A</p><div><![CDATA[ \t ]]></div><p>B</p>" to "A\n\n \t B",
            "<p>A</p><div> <script><p>ignored</p><br/></script>\t</div><p>B</p>" to "A\n\n \tB",
            "<p>A</p><div>\u00a0</div><p>B</p>" to "A\n\nB",
            "<p>A</p><div>\u2003</div><p>B</p>" to "A\n\n\u2003\n\nB",
            " \t<div><p>A</p></div> " to "A",
            "<div> \t<br/><br/></div>" to ""
        )
        for ((body, expected) in cases) {
            assertEquals(body, expected, XhtmlTextExtractor.extract(EpubFixtures.xhtmlRawBody(body)))
        }
    }

    @Test
    fun extract_growingWhitespaceAndBreakTails_preservesExactOutput() {
        for (count in listOf(2_000, 4_000, 8_000)) {
            val cases = listOf(
                "<div> </div>".repeat(count) to "A\n\n${" ".repeat(count)}B",
                "<div><br/></div>".repeat(count) to "A\n\nB"
            )
            for ((tail, expected) in cases) {
                val xml = EpubFixtures.xhtmlRawBody("<p>A</p>$tail<p>B</p>")
                val elapsed = measureNanoTime {
                    assertEquals(expected, XhtmlTextExtractor.extract(xml))
                }
                println("XHTML-TAIL items=$count chars=${xml.length} elapsedNs=$elapsed")
            }
        }
    }

    @Test
    fun extract_mixedWhitespaceAndLineBreaks_matchesEstablishedNormalization() {
        val pieces = listOf(" \t", "\u00a0", "\u2003", "<br/>", "<br/><br/>", "word")
        for (first in pieces) for (second in pieces) for (third in pieces) {
            val body = " A$first$second${third}B "
            val expected = body.replace("<br/>", "\n")
                .replace('\u00a0', ' ')
                .replace(Regex("[ \\t]+\n"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()

            assertEquals(body, expected, XhtmlTextExtractor.extract(EpubFixtures.xhtmlRawBody(body)))
        }
    }
}
