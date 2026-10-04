package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.data.repository.OfflineLookupResult
import io.github.zoot.englishreader.model.ContextCardSaveResult
import io.github.zoot.englishreader.model.ContextWordSelection
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingContextViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val article = ArticleEntity(7, "Source", "Intro.\n\nBefore. Their lives shape lives.")
    private val sentence = SentenceRange(1, "Their lives shape lives.", 8, 32)
    private val firstOccurrence = ContextWordSelection(article, 1, sentence, 14, 19)
    private val secondOccurrence = firstOccurrence.copy(wordStartOffset = 26, wordEndOffset = 31)
    private val sourceCard = ContextCardEntity(
        id = 51,
        articleId = article.id,
        originArticleId = article.id,
        sourceTitle = article.title,
        contentFingerprint = ExcerptRepository.fingerprint(article.content),
        paragraphIndex = 1,
        sentenceStartOffset = 8,
        sentenceEndOffset = 32,
        sentence = sentence.text,
        targetStartOffset = 18,
        targetEndOffset = 23,
        word = "lives",
        dictionaryWord = "life",
        meaning = "n. 生命",
        createdAt = 100,
        updatedAt = 100,
        dueDay = 20_001
    )
    private val fixture = ReadingViewModelFixture()
    private val saveRequests = mutableListOf<SaveRequest>()

    @Before
    fun setUp() {
        coEvery { fixture.articleRepository.getArticleById(article.id) } returns article
        coEvery { fixture.dictionaryRepository.lookupOffline(any()) } returns null
        coEvery { fixture.dictionaryRepository.lookupOnline(any()) } returns emptyList()
        coEvery { fixture.vocabularyRepository.findContextCard(any()) } returns null
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            ContextCardSaveResult.SAVED
        }
    }

    @Test
    fun saveContextWord_secondInflectedOccurrence_directlySavesAllDictionaryMeanings() = runTest {
        coEvery { fixture.dictionaryRepository.lookupOffline("lives") } returns OfflineLookupResult(
            entry = DictionaryEntry("live", null, "v. 活着；v. 居住", null),
            inflectedForm = "lives",
            alternateEntries = listOf(DictionaryEntry("life", null, "n. 生命\nn. 生活", null))
        )
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        assertEquals("live", viewModel.wordDefinition.value?.word)
        assertEquals("lives", viewModel.wordDefinition.value?.inflectedForm)
        assertTrue(saveRequests.isEmpty())
        viewModel.contextSaved.test {
            viewModel.saveContextWord("life")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            runCurrent()
            assertEquals(listOf(SaveRequest(secondOccurrence, "life", "n. 生命\nn. 生活")), saveRequests)
            assertTrue(viewModel.contextCardSaved.value)
            assertFalse(viewModel.isSavingContextCard.value)
            expectNoEvents()
        }
    }

    @Test
    fun saveContextWord_onlyEnglishAvailable_keepsDictionaryAnswer() = runTest {
        coEvery { fixture.dictionaryRepository.lookupOffline("lives") } returns OfflineLookupResult(
            DictionaryEntry("lives", null, "", "to live\nto reside")
        )
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "to live\nto reside")), saveRequests)
        }
    }

    @Test
    fun saveContextWord_missingOrFailedDictionary_savesWithoutManualInput() = runTest {
        for (failure in listOf(null, IOException("dictionary unavailable"))) {
            coEvery { fixture.dictionaryRepository.lookupOnline("lives") } coAnswers {
                if (failure != null) throw failure
                emptyList()
            }
            val viewModel = loadedViewModel()
            selectWord(viewModel)
            assertFalse(viewModel.isLoadingDefinition.value)
            viewModel.contextSaved.test {
                viewModel.saveContextWord("lives")
                assertEquals(ContextSaveFeedback.SAVED, awaitItem())
                assertTrue(viewModel.contextCardSaved.value)
            }
        }
        assertEquals(List(2) { SaveRequest(secondOccurrence, "lives", "") }, saveRequests)
    }

    @Test
    fun saveContextWord_noSelectionOrUnknownCandidate_neverWrites() = runTest {
        val viewModel = loadedViewModel()
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            selectWord(viewModel)
            viewModel.saveContextWord("unrelated")
            viewModel.clearSelection()
            viewModel.saveContextWord("lives")
            runCurrent()
            assertTrue(saveRequests.isEmpty())
            expectNoEvents()
        }
    }

    @Test
    fun beginContextWordSelection_invalidArticleSentenceOrTarget_cannotSave() = runTest {
        val viewModel = loadedViewModel()
        val invalidSelections = listOf(
            secondOccurrence.copy(article = article.copy(id = 8)),
            secondOccurrence.copy(article = article.copy(content = "Changed.\n\nBefore. Their lives shape lives.")),
            secondOccurrence.copy(paragraphIndex = 2),
            secondOccurrence.copy(sentence = sentence.copy(text = "Their lives shape other.")),
            secondOccurrence.copy(sentence = sentence.copy(startOffset = -1, endOffset = 23)),
            secondOccurrence.copy(wordStartOffset = 0, wordEndOffset = 5),
            secondOccurrence.copy(wordStartOffset = 30, wordEndOffset = 33),
            secondOccurrence.copy(wordStartOffset = 26, wordEndOffset = 26),
            secondOccurrence.copy(wordStartOffset = 25, wordEndOffset = 26)
        )
        for (selection in invalidSelections) {
            selectWord(viewModel, selection)
            viewModel.saveContextWord("lives")
            runCurrent()
            assertNull(viewModel.wordDefinition.value)
            assertFalse(viewModel.contextCardSaved.value)
            assertTrue(saveRequests.isEmpty())
        }
    }

    @Test
    fun beginContextWordSelection_firstOccurrenceSaved_secondOccurrenceRemainsIndependent() = runTest {
        coEvery { fixture.vocabularyRepository.findContextCard(firstOccurrence) } returns
            sourceCard.copy(targetStartOffset = 6, targetEndOffset = 11)
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        assertTrue(viewModel.contextCardSaved.value)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("life")
            assertEquals(ContextSaveFeedback.ALREADY_EXISTS, awaitItem())
            assertTrue(saveRequests.isEmpty())
            selectWord(viewModel, secondOccurrence)
            assertFalse(viewModel.contextCardSaved.value)
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "")), saveRequests)
        }
    }

    @Test
    fun saveContextWord_duplicateAtWrite_reportsExistingWithoutAnotherWrite() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            ContextCardSaveResult.ALREADY_EXISTS
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            repeat(2) {
                viewModel.saveContextWord("lives")
                assertEquals(ContextSaveFeedback.ALREADY_EXISTS, awaitItem())
                runCurrent()
            }
            assertTrue(viewModel.contextCardSaved.value)
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "")), saveRequests)
            expectNoEvents()
        }
    }

    @Test
    fun saveContextWord_failure_keepsSelectionAndAllowsRetry() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            if (saveRequests.size == 1) throw IOException("storage unavailable")
            ContextCardSaveResult.SAVED
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.FAILED, awaitItem())
            runCurrent()
            assertFalse(viewModel.contextCardSaved.value)
            assertFalse(viewModel.isSavingContextCard.value)
            assertEquals("lives", viewModel.wordDefinition.value?.word)
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertEquals(List(2) { SaveRequest(secondOccurrence, "lives", "") }, saveRequests)
            assertTrue(viewModel.contextCardSaved.value)
        }
    }

    @Test
    fun saveContextWord_sourceChangedAtWrite_neverReportsSaved() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } returns ContextCardSaveResult.SOURCE_CHANGED
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.SOURCE_CHANGED, awaitItem())
            runCurrent()
            assertFalse(viewModel.contextCardSaved.value)
            assertFalse(viewModel.isSavingContextCard.value)
            expectNoEvents()
        }
    }

    @Test
    fun saveContextWord_pendingSave_repeatedTapWritesOnce() = runTest {
        val result = CompletableDeferred<ContextCardSaveResult>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            result.await()
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            runCurrent()
            assertTrue(viewModel.isSavingContextCard.value)
            viewModel.saveContextWord("lives")
            runCurrent()
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "")), saveRequests)
            expectNoEvents()
            result.complete(ContextCardSaveResult.SAVED)
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            runCurrent()
            assertFalse(viewModel.isSavingContextCard.value)
            assertTrue(viewModel.contextCardSaved.value)
        }
    }

    @Test
    fun saveContextWord_cancelled_releasesBusyWithoutSuccessOrFailure() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } throws CancellationException()
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            runCurrent()
            assertFalse(viewModel.isSavingContextCard.value)
            assertFalse(viewModel.contextCardSaved.value)
            expectNoEvents()
        }
    }

    @Test
    fun lookupContextWord_lateOldDefinition_cannotReplaceOrSaveNewPendingLookup() = runTest {
        val oldResult = CompletableDeferred<OfflineLookupResult?>()
        val newResult = CompletableDeferred<OfflineLookupResult?>()
        coEvery { fixture.dictionaryRepository.lookupOffline("lives") } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
        }
        coEvery { fixture.dictionaryRepository.lookupOffline("shape") } coAnswers { newResult.await() }
        val shape = firstOccurrence.copy(wordStartOffset = 20, wordEndOffset = 25)
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        selectWord(viewModel, shape)
        oldResult.complete(OfflineLookupResult(DictionaryEntry("life", null, "过期释义", null)))
        runCurrent()
        assertTrue(viewModel.isLoadingDefinition.value)
        assertNull(viewModel.wordDefinition.value)
        viewModel.saveContextWord("life")
        viewModel.saveContextWord("shape")
        assertTrue(saveRequests.isEmpty())
        newResult.complete(OfflineLookupResult(DictionaryEntry("shape", null, "塑造", null)))
        runCurrent()
        viewModel.contextSaved.test {
            viewModel.saveContextWord("shape")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertEquals(listOf(SaveRequest(shape, "shape", "塑造")), saveRequests)
        }
    }

    @Test
    fun beginContextWordSelection_lateOldDuplicate_cannotMarkNewOccurrence() = runTest {
        val oldResult = CompletableDeferred<ContextCardEntity?>()
        coEvery { fixture.vocabularyRepository.findContextCard(firstOccurrence) } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        selectWord(viewModel, secondOccurrence)
        viewModel.contextSaved.test {
            oldResult.complete(sourceCard.copy(targetStartOffset = 6, targetEndOffset = 11))
            runCurrent()
            assertFalse(viewModel.contextCardSaved.value)
            assertTrue(saveRequests.isEmpty())
            expectNoEvents()
        }
    }

    @Test
    fun beginContextWordSelection_lateOldMiss_cannotClearNewOccurrenceSavedBadge() = runTest {
        val oldResult = CompletableDeferred<ContextCardEntity?>()
        coEvery { fixture.vocabularyRepository.findContextCard(firstOccurrence) } coAnswers { oldResult.await() }
        coEvery { fixture.vocabularyRepository.findContextCard(secondOccurrence) } returns sourceCard
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        selectWord(viewModel, secondOccurrence)
        assertTrue(viewModel.contextCardSaved.value)
        oldResult.complete(null)
        runCurrent()
        assertTrue(viewModel.contextCardSaved.value)
    }

    @Test
    fun saveContextWord_lookupMissArrivesAfterSave_keepsSavedBadge() = runTest {
        val lookup = CompletableDeferred<ContextCardEntity?>()
        coEvery { fixture.vocabularyRepository.findContextCard(secondOccurrence) } coAnswers { lookup.await() }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            lookup.complete(null)
            runCurrent()
            assertTrue(viewModel.contextCardSaved.value)
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "")), saveRequests)
            expectNoEvents()
        }
    }

    @Test
    fun saveContextWord_lateSaveAfterNewSelection_doesNotChangeCurrentSelection() = runTest {
        val result = CompletableDeferred<ContextCardSaveResult>()
        val completed = mutableListOf<SaveRequest>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            val request = SaveRequest(firstArg(), secondArg(), thirdArg())
            result.await().also { completed += request }
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            runCurrent()
            assertTrue(viewModel.isSavingContextCard.value)
            selectWord(viewModel, secondOccurrence)
            assertFalse(viewModel.isSavingContextCard.value)
            result.complete(ContextCardSaveResult.SAVED)
            runCurrent()
            assertEquals(listOf(SaveRequest(firstOccurrence, "lives", "")), completed)
            assertFalse(viewModel.contextCardSaved.value)
            assertEquals("lives", viewModel.wordDefinition.value?.word)
            expectNoEvents()
        }
    }

    @Test
    fun saveContextWord_lateFailureAfterReselectingSameOccurrence_cannotClearNewSaveBusy() = runTest {
        val oldResult = CompletableDeferred<ContextCardSaveResult>()
        val newResult = CompletableDeferred<ContextCardSaveResult>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            if (saveRequests.size == 1) oldResult.await() else newResult.await()
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            runCurrent()
            selectWord(viewModel, firstOccurrence)
            selectWord(viewModel, secondOccurrence)
            viewModel.saveContextWord("lives")
            runCurrent()
            oldResult.completeExceptionally(IOException("late storage failure"))
            runCurrent()
            assertTrue(viewModel.isSavingContextCard.value)
            assertFalse(viewModel.contextCardSaved.value)
            expectNoEvents()
            newResult.complete(ContextCardSaveResult.SAVED)
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            runCurrent()
            assertFalse(viewModel.isSavingContextCard.value)
            assertTrue(viewModel.contextCardSaved.value)
        }
    }

    @Test
    fun loadArticle_pendingOldSave_completesWithoutAffectingNewArticle() = runTest {
        val otherArticle = article.copy(id = 8, title = "Other source")
        coEvery { fixture.articleRepository.getArticleById(8) } returns otherArticle
        val result = CompletableDeferred<ContextCardSaveResult>()
        val completed = CompletableDeferred<SaveRequest>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            val request = SaveRequest(firstArg(), secondArg(), thirdArg())
            result.await().also { completed.complete(request) }
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            runCurrent()
            viewModel.loadArticle(8)
            runCurrent()
            selectWord(viewModel, secondOccurrence.copy(article = otherArticle))
            result.complete(ContextCardSaveResult.SAVED)
            runCurrent()
            assertEquals(SaveRequest(secondOccurrence, "lives", ""), completed.await())
            assertEquals(otherArticle, viewModel.article.value)
            assertFalse(viewModel.contextCardSaved.value)
            assertFalse(viewModel.isSavingContextCard.value)
            expectNoEvents()
        }
    }

    @Test
    fun loadArticle_switchPending_rejectsSelectionFromStillVisibleOldArticle() = runTest {
        val otherArticle = article.copy(id = 8)
        val load = CompletableDeferred<ArticleEntity?>()
        coEvery { fixture.articleRepository.getArticleById(8) } coAnswers { load.await() }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.loadArticle(8)
        runCurrent()
        assertEquals(article, viewModel.article.value)
        assertTrue(viewModel.isLoadingArticle.value)
        selectWord(viewModel, secondOccurrence)
        viewModel.saveContextWord("lives")
        assertTrue(saveRequests.isEmpty())
        load.complete(otherArticle)
        runCurrent()
        selectWord(viewModel, secondOccurrence)
        viewModel.saveContextWord("lives")
        assertTrue(saveRequests.isEmpty())
        selectWord(viewModel, secondOccurrence.copy(article = otherArticle))
        viewModel.contextSaved.test {
            viewModel.saveContextWord("lives")
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertEquals(otherArticle, saveRequests.single().selection.article)
        }
    }

    @Test
    fun observeArticle_bodyChangedOutsideSentence_invalidatesOldSelection() = runTest {
        val updates = MutableStateFlow<ArticleEntity?>(article)
        every { fixture.articleRepository.observeArticle(article.id) } returns updates
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        val changed = article.copy(content = "Changed.\n\nBefore. Their lives shape lives.")
        updates.value = changed
        runCurrent()
        assertEquals(changed, viewModel.article.value)
        assertNull(viewModel.wordDefinition.value)
        assertFalse(viewModel.contextCardSaved.value)
        viewModel.saveContextWord("lives")
        selectWord(viewModel, secondOccurrence)
        viewModel.saveContextWord("lives")
        runCurrent()
        assertTrue(saveRequests.isEmpty())
    }

    @Test
    fun openReadingSession_contextCardRoute_targetsExactSecondOccurrenceAndHighlightsSnapshot() = runTest {
        coEvery { fixture.vocabularyRepository.getContextCard(sourceCard.id) } returns sourceCard
        val viewModel = fixture.create(SavedStateHandle(mapOf("contextCardId" to sourceCard.id)))
        viewModel.highlightedContextCard.test {
            assertNull(awaitItem())
            viewModel.openReadingSession(article.id)
            runCurrent()
            assertEquals(article, viewModel.article.value)
            assertEquals(ReadingAnchor(1, ReadingTextKind.ORIGINAL, 26),
                viewModel.pendingPositionTarget.value?.position?.anchor)
            assertEquals(sourceCard, awaitItem())
            assertEquals(18, viewModel.highlightedContextCard.value?.targetStartOffset)
            assertEquals(23, viewModel.highlightedContextCard.value?.targetEndOffset)
        }
    }

    @Test
    fun openReadingSession_missingOrInexactContextSource_refusesStaleOffsetsAndWordFallback() = runTest {
        val invalidSources = listOf(
            article to null,
            article to sourceCard.copy(articleId = null),
            article to sourceCard.copy(articleId = 8),
            article to sourceCard.copy(sentence = "Their lives shape other."),
            article to sourceCard.copy(word = "life"),
            article to sourceCard.copy(targetStartOffset = 17),
            article.copy(content = "Changed.\n\nBefore. Their lives shape lives.") to sourceCard
        )
        for ((loadedArticle, card) in invalidSources) {
            coEvery { fixture.articleRepository.getArticleById(article.id) } returns loadedArticle
            coEvery { fixture.vocabularyRepository.getContextCard(sourceCard.id) } returns card
            val viewModel = fixture.create(SavedStateHandle(mapOf("contextCardId" to sourceCard.id, "word" to "lives")))
            viewModel.highlightedContextCard.test {
                assertNull(awaitItem())
                viewModel.readingErrors.test {
                    viewModel.openReadingSession(article.id)
                    assertEquals(ReadingError.CONTEXT_SOURCE_UNAVAILABLE, awaitItem())
                    runCurrent()
                    assertEquals(loadedArticle, viewModel.article.value)
                    assertEquals(ReadingAnchor(textKind = ReadingTextKind.TITLE),
                        viewModel.pendingPositionTarget.value?.position?.anchor)
                    assertNull(viewModel.highlightedParagraph.value)
                    assertNull(viewModel.highlightedContextCard.value)
                }
                expectNoEvents()
            }
        }
    }

    private fun TestScope.loadedViewModel(): ReadingViewModel = fixture.create().also {
        it.loadArticle(article.id)
        runCurrent()
    }

    private fun TestScope.selectWord(
        viewModel: ReadingViewModel,
        selection: ContextWordSelection = secondOccurrence
    ) {
        viewModel.beginContextWordSelection(selection)
        viewModel.lookupContextWord(selection)
        runCurrent()
    }

    private data class SaveRequest(
        val selection: ContextWordSelection,
        val dictionaryWord: String,
        val meaning: String
    )
}
