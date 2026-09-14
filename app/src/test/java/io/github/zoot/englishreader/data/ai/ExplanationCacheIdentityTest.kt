package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [ExplanationCacheIdentity] 单元测试。
 *
 * 核心是两组对称断言：**纳入**字段变化必须换键，**排除**字段变化必须同键。
 * 后者是本任务修的串味 bug 的反向验证。
 */
class ExplanationCacheIdentityTest {

    // ---- 纳入字段：变化必须换键 ----

    @Test
    fun hash_outputDeterminingMetadataChanges_produceDifferentKeys() {
        val baseline = identityOf()
        listOf(
            "endpoint" to identityOf(baseUrl = "https://proxy.internal/v1"),
            "model" to identityOf(modelId = "deepseek-v4-pro"),
            "prompt version" to identityOf(promptVersion = "sentence-context-v2"),
            "output language" to identityOf(languageTag = "en-US"),
            "cache format" to baseline.copy(cacheFormatVersion = baseline.cacheFormatVersion + 1),
            "effective temperature" to identityOf(temperature = 0.9)
        ).forEach { (case, changed) ->
            assertNotEquals(case, baseline.hash(), changed.hash())
        }
    }

    @Test
    fun differentInputYieldsDifferentKey() {
        val a = identityOf(input = "First sentence.")
        val b = identityOf(input = "Second sentence.")
        assertNotEquals(a.hash(), b.hash())
    }

    @Test
    fun sentenceAndArticleExplanationTypesYieldDifferentKeys() {
        val sentence = identityOf(explanationType = ExplanationType.SENTENCE_EXPLANATION)
        val article = identityOf(explanationType = ExplanationType.ARTICLE_EXPLANATION)

        assertNotEquals(sentence.hash(), article.hash())
        assertEquals("sentence", sentence.explanationType.toStableToken())
        assertEquals("article", article.explanationType.toStableToken())
    }

    @Test
    fun sentenceTranslationAndSentenceExplanationYieldDifferentKeys() {
        val explanation = identityOf()
        val translation = identityOf(explanationType = ExplanationType.SENTENCE_TRANSLATION)

        assertNotEquals(explanation.hash(), translation.hash())
        assertEquals("sentence-translation", translation.explanationType.toStableToken())
    }

    @Test
    fun cacheFormatVersion_remainsTwoForPromptPolicyChange() {
        assertEquals(2, ExplanationCacheIdentity.CACHE_FORMAT_VERSION)
        assertEquals(2, identityOf().cacheFormatVersion)
    }

    @Test
    fun hash_requestIndependentFields_doNotChangeKey() {
        // 配置身份、凭据和已渲染消息不参与 key。DeepSeek 与兼容模板都发送 temperature，
        // 相同请求语义不能因模板来源不同而产生重复付费。
        val alteredMessages = listOf(
            AiChatMessage("system", "different system wording"),
            AiChatMessage("user", "different user content")
        )
        listOf(
            Triple("profile ID", identityOf(profileId = "profile-alpha"), identityOf(profileId = "profile-beta")),
            Triple("API key", identityOf(apiKey = "sk-first-key"), identityOf(apiKey = "sk-second-key")),
            Triple(
                "equivalent provider templates",
                identityOf(template = AiProviderTemplate.DEEPSEEK),
                identityOf(template = AiProviderTemplate.OPENAI_COMPATIBLE)
            ),
            Triple("serialized messages", identityOf(), identityOf(preparedMessages = alteredMessages))
        ).forEach { (case, first, second) ->
            assertEquals(case, first.hash(), second.hash())
        }
    }

    /**
     * Kimi 非 `moonshot-v1-*` 模型的请求体里根本没有 `temperature` 键，
     * 身份必须反映这一点，否则「带 0.7 发出」与「完全不发」会共享同一个键。
     */
    @Test
    fun kimiOmittedTemperatureDiffersFromExplicitValue() {
        val omitted = identityOf(
            template = AiProviderTemplate.KIMI,
            modelId = "kimi-k3",
            temperature = 0.7
        )
        val explicit = identityOf(
            template = AiProviderTemplate.KIMI,
            modelId = "moonshot-v1-8k",
            temperature = 0.7
        )

        assertEquals("omitted", omitted.requestParameters.canonicalTemperature())
        assertEquals("0.7", explicit.requestParameters.canonicalTemperature())
        assertNotEquals(omitted.hash(), explicit.hash())
    }

