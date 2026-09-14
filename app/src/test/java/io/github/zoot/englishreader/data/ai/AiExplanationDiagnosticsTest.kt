package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiExplanationDiagnosticsTest {

    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @Test
    fun cachedReadFailure_logsSafeStageWithoutExceptionDetails() = runTest {
        val cacheRepository = mockk<ExplanationCacheRepository>()
        val delegate = mockk<AiExecutor>()
        coEvery { cacheRepository.getCachedExplanation(any()) } throws
            IllegalStateException(PRIVATE_MARKER)
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Failure(AiError.Offline)

        CachedAiExecutor(delegate, cacheRepository).execute(
            operation(),
            AiOperationCompletionGate()
        )

        assertSafeLog("CachedAiExecutor", "stage=cache_read category=cache_unavailable")
    }

    @Test
    fun cachedWriteFailure_logsSafeStageWithoutExceptionDetails() = runTest {
        val cacheRepository = mockk<ExplanationCacheRepository>()
        val delegate = mockk<AiExecutor>()
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { delegate.execute(any(), any()) } returns AiClientResult.Success("safe result")
        coEvery { cacheRepository.insertCache(any()) } throws IllegalStateException(PRIVATE_MARKER)

        CachedAiExecutor(delegate, cacheRepository).execute(
            operation(),
            AiOperationCompletionGate()
        )

        assertSafeLog("CachedAiExecutor", "stage=cache_write category=cache_unavailable")
    }

    @Test
    fun resolverFailure_logsMappedCategoryWithoutExceptionDetails() = runTest {
        val profileRepository = mockk<AiProfileRepository>()
        coEvery { profileRepository.resolveValidatedActiveProfile(any()) } throws
            IllegalStateException(PRIVATE_MARKER)
        val resolver = AiExplanationRequestResolver(
            profileRepository,
            AiErrorMapper(isOnline = { true })
        )

        resolver.resolveActive(AiExplanationInput.Sentence("private input"))

        assertSafeLog("AiRequestResolver", "stage=request_resolution category=unknown")
    }

    @Test
    fun repositoryFailure_logsMappedCategoryWithoutExceptionDetails() = runTest {
        val resolver = mockk<AiExplanationRequestResolver>()
        coEvery { resolver.resolveActive(any()) } throws IllegalStateException(PRIVATE_MARKER)
        val repository = AiExplanationRepository(
            requestResolver = resolver,
            executor = mockk(),
            operationRegistry = mockk<AiExplanationOperationRegistry>()
        )

        repository.start(AiExplanationInput.Sentence("private input"))

        assertSafeLog("AiExplanationRepo", "stage=request_start category=unknown")
    }

    private fun assertSafeLog(tag: String, message: String) {
        val logs = ShadowLog.getLogs()
        val rendered = logs.joinToString("\n") { "${it.tag}:${it.msg}" }
        assertTrue("expected log $tag/$message, got $rendered", rendered.contains("$tag:$message"))
        assertFalse(rendered.contains(PRIVATE_MARKER))
        assertFalse(rendered.contains("private input"))
        assertTrue("AI diagnostics must not attach throwables", logs.all { it.throwable == null })
    }

    private fun operation(): ResolvedAiExplanationOperation = mockk {
        every { semanticCacheKey } returns "safe-cache-key"
    }

    private companion object {
        const val PRIVATE_MARKER = "private-ai-exception-marker"
    }
}
