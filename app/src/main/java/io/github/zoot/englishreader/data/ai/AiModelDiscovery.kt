package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy

/** 目录查询只绑定 endpoint 和凭据，不携带模型、温度或用户正文。 */
class AiModelDiscoveryDraft(
    val baseUrl: String,
    val authStrategy: AiAuthStrategy,
    val apiKey: String
) {
    override fun toString(): String =
        "AiModelDiscoveryDraft(baseUrl=[REDACTED], authStrategy=$authStrategy, apiKey=[REDACTED])"
}

sealed interface AiModelDiscoveryResult {
    class Success(modelIds: List<String>) : AiModelDiscoveryResult {
        val modelIds: List<String> = modelIds.map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted()

        init {
            require(this.modelIds.isNotEmpty()) { "Empty model catalog" }
        }

        override fun toString(): String = "ModelDiscoverySuccess(modelCount=${modelIds.size})"
    }

    data class Failure(val error: AiError) : AiModelDiscoveryResult {
        override fun toString(): String = "ModelDiscoveryFailure(error=${error.categoryName})"
    }
}
