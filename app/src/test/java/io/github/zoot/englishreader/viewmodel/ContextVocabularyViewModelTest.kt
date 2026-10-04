package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.repository.OfflineLookupResult
import io.github.zoot.englishreader.data.repository.VocabularyInsertResult
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupingTime
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.contextCard
import io.github.zoot.englishreader.util.contextEntry
import io.github.zoot.englishreader.util.contextRound
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
        every { repository.observeStudyWords() } returns cards.map { values -> values.map(StudyWord::Context) }
        every { repository.observeContextReview() } returns round
        every { dictionary.revision } returns MutableStateFlow(0L)
        every { excerpts.observeAll() } returns flowOf(emptyList())
        viewModel = VocabularyViewModel(repository, dictionary, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), excerpts)
        store.put("context-library", viewModel)
    }

    @After fun tearDown() { store.clear() }

    @Test
    fun deleteFailure_isVisibleAndRetryable_andCoalescesDuplicateRequest() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { repository.deleteStudyWord(any()) } coAnswers { pending.await() }
        viewModel.uiEvent.test {
            viewModel.deleteStudyWord(StudyWord.Context(contextCard()))
            viewModel.deleteStudyWord(StudyWord.Context(contextCard()))
            runCurrent()
            coVerify(exactly = 1) { repository.deleteStudyWord(StudyWord.Context(contextCard())) }
            pending.completeExceptionally(IllegalStateException())
            runCurrent()
            assertEquals(VocabularyUiEvent.ContextDeleteFailed, awaitItem())
            coEvery { repository.deleteStudyWord(any()) } returns true
            viewModel.deleteStudyWord(StudyWord.Context(contextCard()))
            runCurrent()
            assertEquals(VocabularyUiEvent.ContextDeleted, awaitItem())
        }
    }

    @Test
    fun sourceRequest_cancelledAfterSuspension_neverNavigatesOnLateCompletion() = runTest {
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Boolean>()
        coEvery { repository.hasContextSource(any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { pending.await() }
        }
        viewModel.contextToOpen.test {
            viewModel.openContextSource(contextCard())
            started.await()
            viewModel.cancelContextNavigation()
            pending.complete(true)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun studyDetails_missingDictionaryPhonetic_preservesSavedPhoneticWithoutMislabelingHeadword() = runTest {
        val legacy = StudyWord.Legacy(VocabularyEntity(id = 1, word = "lives", articleId = null, phonetic = "/laɪvz/"))
        val context = StudyWord.Context(contextCard().copy(word = "bank"))
        every { repository.observeStudyWords() } returns flowOf(listOf(legacy, context))
        coEvery { dictionary.lookupOffline("lives") } returns OfflineLookupResult(
            DictionaryEntry("live", "  ", "生活", null), inflectedForm = "lives"
        )
        coEvery { dictionary.lookupOffline("bank") } returns OfflineLookupResult(
            DictionaryEntry("bank", "/bæŋk/", "银行", null)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.studyDetails.collect() }
        runCurrent()

        assertEquals(setOf("vocabulary:1", "context:1"), viewModel.studyDetails.value.keys)
        assertEquals("/laɪvz/", viewModel.studyDetails.value.getValue(legacy.key).phonetic)
        assertNull(viewModel.studyDetails.value.getValue(legacy.key).headword)
        assertEquals("/bæŋk/", viewModel.studyDetails.value.getValue(context.key).phonetic)
    }

    @Test
    fun deleteStudyWord_legacy_emitsUndoSnapshotAndRestoresScheduleWithoutReusingId() = runTest {
        val legacy = VocabularyEntity(id = 8, word = "lives", articleId = null,
            studyMeaning = "生活", answerRevision = 2, dueDay = 30, lastReviewedAt = 20,
            pendingStep = 3, intervalDays = 7)
        coEvery { repository.deleteStudyWord(StudyWord.Legacy(legacy)) } returns true
        coEvery { repository.insertVocabulary(legacy.copy(id = 0)) } returns VocabularyInsertResult.Inserted(9)
        viewModel.uiEvent.test {
            viewModel.deleteStudyWord(StudyWord.Legacy(legacy))
            runCurrent()
            val event = awaitItem()
            assertEquals(VocabularyUiEvent.Deleted(legacy), event)
            viewModel.restoreVocabulary((event as VocabularyUiEvent.Deleted).vocabulary)
            runCurrent()
            coVerify(exactly = 1) { repository.insertVocabulary(legacy.copy(id = 0)) }
            expectNoEvents()
        }
    }

    @Test
    fun libraryLoadFailure_showsRetryStateAndRecovers_onExplicitRetry() = runTest {
        every { repository.observeStudyWords() } returns flow { throw IllegalStateException() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.contextLibrary.collect() }
        runCurrent()
        assertTrue(viewModel.contextLibrary.value.failed)
        assertFalse(viewModel.contextLibrary.value.loading)
        every { repository.observeStudyWords() } returns cards.map { values -> values.map(StudyWord::Context) }
        viewModel.retryContextLibrary()
        runCurrent()
        assertFalse(viewModel.contextLibrary.value.failed)
        assertEquals(cards.value.map(StudyWord::Context), viewModel.contextLibrary.value.words)
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
