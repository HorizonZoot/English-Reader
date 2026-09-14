package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatCompletionRequest
import io.github.zoot.englishreader.data.remote.ai.AiChatCompletionResponse
import io.github.zoot.englishreader.data.remote.ai.AiChatChoiceDto
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestMessageDto
import io.github.zoot.englishreader.data.remote.ai.AiChatResponseMessageDto
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSensitiveToStringTest {
    @Test
    fun safeRepresentations_doNotExposeIdentityCredentialsOrContent() {
        val secrets = listOf(
            "profile-secret", "display-secret", "https://private.example/v1",
            "model-secret", "key-secret", "user-secret", "response-secret"
        )
        val metadata = AiProviderProfile(
            "profile-secret", "display-secret", AiProviderTemplate.OPENAI_COMPATIBLE,
            "https://private.example/v1", "model-secret", AiAuthStrategy.API_KEY, 0.2
        )
        val resolved = ResolvedAiProfile(
            "profile-secret", AiProviderTemplate.OPENAI_COMPATIBLE,
            "https://private.example/v1", "model-secret", AiAuthStrategy.API_KEY,
            0.2, "key-secret"
        )
        val config = AiChatRequestConfig(
            "https://private.example/v1", "model-secret",
            AiProviderTemplate.OPENAI_COMPATIBLE, AiAuthStrategy.API_KEY, "key-secret", 0.2
        )
        val request = ResolvedAiExplanationRequest(
            resolved, "model-secret", "user-secret", "zh-CN",
            ExplanationType.SENTENCE_EXPLANATION, "sentence-context-v1",
            listOf(AiChatMessage("user", "user-secret"))
        )
        val prepared = AiPromptPolicy.prepare(AiExplanationInput.Sentence("user-secret"))
            as AiPromptPreparationResult.Ready
        val rendered = listOf(
            metadata.toString(), resolved.toString(), config.toString(), request.toString(),
            AiExplanationInput.Sentence("user-secret").toString(),
            AiExplanationInput.SentenceTranslation("user-secret").toString(),
            AiExplanationInput.Article("user-secret").toString(),
            prepared.prompt.toString(),
            AiClientResult.Success("response-secret").toString(),
            AiConnectionTestEvent(
                "request-secret",
                "profile-secret",
                AiClientResult.Success("response-secret")
            ).toString(),
            AiChatRequestMessageDto("user", "user-secret").toString(),
            AiChatResponseMessageDto("assistant", "response-secret").toString(),
            AiChatChoiceDto(AiChatResponseMessageDto("assistant", "response-secret")).toString(),
            AiChatCompletionResponse(
                listOf(AiChatChoiceDto(AiChatResponseMessageDto("assistant", "response-secret")))
            ).toString(),
            AiChatTransportResult.Content("response-secret").toString(),
            AiChatCompletionRequest(
                "model-secret", listOf(AiChatRequestMessageDto("user", "user-secret"))
            ).toString()
        ).joinToString("|")

        secrets.forEach { secret ->
            assertFalse("Sensitive value leaked: $secret", rendered.contains(secret))
        }
        assertEquals(
            "AiConnectionTestEvent",
            AiConnectionTestEvent(
                "request-secret",
                "profile-secret",
                AiClientResult.Success("response-secret")
            ).toString()
        )
    }

    /** PRD AC7 / ADR-010：快照不得通过默认 data class 或嵌套 profile 的表示泄露敏感信息。 */
    @Test
    fun requestToString_redactsSensitiveFieldsAndKeepsSafeMetadata() {
        val rendered = ResolvedAiExplanationRequest(
            profile = ResolvedAiProfile(
                profileId = "profile-secret",
                providerTemplate = AiProviderTemplate.OPENAI_COMPATIBLE,
                baseUrl = "https://private-proxy.internal/v1",
                modelId = "secret-model",
                authStrategy = AiAuthStrategy.API_KEY,
                apiKey = "sk-secret-key",
                temperature = 0.2
            ),
            normalizedModelId = "secret-model",
            normalizedInput = "The quick brown fox jumps.",
            outputLanguageTag = "zh-CN",
            explanationType = ExplanationType.SENTENCE_EXPLANATION,
            promptVersion = "sentence-context-v1",
            preparedMessages = (
                AiPromptPolicy.prepare(AiExplanationInput.Sentence("The quick brown fox jumps."))
                    as AiPromptPreparationResult.Ready
                ).prompt.messages
        ).toString()

        assertFalse("endpoint 不得出现", rendered.contains("private-proxy.internal"))
        assertFalse("model 不得出现", rendered.contains("secret-model"))
        assertFalse("输入正文不得出现", rendered.contains("quick brown fox"))
        assertTrue(rendered.contains("[REDACTED]"))
        assertFalse("嵌套 profile 不得整体展开", rendered.contains("ResolvedAiProfile("))
        assertFalse("apiKey 不得出现", rendered.contains("sk-secret-key"))
        assertFalse("profileId 不得出现", rendered.contains("profile-secret"))

        // 脱敏仍需保留安全字段，以便诊断请求类型和 prompt 版本。
        assertTrue(rendered.contains("sentence-context-v1"))
        assertTrue(rendered.contains("zh-CN"))
        assertTrue(rendered.contains("SENTENCE_EXPLANATION"))
    }
}
