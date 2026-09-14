package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.categoryName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 可与 UI 状态关联的不可变句子元数据。
 *
 * 具体的 `SelectedSentence` 值对象归阅读入口那侧所有。此处只保留这个窄接口，是为了让
 * sheet target 能直接携带那份快照，既不必复制它，也不会让 AI 请求边界反向依赖阅读侧的
 * 专有类型。偏移值是单个 `InteractiveText` 发出的段落内半开区间；跨段落的身份标识是
 * `sentenceIndex`，偏移不进入缓存身份。
 */
interface SelectedSentenceSnapshot {
    val articleId: Long
    val sentenceIndex: Int
    val rawText: String
    val normalizedText: String
    /** 段落内的半开区间起始偏移；不是文章全局坐标。 */
    val startOffset: Int

    /** 段落内的结束偏移（不含该位置）；不是文章全局坐标。 */
    val endOffset: Int
}

/** 仅供解释 sheet 使用的 UI 关联；绝不参与 AI 请求或缓存身份。 */
sealed interface AiExplanationTarget {
    data class Sentence(val snapshot: SelectedSentenceSnapshot) : AiExplanationTarget {
        override fun toString(): String = "Sentence(snapshot=[REDACTED])"
    }

    data class Article(val articleId: Long) : AiExplanationTarget {
        init {
            require(articleId >= 0) { "articleId must not be negative" }
        }

        override fun toString(): String = "Article(articleId=[REDACTED])"
    }
}

/** 单个由 application 持有的解释操作的不透明身份标识。 */
class AiOperationRef internal constructor(
    internal val cacheKey: String,
    val operationId: String
) {
    override fun equals(other: Any?): Boolean =
        other is AiOperationRef && cacheKey == other.cacheKey && operationId == other.operationId

    override fun hashCode(): Int = 31 * cacheKey.hashCode() + operationId.hashCode()

    override fun toString(): String =
        "AiOperationRef(cacheKey=[REDACTED], operationId=[REDACTED])"
}

sealed interface AiOperationOutcome {
    data class Success(val explanation: String) : AiOperationOutcome {
        override fun toString(): String = "Success(explanation=[REDACTED])"
    }

    data class Failure(val error: AiError) : AiOperationOutcome {
        override fun toString(): String = "Failure(error=${error.categoryName})"
    }

    data object Cancelled : AiOperationOutcome
}

/** 安全的观察者边界；底层的 Deferred 与语义 key 保持 internal 不外泄。 */
class AiOperationHandle internal constructor(
    val ref: AiOperationRef,
    private val deferred: Deferred<AiOperationOutcome>
) {
    suspend fun awaitOutcome(): AiOperationOutcome = try {
        deferred.await()
    } catch (cancellation: CancellationException) {
        currentCoroutineContext().ensureActive()
        AiOperationOutcome.Cancelled
    } catch (_: Exception) {
        AiOperationOutcome.Failure(AiError.Unknown)
    }

    internal val isCompleted: Boolean
        get() = deferred.isCompleted

    internal fun cancel() {
        deferred.cancel()
    }

    override fun toString(): String = "AiOperationHandle(ref=[REDACTED], outcome=[REDACTED])"
}

class AiSheetRequestToken internal constructor(internal val generation: Long) {
    override fun equals(other: Any?): Boolean =
        other is AiSheetRequestToken && generation == other.generation

    override fun hashCode(): Int = generation.hashCode()

    override fun toString(): String = "AiSheetRequestToken(generation=[REDACTED])"
}

data class AiSheetAttachment(
    val generation: Long,
    val operationRef: AiOperationRef
) {
    override fun toString(): String =
        "AiSheetAttachment(generation=$generation, operationRef=[REDACTED])"
}

sealed interface AiSheetState {
    data object Hidden : AiSheetState

    data class Loading(
        val requestToken: AiSheetRequestToken,
        val target: AiExplanationTarget
    ) : AiSheetState {
        override fun toString(): String = "Loading(requestToken=[REDACTED], target=[REDACTED])"
    }

    data class Visible(
        val attachment: AiSheetAttachment,
        val outcome: AiOperationOutcome? = null,
        val target: AiExplanationTarget
    ) : AiSheetState {
        override fun toString(): String =
            "Visible(attachment=[REDACTED], outcome=${outcome?.let { "[REDACTED]" }}, target=[REDACTED])"
    }

    data class Rejected(
        val error: AiError,
        val target: AiExplanationTarget
    ) : AiSheetState {
        override fun toString(): String =
            "Rejected(error=${error.categoryName}, target=[REDACTED])"
    }
}
