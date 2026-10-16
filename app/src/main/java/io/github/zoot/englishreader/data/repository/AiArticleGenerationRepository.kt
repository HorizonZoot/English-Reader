package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerationParameters
import io.github.zoot.englishreader.data.ai.AiArticleGenerationResult
import io.github.zoot.englishreader.data.ai.AiArticleGenerator
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticle
import io.github.zoot.englishreader.data.ai.aiFailureCause
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val article: AiGeneratedArticle,
    val saveStatus: AiArticleSaveStatus = AiArticleSaveStatus.NotSaved
) {
    override fun toString(): String =
        "AiArticlePreview(resultId=$resultId, profileId=[REDACTED], article=$article, saveStatus=$saveStatus)"
}

internal sealed interface AiArticleSaveStatus {
    data object NotSaved : AiArticleSaveStatus
    data object Saving : AiArticleSaveStatus
    data class Saved(val articleId: Long) : AiArticleSaveStatus
    data object Failed : AiArticleSaveStatus
    data object Cancelled : AiArticleSaveStatus
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

    val isSaving: Boolean
        get() = preview?.saveStatus == AiArticleSaveStatus.Saving
}

/** 仅保留本进程中的一份草稿与最新预览；订阅者离开不会取消生成或本地保存。 */
@Singleton
internal class AiArticleGenerationRepository @Inject constructor(
    private val generator: AiArticleGenerator,
    @ApplicationCoroutineScope private val ownerScope: CoroutineScope,
    private val articleRepository: ArticleRepository
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(AiArticleGenerationState())
    val state: StateFlow<AiArticleGenerationState> = mutableState.asStateFlow()
    private var inFlight: AiArticleGenerationStatus.Running? = null
    private var inFlightSave: AiArticlePreview? = null

    fun updateDraft(draft: AiArticleGenerationDraft): Boolean = synchronized(lock) {
        if (inFlight != null || inFlightSave != null) return@synchronized false
        mutableState.value = mutableState.value.copy(draft = draft)
        true
    }

    fun discardPreview(resultId: String): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (inFlight != null || inFlightSave != null || current.preview?.resultId != resultId) {
            return@synchronized false
        }
        mutableState.value = current.copy(preview = null, status = AiArticleGenerationStatus.Idle)
        true
    }

    /**
     * true 表示已接受保存或该结果已保存，并不表示新的写入已完成。
     * 调用方从当前预览的 Saved 获取 articleId；导航失败不重置此状态。
     */
    fun savePreview(resultId: String): Boolean {
        val preview = synchronized(lock) {
            val current = mutableState.value
            val preview = current.preview?.takeIf { it.resultId == resultId } ?: return false
            if (inFlight != null || inFlightSave != null) return false
            if (preview.saveStatus is AiArticleSaveStatus.Saved) return true
            if (!ownerScope.isActive) return false
            inFlightSave = preview
            mutableState.value = current.copy(preview = preview.copy(saveStatus = AiArticleSaveStatus.Saving))
            preview
        }
        var savedArticleId: Long? = null
        val job = ownerScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                // 仅保护一次本地写入及返回 ID 的接收，避免已提交却因取消丢失 ID、重试后重复入库。
                withContext(NonCancellable) {
                    savedArticleId = articleRepository.insertArticle(
                        ArticleEntity(title = preview.article.title, content = preview.article.content)
                    )
                }
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                (error.aiFailureCause() as? CancellationException)?.let { throw it }
                // 普通本地错误映射为 Failed，不携带可能包含正文的 SQL 或异常消息。
            }
        }
        job.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (inFlightSave !== preview) return@synchronized
                inFlightSave = null
                val current = mutableState.value
                val currentPreview = current.preview?.takeIf { it.resultId == resultId } ?: return@synchronized
                val articleId = savedArticleId
                val saveStatus = when {
                    articleId != null -> AiArticleSaveStatus.Saved(articleId)
                    cause is CancellationException -> AiArticleSaveStatus.Cancelled
                    else -> AiArticleSaveStatus.Failed
                }
                mutableState.value = current.copy(preview = currentPreview.copy(saveStatus = saveStatus))
            }
        }
        return true
    }

    /** 同步占位，连续点击只能接受一次；UI 不持有或取消 owner Job。 */
    fun generate(): Boolean {
        val (request, draft) = synchronized(lock) {
            if (inFlight != null || inFlightSave != null || !ownerScope.isActive) return false
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
