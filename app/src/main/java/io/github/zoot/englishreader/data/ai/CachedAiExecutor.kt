package io.github.zoot.englishreader.data.ai

import android.util.Log
import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import kotlinx.coroutines.CancellationException

/**
 * 语义缓存层：命中即返回，未命中委托远端并写回。
 *
 * **fail-open**：缓存是性能优化与成本控制，不是功能可用性的前置条件。Room 读写失败只会出现在
 * 磁盘满、权限丢失、数据库损坏这类严重系统级问题上，此时应让远端请求继续可用——最坏后果是
 * 一次重复付费，优于功能完全不可用。
 *
 * 本层不直接实现 TTL 与容量策略：Repository 读取时过滤过期记录，事务写入时清理并裁剪。
 */
internal class CachedAiExecutor(
    private val delegate: AiExecutor,
    private val cacheRepository: ExplanationCacheRepository,
    private val beforeCacheCommit: suspend () -> Unit = {}
) : AiExecutor {

    override suspend fun execute(
        operation: ResolvedAiExplanationOperation,
        completionGate: AiOperationCompletionGate
    ): AiClientResult {
        val cacheKey = operation.semanticCacheKey

        val cached = try {
            cacheRepository.getCachedExplanation(cacheKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Log.w(TAG, "stage=cache_read category=cache_unavailable")
            null
        }

        // 空白正文视为未命中：`AiClientResult.Success.text` 对下游保证「非空且非纯空白」，
        // 直接拿库里的值构造 Success 会绕过这个不变量，让 6.7 渲染出无提示、无重试入口的
        // 空面板。不在此额外发起删除；Repository 的后续事务写入会统一维护缓存。
        if (cached != null && cached.explanation.isNotBlank()) {
            return AiClientResult.Success(cached.explanation)
        }

        // 不 catch delegate 的异常：远端失败语义由 RemoteAiExecutor 自己表达，
        // 缓存层无权把它改写成别的结果。
        val result = delegate.execute(operation, completionGate)

        if (result is AiClientResult.Success) {
            // 用于确定性复现「远端已成功、尚未提交」竞争的测试缝。生产环境用空实现默认值：
            // 它不做任何事，也不会放大运行时的取消窗口。
            beforeCacheCommit()
            if (!completionGate.tryBeginCacheCommit()) {
                throw CancellationException("AI operation was cancelled before cache commit")
            }
            try {
                cacheRepository.insertCache(
                    ExplanationCacheEntity(cacheKey = cacheKey, explanation = result.text)
                )
            } catch (cancellation: CancellationException) {
                // 远端已成功但写入期间被取消时，仍然传播取消而**不**返回成功结果：
                // 这是结构化并发的正确语义。不用 NonCancellable 抢救这次结果——
                // 调用方已经放弃了它。
                throw cancellation
            } catch (_: Exception) {
                Log.w(TAG, "stage=cache_write category=cache_unavailable")
                // 缓存是优化；远端成功仍然有效。
            } finally {
                // 抢到提交这一刻就是成功的线性化点。此后无论这次 Room 写入还在挂起还是已经返回，
                // 精确取消都不可能再声称自己成功了。
                completionGate.completeCacheCommit()
            }
        }

        return result
    }

    private companion object {
        const val TAG = "CachedAiExecutor"
    }
}
