package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.model.AiOperationOutcome
import java.util.concurrent.atomic.AtomicReference

/** 一份不可变的请求快照，以及由它派生出的唯一语义键。 */
internal data class ResolvedAiExplanationOperation(
    val request: ResolvedAiExplanationRequest,
    val semanticCacheKey: String
) {
    override fun toString(): String =
        "ResolvedAiExplanationOperation(request=[REDACTED], semanticCacheKey=[REDACTED])"
}

internal sealed interface AiExplanationResolutionResult {
    data class Ready(val operation: ResolvedAiExplanationOperation) :
        AiExplanationResolutionResult {
        override fun toString(): String = "Ready(operation=[REDACTED])"
    }

    data class Rejected(val error: AiError) : AiExplanationResolutionResult {
        override fun toString(): String = "Rejected(error=${error.categoryName})"
    }
}

/**
 * 单个操作级别的线性化闸门，由注册表取消与缓存提交共用。
 *
 * 这里完全不涉及注册表全局的 map mutex，因此抢占缓存提交不会在 Room I/O 期间一直持有那把
 * mutex。一旦提交被抢到，即使写入仍处于挂起状态，精确取消也已经失败；而在抢到之前，取消会
 * 阻止写入以及终态成功的发布。
 */
internal class AiOperationCompletionGate {
    private val state = AtomicReference(State.RUNNING)

    fun tryCancel(): Boolean = state.compareAndSet(State.RUNNING, State.CANCELLED)

    fun tryBeginCacheCommit(): Boolean = state.compareAndSet(State.RUNNING, State.COMMITTING)

    fun completeCacheCommit() {
        check(state.compareAndSet(State.COMMITTING, State.SUCCEEDED)) {
            "Cache commit completed without owning the completion gate"
        }
    }

    fun tryPublish(outcome: AiOperationOutcome): Boolean = when (outcome) {
        is AiOperationOutcome.Success ->
            state.compareAndSet(State.RUNNING, State.SUCCEEDED) || state.get() == State.SUCCEEDED
        is AiOperationOutcome.Failure -> state.compareAndSet(State.RUNNING, State.FAILED)
        AiOperationOutcome.Cancelled -> state.get() == State.CANCELLED
    }

    override fun toString(): String = "AiOperationCompletionGate(state=[REDACTED])"

    private enum class State {
        RUNNING,
        COMMITTING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
