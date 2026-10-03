package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.contextEntry
import io.github.zoot.englishreader.util.contextRound
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ContextReviewViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val repository = mockk<VocabularyRepository>()
    private val store = ViewModelStore()
    private val saved = MutableStateFlow<ContextReviewRound?>(contextRound())
    private var day = 20_000L

    @Before
    fun setUp() {
        every { repository.currentReviewDay() } answers { day }
        every { repository.observeContextReview() } returns saved
        coEvery { repository.startOrResumeContextReview() } coAnswers { checkNotNull(saved.value) }
        coEvery { repository.startNextContextReview() } coAnswers { checkNotNull(saved.value) }
    }

    @After
    fun tearDown() { store.clear() }

    private fun create() = ContextReviewViewModel(repository).also { store.put("review", it) }

    @Test
    fun startNextRound_restoredCompletedRound_waitsForExplicitNextAction() = runTest {
        val entry = contextEntry(revealed = true)
        val completed = contextRound(listOf(entry.copy(item = entry.item.copy(grade = ContextGrade.REMEMBERED))))
        saved.value = completed
        val viewModel = create()
        runCurrent()
        viewModel.refreshDate()
        assertEquals(completed, viewModel.state.value.round)
        assertNull(viewModel.state.value.round!!.current())
        coVerify(exactly = 1) { repository.startOrResumeContextReview() }
        coVerify(exactly = 0) { repository.startNextContextReview() }

        val next = contextRound(listOf(contextEntry(id = 2, sessionToken = "next")), token = "next")
        coEvery { repository.startNextContextReview() } coAnswers { next.also { saved.value = it } }
        viewModel.startNextRound()
        runCurrent()
        assertEquals(next, viewModel.state.value.round)
        viewModel.startNextRound()
        coVerify(exactly = 1) { repository.startNextContextReview() }
        coVerify(exactly = 1) { repository.startOrResumeContextReview() }
    }

    @Test
    fun openContextSource_onlyCurrentRevealedItem_canValidateAndNavigateOnce() = runTest {
        saved.value = contextRound(listOf(contextEntry(), contextEntry(id = 2, revealed = true)))
        coEvery { repository.hasContextSource(any()) } returns true
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.openContextSource("item-1")
            viewModel.openContextSource("item-2")
            viewModel.openContextSource("stale-item")
            runCurrent()
            coVerify(exactly = 0) { repository.hasContextSource(any()) }
            expectNoEvents()

            val revealed = contextEntry(revealed = true)
            saved.value = contextRound(listOf(revealed, contextEntry(id = 2)))
            runCurrent()
            viewModel.openContextSource("item-1")
            runCurrent()
            val source = awaitItem()
            assertEquals(revealed.card, viewModel.consumeContextSource(source))
            assertNull(viewModel.consumeContextSource(source))
            coVerify(exactly = 1) { repository.hasContextSource(revealed.card) }
            expectNoEvents()
        }
    }

    @Test
    fun openContextSource_changedOrDeletedSource_reportsUnavailableWithoutNavigation() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.hasContextSource(any()) } returns false
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.uiEvent.test {
                viewModel.openContextSource("item-1")
                runCurrent()
                assertEquals(ContextReviewEvent.SOURCE_UNAVAILABLE, awaitItem())

                val entry = saved.value!!.current()!!
                saved.value = contextRound(listOf(entry.copy(card = entry.card.copy(articleId = null))))
                runCurrent()
                viewModel.openContextSource("item-1")
                runCurrent()
                assertEquals(ContextReviewEvent.SOURCE_UNAVAILABLE, awaitItem())
                expectNoEvents()
            }
            expectNoEvents()
        }
    }

    @Test
    fun openContextSource_readFailure_reportsUnavailableButCancellationIsSilent() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.hasContextSource(any()) } throws IOException()
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.uiEvent.test {
                viewModel.openContextSource("item-1")
                runCurrent()
                assertEquals(ContextReviewEvent.SOURCE_UNAVAILABLE, awaitItem())
                coEvery { repository.hasContextSource(any()) } throws CancellationException()
                viewModel.openContextSource("item-1")
                runCurrent()
                expectNoEvents()
            }
            expectNoEvents()
        }
    }

    @Test
    fun cancelContextNavigation_lateNonCooperativeLookup_dropsNavigationAndFeedback() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        val reply = CompletableDeferred<Boolean>()
        coEvery { repository.hasContextSource(any()) } coAnswers { withContext(NonCancellable) { reply.await() } }
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.uiEvent.test {
                viewModel.openContextSource("item-1")
                runCurrent()
                coVerify(exactly = 1) { repository.hasContextSource(any()) }
                viewModel.cancelContextNavigation()
                reply.complete(true)
                runCurrent()
                expectNoEvents()
            }
            expectNoEvents()
        }
    }

    @Test
    fun openContextSource_turnChangesDuringLookup_dropsLateFailure() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        val reply = CompletableDeferred<Boolean>()
        coEvery { repository.hasContextSource(any()) } coAnswers { withContext(NonCancellable) { reply.await() } }
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.uiEvent.test {
                viewModel.openContextSource("item-1")
                runCurrent()
                coVerify(exactly = 1) { repository.hasContextSource(any()) }
                saved.value = contextRound(listOf(contextEntry(id = 2, revealed = true)))
                runCurrent()
                reply.completeExceptionally(IOException())
                runCurrent()
                assertEquals("item-2", viewModel.state.value.round!!.current()!!.item.itemToken)
                expectNoEvents()
            }
            expectNoEvents()
        }
    }

    @Test
    fun openContextSource_ratingStarts_cancelsLookupAndRejectsSourceWhileBusy() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        val sourceReply = CompletableDeferred<Boolean>()
        val gradeReply = CompletableDeferred<Boolean>()
        coEvery { repository.hasContextSource(any()) } coAnswers { withContext(NonCancellable) { sourceReply.await() } }
        coEvery { repository.gradeContextAnswer(any(), any()) } coAnswers { gradeReply.await() }
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.openContextSource("item-1")
            runCurrent()
            viewModel.grade("item-1", ContextGrade.REMEMBERED)
            runCurrent()
            assertTrue(viewModel.state.value.busy)
            viewModel.openContextSource("item-1")
            coVerify(exactly = 1) { repository.hasContextSource(any()) }
            gradeReply.complete(true)
            sourceReply.complete(true)
            runCurrent()
            assertFalse(viewModel.state.value.busy)
            expectNoEvents()
        }
    }

    @Test
    fun consumeContextSource_cancelledOrSupersededEvent_neverNavigates() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.hasContextSource(any()) } returns true
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.openContextSource("item-1")
            runCurrent()
            val cancelled = awaitItem()
            viewModel.cancelContextNavigation()
            assertNull(viewModel.consumeContextSource(cancelled))

            viewModel.openContextSource("item-1")
            runCurrent()
            val superseded = awaitItem()
            viewModel.openContextSource("item-1")
            runCurrent()
            val current = awaitItem()
            assertNull(viewModel.consumeContextSource(superseded))
            assertEquals(current.entry.card, viewModel.consumeContextSource(current))
        }
    }

    @Test
    fun consumeContextSource_currentAnswerHiddenAfterEmission_rejectsEvent() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.hasContextSource(any()) } returns true
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.openContextSource("item-1")
            runCurrent()
            val source = awaitItem()
            saved.value = contextRound()
            runCurrent()
            assertNull(viewModel.consumeContextSource(source))
        }
    }

    @Test
    fun consumeContextSource_dayChangesAfterEmission_rejectsOldRound() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.hasContextSource(any()) } returns true
        val viewModel = create()
        runCurrent()
        viewModel.contextToOpen.test {
            viewModel.openContextSource("item-1")
            runCurrent()
            val source = awaitItem()
            val tomorrow = contextRound(listOf(contextEntry(sessionToken = "tomorrow")), day = day + 1, token = "tomorrow")
            coEvery { repository.startOrResumeContextReview() } coAnswers { tomorrow.also { saved.value = it } }
            day++
            assertNull(viewModel.consumeContextSource(source))
            runCurrent()
            assertEquals(tomorrow, viewModel.state.value.round)
            expectNoEvents()
        }
    }

    @Test
    fun openContextSource_noObserver_doesNotReplayOnReturn() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.hasContextSource(any()) } returns true
        val viewModel = create()
        runCurrent()
        viewModel.openContextSource("item-1")
        runCurrent()
        coVerify(exactly = 1) { repository.hasContextSource(any()) }
        viewModel.contextToOpen.test { expectNoEvents() }
    }

    @Test
    fun recreate_revealedStoredTurn_keepsAnswerAndRejectsStaleActions() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        create()
        runCurrent()
        val restored = create()
        runCurrent()
        assertFalse(restored.state.value.busy)
        assertTrue(restored.state.value.round!!.current()!!.item.revealed)
        restored.grade("old-item", ContextGrade.REMEMBERED)
        restored.reveal("item-1")
        coVerify(exactly = 0) { repository.gradeContextAnswer(any(), any()) }
        coVerify(exactly = 0) { repository.revealContextAnswer(any()) }
    }

    @Test
    fun reveal_inFlightDoubleTap_andGradeBeforeReveal_onlyPersistsOnce() = runTest {
        val reply = CompletableDeferred<Boolean>()
        coEvery { repository.revealContextAnswer("item-1") } coAnswers {
            reply.await().also { saved.value = contextRound(listOf(contextEntry(revealed = true))) }
        }
        val viewModel = create()
        runCurrent()
        viewModel.grade("item-1", ContextGrade.REMEMBERED)
        viewModel.reveal("item-1")
        viewModel.reveal("item-1")
        runCurrent()
        assertTrue(viewModel.state.value.busy)
        coVerify(exactly = 0) { repository.gradeContextAnswer(any(), any()) }
        reply.complete(true)
        runCurrent()
        assertTrue(viewModel.state.value.round!!.current()!!.item.revealed)
        assertFalse(viewModel.state.value.busy)
        coVerify(exactly = 1) { repository.revealContextAnswer("item-1") }
    }

    @Test
    fun grade_writeFails_keepsTurnRetryableAndShowsOneShotFeedback() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.gradeContextAnswer(any(), any()) } throws IOException()
        val viewModel = create()
        runCurrent()
        viewModel.uiEvent.test {
            viewModel.grade("item-1", ContextGrade.FORGOTTEN)
            runCurrent()
            assertEquals(ContextReviewEvent.SAVE_FAILED, awaitItem())
            assertEquals("item-1", viewModel.state.value.round!!.current()!!.item.itemToken)
            assertFalse(viewModel.state.value.busy)
            coEvery { repository.gradeContextAnswer("item-1", ContextGrade.REMEMBERED) } coAnswers {
                val entry = saved.value!!.current()!!
                saved.value = contextRound(listOf(entry.copy(item = entry.item.copy(grade = ContextGrade.REMEMBERED))))
                true
            }
            viewModel.grade("item-1", ContextGrade.REMEMBERED)
            runCurrent()
            assertNull(viewModel.state.value.round!!.current())
            expectNoEvents()
        }
    }

    @Test
    fun grade_repositoryRejectsStaleRevision_doesNotOptimisticallyAdvance() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.gradeContextAnswer(any(), any()) } returns false
        val viewModel = create()
        runCurrent()
        viewModel.uiEvent.test {
            viewModel.grade("item-1", ContextGrade.FUZZY)
            runCurrent()
            assertEquals(ContextReviewEvent.CARD_CHANGED, awaitItem())
            assertEquals("item-1", viewModel.state.value.round!!.current()!!.item.itemToken)
        }
    }

    @Test
    fun refreshDate_changesDuringLoad_reloadsNewDayWithoutShowingOldRound() = runTest {
        val oldReply = CompletableDeferred<ContextReviewRound>()
        var calls = 0
        val next = contextRound(listOf(contextEntry(sessionToken = "tomorrow")), day = day + 1, token = "tomorrow")
        coEvery { repository.startOrResumeContextReview() } coAnswers {
            if (++calls == 1) oldReply.await() else next.also { saved.value = it }
        }
        val viewModel = create()
        runCurrent()
        day++
        viewModel.refreshDate()
        runCurrent()
        assertNull(viewModel.state.value.round)
        oldReply.complete(contextRound())
        runCurrent()
        assertEquals(next, viewModel.state.value.round)
        assertFalse(viewModel.state.value.busy)
        assertEquals(2, calls)
    }

    @Test
    fun observe_failure_retryResubscribesAndRestoresStoredTurn() = runTest {
        every { repository.observeContextReview() } returns flow { throw IOException() }
        val viewModel = create()
        runCurrent()
        assertTrue(viewModel.state.value.failed)
        every { repository.observeContextReview() } returns saved
        viewModel.retry()
        runCurrent()
        assertFalse(viewModel.state.value.failed)
        assertEquals(saved.value, viewModel.state.value.round)
    }

    @Test
    fun grade_cancellation_doesNotEmitFailureOrAdvance() = runTest {
        saved.value = contextRound(listOf(contextEntry(revealed = true)))
        coEvery { repository.gradeContextAnswer(any(), any()) } throws CancellationException()
        val viewModel = create()
        runCurrent()
        viewModel.uiEvent.test {
            viewModel.grade("item-1", ContextGrade.FORGOTTEN)
            runCurrent()
            assertFalse(viewModel.state.value.busy)
            assertEquals("item-1", viewModel.state.value.round!!.current()!!.item.itemToken)
            expectNoEvents()
        }
    }
}
