package io.github.zoot.englishreader.data.ai

import android.util.Log
import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** prompt 准备、profile 捕获、请求构造与缓存身份的唯一归属方。 */
@Singleton
internal class AiExplanationRequestResolver @Inject constructor(
    private val profileRepository: AiProfileRepository,
    private val errorMapper: AiErrorMapper
) {
    suspend fun resolve(
        profileId: String,
        input: AiExplanationInput
    ): AiExplanationResolutionResult = resolvePrepared(input) { prepared ->
        profileRepository.resolveValidatedProfile(
            profileId,
            AiEndpointResolver::chatCompletionsUrl
        ).toOperation(prepared)
    }

    suspend fun resolveActive(input: AiExplanationInput): AiExplanationResolutionResult =
        resolvePrepared(input) { prepared ->
            profileRepository.resolveValidatedActiveProfile(
                AiEndpointResolver::chatCompletionsUrl
            ).toOperation(prepared)
        }

    /**
     * 只解析一次活跃 profile，供多请求任务复用。
     *
     * 全文翻译一个任务发几百个请求。若每段都走 [resolveActive]，用户中途切换 profile 会让
     * 同一任务的前半段走 A、后半段走 B——两者 endpoint/model 不同，译文风格分叉，且
     * 「预计费用」的前提也不成立。PRD 要求 immutable request snapshot 正是为此。
     */
    suspend fun resolveActiveProfileSnapshot(): ProfileResolutionResult =
        profileRepository.resolveValidatedActiveProfile(AiEndpointResolver::chatCompletionsUrl)

    /** 用已解析的 profile 快照构造一次操作；不再访问凭据存储。 */
    fun resolveWithProfile(
        profile: ResolvedAiProfile,
        input: AiExplanationInput
    ): AiExplanationResolutionResult = when (val prepared = AiPromptPolicy.prepare(input)) {
        is AiPromptPreparationResult.Ready -> createOperation(profile, prepared.prompt)
        is AiPromptPreparationResult.Rejected ->
            AiExplanationResolutionResult.Rejected(prepared.error)
    }

    /** 把 profile 解析失败映射为 typed error；`Available` 返回 null。 */
    fun mapProfileFailure(result: ProfileResolutionResult): AiError? =
        errorMapper.mapProfileResolution(result)

    private suspend fun resolvePrepared(
        input: AiExplanationInput,
        resolveProfile: suspend (PreparedAiPrompt) -> AiExplanationResolutionResult
    ): AiExplanationResolutionResult = try {
        when (val prepared = AiPromptPolicy.prepare(input)) {
            is AiPromptPreparationResult.Ready -> resolveProfile(prepared.prompt)
            is AiPromptPreparationResult.Rejected ->
                AiExplanationResolutionResult.Rejected(prepared.error)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        Log.e(TAG, "stage=request_resolution category=${AiError.Unknown.categoryName}")
        AiExplanationResolutionResult.Rejected(AiError.Unknown)
    }

    private fun ProfileResolutionResult.toOperation(
        prepared: PreparedAiPrompt
    ): AiExplanationResolutionResult = when (this) {
        is ProfileResolutionResult.Available -> createOperation(profile, prepared)
        else -> AiExplanationResolutionResult.Rejected(
            requireNotNull(errorMapper.mapProfileResolution(this))
        )
    }

    private fun createOperation(
        profile: ResolvedAiProfile,
        prepared: PreparedAiPrompt
    ): AiExplanationResolutionResult {
        val request = ResolvedAiExplanationRequest(
            profile = profile,
            normalizedModelId = AiTextNormalizer.normalizeModelId(profile.modelId),
            normalizedInput = prepared.normalizedInput,
            outputLanguageTag = AiTextNormalizer.normalizeLanguageTag(OUTPUT_LANGUAGE_TAG),
            explanationType = prepared.explanationType,
            promptVersion = prepared.promptVersion,
            preparedMessages = prepared.messages
        )
        return AiExplanationResolutionResult.Ready(
            ResolvedAiExplanationOperation(
                request = request,
                semanticCacheKey = ExplanationCacheIdentity.from(request).hash()
            )
        )
    }

    private companion object {
        const val TAG = "AiRequestResolver"
        const val OUTPUT_LANGUAGE_TAG = "zh-CN"
    }
}
