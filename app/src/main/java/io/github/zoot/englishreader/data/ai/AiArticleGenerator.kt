package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiThinkingMode
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
internal class AiArticleGenerator internal constructor(
    private val profileRepository: AiProfileRepository,
    private val transportProvider: () -> AiChatTransport,
    private val errorMapper: AiErrorMapper
) {
    constructor(
        profileRepository: AiProfileRepository,
        transport: AiChatTransport,
        errorMapper: AiErrorMapper
    ) : this(profileRepository, { transport }, errorMapper)

    /**
     * transport 延迟到第一次生成才取。打开生成页时 Hilt 在主线程构造本类；直接注入会顺带
     * 建出 AI 的 OkHttp（含 TLS 初始化）、Retrofit 与 Moshi，拖慢进入页面的那一帧。
     * 生成在 application scope 的 IO 线程上执行，网络栈改在那里构建。
     */
    @Inject
    constructor(
        profileRepository: AiProfileRepository,
        transport: dagger.Lazy<AiChatTransport>,
        errorMapper: AiErrorMapper
    ) : this(profileRepository, transport::get, errorMapper)

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
        val modelId = AiTextNormalizer.normalizeModelId(profile.modelId)
        val config = AiChatRequestConfig(
            baseUrl = profile.baseUrl,
            modelId = modelId,
            providerTemplate = profile.providerTemplate,
            authStrategy = profile.authStrategy,
            apiKey = profile.apiKey,
            temperature = profile.temperature,
            maxTokens = when (prepared.parameters.length) {
                AiArticleLength.SHORT -> 2_048
                AiArticleLength.MEDIUM -> 3_072
                AiArticleLength.LONG -> 4_096
            },
            thinkingMode = generationThinkingMode(profile.providerTemplate, profile.baseUrl, modelId)
        )
        val response = transportProvider().complete(config, prepared.messages)
        currentCoroutineContext().ensureActive()
        return when (val parsed = AiGeneratedArticleParser.parse(response, prepared.parameters)) {
            is AiGeneratedArticleParseResult.Ready -> AiArticleGenerationResult.Success(parsed.article)
            is AiGeneratedArticleParseResult.Rejected ->
                AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Output(parsed.reason))
        }
    }

    private fun generationThinkingMode(
        provider: AiProviderTemplate,
        baseUrl: String,
        modelId: String
    ): AiThinkingMode? {
        // 仅对已确认支持非思考模式的官方模型设置参数；不把模板能力推断给代理或未知模型。
        val supportedModel = when (provider) {
            AiProviderTemplate.DEEPSEEK -> modelId == "deepseek-v4-flash"
            AiProviderTemplate.ZHIPU -> modelId == "glm-5.2"
            else -> false
        }
        if (!supportedModel) return null
        val endpoint = AiEndpointResolver.chatCompletionsUrl(baseUrl)
        val officialEndpoint = AiEndpointResolver.chatCompletionsUrl(requireNotNull(provider.defaultBaseUrl))
        val isOfficial = endpoint == officialEndpoint ||
            (provider == AiProviderTemplate.DEEPSEEK &&
                endpoint == AiEndpointResolver.chatCompletionsUrl("https://api.deepseek.com/v1"))
        return AiThinkingMode.DISABLED.takeIf { isOfficial }
    }

    private fun fail(error: AiError) =
        AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(error))
}
