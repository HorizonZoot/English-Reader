package io.github.zoot.englishreader.data.repository

import android.util.Log
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.ai.AiExplanationResolutionResult
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.categoryName
import io.github.zoot.englishreader.model.AiOperationHandle
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AiExplanationStartResult {
    data class Started(val handle: AiOperationHandle) : AiExplanationStartResult {
        override fun toString(): String = "Started(handle=[REDACTED])"
    }

    data class Rejected(val error: AiError) : AiExplanationStartResult {
        override fun toString(): String = "Rejected(error=${error.categoryName})"
    }
}

/** 面向 Reading 的公开边界：基于活跃 profile、由 application 持有的解释操作。 */
@Singleton
class AiExplanationRepository internal @Inject constructor(
    private val requestResolver: AiExplanationRequestResolver,
    private val executor: AiExecutor,
    private val operationRegistry: AiExplanationOperationRegistry
) {
    suspend fun start(input: AiExplanationInput): AiExplanationStartResult = try {
        when (val resolution = requestResolver.resolveActive(input)) {
            is AiExplanationResolutionResult.Ready -> AiExplanationStartResult.Started(
                operationRegistry.attachOrStart(resolution.operation.semanticCacheKey) { gate ->
                    executor.execute(resolution.operation, gate)
                }
            )
            is AiExplanationResolutionResult.Rejected ->
                AiExplanationStartResult.Rejected(resolution.error)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        Log.e(TAG, "stage=request_start category=${AiError.Unknown.categoryName}")
        AiExplanationStartResult.Rejected(AiError.Unknown)
    }

    private companion object {
        const val TAG = "AiExplanationRepo"
    }
}
