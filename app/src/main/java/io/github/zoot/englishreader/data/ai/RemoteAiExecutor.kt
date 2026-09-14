package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.remote.ai.AiThinkingMode
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import kotlinx.coroutines.CancellationException

/**
 * 真正发起网络请求的 executor。
 *
 * 承接 6.3 `DefaultAiClient` 里的 transport 调用与结果映射：6.4 把这段逻辑下移一层，
 * 好让 [CachedAiExecutor] 能包在它外面，而 `DefaultAiClient` 专注于「解析恰好一次」。
 */
internal class RemoteAiExecutor(
    private val transport: AiChatTransport,
    private val errorMapper: AiErrorMapper = AiErrorMapper(isOnline = { true })
) : AiExecutor {

    override suspend fun execute(
        operation: ResolvedAiExplanationOperation,
        completionGate: AiOperationCompletionGate
    ): AiClientResult {
        return try {
            val request = operation.request
            val profile = request.profile
            val config = AiChatRequestConfig(
                baseUrl = profile.baseUrl,
                // 用快照里已规范化的值，不在此重新规范化：identity 与 remote 必须看到
                // 完全相同的 model ID，否则 Kimi 家族前缀判据会在两侧得出不同结论。
                modelId = request.normalizedModelId,
                providerTemplate = profile.providerTemplate,
                authStrategy = profile.authStrategy,
                apiKey = profile.apiKey,
                temperature = profile.temperature
            )

            when (val result = transport.complete(config, request.preparedMessages)) {
                is AiChatTransportResult.Content -> AiClientResult.Success(result.text)
                AiChatTransportResult.NoContent ->
                    AiClientResult.Failure(AiError.NoContent)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AiClientResult.Failure(errorMapper.map(error))
        }
    }

    suspend fun testConnection(profile: ResolvedAiProfile): AiClientResult = try {
        val isDeepSeek = profile.providerTemplate == AiProviderTemplate.DEEPSEEK
        val probeMessage = if (isDeepSeek) {
            CONNECTION_TEST_MESSAGE_DEEPSEEK
        } else {
            CONNECTION_TEST_MESSAGE_DEFAULT
        }
        val config = AiChatRequestConfig(
            baseUrl = profile.baseUrl,
            modelId = profile.modelId,
            providerTemplate = profile.providerTemplate,
            authStrategy = profile.authStrategy,
            apiKey = profile.apiKey,
            temperature = profile.temperature,
            maxTokens = if (isDeepSeek) {
                CONNECTION_TEST_MAX_TOKENS_DEEPSEEK
            } else {
                CONNECTION_TEST_MAX_TOKENS_DEFAULT
            },
            thinkingMode = if (isDeepSeek) {
                AiThinkingMode.DISABLED
            } else {
                null
            }
        )
        when (val result = transport.complete(
            config,
            listOf(AiChatMessage(role = "user", content = probeMessage))
        )) {
            is AiChatTransportResult.Content -> AiClientResult.Success(result.text)
            AiChatTransportResult.NoContent -> AiClientResult.Failure(AiError.NoContent)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        AiClientResult.Failure(errorMapper.map(error))
    }

    private companion object {
        const val CONNECTION_TEST_MESSAGE_DEEPSEEK = "Reply with OK only."
        const val CONNECTION_TEST_MESSAGE_DEFAULT = "Connection test"
        const val CONNECTION_TEST_MAX_TOKENS_DEEPSEEK = 4
        const val CONNECTION_TEST_MAX_TOKENS_DEFAULT = 1
    }
}
