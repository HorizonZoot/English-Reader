package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiRequestParameterPolicyTest {

    @Test
    fun supportedModels_sendTemperature() {
        val cases = listOf(
            AiProviderTemplate.KIMI to "moonshot-v1-8k",
            AiProviderTemplate.KIMI to "MOONSHOT-V1-32K",
            AiProviderTemplate.DEEPSEEK to "deepseek-v4-flash",
            AiProviderTemplate.ZHIPU to "glm-5.2",
            AiProviderTemplate.OPENAI_COMPATIBLE to "gpt-4o-mini"
        )

        cases.forEach { (template, modelId) ->
            assertEquals(
                "$template: $modelId",
                0.2,
                AiRequestParameterPolicy.temperatureFor(config(template, modelId))!!,
                0.0
            )
        }
    }

    @Test
    fun kimi_modelsOutsideMoonshotV1Family_omitTemperature() {
        listOf("kimi-k3", "kimi-k2.5", "kimi-k9-preview", "moonshot-v10", "moonshot-v1evil")
            .forEach { modelId ->
                assertNull(
                    modelId,
                    AiRequestParameterPolicy.temperatureFor(config(AiProviderTemplate.KIMI, modelId))
                )
            }
    }

    private fun config(
        template: AiProviderTemplate,
        modelId: String
    ): AiChatRequestConfig = AiChatRequestConfig(
        baseUrl = "https://example.test/v1",
        modelId = modelId,
        providerTemplate = template,
        authStrategy = AiAuthStrategy.API_KEY,
        apiKey = "test-key",
        temperature = 0.2
    )
}
