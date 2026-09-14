package io.github.zoot.englishreader.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderTemplateTest {

    @Test
    fun providerTemplates_exposeExpectedDefaults() {
        val cases = listOf(
            AiProviderTemplate.DEEPSEEK to ("https://api.deepseek.com" to "deepseek-v4-flash"),
            AiProviderTemplate.KIMI to ("https://api.moonshot.ai/v1" to "kimi-k3"),
            AiProviderTemplate.ZHIPU to (
                "https://open.bigmodel.cn/api/paas/v4/" to "glm-5.2"
            ),
            AiProviderTemplate.OPENAI_COMPATIBLE to (null to null)
        )

        cases.forEach { (template, expectedDefaults) ->
            val (expectedBaseUrl, expectedModelId) = expectedDefaults
            assertEquals(expectedBaseUrl, template.defaultBaseUrl)
            assertEquals(expectedModelId, template.defaultModelId)
        }
    }
}
