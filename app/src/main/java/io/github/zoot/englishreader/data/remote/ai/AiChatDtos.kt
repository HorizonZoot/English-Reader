package io.github.zoot.englishreader.data.remote.ai

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * OpenAI 兼容的 chat completions 请求体。
 *
 * `temperature` 可空**仅在本层**：null 时 Moshi 默认省略该键，JSON 中完全不出现
 * `temperature`——这正是 Kimi K 系列所要求的。`"temperature": null` 是另一种请求，
 * provider 可能拒绝，所以测试断言的是「键缺失」而非「值为 null」。
 *
 * `stream` 显式为 false，不依赖默认值省略。
 */
@JsonClass(generateAdapter = true)
class AiChatCompletionRequest(
    @Json(name = "model")
    val model: String,

    @Json(name = "messages")
    val messages: List<AiChatRequestMessageDto>,

    @Json(name = "temperature")
    val temperature: Double? = null,

    @Json(name = "stream")
    val stream: Boolean = false,

    @Json(name = "max_tokens")
    val maxTokens: Int? = null,

    @Json(name = "thinking")
    val thinking: AiThinkingDto? = null
) {
    override fun toString(): String =
        "AiChatCompletionRequest(" +
            "model=[REDACTED], " +
            "messages=<${messages.size} redacted>, " +
            "temperature=$temperature, " +
            "stream=$stream, " +
            "maxTokens=$maxTokens, " +
            "thinking=$thinking)"
}

@JsonClass(generateAdapter = true)
class AiThinkingDto(
    @Json(name = "type")
    val type: String
) {
    override fun toString(): String = "AiThinkingDto(type=$type)"
}

/**
 * 出站消息，`content` 非空。
 *
 * 与响应侧刻意分开：共用一个可空 DTO 会让请求类型能表达非法状态（没有正文的 user 消息），
 * 并把三种本质不同的情况混成一个可空字段——非法出站请求、空 `choices`、响应正文为 null。
 */
@JsonClass(generateAdapter = true)
class AiChatRequestMessageDto(
    @Json(name = "role")
    val role: String,

    @Json(name = "content")
    val content: String
) {
    override fun toString(): String =
        "AiChatRequestMessageDto(role=$role, content=[REDACTED])"
}

/** 非流式响应体。 */
@JsonClass(generateAdapter = true)
class AiChatCompletionResponse(
    @Json(name = "choices")
    val choices: List<AiChatChoiceDto>? = null
) {
    override fun toString(): String =
        "AiChatCompletionResponse(choices=<${choices?.size ?: 0} redacted>)"
}

@JsonClass(generateAdapter = true)
class AiChatChoiceDto(
    @Json(name = "message")
    val message: AiChatResponseMessageDto? = null
) {
    override fun toString(): String = "AiChatChoiceDto(message=[REDACTED])"
}

/** 入站消息，两个字段都可空——只有响应侧真的允许缺失。 */
@JsonClass(generateAdapter = true)
class AiChatResponseMessageDto(
    @Json(name = "role")
    val role: String? = null,

    @Json(name = "content")
    val content: String? = null
) {
    override fun toString(): String =
        "AiChatResponseMessageDto(role=$role, content=[REDACTED])"
}
