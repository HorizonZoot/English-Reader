package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * AI 能力的统一门面。
 *
 * 封装 profile 解析、缓存、transport 调用、结果映射，阻止 provider template、auth strategy、
 * DTO 细节上浮到 ViewModel。
 *
 * 阅读解释必须经 [AiExplanationRepository] 的 `start()` 进入；其内部
 * [AiExplanationOperationRegistry] 负责按语义键执行 single-flight，并提供不透明的操作取消句柄。
 * 直接调用 [explain] 仅保留给兼容性 facade，不提供 registry handle、single-flight 或精确操作取消能力，
 * 因此不应直接接入付费阅读 UI。连接测试拥有独立的 profile-keyed single-flight。
 */
interface AiClient {

    /**
     * 解释有界的句子或文章上下文。
     *
     * 准备阶段是纯计算，发生在 profile 解析、凭据访问、缓存查询和付费操作归属之前。
     */
    suspend fun explain(profileId: String, input: AiExplanationInput): AiClientResult

    /** 为已有的句子调用方保留的兼容便捷方法。 */
    suspend fun explain(profileId: String, sentence: String): AiClientResult =
        explain(profileId, AiExplanationInput.Sentence(sentence))

    /** 为 [profileId] 发送一次显式、不走缓存、兼容 provider 的有界诊断请求。 */
    suspend fun testConnection(profileId: String): AiClientResult

    /** 测试编辑器中的草稿，不持久化元数据，也不持久化凭据明文。 */
    suspend fun testConnectionDraft(draft: AiConnectionDraft): AiClientResult

    /** 当前有由 application 持有的连接测试正在运行的 profile 集合。 */
    val inFlightProfileIds: StateFlow<Set<String>>
}

const val AI_DRAFT_CONNECTION_TEST_ID = "unsaved-profile-draft"

data class AiConnectionDraft(
    val providerTemplate: AiProviderTemplate,
    val baseUrl: String,
    val modelId: String,
    val authStrategy: AiAuthStrategy,
    val temperature: Double,
    val apiKey: String
) {
    override fun toString(): String =
        "AiConnectionDraft(providerTemplate=$providerTemplate, baseUrl=[REDACTED], " +
            "modelId=[REDACTED], authStrategy=$authStrategy, temperature=$temperature, " +
            "apiKey=[REDACTED])"
}

/**
 * [AiClient] 的返回结果。
 *
 * 只区分「成功拿到文本」与 typed failure。
 */
sealed interface AiClientResult {

    /**
     * 成功拿到解释文本。
     *
     * [text] 保证非空且非纯空白（transport 已保证）。
     */
    data class Success(val text: String) : AiClientResult {
        override fun toString(): String = "Success(text=[REDACTED])"
    }

    /**
     * 失败：profile 解析失败、模型未返回内容、网络错误等。
     *
     * [error] 是稳定的安全分类。原始异常文本永远不会越过这层边界。
     */
    data class Failure(val error: AiError) : AiClientResult {
        override fun toString(): String = "Failure(error=${error.categoryName})"
    }
}
