package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerationParameters
import io.github.zoot.englishreader.data.ai.AiArticleGenerationResult
import io.github.zoot.englishreader.data.ai.AiArticleGenerator
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticle
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AiArticleGenerationSaveTest {
    private val generator = mockk<AiArticleGenerator>()
    private val articleRepository = mockk<ArticleRepository>()
    private val article = AiGeneratedArticle(
        title = "Private generated title",
        content = "Private first paragraph.\n\nPrivate second paragraph.",
        parameters = AiArticleGenerationParameters(topic = "private topic"),
        wordCount = 8
    )

    @Before
    fun setUp() {
        coEvery { generator.generate(any(), any()) } returns AiArticleGenerationResult.Success(article)
        coEvery { articleRepository.insertArticle(any()) } returns 77L
        coEvery { articleRepository.getArticleById(any()) } answers {
            ArticleEntity(id = firstArg(), title = article.title, content = article.content)
        }
    }

    @Test
    fun savePreview_noPreviewOrWrongResultId_doesNotWrite() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articleRepository)
        assertFalse(repository.savePreview("missing"))
        assertTrue(repository.generate())
        runCurrent()
        val before = repository.state.value

        assertFalse(repository.savePreview("stale"))
        assertEquals(before, repository.state.value)
        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun savePreview_validResult_persistsOnlyArticleAndPublishesReturnedId() = runTest {
        val captured = slot<ArticleEntity>()
        coEvery { articleRepository.insertArticle(capture(captured)) } returns 123L
        val repository = generatedRepository()
        val preview = repository.state.value.preview!!

        assertTrue(repository.savePreview(preview.resultId))
        assertTrue(repository.state.value.isSaving)
        assertEquals(AiArticleSaveStatus.Saving, repository.state.value.preview!!.saveStatus)
        runCurrent()

        assertFalse(repository.state.value.isSaving)
        assertEquals(AiArticleSaveStatus.Saved(123L), repository.state.value.preview!!.saveStatus)
        assertEquals(preview.article, repository.state.value.preview!!.article)
        assertEquals(0L, captured.captured.id)
        assertEquals(article.title, captured.captured.title)
        assertEquals(article.content, captured.captured.content)
        assertNull(captured.captured.translation)
        assertNull(captured.captured.source)
        assertNull(captured.captured.lastReadAt)
        coVerify(exactly = 1) { generator.generate(any(), any()) }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun savePreview_repeatedConcurrentClicks_onlyOneWriteUntilCompletion() = runTest {
        val write = CompletableDeferred<Long>()
        coEvery { articleRepository.insertArticle(any()) } coAnswers { write.await() }
        val repository = generatedRepository()
        val resultId = repository.state.value.preview!!.resultId
        val executor = Executors.newFixedThreadPool(4)
        try {
            val clicks = List(12) { executor.submit(Callable { repository.savePreview(resultId) }) }
            assertEquals(1, clicks.count { it.get(5, TimeUnit.SECONDS) })
            runCurrent()
            assertTrue(repository.state.value.isSaving)
            assertFalse(repository.savePreview(resultId))
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
            write.complete(77L)
            runCurrent()

            assertEquals(AiArticleSaveStatus.Saved(77L), repository.state.value.preview!!.saveStatus)
            assertTrue(repository.savePreview(resultId))
            runCurrent()
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        } finally {
            write.complete(77L)
            executor.shutdownNow()
        }
    }

    @Test
    fun generateAndSave_concurrentClicks_acceptOnlyOneOperation() = runTest {
        val repository = generatedRepository()
        val resultId = repository.state.value.preview!!.resultId
        val generation = CompletableDeferred<AiArticleGenerationResult>()
        val write = CompletableDeferred<Long>()
        coEvery { generator.generate(any(), any()) } coAnswers { generation.await() }
        coEvery { articleRepository.insertArticle(any()) } coAnswers { write.await() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val attempts = List(12) { index ->
                executor.submit(Callable {
                    if (index % 2 == 0) repository.generate() else repository.savePreview(resultId)
                })
            }
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            runCurrent()
            val running = repository.state.value
            assertTrue(running.isGenerating.xor(running.isSaving))
            coVerify(exactly = if (running.isGenerating) 2 else 1) { generator.generate(any(), any()) }
            coVerify(exactly = if (running.isSaving) 1 else 0) { articleRepository.insertArticle(any()) }

            generation.complete(AiArticleGenerationResult.Success(article))
            write.complete(77L)
            runCurrent()
            assertFalse(repository.state.value.isGenerating)
            assertFalse(repository.state.value.isSaving)
        } finally {
            generation.complete(AiArticleGenerationResult.Success(article))
            write.complete(77L)
            executor.shutdownNow()
        }
    }

    @Test
    fun savePreview_generationPending_rejectsOldPreviewSaveUntilGenerationFails() = runTest {
        val repository = generatedRepository()
        val preview = repository.state.value.preview!!
        val generation = CompletableDeferred<AiArticleGenerationResult>()
        coEvery { generator.generate(any(), any()) } coAnswers { generation.await() }

        assertTrue(repository.generate())
        assertFalse(repository.savePreview(preview.resultId))
        runCurrent()
        assertTrue(repository.state.value.isGenerating)
        assertFalse(repository.savePreview(preview.resultId))
        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
        generation.complete(AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(AiError.Offline)))
        runCurrent()

        assertEquals(preview, repository.state.value.preview)
        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        assertEquals(AiArticleSaveStatus.Saved(77L), repository.state.value.preview!!.saveStatus)
    }

    @Test
    fun savePreview_writePending_locksGenerationDraftAndDiscardThenUnlocks() = runTest {
        val write = CompletableDeferred<Long>()
        coEvery { articleRepository.insertArticle(any()) } coAnswers { write.await() }
        val repository = generatedRepository()
        val before = repository.state.value
        val preview = before.preview!!
        val nextDraft = before.draft.copy(profileId = "changed-profile")
        try {
            assertTrue(repository.savePreview(preview.resultId))
            assertFalse(repository.generate())
            assertFalse(repository.updateDraft(nextDraft))
            assertFalse(repository.discardPreview(preview.resultId))
            runCurrent()
            assertFalse(repository.generate())
            assertFalse(repository.updateDraft(nextDraft))
            assertFalse(repository.discardPreview(preview.resultId))
            assertEquals(before.draft, repository.state.value.draft)
            assertEquals(preview.article, repository.state.value.preview!!.article)
            coVerify(exactly = 1) { generator.generate(any(), any()) }

            write.complete(77L)
            runCurrent()
            assertTrue(repository.updateDraft(nextDraft))
            assertTrue(repository.discardPreview(preview.resultId))
            assertTrue(repository.generate())
            runCurrent()
            assertNotNull(repository.state.value.preview)
        } finally {
            write.complete(77L)
        }
    }

    @Test
    fun savePreview_failedNavigationOrChangedDraft_reusesSavedIdWithoutWriting() = runTest {
        val repository = generatedRepository()
        val resultId = repository.state.value.preview!!.resultId
        assertTrue(repository.savePreview(resultId))
        runCurrent()
        val savedPreview = repository.state.value.preview
        assertTrue(repository.updateDraft(AiArticleGenerationDraft(profileId = "other-profile")))

        repeat(3) {
            assertTrue(repository.savePreview(resultId))
            runCurrent()
        }

        assertEquals(savedPreview, repository.state.value.preview)
        assertEquals(AiArticleSaveStatus.Saved(77L), repository.state.value.preview!!.saveStatus)
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun savePreview_deletedSavedArticle_reinsertsOnceAfterLookupWithoutRegeneration() = runTest {
        val repository = generatedRepository()
        val preview = repository.state.value.preview!!
        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        val lookup = CompletableDeferred<ArticleEntity?>()
        val written = slot<ArticleEntity>()
        coEvery { articleRepository.getArticleById(77L) } coAnswers { lookup.await() }
        coEvery { articleRepository.insertArticle(capture(written)) } returns 88L

        assertTrue(repository.savePreview(preview.resultId))
        assertFalse(repository.savePreview(preview.resultId))
        runCurrent()
        assertTrue(repository.state.value.isSaving)
        assertFalse(repository.generate())
        assertFalse(repository.discardPreview(preview.resultId))
        assertFalse(repository.updateDraft(AiArticleGenerationDraft(profileId = "changed")))
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        lookup.complete(null)
        runCurrent()

        assertEquals(AiArticleSaveStatus.Saved(88L), repository.state.value.preview!!.saveStatus)
        assertEquals(preview.resultId, repository.state.value.preview!!.resultId)
        assertEquals(preview.article.content, written.captured.content)
        assertEquals(preview.article.title, written.captured.title)
        assertEquals(0L, written.captured.id)
        coVerify(exactly = 1) { articleRepository.getArticleById(77L) }
        coVerify(exactly = 2) { articleRepository.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun savePreview_lookupFailure_preservesSavedIdAndRetriesLookupWithoutWriting() = runTest {
        val repository = generatedRepository()
        val preview = repository.state.value.preview!!
        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        coEvery { articleRepository.getArticleById(77L) } throws IllegalStateException("private SQL content")

        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        assertFalse(repository.state.value.isSaving)
        assertEquals(AiArticleSaveStatus.Saved(77L, AiArticleLookupFailure.FAILED), repository.state.value.preview!!.saveStatus)
        assertFalse(repository.state.value.toString().contains("private SQL content"))
        coVerify(exactly = 1) { articleRepository.getArticleById(77L) }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        coEvery { articleRepository.getArticleById(77L) } returns ArticleEntity(77L, article.title, article.content)
        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        assertEquals(AiArticleSaveStatus.Saved(77L), repository.state.value.preview!!.saveStatus)
        coVerify(exactly = 2) { articleRepository.getArticleById(77L) }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun savePreview_lookupWrappedCancellation_preservesSavedIdWithoutWriting() = runTest {
        val repository = generatedRepository()
        val id = repository.state.value.preview!!.resultId
        assertTrue(repository.savePreview(id))
        runCurrent()
        coEvery { articleRepository.getArticleById(77L) } throws
            IllegalStateException("private SQL content", CancellationException("cancelled"))

        assertTrue(repository.savePreview(id))
        runCurrent()
        assertFalse(repository.state.value.isSaving)
        assertEquals(AiArticleSaveStatus.Saved(77L, AiArticleLookupFailure.CANCELLED), repository.state.value.preview!!.saveStatus)
        coVerify(exactly = 1) { articleRepository.getArticleById(77L) }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun savePreview_lookupOwnerCancelledBeforeLaunch_preservesSavedId() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = generatedRepository(owner)
        val id = repository.state.value.preview!!.resultId
        try {
            assertTrue(repository.savePreview(id))
            runCurrent()
            assertTrue(repository.savePreview(id))
            assertTrue(repository.state.value.isSaving)
            owner.cancel()
            runCurrent()
            assertFalse(repository.state.value.isSaving)
            assertEquals(AiArticleSaveStatus.Saved(77L, AiArticleLookupFailure.CANCELLED), repository.state.value.preview!!.saveStatus)
            coVerify(exactly = 0) { articleRepository.getArticleById(any()) }
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun savePreview_missingArticleWriteFails_retriesLocalInsertWithoutStaleId() = runTest {
        val repository = generatedRepository()
        val id = repository.state.value.preview!!.resultId
        assertTrue(repository.savePreview(id))
        runCurrent()
        coEvery { articleRepository.getArticleById(77L) } returns null
        coEvery { articleRepository.insertArticle(any()) } throws IllegalStateException("private SQL content")

        assertTrue(repository.savePreview(id))
        runCurrent()
        assertEquals(AiArticleSaveStatus.Failed, repository.state.value.preview!!.saveStatus)
        coEvery { articleRepository.insertArticle(any()) } returns 88L
        assertTrue(repository.savePreview(id))
        runCurrent()

        assertEquals(AiArticleSaveStatus.Saved(88L), repository.state.value.preview!!.saveStatus)
        coVerify(exactly = 1) { articleRepository.getArticleById(77L) }
        coVerify(exactly = 3) { articleRepository.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun savePreview_cancelledLookupReturnsMissing_preservesSavedIdWithoutInserting() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = generatedRepository(owner)
        val id = repository.state.value.preview!!.resultId
        try {
            assertTrue(repository.savePreview(id))
            runCurrent()
            coEvery { articleRepository.getArticleById(77L) } coAnswers {
                currentCoroutineContext().cancel()
                null
            }
            assertTrue(repository.savePreview(id))
            runCurrent()
            assertEquals(AiArticleSaveStatus.Saved(77L, AiArticleLookupFailure.CANCELLED), repository.state.value.preview!!.saveStatus)
            assertFalse(repository.state.value.isSaving)
            coVerify(exactly = 1) { articleRepository.getArticleById(77L) }
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun savePreview_storageFailure_keepsPreviewAndRetriesOnlyLocalWriteOnDemand() = runTest {
        coEvery { articleRepository.insertArticle(any()) } throws IllegalStateException("private SQL content")
        val repository = generatedRepository()
        val before = repository.state.value
        val preview = before.preview!!
        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        advanceUntilIdle()

        assertEquals(AiArticleSaveStatus.Failed, repository.state.value.preview!!.saveStatus)
        assertFalse(repository.state.value.isSaving)
        assertEquals(before.draft, repository.state.value.draft)
        assertEquals(preview.article, repository.state.value.preview!!.article)
        assertEquals(preview.resultId, repository.state.value.preview!!.resultId)
        assertFalse(repository.state.value.toString().contains("private SQL content"))
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }

        coEvery { articleRepository.insertArticle(any()) } returns 88L
        assertTrue(repository.savePreview(preview.resultId))
        runCurrent()
        assertEquals(AiArticleSaveStatus.Saved(88L), repository.state.value.preview!!.saveStatus)
        coVerify(exactly = 2) { articleRepository.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun generate_savedPreviewAndFailedRegeneration_keepsOriginalSavedId() = runTest {
        val repository = generatedRepository()
        val resultId = repository.state.value.preview!!.resultId
        assertTrue(repository.savePreview(resultId))
        runCurrent()
        val savedPreview = repository.state.value.preview
        coEvery { generator.generate(any(), any()) } returns
            AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(AiError.Offline))

        assertTrue(repository.generate())
        runCurrent()
        assertEquals(savedPreview, repository.state.value.preview)
        assertTrue(repository.savePreview(resultId))
        runCurrent()
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun generate_sameContentWithNewResultIdentity_hasIndependentSaveAndRejectsOldId() = runTest {
        val repository = generatedRepository()
        val oldId = repository.state.value.preview!!.resultId
        assertTrue(repository.savePreview(oldId))
        runCurrent()
        assertTrue(repository.generate())
        runCurrent()
        val fresh = repository.state.value.preview!!

        assertFalse(oldId == fresh.resultId)
        assertEquals(AiArticleSaveStatus.NotSaved, fresh.saveStatus)
        assertFalse(repository.savePreview(oldId))
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        coEvery { articleRepository.insertArticle(any()) } returns 88L
        assertTrue(repository.savePreview(fresh.resultId))
        runCurrent()
        assertEquals(AiArticleSaveStatus.Saved(88L), repository.state.value.preview!!.saveStatus)
        coVerify(exactly = 2) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun discardPreview_savedResult_dropsOnlyMemoryAndRejectsStaleSave() = runTest {
        val repository = generatedRepository()
        val resultId = repository.state.value.preview!!.resultId
        assertTrue(repository.savePreview(resultId))
        runCurrent()

        assertTrue(repository.discardPreview(resultId))
        assertNull(repository.state.value.preview)
        assertFalse(repository.savePreview(resultId))
        coVerify(exactly = 0) { articleRepository.deleteArticle(any()) }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun savePreview_observerAndCallerLeave_writeContinuesAndReattachmentSeesSavedId() = runTest {
        val write = CompletableDeferred<Long>()
        coEvery { articleRepository.insertArticle(any()) } coAnswers { write.await() }
        val repository = generatedRepository()
        val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.state.collect() }
        val caller = backgroundScope.launch {
            assertTrue(repository.savePreview(repository.state.value.preview!!.resultId))
        }
        try {
            runCurrent()
            observer.cancel()
            caller.cancel()
            assertTrue(repository.state.value.isSaving)

            write.complete(77L)
            runCurrent()
            var reattached: AiArticleGenerationState? = null
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                repository.state.collect { reattached = it }
            }
            assertEquals(AiArticleSaveStatus.Saved(77L), reattached!!.preview!!.saveStatus)
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        } finally {
            write.complete(77L)
        }
    }

    @Test
    fun savePreview_ownerCancelledBeforeLaunch_clearsSavingWithoutWrite() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = generatedRepository(owner)
        val preview = repository.state.value.preview!!
        try {
            assertTrue(repository.savePreview(preview.resultId))
            owner.cancel()
            runCurrent()

            assertFalse(repository.state.value.isSaving)
            assertEquals(AiArticleSaveStatus.Cancelled, repository.state.value.preview!!.saveStatus)
            assertEquals(preview.article, repository.state.value.preview!!.article)
            assertFalse(repository.savePreview(preview.resultId))
            assertTrue(repository.discardPreview(preview.resultId))
            coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun savePreview_ownerAlreadyCancelled_doesNotChangePreviewOrWrite() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = generatedRepository(owner)
        owner.cancel()
        val before = repository.state.value

        assertFalse(repository.savePreview(before.preview!!.resultId))
        runCurrent()
        assertEquals(before, repository.state.value)
        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun savePreview_cancelDuringCommit_keepsReturnedIdAndPropagatesCancellation() = runTest {
        val ownerJob = SupervisorJob()
        val owner = CoroutineScope(ownerJob + StandardTestDispatcher(testScheduler))
        val write = CompletableDeferred<Long>()
        coEvery { articleRepository.insertArticle(any()) } coAnswers { write.await() }
        val repository = generatedRepository(owner)
        val resultId = repository.state.value.preview!!.resultId
        try {
            assertTrue(repository.savePreview(resultId))
            runCurrent()
            val saveJob = ownerJob.children.single()
            owner.cancel()
            runCurrent()
            assertTrue(repository.state.value.isSaving)
            assertFalse(saveJob.isCompleted)
            assertFalse(repository.savePreview(resultId))
            assertFalse(repository.discardPreview(resultId))

            write.complete(77L)
            runCurrent()
            assertTrue(saveJob.isCancelled)
            assertTrue(saveJob.isCompleted)
            assertFalse(repository.state.value.isSaving)
            assertEquals(AiArticleSaveStatus.Saved(77L), repository.state.value.preview!!.saveStatus)
            assertFalse(repository.savePreview(resultId))
            coVerify(exactly = 0) { articleRepository.getArticleById(any()) }
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        } finally {
            write.complete(77L)
            owner.cancel()
            runCurrent()
        }
    }

    @Test
    fun savePreview_cancelAtInsertReturn_doesNotLoseCommittedId() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = generatedRepository(owner)
        coEvery { articleRepository.insertArticle(any()) } coAnswers {
            owner.cancel()
            77L
        }
        val resultId = repository.state.value.preview!!.resultId
        try {
            assertTrue(repository.savePreview(resultId))
            runCurrent()

            assertEquals(AiArticleSaveStatus.Saved(77L), repository.state.value.preview!!.saveStatus)
            assertFalse(repository.savePreview(resultId))
            runCurrent()
            coVerify(exactly = 0) { articleRepository.getArticleById(any()) }
            coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun savePreview_directOrWrappedCancellation_propagatesAndNeverBecomesStorageFailure() = runTest {
        val cancellation = CancellationException("private cancellation")
        for (failure in listOf(cancellation, IllegalStateException("private wrapper", cancellation))) {
            var saveJob: Job? = null
            val ownerJob = SupervisorJob()
            val owner = CoroutineScope(ownerJob + StandardTestDispatcher(testScheduler))
            coEvery { articleRepository.insertArticle(any()) } coAnswers {
                saveJob = ownerJob.children.single()
                assertTrue(currentCoroutineContext()[Job]!!.isActive)
                throw failure
            }
            val repository = generatedRepository(owner)
            val preview = repository.state.value.preview!!
            try {
                assertTrue(repository.savePreview(preview.resultId))
                runCurrent()
                assertTrue(saveJob!!.isCancelled)
                assertEquals(AiArticleSaveStatus.Cancelled, repository.state.value.preview!!.saveStatus)
                assertFalse(repository.state.value.isSaving)
                assertEquals(preview.article, repository.state.value.preview!!.article)
                assertTrue(repository.updateDraft(AiArticleGenerationDraft()))
            } finally {
                owner.cancel()
            }
        }
    }

    @Test
    fun savePreview_stateAndCompletion_redactContentAndProfile() = runTest {
        val repository = generatedRepository()
        val resultId = repository.state.value.preview!!.resultId
        val states = mutableListOf(repository.state.value.toString())
        assertTrue(repository.savePreview(resultId))
        states += repository.state.value.toString()
        runCurrent()
        states += repository.state.value.toString()

        for (rendered in states) {
            for (secret in listOf(article.title, article.content, article.parameters.topic, "private-profile")) {
                assertFalse(rendered.contains(secret))
            }
        }
    }

    private fun TestScope.generatedRepository(owner: CoroutineScope = backgroundScope): AiArticleGenerationRepository {
        val repository = AiArticleGenerationRepository(generator, owner, articleRepository)
        assertTrue(repository.updateDraft(AiArticleGenerationDraft(profileId = "private-profile")))
        assertTrue(repository.generate())
        runCurrent()
        assertNotNull(repository.state.value.preview)
        return repository
    }
}
