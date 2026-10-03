package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupingTime
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.contextCard
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ContextVocabularyViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val repository = mockk<VocabularyRepository>()
    private val dictionary = mockk<DictionaryRepository>()
    private val excerpts = mockk<ExcerptRepository>()
    private val cards = MutableStateFlow(listOf(contextCard()))
    private val round = MutableStateFlow<ContextReviewRound?>(null)
    private val store = ViewModelStore()
    private lateinit var viewModel: VocabularyViewModel

    @Before
    fun setUp() {
        every { repository.getAllVocabularyWithSource() } returns flowOf(emptyList())
        every { repository.observeContextCards() } returns cards
        every { repository.observeContextReview() } returns round
        every { dictionary.revision } returns MutableStateFlow(0L)
        every { excerpts.observeAll() } returns flowOf(emptyList())
        viewModel = VocabularyViewModel(repository, dictionary, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), excerpts)
        store.put("context-library", viewModel)
    }

    @After fun tearDown() { store.clear() }

    @Test
    fun confirmFailure_keepsEditedTextAndAllowsRetry_andRejectsDuplicateTap() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { repository.editContextMeaning(any(), any()) } coAnswers { pending.await() }
        viewModel.editContextMeaning(contextCard())
        viewModel.updateContextMeaning("financial institution")
        viewModel.uiEvent.test {
            viewModel.confirmContextMeaning()
            viewModel.confirmContextMeaning()
            runCurrent()
            assertTrue(viewModel.contextEditor.value!!.saving)
            coVerify(exactly = 1) { repository.editContextMeaning(any(), "financial institution") }
            pending.completeExceptionally(IllegalStateException("write failed"))
            runCurrent()
            assertEquals(VocabularyUiEvent.ContextSaveFailed, awaitItem())
            assertEquals("financial institution", viewModel.contextEditor.value!!.meaning)
            assertFalse(viewModel.contextEditor.value!!.saving)
            coEvery { repository.editContextMeaning(any(), any()) } returns true
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(VocabularyUiEvent.ContextMeaningSaved, awaitItem())
            assertNull(viewModel.contextEditor.value)
        }
    }

    @Test
    fun confirmCancelled_releasesBusyWithoutEmittingSuccessOrFailure() = runTest {
        coEvery { repository.editContextMeaning(any(), any()) } throws CancellationException()
        viewModel.editContextMeaning(contextCard())
        viewModel.uiEvent.test {
            viewModel.confirmContextMeaning()
            runCurrent()
            assertFalse(viewModel.contextEditor.value!!.saving)
            expectNoEvents()
        }
    }

    @Test
    fun oldEditorCompletion_doesNotCloseNewEditorOrEmitStaleSuccess() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { repository.editContextMeaning(any(), any()) } coAnswers { pending.await() }
        viewModel.editContextMeaning(contextCard())
        viewModel.confirmContextMeaning()
        runCurrent()
        viewModel.editContextMeaning(contextCard(2))
        viewModel.updateContextMeaning("new edit")
        viewModel.uiEvent.test {
            pending.complete(true)
            runCurrent()
            assertEquals(2L, viewModel.contextEditor.value!!.card.id)
            assertEquals("new edit", viewModel.contextEditor.value!!.meaning)
            expectNoEvents()
        }
    }

    @Test
    fun deleteFailure_isVisibleAndRetryable_andCoalescesDuplicateRequest() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { repository.deleteContextCard(1) } coAnswers { pending.await() }
        viewModel.uiEvent.test {
            viewModel.deleteContextCard(contextCard())
            viewModel.deleteContextCard(contextCard())
            runCurrent()
            coVerify(exactly = 1) { repository.deleteContextCard(1) }
            pending.completeExceptionally(IllegalStateException())
            runCurrent()
            assertEquals(VocabularyUiEvent.ContextDeleteFailed, awaitItem())
            coEvery { repository.deleteContextCard(1) } returns true
            viewModel.deleteContextCard(contextCard())
            runCurrent()
            assertEquals(VocabularyUiEvent.ContextDeleted, awaitItem())
        }
    }

    @Test
    fun sourceRequest_cancelledAfterSuspension_neverNavigatesOnLateCompletion() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { repository.hasContextSource(any()) } coAnswers { withContext(NonCancellable) { pending.await() } }
        viewModel.contextToOpen.test {
            viewModel.openContextSource(contextCard())
            runCurrent()
            viewModel.cancelContextNavigation()
            pending.complete(true)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun libraryLoadFailure_showsRetryStateAndRecovers_onExplicitRetry() = runTest {
        every { repository.observeContextCards() } returns flow { throw IllegalStateException() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.contextLibrary.collect() }
        runCurrent()
        assertTrue(viewModel.contextLibrary.value.failed)
        assertFalse(viewModel.contextLibrary.value.loading)
        every { repository.observeContextCards() } returns cards
        viewModel.retryContextLibrary()
        runCurrent()
        assertFalse(viewModel.contextLibrary.value.failed)
        assertEquals(cards.value, viewModel.contextLibrary.value.cards)
    }

    @Test
    fun libraryClockChange_recomputesDueAndExpiresPreviousDaysResumeState() = runTest {
        cards.value = listOf(contextCard().copy(dueDay = 20_001L))
        round.value = contextRound(listOf(contextEntry()), day = 20_000L)
        viewModel.updateGroupingTime(VocabularyGroupingTime(20_000L * 86_400_000L, ZoneOffset.UTC))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.contextLibrary.collect() }
        runCurrent()
        assertEquals(0, viewModel.contextLibrary.value.dueCount)
        assertTrue(viewModel.contextLibrary.value.canResume)
        viewModel.updateGroupingTime(VocabularyGroupingTime(20_001L * 86_400_000L, ZoneOffset.UTC))
        runCurrent()
        assertEquals(1, viewModel.contextLibrary.value.dueCount)
        assertFalse(viewModel.contextLibrary.value.canResume)
    }
}
