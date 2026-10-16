package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiArticleDifficulty
import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerationParameters
import io.github.zoot.englishreader.data.ai.AiArticleGenerationResult
import io.github.zoot.englishreader.data.ai.AiArticleGenerator
import io.github.zoot.englishreader.data.ai.AiArticleLength
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticle
import io.github.zoot.englishreader.data.ai.AiGeneratedArticleRejection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AiArticleGenerationRepositoryTest {
    private val generator: AiArticleGenerator = mockk()
    private val draft = AiArticleGenerationDraft(
        AiArticleGenerationParameters(topic = "private topic"),
        "private profile"
    )

    @Test
    fun state_newSession_hasDefaultsAndNoImplicitRequest() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        assertEquals(AiArticleGenerationDraft(), repository.state.value.draft)
        assertEquals(AiArticleDifficulty.BEGINNER, repository.state.value.draft.parameters.difficulty)
        assertEquals(AiArticleLength.MEDIUM, repository.state.value.draft.parameters.length)
        assertFalse(repository.state.value.isGenerating)
        assertNull(repository.state.value.preview)
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun generate_concurrentClicks_acceptsOnlyOneRequest() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        repository.updateDraft(draft)
        val release = CompletableDeferred<AiArticleGenerationResult>()
        coEvery { generator.generate(any(), any()) } coAnswers { release.await() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val attempts = (1..12).map { executor.submit(Callable { repository.generate() }) }
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        assertTrue(repository.state.value.isGenerating)
        runCurrent()
        coVerify(exactly = 1) { generator.generate(draft.profileId, draft.parameters) }
        release.complete(success())
        runCurrent()
        assertEquals("First", repository.state.value.preview?.article?.title)
        assertFalse(repository.state.value.isGenerating)
    }

    @Test
    fun generate_running_preservesOldPreviewAndLocksDraftAndDiscard() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        repository.updateDraft(draft)
        coEvery { generator.generate(any(), any()) } returns success()
        assertTrue(repository.generate())
        runCurrent()
        val original = requireNotNull(repository.state.value.preview)
        val changed = draft.copy(parameters = draft.parameters.copy(topic = "new topic"), profileId = "other")
        assertTrue(repository.updateDraft(changed))
        val release = CompletableDeferred<AiArticleGenerationResult>()
        coEvery { generator.generate(any(), any()) } coAnswers { release.await() }

        assertTrue(repository.generate())
        assertFalse(repository.updateDraft(draft))
        assertFalse(repository.discardPreview(original.resultId))
        assertFalse(repository.generate())
        assertSame(original, repository.state.value.preview)
        assertEquals(changed, repository.state.value.draft)
        runCurrent()
        release.complete(
            AiArticleGenerationResult.Success(original.article.copy(title = "Second", parameters = changed.parameters))
        )
        runCurrent()
        val replacement = requireNotNull(repository.state.value.preview)
        assertNotEquals(original.resultId, replacement.resultId)
        assertEquals(changed.parameters, replacement.article.parameters)
        assertEquals(changed.profileId, replacement.profileId)
        assertEquals("Second", replacement.article.title)
    }

    @Test
    fun state_editingDraft_doesNotRelabelExistingPreview() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        repository.updateDraft(draft)
        coEvery { generator.generate(any(), any()) } returns success()
        repository.generate()
        runCurrent()
        val original = repository.state.value.preview
        repository.updateDraft(draft.copy(parameters = AiArticleGenerationParameters(AiArticleDifficulty.ADVANCED)))
        assertSame(original, repository.state.value.preview)
        assertEquals(draft.parameters, repository.state.value.preview?.article?.parameters)
    }

    @Test
    fun generate_failedRegeneration_keepsOldPreviewAndRequiresManualRetry() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        repository.updateDraft(draft)
        coEvery { generator.generate(any(), any()) } returns success()
        repository.generate()
        runCurrent()
        val original = repository.state.value.preview
        val failure = AiArticleGenerationFailure.Request(AiError.RateLimited(30))
        coEvery { generator.generate(any(), any()) } returns AiArticleGenerationResult.Failure(failure)
        repository.generate()
        val failedId = (repository.state.value.status as AiArticleGenerationStatus.Running).requestId
        runCurrent()
        assertSame(original, repository.state.value.preview)
        assertEquals(AiArticleGenerationStatus.Failed(failedId, failure), repository.state.value.status)
        runCurrent()
        coVerify(exactly = 2) { generator.generate(any(), any()) }

        coEvery { generator.generate(any(), any()) } returns success("Retry")
        assertTrue(repository.generate())
        runCurrent()
        assertEquals("Retry", repository.state.value.preview?.article?.title)
        assertEquals(AiArticleGenerationStatus.Idle, repository.state.value.status)
        coVerify(exactly = 3) { generator.generate(any(), any()) }
    }

    @Test
    fun generate_rejectedOutput_keepsOldPreview() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        coEvery { generator.generate(any(), any()) } returns success()
        repository.generate()
        runCurrent()
        val original = repository.state.value.preview
        val failure = AiArticleGenerationFailure.Output(AiGeneratedArticleRejection.TRUNCATED_RESPONSE)
        coEvery { generator.generate(any(), any()) } returns AiArticleGenerationResult.Failure(failure)
        repository.generate()
        runCurrent()
        assertSame(original, repository.state.value.preview)
        assertEquals(failure, (repository.state.value.status as AiArticleGenerationStatus.Failed).failure)
    }

    @Test
    fun state_observerLeavesAndReturns_requestContinuesWithoutResubmission() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        repository.updateDraft(draft)
        val release = CompletableDeferred<AiArticleGenerationResult>()
        coEvery { generator.generate(any(), any()) } coAnswers { release.await() }
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.state.collect { }
        }
        repository.generate()
        runCurrent()
        observer.cancel()
        runCurrent()
        assertTrue(repository.state.value.isGenerating)
        assertEquals(draft, repository.state.first().draft)
        assertFalse(repository.generate())
        release.complete(success())
        runCurrent()
        assertEquals("First", repository.state.first().preview?.article?.title)
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun generate_ownerCancelledBeforeDispatch_releasesReservationWithoutCallingGenerator() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val repository = AiArticleGenerationRepository(generator, owner)
        assertTrue(repository.generate())
        owner.cancel()
        runCurrent()
        assertTrue(repository.state.value.status is AiArticleGenerationStatus.Cancelled)
        assertFalse(repository.state.value.isGenerating)
        assertFalse(repository.generate())
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun generate_requestCancellation_isDistinctFromFailureAndAllowsNextExplicitRequest() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        coEvery { generator.generate(any(), any()) } returns success()
        repository.generate()
        runCurrent()
        val original = repository.state.value.preview
        coEvery { generator.generate(any(), any()) } throws CancellationException("private reason")
        repository.generate()
        runCurrent()
        assertTrue(repository.state.value.status is AiArticleGenerationStatus.Cancelled)
        assertSame(original, repository.state.value.preview)
        coEvery { generator.generate(any(), any()) } returns success("New")
        assertTrue(repository.generate())
        runCurrent()
        assertEquals("New", repository.state.value.preview?.article?.title)
    }

    @Test
    fun generate_wrappedCancellation_isNotConvertedToUnknownFailure() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        coEvery { generator.generate(any(), any()) } throws
            IllegalStateException("private wrapper", CancellationException("private cause"))
        repository.generate()
        runCurrent()
        assertTrue(repository.state.value.status is AiArticleGenerationStatus.Cancelled)
        assertFalse(repository.state.value.isGenerating)
        assertFalse(repository.state.value.toString().contains("private"))
    }

    @Test
    fun generate_nonCooperativeCompletionAfterOwnerCancellation_doesNotReplacePreview() = runTest {
        val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val repository = AiArticleGenerationRepository(generator, owner)
        coEvery { generator.generate(any(), any()) } returns success()
        repository.generate()
        runCurrent()
        val original = repository.state.value.preview
        val release = CompletableDeferred<Unit>()
        coEvery { generator.generate(any(), any()) } coAnswers {
            withContext(NonCancellable) { release.await() }
            success("Late")
        }
        repository.generate()
        runCurrent()
        owner.cancel()
        assertFalse(repository.generate())
        release.complete(Unit)
        runCurrent()
        assertSame(original, repository.state.value.preview)
        assertTrue(repository.state.value.status is AiArticleGenerationStatus.Cancelled)
    }

    @Test
    fun discardPreview_staleResultId_doesNotRemoveReplacement() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        coEvery { generator.generate(any(), any()) } returnsMany listOf(success(), success("New"))
        repository.generate()
        runCurrent()
        val oldId = requireNotNull(repository.state.value.preview).resultId
        repository.generate()
        runCurrent()
        val newId = requireNotNull(repository.state.value.preview).resultId
        assertFalse(repository.discardPreview(oldId))
        assertEquals(newId, repository.state.value.preview?.resultId)
        assertTrue(repository.discardPreview(newId))
        assertNull(repository.state.value.preview)
    }

    @Test
    fun state_freshRepository_doesNotRestorePreviewOrRestartRequest() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        coEvery { generator.generate(any(), any()) } returns success()
        repository.updateDraft(draft)
        repository.generate()
        runCurrent()
        val fresh = AiArticleGenerationRepository(generator, backgroundScope)
        assertEquals(AiArticleGenerationState(), fresh.state.value)
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun state_successAndFailureDiagnostics_redactAllSensitiveContent() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope)
        repository.updateDraft(draft)
        val success = success("private title")
        coEvery { generator.generate(any(), any()) } returns success
        repository.generate()
        runCurrent()
        listOf(repository.state.value.toString(), success.toString()).forEach { diagnostic ->
            listOf("private profile", "private topic", "private title", "private article").forEach {
                assertFalse(diagnostic.contains(it))
            }
        }
        coEvery { generator.generate(any(), any()) } throws IllegalStateException("private exception")
        repository.generate()
        runCurrent()
        assertFalse(repository.state.value.toString().contains("private exception"))
        assertEquals(
            AiArticleGenerationFailure.Request(AiError.Unknown),
            (repository.state.value.status as AiArticleGenerationStatus.Failed).failure
        )
    }

    private fun success(title: String = "First") = AiArticleGenerationResult.Success(
        AiGeneratedArticle(title, "private article", draft.parameters, 2)
    )
}
