package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerationParameters
import io.github.zoot.englishreader.data.ai.AiArticleGenerationResult
import io.github.zoot.englishreader.data.ai.AiArticleGenerator
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticle
import io.github.zoot.englishreader.data.ai.aiFailureCause
import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal data class AiArticleGenerationDraft(
    val parameters: AiArticleGenerationParameters = AiArticleGenerationParameters(),
    val profileId: String? = null
) {
    override fun toString(): String =
        "AiArticleGenerationDraft(parameters=$parameters, profileId=[REDACTED])"
}

internal data class AiArticlePreview(
    val resultId: String,
    val profileId: String?,
    val article: AiGeneratedArticle
) {
    override fun toString(): String =
        "AiArticlePreview(resultId=$resultId, profileId=[REDACTED], article=$article)"
}

internal sealed interface AiArticleGenerationStatus {
    data object Idle : AiArticleGenerationStatus
    data class Running(val requestId: String) : AiArticleGenerationStatus
    data class Failed(val requestId: String, val failure: AiArticleGenerationFailure) :
        AiArticleGenerationStatus
    data class Cancelled(val requestId: String) : AiArticleGenerationStatus
}

internal data class AiArticleGenerationState(
    val draft: AiArticleGenerationDraft = AiArticleGenerationDraft(),
    val status: AiArticleGenerationStatus = AiArticleGenerationStatus.Idle,
    val preview: AiArticlePreview? = null
) {
    val isGenerating: Boolean
        get() = status is AiArticleGenerationStatus.Running
}

/** 仅保留本进程中的一份草稿与最新预览；订阅者离开不会取消付费请求。 */
@Singleton
internal class AiArticleGenerationRepository @Inject constructor(
    private val generator: AiArticleGenerator,
    @ApplicationCoroutineScope private val ownerScope: CoroutineScope
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(AiArticleGenerationState())
    val state: StateFlow<AiArticleGenerationState> = mutableState.asStateFlow()
    private var inFlight: AiArticleGenerationStatus.Running? = null

    fun updateDraft(draft: AiArticleGenerationDraft): Boolean = synchronized(lock) {
        if (inFlight != null) return@synchronized false
        mutableState.value = mutableState.value.copy(draft = draft)
        true
    }

    fun discardPreview(resultId: String): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (inFlight != null || current.preview?.resultId != resultId) return@synchronized false
        mutableState.value = current.copy(preview = null, status = AiArticleGenerationStatus.Idle)
        true
    }

    /** 同步占位，连续点击只能接受一次；UI 不持有或取消 owner Job。 */
    fun generate(): Boolean {
        val (request, draft) = synchronized(lock) {
            if (inFlight != null || !ownerScope.isActive) return false
            val request = AiArticleGenerationStatus.Running(UUID.randomUUID().toString())
            val current = mutableState.value
            inFlight = request
            mutableState.value = current.copy(status = request)
            request to current.draft
        }
        var result: AiArticleGenerationResult? = null
        val job = ownerScope.launch {
            result = try {
                generator.generate(draft.profileId, draft.parameters).also {
                    currentCoroutineContext().ensureActive()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                (error.aiFailureCause() as? CancellationException)?.let { throw it }
                AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(AiError.Unknown))
            }
        }
        // 即使 owner 在 launch 执行前结束，仍会解除占位；取消是终态而非普通失败。
        job.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (inFlight !== request) return@synchronized
                val current = mutableState.value
                val completed = result
                val next = when {
                    cause is CancellationException -> current.copy(
                        status = AiArticleGenerationStatus.Cancelled(request.requestId)
                    )
                    completed is AiArticleGenerationResult.Success -> current.copy(
                        status = AiArticleGenerationStatus.Idle,
                        preview = AiArticlePreview(request.requestId, draft.profileId, completed.article)
                    )
                    else -> current.copy(
                        status = AiArticleGenerationStatus.Failed(
                            request.requestId,
                            (completed as? AiArticleGenerationResult.Failure)?.failure
                                ?: AiArticleGenerationFailure.Request(AiError.Unknown)
                        )
                    )
                }
                inFlight = null
                mutableState.value = next
            }
        }
        return true
    }
}
