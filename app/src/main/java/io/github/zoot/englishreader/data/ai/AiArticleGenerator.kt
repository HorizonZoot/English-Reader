package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

/** 一次主动生成对应一次请求；不接解释缓存、连接探测或自动重试。 */
internal class AiArticleGenerator @Inject constructor(
    private val profileRepository: AiProfileRepository,
    private val transport: AiChatTransport,
    private val errorMapper: AiErrorMapper
) {
    suspend fun generate(
        profileId: String?,
        parameters: AiArticleGenerationParameters
    ): AiArticleGenerationResult = try {
        currentCoroutineContext().ensureActive()
        when (val prepared = AiArticlePromptPolicy.prepare(parameters)) {
            is AiArticlePromptPreparationResult.Rejected -> fail(prepared.error)
            is AiArticlePromptPreparationResult.Ready ->
                if (profileId == null) fail(AiError.NoActiveProfile)
                else execute(profileId, prepared.prompt)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        fail(errorMapper.map(error))
    }

    private suspend fun execute(
        profileId: String,
        prepared: PreparedAiArticlePrompt
    ): AiArticleGenerationResult {
        val resolution = profileRepository.resolveValidatedProfile(
            profileId,
            AiEndpointResolver::chatCompletionsUrl
        )
        currentCoroutineContext().ensureActive()
        if (resolution !is ProfileResolutionResult.Available) {
            return fail(requireNotNull(errorMapper.mapProfileResolution(resolution)))
        }
        val profile = resolution.profile
        val config = AiChatRequestConfig(
            baseUrl = profile.baseUrl,
            modelId = AiTextNormalizer.normalizeModelId(profile.modelId),
            providerTemplate = profile.providerTemplate,
            authStrategy = profile.authStrategy,
            apiKey = profile.apiKey,
            temperature = profile.temperature,
            maxTokens = when (prepared.parameters.length) {
                AiArticleLength.SHORT -> 2_048
                AiArticleLength.MEDIUM -> 3_072
                AiArticleLength.LONG -> 4_096
            }
        )
        val response = transport.complete(config, prepared.messages)
        currentCoroutineContext().ensureActive()
        return when (val parsed = AiGeneratedArticleParser.parse(response, prepared.parameters)) {
            is AiGeneratedArticleParseResult.Ready -> AiArticleGenerationResult.Success(parsed.article)
            is AiGeneratedArticleParseResult.Rejected ->
                AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Output(parsed.reason))
        }
    }

    private fun fail(error: AiError) =
        AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(error))
}
