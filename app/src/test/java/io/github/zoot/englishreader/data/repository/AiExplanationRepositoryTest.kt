package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiErrorMapper
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.ai.AiExplanationResolutionResult
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.AiPromptPolicy
import io.github.zoot.englishreader.data.ai.AiPromptPreparationResult
import io.github.zoot.englishreader.data.ai.AiTextNormalizer
import io.github.zoot.englishreader.data.ai.ExplanationCacheIdentity
import io.github.zoot.englishreader.data.ai.ExplanationType
import io.github.zoot.englishreader.data.ai.ResolvedAiExplanationOperation
import io.github.zoot.englishreader.data.ai.ResolvedAiExplanationRequest
import io.github.zoot.englishreader.data.ai.resolvedAiProfile
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiExplanationRepositoryTest {
    private val resolver: AiExplanationRequestResolver = mockk()
    private val executor: AiExecutor = mockk()

    @Test
    fun start_resolverRejection_returnsTypedErrorWithoutExecutorCall() = runTest {
        val fixture = fixture()
        try {
            coEvery { resolver.resolveActive(any()) } returns
                AiExplanationResolutionResult.Rejected(AiError.NoActiveProfile)

            val result = fixture.repository.start(AiExplanationInput.Sentence("Hello"))

            assertEquals(AiExplanationStartResult.Rejected(AiError.NoActiveProfile), result)
            coVerify(exactly = 0) { executor.execute(any(), any()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun start_overLimitArticle_rejectsBeforeProfileRegistryExecutorAndNetworkChain() = runTest {
        val profileRepository: AiProfileRepository = mockk()
        val registry: AiExplanationOperationRegistry = mockk()
        val realResolver = AiExplanationRequestResolver(
            profileRepository = profileRepository,
            errorMapper = AiErrorMapper(isOnline = { true })
        )
        val repository = AiExplanationRepository(realResolver, executor, registry)

        val result = repository.start(
            AiExplanationInput.Article(
                "x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1)
            )
        )

        assertEquals(
            AiExplanationStartResult.Rejected(
                AiError.InputTooLong(
                    actualChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1,
                    maxChars = ImportBudget.MAX_FULL_EXPLANATION_CHARS
                )
            ),
            result
        )
        coVerify(exactly = 0) { profileRepository.resolveValidatedActiveProfile(any()) }
        coVerify(exactly = 0) { registry.attachOrStart(any(), any()) }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test
    fun start_concurrentSameKey_reusesOperationAndExecutesOnce() = runTest {
        val fixture = fixture()
        val operation = operation("same-key")
        val release = CompletableDeferred<Unit>()
        try {
            coEvery { resolver.resolveActive(any()) } returns
                AiExplanationResolutionResult.Ready(operation)
            coEvery { executor.execute(operation, any()) } coAnswers {
                release.await()
                AiClientResult.Success("result")
            }

            val first = fixture.repository.start(AiExplanationInput.Sentence("Hello")).requireStarted()
            runCurrent()
            val second = fixture.repository.start(AiExplanationInput.Sentence("Hello")).requireStarted()

            assertEquals(first.handle.ref, second.handle.ref)
            coVerify(exactly = 1) { executor.execute(operation, any()) }
            release.complete(Unit)
            advanceUntilIdle()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun start_differentKeys_createIndependentOperations() = runTest {
        val fixture = fixture()
        val firstOperation = operation("key-a")
        val secondOperation = operation("key-b")
        try {
            coEvery { resolver.resolveActive(AiExplanationInput.Sentence("first")) } returns
                AiExplanationResolutionResult.Ready(firstOperation)
            coEvery { resolver.resolveActive(AiExplanationInput.Sentence("second")) } returns
                AiExplanationResolutionResult.Ready(secondOperation)
            coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("result")

            val first = fixture.repository.start(
                AiExplanationInput.Sentence("first")
            ).requireStarted()
            val second = fixture.repository.start(
                AiExplanationInput.Sentence("second")
            ).requireStarted()
            advanceUntilIdle()

            assertNotEquals(first.handle.ref, second.handle.ref)
            coVerify(exactly = 2) { executor.execute(any(), any()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun start_executorFailure_survivesHandleObservation() = runTest {
        val fixture = fixture()
        val operation = operation("key-a")
        try {
            coEvery { resolver.resolveActive(any()) } returns
                AiExplanationResolutionResult.Ready(operation)
            coEvery { executor.execute(operation, any()) } returns
                AiClientResult.Failure(AiError.TlsFailure)

            val started = fixture.repository.start(
                AiExplanationInput.Sentence("Hello")
            ).requireStarted()
            advanceUntilIdle()

            assertEquals(
                AiOperationOutcome.Failure(AiError.TlsFailure),
                started.handle.awaitOutcome()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun start_unexpectedPreRegistrationException_mapsToUnknownWithoutDetail() = runTest {
        val fixture = fixture()
        try {
            coEvery { resolver.resolveActive(any()) } throws
                IllegalStateException("private endpoint")

            val result = fixture.repository.start(AiExplanationInput.Sentence("private input"))

            assertEquals(AiExplanationStartResult.Rejected(AiError.Unknown), result)
            assertFalse(result.toString().contains("private endpoint"))
            assertFalse(result.toString().contains("private input"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun start_registryEntryException_mapsToUnknownWithoutLeakingDetail() = runTest {
        val registry: AiExplanationOperationRegistry = mockk()
        val operation = operation("semantic-key")
        coEvery { resolver.resolveActive(any()) } returns
            AiExplanationResolutionResult.Ready(operation)
        coEvery { registry.attachOrStart(any(), any()) } throws
            IllegalStateException("private registry detail")
        val repository = AiExplanationRepository(resolver, executor, registry)

        val result = repository.start(AiExplanationInput.Sentence("private input"))

        assertEquals(AiExplanationStartResult.Rejected(AiError.Unknown), result)
        assertFalse(result.toString().contains("private registry detail"))
        assertFalse(result.toString().contains("private input"))
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test
    fun start_preRegistrationCancellation_propagates() = runTest {
        val fixture = fixture()
        try {
            coEvery { resolver.resolveActive(any()) } throws CancellationException("cancelled")

            val thrown = runCatching {
                fixture.repository.start(AiExplanationInput.Sentence("Hello"))
            }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun startedResult_toString_redactsHandleAndSemanticKey() = runTest {
        val fixture = fixture()
        val operation = operation("semantic-secret")
        try {
            coEvery { resolver.resolveActive(any()) } returns
                AiExplanationResolutionResult.Ready(operation)
            coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("secret text")

            val result = fixture.repository.start(AiExplanationInput.Sentence("Hello"))

            assertFalse(result.toString().contains("semantic-secret"))
            assertFalse(result.toString().contains("secret text"))
        } finally {
            fixture.close()
        }
    }

    private fun TestScope.fixture(): Fixture {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val registry = AiExplanationOperationRegistry(scope)
        return Fixture(scope, AiExplanationRepository(resolver, executor, registry))
    }

    private fun operation(key: String): ResolvedAiExplanationOperation {
        // profile 在本文件里纯粹是走通链路所需的背景：executor 是 mock，语义键由 [key]
        // 显式传入，没有任何断言读取 profile 字段。因此用 canonical 默认值即可，
        // 需要区分的事实都在调用点写着。
        val profile = resolvedAiProfile()
        val prompt = (
            AiPromptPolicy.prepare(AiExplanationInput.Sentence("Hello")) as
                AiPromptPreparationResult.Ready
            ).prompt
        val request = ResolvedAiExplanationRequest(
            profile = profile,
            normalizedModelId = AiTextNormalizer.normalizeModelId(profile.modelId),
            normalizedInput = prompt.normalizedInput,
            outputLanguageTag = "zh-CN",
            explanationType = ExplanationType.SENTENCE_EXPLANATION,
            promptVersion = prompt.promptVersion,
            preparedMessages = prompt.messages
        )
        return ResolvedAiExplanationOperation(request, key)
    }

    private data class Fixture(
        val applicationScope: CoroutineScope,
        val repository: AiExplanationRepository
    ) {
        fun close() = applicationScope.cancel()
    }
}
