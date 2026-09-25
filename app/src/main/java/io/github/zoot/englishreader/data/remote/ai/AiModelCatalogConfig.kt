package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy

/** 一次模型目录请求的不可变快照，不要求先选定模型。 */
class AiModelCatalogConfig(
    val baseUrl: String,
    val authStrategy: AiAuthStrategy,
    val apiKey: String
) {
    override fun toString(): String =
        "AiModelCatalogConfig(baseUrl=[REDACTED], authStrategy=$authStrategy, apiKey=[REDACTED])"
}
