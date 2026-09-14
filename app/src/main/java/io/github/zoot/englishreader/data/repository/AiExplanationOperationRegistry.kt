package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiOperationCompletionGate
import io.github.zoot.englishreader.model.AiOperationHandle
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在途付费操作的登记处，生命周期与进程一致。
 *
 * 职责只有一件：**按 cacheKey 找到可复用的在途操作，或新建一个**。它不查 Room、不算语义
 * 缓存身份，也不把这个 UI operation registry 当作通用 Repository single-flight（6.19）。
 *
 * 之所以由进程级作用域持有而非 viewModelScope：关闭面板不代表服务端没生成、没计费，
 * 请求必须能活过 UI。
 */
@Singleton
class AiExplanationOperationRegistry @Inject constructor(
    @ApplicationCoroutineScope private val applicationScope: CoroutineScope
) {

    private val mutex = Mutex()
    private val records = mutableMapOf<String, OperationRecord>()

    /**
     * 复用同 key 的在途操作，没有则新建。
     *
     * @param cacheKey 不透明标识符，不得为空白
     * @param operation 实际工作体。6.17 不接网络，测试与将来的调用方自行提供
     */
    internal suspend fun attachOrStart(
        cacheKey: String,
        operation: suspend (AiOperationCompletionGate) -> AiClientResult
    ): AiOperationHandle = mutex.withLock {
        require(cacheKey.isNotBlank()) { "cacheKey must not be blank" }

        // 已完成但 cleanup 回调尚未跑到的僵尸记录不可复用：它的结果已定，
        // 复用会让调用方拿到一个永远不会再更新的终态。
        records[cacheKey]
            ?.takeUnless { it.handle.isCompleted }
            ?.let { return@withLock it.handle }
        records.remove(cacheKey)

        val ref = AiOperationRef(cacheKey, UUID.randomUUID().toString())
        val completionGate = AiOperationCompletionGate()
        // LAZY + 稍后 start()：确保 record 先入 map，消除「操作已完成而记录还没登记」的窗口
        val deferred = applicationScope.async(start = CoroutineStart.LAZY) {
            try {
                val outcome = when (val result = operation(completionGate)) {
                    is AiClientResult.Success -> AiOperationOutcome.Success(result.text)
                    is AiClientResult.Failure -> AiOperationOutcome.Failure(result.error)
                }
                if (completionGate.tryPublish(outcome)) outcome else AiOperationOutcome.Cancelled
            } catch (exception: CancellationException) {
                // 操作可能在完成清理移除其记录之前，就自行以 CancellationException 终止。
                // 先关闭 gate：这样与该清理竞争的精确取消就无法声称是它阻止了一次缓存提交。
                completionGate.tryCancel()
                throw exception
            } catch (_: Exception) {
                val outcome = AiOperationOutcome.Failure(AiError.Unknown)
                if (completionGate.tryPublish(outcome)) outcome else AiOperationOutcome.Cancelled
            }
        }
        val handle = AiOperationHandle(ref, deferred)
        records[cacheKey] = OperationRecord(handle, completionGate)
        deferred.invokeOnCompletion {
            // 回调可能在持锁上下文中同步触发，故清理另起协程去抢锁，避免重入死锁
            applicationScope.launch { removeIfCurrent(ref) }
        }
        deferred.start()
        handle
    }

    /**
     * 精确取消：cacheKey 与 operationId 必须同时匹配。
     *
     * @return 是否取消了当前操作；引用过期时为 false 且无副作用
     */
    suspend fun cancel(ref: AiOperationRef): Boolean = mutex.withLock {
        val current = records[ref.cacheKey] ?: return@withLock false
        if (current.handle.ref.operationId != ref.operationId) return@withLock false

        // 先移除再取消：`cancel()` 可能立即触发终态回调，此时记录已不在 map 中，
        // 那个回调里的 compare-and-remove 自然成为 no-op，消除重入/竞争路径。
        // （替代操作的安全由 Mutex 与上面的 operationId 比较独立保证，与本顺序无关。）
        if (!current.completionGate.tryCancel()) return@withLock false
        records.remove(ref.cacheKey)
        current.handle.cancel()
        true
    }

    /** 仅当 map 中当前记录仍是同一操作时才移除，防止旧操作收尾时删掉替代者。 */
    private suspend fun removeIfCurrent(ref: AiOperationRef) = mutex.withLock {
        val current = records[ref.cacheKey] ?: return@withLock
        if (current.handle.ref.operationId == ref.operationId) {
            records.remove(ref.cacheKey)
        }
    }

    private class OperationRecord(
        val handle: AiOperationHandle,
        val completionGate: AiOperationCompletionGate
    )
}
