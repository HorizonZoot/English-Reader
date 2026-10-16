package io.github.zoot.englishreader.data.ai

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportBudgetValidator
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import okio.Buffer
import java.io.IOException

internal object AiGeneratedArticleParser {
    private val LATIN_WORD_PART = Regex("\\p{IsLatin}[\\p{IsLatin}\\p{M}]*")

    fun parse(
        response: AiChatTransportResult,
        parameters: AiArticleGenerationParameters
    ): AiGeneratedArticleParseResult {
        val raw = when (response) {
            is AiChatTransportResult.Content -> response.text
            AiChatTransportResult.NoContent -> return reject(AiGeneratedArticleRejection.NO_CONTENT)
            is AiChatTransportResult.Truncated ->
                return reject(AiGeneratedArticleRejection.TRUNCATED_RESPONSE)
        }
        if (raw.isBlank()) return reject(AiGeneratedArticleRejection.NO_CONTENT)
        val fields = readFields(raw) ?: return reject(AiGeneratedArticleRejection.MALFORMED_RESPONSE)
        val title = normalizeText(fields.first)
        val content = normalizeText(fields.second)
        if (title.isEmpty() || title.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } ||
            countWords(title) == 0
        ) {
            return reject(AiGeneratedArticleRejection.INVALID_TITLE)
        }
        if (title.length > ImportBudget.MAX_TITLE_CHARS) {
            return reject(AiGeneratedArticleRejection.TITLE_TOO_LONG)
        }
        try {
            ImportBudgetValidator.validate(content)
        } catch (error: ImportException) {
            val reason = when (error.failure) {
                ImportFailure.EmptyContent -> AiGeneratedArticleRejection.NO_CONTENT
                is ImportFailure.ContentTooLong -> AiGeneratedArticleRejection.CONTENT_TOO_LONG
                is ImportFailure.TooManyParagraphs -> AiGeneratedArticleRejection.TOO_MANY_PARAGRAPHS
                is ImportFailure.ParagraphTooLong -> AiGeneratedArticleRejection.PARAGRAPH_TOO_LONG
                else -> throw error
            }
            return reject(reason)
        }
        val wordCount = countWords(content)
        if (wordCount == 0) return reject(AiGeneratedArticleRejection.MALFORMED_RESPONSE)
        return AiGeneratedArticleParseResult.Ready(
            AiGeneratedArticle(title, content, parameters, wordCount)
        )
    }

    private fun readFields(raw: String): Pair<String, String>? {
        val json = unwrapFence(raw.trim()) ?: return null
        // 只读内存中的 JSON；解析异常不向外携带可能含正文的 message/path。
        return try {
            JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return null
                reader.beginObject()
                var title: String? = null
                var content: String? = null
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (reader.peek() != JsonReader.Token.STRING) return null
                    when (name) {
                        "title" -> {
                            if (title != null) return null
                            title = reader.nextString()
                        }
                        "content" -> {
                            if (content != null) return null
                            content = reader.nextString()
                        }
                        else -> return null
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null
                if (title == null || content == null) null else title to content
            }
        } catch (_: JsonDataException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun unwrapFence(raw: String): String? {
        if (!raw.startsWith("```")) return raw
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0) return null
        val opening = raw.substring(0, firstLineEnd).trimEnd('\r')
        if (opening != "```" && !opening.equals("```json", ignoreCase = true)) return null
        val body = raw.substring(firstLineEnd + 1)
        if (!body.endsWith("\n```")) return null
        return body.dropLast(4).trim()
    }

    private fun normalizeText(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** Latin 字母词（含变音符），内部单个撇号或连字符连接的词合算一个；纯数字不计。 */
    private fun countWords(content: String): Int {
        var count = 0
        var previousEnd = -1
        LATIN_WORD_PART.findAll(content).forEach { part ->
            val joined = count > 0 && part.range.first == previousEnd + 2 &&
                content[previousEnd + 1] in "'’‐‑-"
            if (!joined) count++
            previousEnd = part.range.last
        }
        return count
    }

    private fun reject(reason: AiGeneratedArticleRejection) =
        AiGeneratedArticleParseResult.Rejected(reason)
}
