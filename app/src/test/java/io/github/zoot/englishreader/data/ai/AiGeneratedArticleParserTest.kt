package io.github.zoot.englishreader.data.ai

import com.squareup.moshi.JsonWriter
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiGeneratedArticleParserTest {
    private val parameters = AiArticleGenerationParameters(topic = "private-topic-marker")

    @Test
    fun parse_completeObject_preservesContentAndRequestParameters() {
        val content = "One small trip.\n\nWe don't rush."
        val article = ready(json("A Journey", content))

        assertEquals("A Journey", article.title)
        assertEquals(content, article.content)
        assertEquals(6, article.wordCount)
        assertEquals(parameters, article.parameters)
    }

    @Test
    fun parse_whitespaceAndLineEndings_normalizesWithoutChangingParagraphText() {
        val article = ready(json("  A title  ", "  First.\r\n\r\n  Second.\rThird.  "))
        assertEquals("A title", article.title)
        assertEquals("First.\n\n  Second.\nThird.", article.content)
    }

    @Test
    fun parse_reorderedFields_acceptsObjectWithoutOrderDependency() {
        val article = ready("{\"content\":\"A complete story.\",\"title\":\"A title\"}")
        assertEquals("A complete story.", article.content)
    }

    @Test
    fun parse_completeOuterCodeFence_acceptsOnlyJsonOrUnlabelledWrapper() {
        val raw = json()
        listOf("```json\n$raw\n```", "```\n$raw\n```", " \r\n```JSON\r\n$raw\r\n```  ")
            .forEach { assertEquals("A title", ready(it).title) }
    }

    @Test
    fun parse_malformedOrAmbiguousStructure_rejectsWithoutGuessingOrCoercion() {
        val invalid = listOf(
            "not JSON", "[]", "null", "\"a story\"", "{}",
            "{\"title\":\"Title\"}", "{\"content\":\"Text\"}",
            "{\"title\":null,\"content\":\"Text\"}",
            "{\"title\":12,\"content\":\"Text\"}",
            "{\"title\":\"Title\",\"content\":false}",
            "{\"title\":\"Title\",\"content\":[\"Text\"]}",
            "{\"title\":\"Title\",\"content\":\"Text\",\"extra\":\"Text\"}",
            "{\"title\":\"Title\",\"title\":\"Other\",\"content\":\"Text\"}",
            "{\"title\":\"Title\",\"content\":\"Text\",\"content\":\"Other\"}",
            "{title:'Title',content:'Text'}",
            "{\"title\":\"Title\",\"content\":\"unfinished",
            "{\"title\":\"Title\",\"content\":\"Text\",}",
            "/* note */" + json(), json() + json(),
            "Here is your article:\n" + json(), json() + "\nDone.",
            "```text\n${json()}\n```", "```json\n${json()}",
            "```json\n${json()}\n```\nExplanation", "```json```",
            "```json\n```", "```\n```", "```json\r\n```", "```json\n\n```"
        )
        invalid.forEachIndexed { index, raw ->
            assertEquals("case=$index", rejected(AiGeneratedArticleRejection.MALFORMED_RESPONSE), parse(raw))
        }
    }

    @Test
    fun parse_explicitTruncation_rejectsEvenWhenPartialTextLooksComplete() {
        listOf(null, "", json()).forEach { text ->
            assertEquals(
                rejected(AiGeneratedArticleRejection.TRUNCATED_RESPONSE),
                AiGeneratedArticleParser.parse(AiChatTransportResult.Truncated(text), parameters)
            )
        }
    }

    @Test
    fun parse_absentOrBlankResponse_returnsNoContent() {
        assertEquals(
            rejected(AiGeneratedArticleRejection.NO_CONTENT),
            AiGeneratedArticleParser.parse(AiChatTransportResult.NoContent, parameters)
        )
        listOf("", " \r\n\t ").forEach {
            assertEquals(rejected(AiGeneratedArticleRejection.NO_CONTENT), parse(it))
        }
    }

    @Test
    fun parse_blankBody_returnsNoContent() {
        assertEquals(rejected(AiGeneratedArticleRejection.NO_CONTENT), parse(json(content = " \r\n\t ")))
    }

    @Test
    fun parse_titleInvalidOrOverLimit_rejectsInsteadOfTruncating() {
        listOf(
            "", " \t ", "Title\nOther", "Title\rOther", "123 中文",
            "Title\u2028Other", "Title\u2029Other", "Title\u0085Other", "Title\u0000Other"
        ).forEach { title ->
            assertEquals(rejected(AiGeneratedArticleRejection.INVALID_TITLE), parse(json(title = title)))
        }
        assertEquals(
            rejected(AiGeneratedArticleRejection.TITLE_TOO_LONG),
            parse(json(title = "a".repeat(ImportBudget.MAX_TITLE_CHARS + 1)))
        )
        assertEquals(
            rejected(AiGeneratedArticleRejection.TITLE_TOO_LONG),
            parse(json(title = "a".repeat(ImportBudget.MAX_TITLE_CHARS - 1) + "😀"))
        )
        val boundary = "a".repeat(ImportBudget.MAX_TITLE_CHARS - 2) + "😀"
        assertEquals(boundary, ready(json(title = boundary)).title)
    }

    @Test
    fun parse_bodyCharacterBudget_acceptsBoundaryAndRejectsNextUnit() {
        val prefix = List(4) { "a".repeat(8_000) }.joinToString("\n\n") + "\n\n"
        val atLimit = prefix + "a".repeat(ImportBudget.MAX_IMPORT_CHARS - prefix.length)
        assertEquals(atLimit, ready(json(content = atLimit)).content)
        assertEquals(
            rejected(AiGeneratedArticleRejection.CONTENT_TOO_LONG),
            parse(json(content = atLimit + "a"))
        )
    }

    @Test
    fun parse_paragraphBudget_acceptsBoundaryAndRejectsNextParagraph() {
        val separator = "\r\n  \r\n\r\n  "
        val atLimit = List(ImportBudget.MAX_IMPORT_PARAGRAPHS) { "Word." }.joinToString(separator)
        assertEquals(ImportBudget.MAX_IMPORT_PARAGRAPHS, ready(json(content = atLimit)).wordCount)
        assertEquals(
            rejected(AiGeneratedArticleRejection.TOO_MANY_PARAGRAPHS),
            parse(json(content = atLimit + separator + "Word."))
        )
    }

    @Test
    fun parse_singleParagraphBudget_acceptsBoundaryAndRejectsNextUnit() {
        val atLimit = "a".repeat(ImportBudget.MAX_PARAGRAPH_CHARS)
        assertEquals(atLimit, ready(json(content = atLimit)).content)
        assertEquals(
            rejected(AiGeneratedArticleRejection.PARAGRAPH_TOO_LONG),
            parse(json(content = atLimit + "a"))
        )
    }

    @Test
    fun parse_aboveFullExplanationBudget_doesNotApplyExplanationLimit() {
        val content = "a".repeat(4_100) + "\n\n" + "b".repeat(4_100)
        assertTrue(content.length > ImportBudget.MAX_FULL_EXPLANATION_CHARS)
        assertEquals(content, ready(json(content = content)).content)
    }

    @Test
    fun parse_wordCount_countsLatinWordsAndInternalConnectorsButNotTitleOrNumbers() {
        val content = "Don't don’t mother-in-law co‐operate non‑stop café naïve cafe\u0301 " +
            "‘hello’ — world 123 45.6 中文 😀"
        assertEquals(10, ready(json("A long title with many words", content)).wordCount)
    }

    @Test
    fun parse_separatedOrRepeatedConnectors_doNotMergeDistinctWords() {
        assertEquals(6, ready(json(content = "one - two three--four five’ six")).wordCount)
    }

    @Test
    fun parse_noLatinWords_rejectsObviousNonEnglishOrNonProseOutput() {
        listOf("中文文章。", "123 45.6 😀", "--- !!!").forEach { content ->
            assertEquals(rejected(AiGeneratedArticleRejection.MALFORMED_RESPONSE), parse(json(content = content)))
        }
    }

    @Test
    fun parse_wordTarget_isOnlyWarningAndIncludesBothBoundaries() {
        AiArticleLength.entries.forEach { length ->
            val low = length.targetWords * 70 / 100
            val high = length.targetWords * 130 / 100
            listOf(low - 1 to true, low to false, high to false, high + 1 to true).forEach { (words, warn) ->
                val content = List(words) { "word" }.joinToString(" ")
                val result = AiGeneratedArticleParser.parse(
                    AiChatTransportResult.Content(json(content = content)),
                    parameters.copy(length = length)
                )
                assertTrue(result is AiGeneratedArticleParseResult.Ready)
                val article = (result as AiGeneratedArticleParseResult.Ready).article
                assertEquals(words, article.wordCount)
                assertEquals(warn, article.isOutsideWordTarget)
                assertEquals(content, article.content)
            }
        }
    }

    @Test
    fun parse_manyConnectedWordParts_countsWithoutRecursivePatternOverflow() {
        val content = "a-".repeat(3_999) + "a"
        assertEquals(1, ready(json(content = content)).wordCount)
    }

    @Test
    fun toString_articleAndParseResult_redactTitleBodyAndTopic() {
        val result = parse(json("private-title-marker", "private-body-marker"))
        assertTrue(result is AiGeneratedArticleParseResult.Ready)
        val article = (result as AiGeneratedArticleParseResult.Ready).article
        listOf(result.toString(), article.toString()).forEach { text ->
            listOf("private-title-marker", "private-body-marker", "private-topic-marker").forEach {
                assertFalse(text.contains(it))
            }
        }
        val invalid = parse("{\"private-body-marker\":\"private-topic-marker\"}")
        assertEquals(rejected(AiGeneratedArticleRejection.MALFORMED_RESPONSE), invalid)
        assertFalse(invalid.toString().contains("private-"))
    }

    private fun json(title: String = "A title", content: String = "A complete story."): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginObject()
            writer.name("title").value(title)
            writer.name("content").value(content)
            writer.endObject()
        }
        return buffer.readUtf8()
    }

    private fun parse(raw: String): AiGeneratedArticleParseResult =
        AiGeneratedArticleParser.parse(AiChatTransportResult.Content(raw), parameters)

    private fun ready(raw: String): AiGeneratedArticle {
        val result = parse(raw)
        assertTrue("expected ready article, was $result", result is AiGeneratedArticleParseResult.Ready)
        return (result as AiGeneratedArticleParseResult.Ready).article
    }

    private fun rejected(reason: AiGeneratedArticleRejection) =
        AiGeneratedArticleParseResult.Rejected(reason)
}
