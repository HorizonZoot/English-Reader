package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate

/**
 * transport 层自有的请求输入。
 *
 * 刻意**不引用** `ResolvedAiProfile`：那属 repository 层，remote 若反向依赖它会让分层
 * 箭头双向。把 `ResolvedAiProfile` 映射成本类型是 6.3 的职责。
 *
 * `temperature` 在此为非空，因为它表达「用户配置了什么值」；本次请求是否真的发送该参数
 * 由 [AiRequestParameterPolicy] 决定，而不是靠这一层的可空性表达。
 */
class AiChatRequestConfig(
    val baseUrl: String,
    val modelId: String,
    val providerTemplate: AiProviderTemplate,
    val authStrategy: AiAuthStrategy,
    val apiKey: String,
    val temperature: Double,
    val maxTokens: Int? = null,
    /** 可选的 provider 专有 thinking 控制项；普通解释请求不发送该字段。 */
    val thinkingMode: AiThinkingMode? = null
) {
    /**
     * 同时脱敏 `apiKey` 与 `baseUrl`。
     *
     * ADR-010 明确禁止记录完整 URL：自定义 `OPENAI_COMPATIBLE` 端点本身可能是私有代理
     * 主机，或在查询串里携带令牌。Model ID 也可能包含私有部署信息；只保留 provider
     * template、auth strategy、temperature 与有界 request policy 等非身份字段。
     */
    override fun toString(): String =
        "AiChatRequestConfig(" +
            "providerTemplate=$providerTemplate, " +
            "modelId=[REDACTED], " +
            "authStrategy=$authStrategy, " +
            "temperature=$temperature, " +
            "maxTokens=$maxTokens, " +
            "thinkingMode=$thinkingMode, " +
            "baseUrl=[REDACTED], " +
            "apiKey=[REDACTED])"
}

/** 当前请求边界所支持的 provider thinking 控制项子集。 */
enum class AiThinkingMode {
    DISABLED
}

/**
 * transport 层的消息输入。
 *
 * `content` 非空：出站消息没有正文属非法状态，不应可表示。只有响应侧才允许 null。
 */
class AiChatMessage(
    val role: String,
    val content: String
) {
    /** 不泄露正文：这里装的是文章原句与 prompt。 */
    override fun toString(): String =
        "AiChatMessage(role=$role, content=[REDACTED])"
}

/**
 * transport 的返回结果。
 *
 * 只区分「拿到内容」与「响应格式正确但无可用内容」。**不**对 HTTP 状态码、超时、网络故障
 * 做分类——那是 6.9 的错误分类法，此处让 Retrofit/OkHttp 的异常照常抛出。
 */
sealed interface AiChatTransportResult {

    /**
     * 成功取到可渲染的正文。
     *
     * `text` 保证非空且非纯空白——空白正文归 [NoContent]，见其说明。
     */
    class Content(val text: String) : AiChatTransportResult {
        /** 正文是模型解释，不进日志。 */
        override fun toString(): String = "Content(text=[REDACTED])"
    }

    /**
     * 响应本身合法，但没有可用正文。三种情形：
     *
     * 1. `choices` 为空数组或缺失；
     * 2. `message` 或 `content` 为 null；
     * 3. **`content` 是纯空白字符串**。
     *
     * 第 1 条单列此分支的直接原因是 `choices[0]` 在空数组上会抛
     * `IndexOutOfBoundsException`，那会表现为不明崩溃而非可处理结果；网关返回
     * `{"choices":[]}` 是真实可能。
     *
     * 第 3 条是显式决策而非实现巧合：`Content` 会被 6.7 直接渲染进解释面板，纯空白正文
     * 会让用户看到一个空面板——没有提示、也没有重试入口。归入 `NoContent` 才能显示
     * 「模型未返回内容」。对用户而言两者都是「没拿到解释」，但后者可解释、可操作。
     */
    data object NoContent : AiChatTransportResult
}
