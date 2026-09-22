package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import retrofit2.HttpException
import javax.inject.Inject

/**
 * 窄 transport 契约：一次非流式 chat completion。
 *
 * 刻意不引用 `ResolvedAiProfile` 或任何 repository 类型，保证 remote 不反向依赖
 * repository。把 profile 映射成 [AiChatRequestConfig] 是 6.3 的职责，它也是第一个真实调用方。
 */
interface AiChatTransport {

    suspend fun complete(
        config: AiChatRequestConfig,
        messages: List<AiChatMessage>
    ): AiChatTransportResult
}

/**
 * Retrofit 实现。
 *
 * 通过构造器接收 [AiChatCompletionApi]；生产装配由 qualified AI network module 统一提供，
 * transport 本身不持有 OkHttp/Retrofit 的构建策略。
 */
class RetrofitAiChatTransport @Inject constructor(
    private val api: AiChatCompletionApi
) : AiChatTransport {

    override suspend fun complete(
        config: AiChatRequestConfig,
        messages: List<AiChatMessage>
    ): AiChatTransportResult {
        val response = api.createChatCompletion(
            url = composeChatCompletionsUrl(config.baseUrl),
            authorization = authorizationHeader(config),
            request = AiChatCompletionRequest(
                model = config.modelId,
                messages = messages.map { AiChatRequestMessageDto(it.role, it.content) },
                temperature = AiRequestParameterPolicy.temperatureFor(config),
                maxTokens = config.maxTokens,
                thinking = config.thinkingMode?.let { mode ->
                    when (mode) {
                        AiThinkingMode.DISABLED -> AiThinkingDto(type = "disabled")
                    }
                }
            )
        )

        if (response.code() != HTTP_OK) {
            throw HttpException(response)
        }

        // 不用 choices[0]：空数组会抛 IndexOutOfBoundsException，表现为不明崩溃。
        val choice = response.body()?.choices?.firstOrNull()
        val text = choice?.message?.content
        return when {
            choice?.finishReason == "length" -> AiChatTransportResult.Truncated(text)
            text.isNullOrBlank() -> AiChatTransportResult.NoContent
            else -> AiChatTransportResult.Content(text)
        }
    }

    /**
     * 用 exhaustive when，无 else 分支：将来出现非 Bearer 方案时应扩展现有枚举，
     * 而非新增平行的认证枚举。当前三家（DeepSeek / Kimi / 智谱）都用 Bearer。
     */
    private fun authorizationHeader(config: AiChatRequestConfig): String =
        when (config.authStrategy) {
            AiAuthStrategy.API_KEY -> "Bearer ${config.apiKey}"
        }

    private companion object {

        const val HTTP_OK = 200

        /**
         * 委托给 [AiEndpointResolver]，与缓存身份共用同一份归一化。
         *
         * 6.4 之前这里是本类的私有实现。两份各自维护的归一化会随时间漂移，
         * 一旦漂移，缓存键描述的端点就不再是真实请求的端点。
         */
        fun composeChatCompletionsUrl(baseUrl: String): String =
            AiEndpointResolver.chatCompletionsUrl(baseUrl)
    }
}
