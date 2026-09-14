package io.github.zoot.englishreader.data.local

import com.squareup.moshi.JsonClass

enum class AiProviderTemplate(
    val defaultBaseUrl: String?,
    val defaultModelId: String?
) {
    DEEPSEEK(
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModelId = "deepseek-v4-flash"
    ),
    KIMI(
        defaultBaseUrl = "https://api.moonshot.ai/v1",
        defaultModelId = "kimi-k3"
    ),
    ZHIPU(
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4/",
        defaultModelId = "glm-5.2"
    ),
    OPENAI_COMPATIBLE(
        defaultBaseUrl = null,
        defaultModelId = null
    )
}

enum class AiAuthStrategy {
    API_KEY
}

@JsonClass(generateAdapter = true)
data class AiProviderProfile(
    val profileId: String,
    val displayName: String,
    val providerTemplate: AiProviderTemplate,
    val baseUrl: String,
    val modelId: String,
    val authStrategy: AiAuthStrategy,
    val temperature: Double
) {
    override fun toString(): String =
        "AiProviderProfile(" +
            "profileId=[REDACTED], " +
            "displayName=[REDACTED], " +
            "providerTemplate=$providerTemplate, " +
            "baseUrl=[REDACTED], " +
            "modelId=[REDACTED], " +
            "authStrategy=$authStrategy, " +
            "temperature=$temperature)"
}
