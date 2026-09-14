package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/**
 * [AiClient] 的默认实现。
 *
 * 只做三件事：解析 profile（**恰好一次**）、规范化输入、把请求快照交给 [AiExecutor]。
 * 网络调用在 [RemoteAiExecutor]，缓存在 [CachedAiExecutor]，两者都不在本类可见范围内。
 *
 * 「恰好一次」是硬约束而非优化：若缓存层与远端层各自解析一遍，两次之间用户改了 endpoint
 * 或 model，就会把请求 B 的结果写进身份 A 的缓存键下。详见 [ResolvedAiExplanationRequest]。
 */
internal class DefaultAiClient(
    private val profileRepository: AiProfileRepository,
    private val requestResolver: AiExplanationRequestResolver,
    private val executor: AiExecutor,
    private val connectionExecutor: RemoteAiExecutor,
    private val connectionRegistry: AiConnectionTestRegistry,
    private val errorMapper: AiErrorMapper
) : AiClient {

    internal constructor(
        profileRepository: AiProfileRepository,
        executor: AiExecutor,
        connectionExecutor: RemoteAiExecutor,
        connectionRegistry: AiConnectionTestRegistry,
        errorMapper: AiErrorMapper
    ) : this(
        profileRepository = profileRepository,
        requestResolver = AiExplanationRequestResolver(profileRepository, errorMapper),
        executor = executor,
        connectionExecutor = connectionExecutor,
        connectionRegistry = connectionRegistry,
        errorMapper = errorMapper
    )

    override val inFlightProfileIds: StateFlow<Set<String>> =
        connectionRegistry.inFlightProfileIds

    override suspend fun explain(profileId: String, input: AiExplanationInput): AiClientResult {
        return try {
            when (val resolution = requestResolver.resolve(profileId, input)) {
                is AiExplanationResolutionResult.Ready -> executor.execute(
                    resolution.operation,
                    AiOperationCompletionGate()
                )
                is AiExplanationResolutionResult.Rejected ->
                    AiClientResult.Failure(resolution.error)
            }
        } catch (cancellation: CancellationException) {
            // 取消不是失败：吞掉它会让 6.11 的显式取消变成一次「请求失败」，
            // 也会让调用方的 scope 无法正常结束。
            throw cancellation
        } catch (error: Exception) {
            AiClientResult.Failure(errorMapper.map(error))
        }
    }

    override suspend fun testConnection(profileId: String): AiClientResult {
        if (profileId.isBlank()) return AiClientResult.Failure(AiError.ProfileNotFound)
        return connectionRegistry.run(profileId) {
            try {
                val resolved = when (val result = profileRepository.resolveValidatedProfile(
                    profileId,
                    AiEndpointResolver::chatCompletionsUrl
                )) {
                    is ProfileResolutionResult.Available -> result.profile
                    else -> return@run AiClientResult.Failure(
                        errorMapper.mapProfileResolution(result)!!
                    )
                }
                connectionExecutor.testConnection(resolved)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AiClientResult.Failure(errorMapper.map(error))
            }
        }
    }

    override suspend fun testConnectionDraft(draft: AiConnectionDraft): AiClientResult {
        val normalizedBaseUrl = draft.baseUrl.trim()
        val normalizedModelId = draft.modelId.trim()
        val normalizedApiKey = draft.apiKey.trim()
        if (normalizedBaseUrl.isBlank() || normalizedModelId.isBlank() ||
            normalizedApiKey.isBlank() || !draft.temperature.isFinite()
        ) {
            return AiClientResult.Failure(AiError.ProfileNotFound)
        }
        try {
            AiEndpointResolver.chatCompletionsUrl(normalizedBaseUrl)
        } catch (_: IllegalArgumentException) {
            return AiClientResult.Failure(AiError.InvalidEndpoint)
        }
        val draftFlightId = draftFlightId(draft.copy(
            baseUrl = normalizedBaseUrl,
            modelId = normalizedModelId,
            apiKey = normalizedApiKey
        ))
        return connectionRegistry.run(draftFlightId) {
            try {
                connectionExecutor.testConnection(
                    ResolvedAiProfile(
                        profileId = AI_DRAFT_CONNECTION_TEST_ID,
                        providerTemplate = draft.providerTemplate,
                        baseUrl = normalizedBaseUrl,
                        modelId = normalizedModelId,
                        authStrategy = draft.authStrategy,
                        temperature = draft.temperature,
                        apiKey = normalizedApiKey
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AiClientResult.Failure(errorMapper.map(error))
            }
        }
    }

    private fun draftFlightId(draft: AiConnectionDraft): String {
        val value = buildString {
            append(draft.providerTemplate.name)
            append('|').append(draft.baseUrl)
            append('|').append(draft.modelId)
            append('|').append(draft.authStrategy.name)
            append('|').append(draft.temperature)
            append('|').append(draft.apiKey)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "draft-$digest"
    }

}
