package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [CachedAiExecutor] 单元测试。
 *
 * 覆盖 fail-open 语义、公开不变量守护、以及「读写使用同一个键」。
 */
class CachedAiExecutorTest {

    private val delegate: AiExecutor = mockk()
    private val cacheRepository: ExplanationCacheRepository = mockk()
    private val executor = CachedAiExecutor(delegate, cacheRepository)

    // ---- 命中路径 ----

    @Test
    fun repeatedCacheHits_keepRemoteDelegateUnused() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns
            ExplanationCacheEntity(cacheKey = "k", explanation = "cached explanation")

        val first = executor.execute(operation(), AiOperationCompletionGate())
        val second = executor.execute(operation(), AiOperationCompletionGate())

        assertEquals(AiClientResult.Success("cached explanation"), first)
        assertEquals(AiClientResult.Success("cached explanation"), second)
        coVerify(exactly = 0) { delegate.execute(any(), any()) }
    }

    /**
     * 空白正文视为未命中。
     *
     * `AiClientResult.Success.text` 对下游保证「非空且非纯空白」；直接拿库里的值构造
     * Success 会绕过该不变量，让 6.7 渲染出无提示、无重试入口的空面板。
     */
    @Test
    fun unusableCachedExplanation_treatedAsMiss() = runTest {
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Success("fresh explanation")
        coEvery { cacheRepository.insertCache(any()) } returns Unit
        listOf("   ", "").forEachIndexed { index, cached ->
            coEvery { cacheRepository.getCachedExplanation(any()) } returns
                ExplanationCacheEntity(cacheKey = "k", explanation = cached)

            val result = executor.execute(operation(), AiOperationCompletionGate())

            assertEquals("cached length=${cached.length}", AiClientResult.Success("fresh explanation"), result)
            coVerify(exactly = index + 1) { delegate.execute(any(), any()) }
        }
    }

    // ---- 未命中路径 ----

    @Test
    fun cacheMiss_callsDelegateAndWritesResult() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Success("fresh explanation")
        coEvery { cacheRepository.insertCache(any()) } returns Unit

        val result = executor.execute(operation(), AiOperationCompletionGate())

        assertEquals(AiClientResult.Success("fresh explanation"), result)
        coVerify(exactly = 1) { cacheRepository.insertCache(any()) }
    }

    @Test
    fun failureResult_isNotCached() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Failure(AiError.NoContent)

        val result = executor.execute(operation(), AiOperationCompletionGate())

        assertEquals(AiClientResult.Failure(AiError.NoContent), result)
        coVerify(exactly = 0) { cacheRepository.insertCache(any()) }
    }

    // ---- 读写使用同一个键 ----

    @Test
    fun cacheUsesPrecomputedBundleKey_withoutRecalculatingIdentity() = runTest {
        val readKey = slot<String>()
        val writtenEntity = slot<ExplanationCacheEntity>()
        coEvery { cacheRepository.getCachedExplanation(capture(readKey)) } returns null
        coEvery { delegate.execute(any(), any()) } returns
            AiClientResult.Success("fresh explanation")
        coEvery { cacheRepository.insertCache(capture(writtenEntity)) } returns Unit

        executor.execute(
            operation(semanticCacheKey = "precomputed-semantic-key"),
            AiOperationCompletionGate()
        )

        assertEquals("precomputed-semantic-key", readKey.captured)
        assertEquals("precomputed-semantic-key", writtenEntity.captured.cacheKey)
    }

    // ---- fail-open：读侧 ----

    @Test
    fun cacheReadFailure_fallsThroughToDelegate() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } throws IOException("disk full")
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Success("fresh explanation")
        coEvery { cacheRepository.insertCache(any()) } returns Unit

        val result = executor.execute(operation(), AiOperationCompletionGate())

        assertEquals(AiClientResult.Success("fresh explanation"), result)
        coVerify(exactly = 1) { delegate.execute(any(), any()) }
    }

    // ---- fail-open：写侧 ----

    @Test
    fun cacheWriteFailure_preservesRemoteSuccess() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Success("fresh explanation")
        coEvery { cacheRepository.insertCache(any()) } throws IOException("disk full")

        val result = executor.execute(operation(), AiOperationCompletionGate())

        assertEquals(
            "写缓存失败不得影响已取得的远端结果",
            AiClientResult.Success("fresh explanation"),
            result
        )
    }

    // ---- delegate 异常不被缓存层改写 ----

    @Test
    fun delegateException_isNotSwallowedByCacheLayer() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } throws IllegalStateException("boom")

        val thrown = runCatching {
            executor.execute(operation(), AiOperationCompletionGate())
        }.exceptionOrNull()

        assertTrue("远端异常应由上层边界处理，缓存层不得吞掉", thrown is IllegalStateException)
    }

    // ---- 取消传播 ----

    @Test
    fun cacheReadCancellation_propagates() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } throws
            CancellationException("cancelled")

        val thrown = runCatching {
            executor.execute(operation(), AiOperationCompletionGate())
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 0) { delegate.execute(any(), any()) }
    }

    /**
     * 远端已成功但写入期间被取消时，仍然传播取消而**不**返回成功结果。
     *
     * 这是结构化并发的正确语义：调用方已经放弃了这次请求，不应用 NonCancellable 抢救结果。
     */
    @Test
    fun cacheWriteCancellation_propagatesEvenAfterRemoteSuccess() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Success("fresh explanation")
        coEvery { cacheRepository.insertCache(any()) } throws CancellationException("cancelled")

        val thrown = runCatching {
            executor.execute(operation(), AiOperationCompletionGate())
        }.exceptionOrNull()

        assertTrue(
            "远端成功也不能压过取消语义",
            thrown is CancellationException
        )
    }

    @Test
    fun remoteCancellation_propagatesWithoutCacheWrite() = runTest {
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } throws CancellationException("cancelled")

        val thrown = runCatching {
            executor.execute(operation(), AiOperationCompletionGate())
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 0) { cacheRepository.insertCache(any()) }
    }

    // ---- 辅助方法 ----

    private fun operation(semanticCacheKey: String? = null): ResolvedAiExplanationOperation {
        val request = ResolvedAiExplanationRequest(
        profile = resolvedAiProfile(),
        normalizedModelId = "deepseek-v4-flash",
        normalizedInput = "Hello world.",
        outputLanguageTag = "zh-CN",
        explanationType = ExplanationType.SENTENCE_EXPLANATION,
        promptVersion = "sentence-context-v1",
        preparedMessages = (
            AiPromptPolicy.prepare(AiExplanationInput.Sentence("Hello world."))
                as AiPromptPreparationResult.Ready
            ).prompt.messages
        )
        return ResolvedAiExplanationOperation(
            request = request,
            semanticCacheKey = semanticCacheKey ?: ExplanationCacheIdentity.from(request).hash()
        )
    }
}