    @Test
    fun endpointIdentityIsNormalizedForm() {
        val identity = identityOf(baseUrl = "HTTPS://API.MOONSHOT.AI:443/v1/")
        assertEquals("https://api.moonshot.ai/v1/chat/completions", identity.endpointIdentity)
    }

    // ---- canonical payload 抗错位 ----

    /**
     * 长度前缀让字段边界与内容无关：值里出现分隔符也不会让两组不同字段折叠成同一载荷。
     *
     * 这两个输入若用朴素分隔符拼接（如 `a:b` 与 `a`+`b`），会产出相同载荷。
     */
    @Test
    fun fieldValuesContainingSeparatorsDoNotCollide() {
        val a = identityOf(input = "alpha", promptVersion = "beta")
        val b = identityOf(input = "alpha:5:beta", promptVersion = "")
        assertNotEquals(a.hash(), b.hash())
    }

    @Test
    fun fieldValuesContainingNewlinesDoNotCollide() {
        val a = identityOf(input = "one\ntwo", languageTag = "zh-CN")
        val b = identityOf(input = "one", languageTag = "zh-CN")
        assertNotEquals(a.hash(), b.hash())
    }

    @Test
    fun sameInputsYieldStableKey() {
        assertEquals(identityOf().hash(), identityOf().hash())
    }

    // ---- 脱敏 ----

    @Test
    fun toStringRedactsEndpointModelAndInput() {
        val rendered = identityOf(
            baseUrl = "https://private-proxy.internal/v1",
            modelId = "secret-model",
            input = "The quick brown fox jumps."
        ).toString()

        assertFalse("endpoint 不得出现", rendered.contains("private-proxy.internal"))
        assertFalse("modelId 不得出现", rendered.contains("secret-model"))
        assertFalse("输入正文不得出现", rendered.contains("quick brown fox"))
        assertEquals(true, rendered.contains("[REDACTED]"))
    }

    // ---- 辅助方法 ----

    private fun identityOf(
        profileId: String = "test-profile",
        template: AiProviderTemplate = AiProviderTemplate.DEEPSEEK,
        baseUrl: String = "https://api.deepseek.com",
        modelId: String = "deepseek-v4-flash",
        apiKey: String = "sk-test-key",
        temperature: Double = 0.2,
        input: String = "Hello world.",
        languageTag: String = "zh-CN",
        promptVersion: String = "sentence-context-v1",
        explanationType: ExplanationType = ExplanationType.SENTENCE_EXPLANATION,
        preparedMessages: List<AiChatMessage>? = null
    ): ExplanationCacheIdentity = ExplanationCacheIdentity.from(
        ResolvedAiExplanationRequest(
            profile = ResolvedAiProfile(
                profileId = profileId,
                providerTemplate = template,
                baseUrl = baseUrl,
                modelId = modelId,
                authStrategy = AiAuthStrategy.API_KEY,
                apiKey = apiKey,
                temperature = temperature
            ),
            normalizedModelId = AiTextNormalizer.normalizeModelId(modelId),
            normalizedInput = input,
            outputLanguageTag = languageTag,
            explanationType = explanationType,
            promptVersion = promptVersion,
            preparedMessages = preparedMessages ?: (
                AiPromptPolicy.prepare(
                    when (explanationType) {
                        ExplanationType.SENTENCE_EXPLANATION ->
                            AiExplanationInput.Sentence(input)
                        ExplanationType.SENTENCE_TRANSLATION ->
                            AiExplanationInput.SentenceTranslation(input)
                        ExplanationType.PARAGRAPH_TRANSLATION ->
                            AiExplanationInput.ParagraphTranslation(input)
                        ExplanationType.ARTICLE_EXPLANATION ->
                            AiExplanationInput.Article(input)
                    }
                )
                    as AiPromptPreparationResult.Ready
                ).prompt.messages
        )
    )
}
