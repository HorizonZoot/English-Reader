package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver
import io.github.zoot.englishreader.data.remote.ai.AiRequestParameterPolicy
import io.github.zoot.englishreader.util.CacheKeyFactory

/**
 * 语义化缓存身份：缓存键只由**决定输出的输入**构成。
 *
 * 由此得到两个必须成立的性质：
 * - 两个 profile 只要请求语义相同（同 endpoint + model + 有效参数 + prompt + 输入），就**共享**缓存；
 * - 同一句子出现在不同文章里也**共享**缓存。
 *
 * 明确**不含** `profileId` / `credentialId` / `apiKey` / 显示名 / `providerTemplate` /
 * `articleId` / `sentenceIndex` / `startOffset` / `endOffset` / `authStrategy` / `createdAt`。
 *
 * 其中排除 `providerTemplate` 是反直觉但关键的一条：无条件把模板 ID 塞进身份，会让同一
 * endpoint+model+prompt 因「用户当初从哪个预设创建的 profile」不同而生成两个键，重新产生
 * 重复付费。厂商差异应当由**有效请求参数**表达——例如 Kimi 省略 `temperature` 这件事，
 * 已经体现在 [AiRequestParametersIdentity.temperature] 为 null 上。
 */
internal data class ExplanationCacheIdentity(
    val cacheFormatVersion: Int,
    val endpointIdentity: String,
    val modelId: String,
    val requestParameters: AiRequestParametersIdentity,
    val explanationType: ExplanationType,
    val promptVersion: String,
    val normalizedInput: String,
    val outputLanguageTag: String
) {

    /** @return SHA-256 缓存键 */
    fun hash(): String = CacheKeyFactory.generate(buildCanonicalPayload())

    /**
     * 带**字段名与 UTF-8 字节长度前缀**的规范载荷。
     *
     * 不用 data class 的 `toString()`、JSON 或分隔符拼接：前两者的字段顺序不受语言规范保证，
     * 分隔符拼接则会在字段值本身含分隔符时产生碰撞。长度前缀让每个字段的边界与内容无关，
     * 因此值里出现 `:`、换行甚至 Unit Separator 都不会让两组不同字段折叠成同一载荷。
     */
    private fun buildCanonicalPayload(): String = buildString {
        appendField("cacheFormatVersion", cacheFormatVersion.toString())
        appendField("endpointIdentity", endpointIdentity)
        appendField("modelId", modelId)
        appendField("temperature", requestParameters.canonicalTemperature())
        appendField("explanationType", explanationType.toStableToken())
        appendField("promptVersion", promptVersion)
        appendField("normalizedInput", normalizedInput)
        appendField("outputLanguageTag", outputLanguageTag)
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        val byteLength = value.toByteArray(Charsets.UTF_8).size
        append(name).append(':').append(byteLength).append(':').append(value).append('\n')
    }

    /** 脱敏：endpoint 可能是私有代理主机，输入是用户正在读的文章内容（ADR-010）。 */
    override fun toString(): String =
        "ExplanationCacheIdentity(" +
            "cacheFormatVersion=$cacheFormatVersion, " +
            "endpointIdentity=[REDACTED], " +
            "modelId=[REDACTED], " +
            "requestParameters=$requestParameters, " +
            "explanationType=${explanationType.toStableToken()}, " +
            "promptVersion=$promptVersion, " +
            "normalizedInput=[REDACTED], " +
            "outputLanguageTag=$outputLanguageTag)"

    companion object {

        /**
         * identity 字段集合或规范化/序列化规则发生不兼容变化时 bump。
         *
         * 与 `promptVersion` 职责严格区分：后者管 prompt 内容、上下文策略与解释格式。
         * 本值**只进哈希、不建列**——旧格式行因键不匹配自然失效，由固定 TTL 与容量策略
         * 回收，无需显式清除。
         */
        const val CACHE_FORMAT_VERSION = 2

        /**
         * 从已解析的请求派生身份。这是纯函数，**不**做 I/O。
         *
         * `temperature` 取 [AiRequestParameterPolicy] 裁剪后的**有效值**而非 profile 原始值：
         * Kimi 非 `moonshot-v1-*` 模型的请求体里根本没有该键，身份必须反映这一点，
         * 否则「带 0.7 发出」与「完全不发」会共享同一个缓存键。
         *
         * 裁剪判据必须用 [ResolvedAiExplanationRequest.normalizedModelId]，与
         * [RemoteAiExecutor] 实际发送的值一致：若这里用 `profile.modelId` 原始值，
         * `"  moonshot-v1-8k  "` 不匹配家族前缀，身份记为省略而请求实际发送了 temperature。
         */
        fun from(request: ResolvedAiExplanationRequest): ExplanationCacheIdentity {
            val profile = request.profile

            val effectiveTemperature = AiRequestParameterPolicy.temperatureFor(
                AiChatRequestConfig(
                    baseUrl = profile.baseUrl,
                    modelId = request.normalizedModelId,
                    providerTemplate = profile.providerTemplate,
                    authStrategy = profile.authStrategy,
                    apiKey = profile.apiKey,
                    temperature = profile.temperature
                )
            )

            return ExplanationCacheIdentity(
                cacheFormatVersion = CACHE_FORMAT_VERSION,
                endpointIdentity = AiEndpointResolver.chatCompletionsUrl(profile.baseUrl),
                modelId = request.normalizedModelId,
                requestParameters = AiRequestParametersIdentity(
                    temperature = AiTextNormalizer.normalizeTemperature(effectiveTemperature)
                ),
                explanationType = request.explanationType,
                promptVersion = request.promptVersion,
                normalizedInput = request.normalizedInput,
                outputLanguageTag = request.outputLanguageTag
            )
        }
    }
}

/**
 * 参与缓存身份的请求参数。
 *
 * 单独成类而非把 `temperature` 平铺进 [ExplanationCacheIdentity]：将来纳入 `top_p`、
 * `max_tokens` 等同为 `Double?` 的参数时，平铺的构造调用会因位置参数错位而静默传错，
 * 具名类型让编译器挡住这类错误。
 */
internal data class AiRequestParametersIdentity(
    val temperature: Double?
) {
    /** null 表示请求体中完全省略该键，用固定 token 表达，不能与任何数值形态混淆。 */
    fun canonicalTemperature(): String = temperature?.toString() ?: OMITTED_TOKEN

    override fun toString(): String =
        "AiRequestParametersIdentity(temperature=${canonicalTemperature()})"

    private companion object {
        const val OMITTED_TOKEN = "omitted"
    }
}
