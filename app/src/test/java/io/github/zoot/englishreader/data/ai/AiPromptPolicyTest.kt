package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.importer.ImportBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptPolicyTest {

    @Test
    fun prepare_sentenceTranslation_normalizesAndReturnsTranslationOnlyPrompt() {
        val result = AiPromptPolicy.prepare(
            AiExplanationInput.SentenceTranslation("  First line\r\nSecond line  ")
        ) as AiPromptPreparationResult.Ready

        assertEquals("First line\nSecond line", result.prompt.normalizedInput)
        assertEquals(ExplanationType.SENTENCE_TRANSLATION, result.prompt.explanationType)
        assertEquals("sentence-translation-v1", result.prompt.promptVersion)
        assertEquals(result.prompt.normalizedInput, result.prompt.messages[1].content)
        assertTrue(result.prompt.messages[0].content.contains("Translate only"))
        assertTrue(result.prompt.messages[0].content.contains("Simplified Chinese"))
        assertTrue(result.prompt.messages[0].content.contains("only the translation"))
    }

    @Test
    fun prepare_sentence_normalizesAndSendsOnlySentence() {
        val result = AiPromptPolicy.prepare(
            AiExplanationInput.Sentence("  First line\r\nSecond line  ")
        ) as AiPromptPreparationResult.Ready

        assertEquals("First line\nSecond line", result.prompt.normalizedInput)
        assertEquals(ExplanationType.SENTENCE_EXPLANATION, result.prompt.explanationType)
        assertEquals("sentence-context-v1", result.prompt.promptVersion)
        assertEquals(2, result.prompt.messages.size)
        assertEquals("system", result.prompt.messages[0].role)
        assertEquals("user", result.prompt.messages[1].role)
        assertEquals("First line\nSecond line", result.prompt.messages[1].content)
        assertTrue(result.prompt.messages[0].content.contains("sentence"))
        assertTrue(result.prompt.messages[0].content.contains("Simplified Chinese"))
    }

    @Test
    fun prepare_validArticle_preservesCompleteNormalizedPromptAtBudgetBoundary() {
        val limit = ImportBudget.MAX_FULL_EXPLANATION_CHARS
        listOf(
            Triple("trimmed below limit", "  " + "x".repeat(limit - 4) + "  ", limit - 4),
            Triple("exactly at limit", "x".repeat(limit), limit)
        ).forEach { (case, raw, expectedLength) ->
            val result = AiPromptPolicy.prepare(AiExplanationInput.Article(raw))
            assertTrue(case, result is AiPromptPreparationResult.Ready)
            val prompt = (result as AiPromptPreparationResult.Ready).prompt

            assertEquals(case, expectedLength, prompt.normalizedInput.length)
            assertEquals(case, ExplanationType.ARTICLE_EXPLANATION, prompt.explanationType)
            assertEquals(case, "article-context-v1", prompt.promptVersion)
            assertEquals(case, prompt.normalizedInput, prompt.messages[1].content)
            assertEquals(case, "system", prompt.messages[0].role)
            assertEquals(case, "user", prompt.messages[1].role)
            assertTrue(case, prompt.messages[0].content.contains("article"))
        }
    }

    @Test
    fun prepare_article_overLimit_rejectsWithoutTruncation() {
        val result = AiPromptPolicy.prepare(
            AiExplanationInput.Article("x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1))
        )

        assertEquals(
            AiPromptPreparationResult.Rejected(
                AiError.InputTooLong(
                    actualChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1,
                    maxChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS
                )
            ),
            result
        )
    }

    @Test
    fun prepare_overLimitSentenceAndTranslation_rejectInsteadOfSendingToRemote() {
        // 长度闸门原先只挡文章，依据是「句子长度天然受 UI 选择约束」。该前提不成立：
        // SentenceSplitter 对无句末标点的段落会把整段作为单个 SentenceRange 返回，
        // 而 MAX_IMPORT_CHARS 是 40000。缺这道闸门时用户点一下就能把 40000 字符发往
        // 远端翻译——付费且远端只回 413/400，界面显示成通用网络错误。
        val overLimit = "a".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1)
        val expected = AiPromptPreparationResult.Rejected(
            AiError.InputTooLong(
                actualChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1,
                maxChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS
            )
        )

        assertEquals(expected, AiPromptPolicy.prepare(AiExplanationInput.Sentence(overLimit)))
        assertEquals(
            expected,
            AiPromptPolicy.prepare(AiExplanationInput.SentenceTranslation(overLimit))
        )
    }

    @Test
    fun prepare_atLimitSentenceAndTranslation_stillSucceed() {
        // 闸门是 `>` 而非 `>=`：正好等于上限必须放行，否则会误拒边界输入。
        val atLimit = "a".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS)

        assertTrue(
            AiPromptPolicy.prepare(AiExplanationInput.Sentence(atLimit))
                is AiPromptPreparationResult.Ready
        )
        assertTrue(
            AiPromptPolicy.prepare(AiExplanationInput.SentenceTranslation(atLimit))
                is AiPromptPreparationResult.Ready
        )
    }

    @Test
    fun prepare_blankSentenceAndArticle_rejectAsNoContent() {
        assertEquals(
            AiPromptPreparationResult.Rejected(AiError.NoContent),
            AiPromptPolicy.prepare(AiExplanationInput.Sentence(" \r\n "))
        )
        assertEquals(
            AiPromptPreparationResult.Rejected(AiError.NoContent),
            AiPromptPolicy.prepare(AiExplanationInput.Article(" \n\t "))
        )
        assertEquals(
            AiPromptPreparationResult.Rejected(AiError.NoContent),
            AiPromptPolicy.prepare(AiExplanationInput.SentenceTranslation(" \r\n "))
        )
    }
}
