package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.ai.AiExplanationResolutionResult
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.AiPromptPolicy
import io.github.zoot.englishreader.data.ai.AiPromptPreparationResult
import io.github.zoot.englishreader.data.ai.CachedAiExecutor
import io.github.zoot.englishreader.data.ai.ExplanationType
import io.github.zoot.englishreader.data.ai.ResolvedAiExplanationOperation
import io.github.zoot.englishreader.data.ai.ResolvedAiExplanationRequest
import io.github.zoot.englishreader.data.ai.resolvedAiProfile
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiExplanationCancellationRaceTest {

    @Test
    fun cancel_afterRemoteSuccessBeforeCommitClaim_winsAndSkipsCacheWrite() = runTest {
        val beforeCommit = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val fixture = fixture(beforeCacheCommit = {
            beforeCommit.complete(Unit)
            releaseCommit.await()
        })
        try {
            coEvery { fixture.cacheRepository.getCachedExplanation(any()) } returns null
            coEvery { fixture.remote.execute(any(), any()) } returns
                AiClientResult.Success("remote success")

            val started = fixture.repository.start(
                AiExplanationInput.Sentence("Hello")
            ).requireStarted()
            runCurrent()
            beforeCommit.await()

            assertTrue(fixture.registry.cancel(started.handle.ref))
            releaseCommit.complete(Unit)
            advanceUntilIdle()

            assertEquals(AiOperationOutcome.Cancelled, started.handle.awaitOutcome())
            coVerify(exactly = 0) { fixture.cacheRepository.insertCache(any()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_duringCacheCommit_losesAndCommittedSuccessIsPublished() = runTest {
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        val fixture = fixture()
        try {
            coEvery { fixture.cacheRepository.getCachedExplanation(any()) } returns null
            coEvery { fixture.remote.execute(any(), any()) } returns
                AiClientResult.Success("remote success")
            coEvery { fixture.cacheRepository.insertCache(any()) } coAnswers {
                insertStarted.complete(Unit)
                releaseInsert.await()
            }

            val started = fixture.repository.start(
                AiExplanationInput.Sentence("Hello")
            ).requireStarted()
            runCurrent()
            insertStarted.await()

            assertFalse(fixture.registry.cancel(started.handle.ref))
            releaseInsert.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                AiOperationOutcome.Success("remote success"),
                started.handle.awaitOutcome()
            )
            coVerify(exactly = 1) { fixture.cacheRepository.insertCache(any()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_whileRemoteIsRunning_winsAndNeverStartsCommit() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val fixture = fixture()
        try {
            coEvery { fixture.cacheRepository.getCachedExplanation(any()) } returns null
            coEvery { fixture.remote.execute(any(), any()) } coAnswers {
                remoteStarted.complete(Unit)
                releaseRemote.await()
                AiClientResult.Success("late success")
            }

            val started = fixture.repository.start(
                AiExplanationInput.Sentence("Hello")
            ).requireStarted()
            runCurrent()
            remoteStarted.await()

            assertTrue(fixture.registry.cancel(started.handle.ref))
            releaseRemote.complete(Unit)
            advanceUntilIdle()

            assertEquals(AiOperationOutcome.Cancelled, started.handle.awaitOutcome())
            coVerify(exactly = 0) { fixture.cacheRepository.insertCache(any()) }
        } finally {
            fixture.close()
        }
    }

    private fun TestScope.fixture(
        beforeCacheCommit: suspend () -> Unit = {}
    ): Fixture {
        val resolver: AiExplanationRequestResolver = mockk()
        val remote: AiExecutor = mockk()
        val cacheRepository: ExplanationCacheRepository = mockk()
        val operation = operation()
        coEvery { resolver.resolveActive(any()) } returns
            AiExplanationResolutionResult.Ready(operation)
        val executor = CachedAiExecutor(remote, cacheRepository, beforeCacheCommit)
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val registry = AiExplanationOperationRegistry(applicationScope)
        return Fixture(
            applicationScope,
            registry,
            remote,
            cacheRepository,
            AiExplanationRepository(resolver, executor, registry)
        )
    }

    private fun operation(): ResolvedAiExplanationOperation {
        // 本文件验的是 remote / cache commit / cancel 三者的胜负关系，profile 只是背景：
        // remote 是 mock，语义键写死为 "semantic-key"，没有断言读取 profile 字段。
        // 用 canonical 默认值，避免与 data/ai 组维护两套不同的 boilerplate。
        val profile = resolvedAiProfile()
        val prompt = (
            AiPromptPolicy.prepare(AiExplanationInput.Sentence("Hello")) as
                AiPromptPreparationResult.Ready
            ).prompt
        val request = ResolvedAiExplanationRequest(
            profile,
            profile.modelId,
            prompt.normalizedInput,
            "zh-CN",
            ExplanationType.SENTENCE_EXPLANATION,
            prompt.promptVersion,
            prompt.messages
        )
        return ResolvedAiExplanationOperation(request, "semantic-key")
    }

    private data class Fixture(
        val applicationScope: CoroutineScope,
        val registry: AiExplanationOperationRegistry,
        val remote: AiExecutor,
        val cacheRepository: ExplanationCacheRepository,
        val repository: AiExplanationRepository
    ) {
        fun close() = applicationScope.cancel()
    }
}
