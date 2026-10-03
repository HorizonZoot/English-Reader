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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun requestContextMeaning_secondInflectedOccurrence_preservesSourceAndUsesChosenDictionaryCandidate() = runTest {
        coEvery { fixture.dictionaryRepository.lookupOffline("lives") } returns OfflineLookupResult(
            entry = DictionaryEntry("live", null, "v. 活着；v. 居住", null),
            inflectedForm = "lives",
            alternateEntries = listOf(DictionaryEntry("life", null, "n. 生命", null))
        )
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        assertEquals("live", viewModel.wordDefinition.value?.word)
        assertEquals("lives", viewModel.wordDefinition.value?.inflectedForm)
        viewModel.requestContextMeaning("life")
        val draft = requireNotNull(viewModel.contextMeaningDraft.value)
        assertEquals(secondOccurrence, draft.selection)
        assertEquals(listOf("n. 生命"), draft.choices)
        assertEquals("life", draft.dictionaryWord)
        assertEquals("", draft.meaning)
        assertFalse(draft.saving)
        assertTrue(saveRequests.isEmpty())

        viewModel.contextSaved.test {
            viewModel.updateContextMeaning("n. 生命")
            assertTrue(saveRequests.isEmpty())
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            runCurrent()
            assertEquals(listOf(SaveRequest(secondOccurrence, "life", "n. 生命")), saveRequests)
            assertTrue(viewModel.contextCardSaved.value)
            assertNull(viewModel.contextMeaningDraft.value)
            expectNoEvents()
        }
    }

    @Test
    fun confirmContextMeaning_blankOrDismissedDraft_neverWrites() = runTest {
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.contextSaved.test {
            viewModel.requestContextMeaning("lives")
            for (meaning in listOf("", " \t\n ")) {
                viewModel.updateContextMeaning(meaning)
                viewModel.confirmContextMeaning()
                runCurrent()
                assertEquals(meaning, viewModel.contextMeaningDraft.value?.meaning)
                assertEquals(false, viewModel.contextMeaningDraft.value?.saving)
                assertTrue(saveRequests.isEmpty())
                assertFalse(viewModel.contextCardSaved.value)
            }
            viewModel.updateContextMeaning("手填释义")
            viewModel.dismissContextMeaning()
            viewModel.confirmContextMeaning()
            runCurrent()
            assertNull(viewModel.contextMeaningDraft.value)
            assertTrue(saveRequests.isEmpty())
            expectNoEvents()
        }
    }

    @Test
    fun lookupContextWord_missingOrFailedDictionary_keepsManualMeaningAvailable() = runTest {
        for (failure in listOf(null, IOException("dictionary unavailable"))) {
            coEvery { fixture.dictionaryRepository.lookupOnline("lives") } coAnswers {
                if (failure != null) throw failure
                emptyList()
            }
            val viewModel = loadedViewModel()
            selectWord(viewModel)
            val definition = requireNotNull(viewModel.wordDefinition.value)
            assertEquals("lives", definition.word)
            assertTrue(definition.chineseDefinitions.isEmpty())
            assertTrue(definition.englishDefinitions.isEmpty())
            assertFalse(viewModel.isLoadingDefinition.value)
            viewModel.requestContextMeaning("lives")
            assertEquals(emptyList<String>(), viewModel.contextMeaningDraft.value?.choices)
            assertEquals("", viewModel.contextMeaningDraft.value?.meaning)

            viewModel.contextSaved.test {
                viewModel.updateContextMeaning("手填释义")
                viewModel.confirmContextMeaning()
                assertEquals(ContextSaveFeedback.SAVED, awaitItem())
                assertNull(viewModel.contextMeaningDraft.value)
                assertTrue(viewModel.contextCardSaved.value)
            }
        }
        assertEquals(List(2) { SaveRequest(secondOccurrence, "lives", "手填释义") }, saveRequests)
    }

    @Test
    fun beginContextWordSelection_invalidArticleSentenceOrTarget_cannotCreateDraft() = runTest {
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
            viewModel.requestContextMeaning("lives")
            viewModel.updateContextMeaning("不能保存")
            viewModel.confirmContextMeaning()
            runCurrent()
            assertNull(viewModel.contextMeaningDraft.value)
            assertNull(viewModel.wordDefinition.value)
            assertFalse(viewModel.contextCardSaved.value)
            assertTrue(saveRequests.isEmpty())
        }
    }

    @Test
    fun beginContextWordSelection_firstOccurrenceSaved_secondOccurrenceRemainsIndependent() = runTest {
        val firstCard = sourceCard.copy(targetStartOffset = 6, targetEndOffset = 11)
        coEvery { fixture.vocabularyRepository.findContextCard(firstOccurrence) } returns firstCard
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        assertTrue(viewModel.contextCardSaved.value)

        viewModel.contextSaved.test {
            viewModel.requestContextMeaning("life")
            assertEquals(ContextSaveFeedback.ALREADY_EXISTS, awaitItem())
            assertNull(viewModel.contextMeaningDraft.value)
            assertTrue(saveRequests.isEmpty())
            selectWord(viewModel, secondOccurrence)
            assertFalse(viewModel.contextCardSaved.value)
            viewModel.requestContextMeaning("lives")
            viewModel.updateContextMeaning("另一处含义")
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "另一处含义")), saveRequests)
        }
    }

    @Test
    fun confirmContextMeaning_duplicateAtWrite_reportsExistingWithoutAnotherWrite() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            ContextCardSaveResult.ALREADY_EXISTS
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("新的输入")

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.ALREADY_EXISTS, awaitItem())
            runCurrent()
            assertTrue(viewModel.contextCardSaved.value)
            assertNull(viewModel.contextMeaningDraft.value)
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "新的输入")), saveRequests)
            expectNoEvents()
        }
    }

    @Test
    fun confirmContextMeaning_failure_preservesEditableDraftForRetry() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            if (saveRequests.size == 1) throw IOException("storage unavailable")
            ContextCardSaveResult.SAVED
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("原输入")
        val originalDraft = requireNotNull(viewModel.contextMeaningDraft.value)

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.FAILED, awaitItem())
            runCurrent()
            assertEquals(originalDraft, viewModel.contextMeaningDraft.value)
            assertFalse(viewModel.contextCardSaved.value)
            viewModel.updateContextMeaning("修正输入")
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            runCurrent()
            assertEquals(
                listOf(SaveRequest(secondOccurrence, "lives", "原输入"), SaveRequest(secondOccurrence, "lives", "修正输入")),
                saveRequests
            )
            assertNull(viewModel.contextMeaningDraft.value)
            assertTrue(viewModel.contextCardSaved.value)
        }
    }

    @Test
    fun confirmContextMeaning_sourceChangedAtWrite_neverReportsSaved() = runTest {
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            ContextCardSaveResult.SOURCE_CHANGED
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("原输入")

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.SOURCE_CHANGED, awaitItem())
            runCurrent()
            assertFalse(viewModel.contextCardSaved.value)
            assertEquals(false, viewModel.contextMeaningDraft.value?.saving)
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "原输入")), saveRequests)
            expectNoEvents()
        }
    }

    @Test
    fun confirmContextMeaning_pendingSave_repeatedConfirmationWritesOnce() = runTest {
        val result = CompletableDeferred<ContextCardSaveResult>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            result.await()
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("确认的输入")

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(true, viewModel.contextMeaningDraft.value?.saving)
            viewModel.updateContextMeaning("保存中的修改")
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "确认的输入")), saveRequests)
            assertEquals("确认的输入", viewModel.contextMeaningDraft.value?.meaning)
            expectNoEvents()
            result.complete(ContextCardSaveResult.SAVED)
            runCurrent()
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertNull(viewModel.contextMeaningDraft.value)
            assertTrue(viewModel.contextCardSaved.value)
            expectNoEvents()
        }
    }

    @Test
    fun lookupContextWord_lateOldDefinition_cannotReplaceNewPendingLookupOrDraft() = runTest {
        val oldResult = CompletableDeferred<OfflineLookupResult?>()
        val newResult = CompletableDeferred<OfflineLookupResult?>()
        coEvery { fixture.dictionaryRepository.lookupOffline("lives") } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
        }
        coEvery { fixture.dictionaryRepository.lookupOffline("shape") } coAnswers { newResult.await() }
        val shape = firstOccurrence.copy(wordStartOffset = 20, wordEndOffset = 25)
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        assertTrue(viewModel.isLoadingDefinition.value)
        selectWord(viewModel, shape)
        viewModel.requestContextMeaning("shape")
        viewModel.updateContextMeaning("当前手填")
        val currentDraft = requireNotNull(viewModel.contextMeaningDraft.value)

        oldResult.complete(OfflineLookupResult(DictionaryEntry("life", null, "过期释义", null)))
        runCurrent()
        assertTrue(viewModel.isLoadingDefinition.value)
        assertNull(viewModel.wordDefinition.value)
        assertEquals(currentDraft, viewModel.contextMeaningDraft.value)

        newResult.complete(OfflineLookupResult(DictionaryEntry("shape", null, "塑造", null)))
        runCurrent()
        assertEquals("shape", viewModel.wordDefinition.value?.word)
        assertFalse(viewModel.isLoadingDefinition.value)
        assertEquals(currentDraft, viewModel.contextMeaningDraft.value)
    }

    @Test
    fun beginContextWordSelection_lateOldDuplicate_cannotMarkNewOccurrenceOrCloseEditor() = runTest {
        val oldResult = CompletableDeferred<ContextCardEntity?>()
        coEvery { fixture.vocabularyRepository.findContextCard(firstOccurrence) } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        selectWord(viewModel, secondOccurrence)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("第二处输入")
        val currentDraft = requireNotNull(viewModel.contextMeaningDraft.value)

        viewModel.contextSaved.test {
            oldResult.complete(sourceCard.copy(targetStartOffset = 6, targetEndOffset = 11))
            runCurrent()
            assertFalse(viewModel.contextCardSaved.value)
            assertEquals(currentDraft, viewModel.contextMeaningDraft.value)
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
        assertNull(viewModel.contextMeaningDraft.value)
    }

    @Test
    fun confirmContextMeaning_lookupMissArrivesAfterSave_keepsConfirmedSavedBadge() = runTest {
        val lookup = CompletableDeferred<ContextCardEntity?>()
        coEvery { fixture.vocabularyRepository.findContextCard(secondOccurrence) } coAnswers { lookup.await() }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("已确认输入")

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            assertEquals(ContextSaveFeedback.SAVED, awaitItem())
            assertTrue(viewModel.contextCardSaved.value)
            lookup.complete(null)
            runCurrent()
            assertTrue(viewModel.contextCardSaved.value)
            assertNull(viewModel.contextMeaningDraft.value)
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "已确认输入")), saveRequests)
            expectNoEvents()
        }
    }

    @Test
    fun confirmContextMeaning_lateSaveAfterNewSelection_completesWithoutChangingCurrentEditor() = runTest {
        val result = CompletableDeferred<ContextCardSaveResult>()
        val completed = mutableListOf<SaveRequest>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            val request = SaveRequest(firstArg(), secondArg(), thirdArg())
            saveRequests += request
            result.await().also { completed += request }
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel, firstOccurrence)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("第一处输入")

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(true, viewModel.contextMeaningDraft.value?.saving)
            selectWord(viewModel, secondOccurrence)
            viewModel.requestContextMeaning("lives")
            viewModel.updateContextMeaning("第二处输入")
            val currentDraft = requireNotNull(viewModel.contextMeaningDraft.value)
            assertFalse(currentDraft.saving)
            assertTrue(completed.isEmpty())

            result.complete(ContextCardSaveResult.SAVED)
            runCurrent()
            assertEquals(listOf(SaveRequest(firstOccurrence, "lives", "第一处输入")), completed)
            assertEquals(currentDraft, viewModel.contextMeaningDraft.value)
            assertFalse(viewModel.contextCardSaved.value)
            expectNoEvents()
        }
    }

    @Test
    fun confirmContextMeaning_lateFailureAfterReselectingSameOccurrence_preservesNewDraft() = runTest {
        val result = CompletableDeferred<ContextCardSaveResult>()
        coEvery { fixture.vocabularyRepository.saveContextCard(any(), any(), any()) } coAnswers {
            saveRequests += SaveRequest(firstArg(), secondArg(), thirdArg())
            result.await()
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("旧输入")
        val oldDraft = requireNotNull(viewModel.contextMeaningDraft.value)

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(true, viewModel.contextMeaningDraft.value?.saving)
            selectWord(viewModel, firstOccurrence)
            selectWord(viewModel, secondOccurrence)
            viewModel.requestContextMeaning("lives")
            viewModel.updateContextMeaning("重新打开后的输入")
            val currentDraft = requireNotNull(viewModel.contextMeaningDraft.value)
            assertNotEquals(oldDraft.id, currentDraft.id)

            result.completeExceptionally(IOException("late storage failure"))
            runCurrent()
            assertEquals(currentDraft, viewModel.contextMeaningDraft.value)
            assertFalse(viewModel.contextCardSaved.value)
            assertEquals(listOf(SaveRequest(secondOccurrence, "lives", "旧输入")), saveRequests)
            expectNoEvents()
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
            saveRequests += request
            result.await().also { completed.complete(request) }
        }
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("旧文章输入")

        viewModel.contextSaved.test {
            viewModel.confirmContextMeaning()
            runCurrent()
            assertEquals(true, viewModel.contextMeaningDraft.value?.saving)
            viewModel.loadArticle(8)
            runCurrent()
            assertEquals(otherArticle, viewModel.article.value)
            assertNull(viewModel.contextMeaningDraft.value)
            assertFalse(viewModel.contextCardSaved.value)
            selectWord(viewModel, secondOccurrence.copy(article = otherArticle))
            viewModel.requestContextMeaning("lives")
            viewModel.updateContextMeaning("新文章输入")
            val currentDraft = requireNotNull(viewModel.contextMeaningDraft.value)

            result.complete(ContextCardSaveResult.SAVED)
            runCurrent()
            assertTrue(completed.isCompleted)
            assertEquals(SaveRequest(secondOccurrence, "lives", "旧文章输入"), completed.await())
            assertEquals(currentDraft, viewModel.contextMeaningDraft.value)
            assertFalse(viewModel.contextCardSaved.value)
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
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("旧输入")
        viewModel.loadArticle(8)
        runCurrent()
        assertEquals(article, viewModel.article.value)
        assertTrue(viewModel.isLoadingArticle.value)
        assertNull(viewModel.contextMeaningDraft.value)

        selectWord(viewModel, secondOccurrence)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("过期输入")
        viewModel.confirmContextMeaning()
        assertNull(viewModel.contextMeaningDraft.value)
        assertTrue(saveRequests.isEmpty())

        load.complete(otherArticle)
        runCurrent()
        selectWord(viewModel, secondOccurrence)
        viewModel.requestContextMeaning("lives")
        assertNull(viewModel.contextMeaningDraft.value)
        selectWord(viewModel, secondOccurrence.copy(article = otherArticle))
        viewModel.requestContextMeaning("lives")
        assertEquals(otherArticle, viewModel.contextMeaningDraft.value?.selection?.article)
    }

    @Test
    fun observeArticle_bodyChangedOutsideSentence_invalidatesDraftAndOldSelection() = runTest {
        val updates = MutableStateFlow<ArticleEntity?>(article)
        every { fixture.articleRepository.observeArticle(article.id) } returns updates
        val viewModel = loadedViewModel()
        selectWord(viewModel)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("旧输入")
        assertNotNull(viewModel.contextMeaningDraft.value)

        val changed = article.copy(content = "Changed.\n\nBefore. Their lives shape lives.")
        updates.value = changed
        runCurrent()
        assertEquals(changed, viewModel.article.value)
        assertNull(viewModel.contextMeaningDraft.value)
        assertNull(viewModel.wordDefinition.value)
        assertFalse(viewModel.contextCardSaved.value)
        viewModel.confirmContextMeaning()
        selectWord(viewModel, secondOccurrence)
        viewModel.requestContextMeaning("lives")
        viewModel.updateContextMeaning("过期输入")
        viewModel.confirmContextMeaning()
        runCurrent()
        assertNull(viewModel.contextMeaningDraft.value)
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
            assertEquals(
                ReadingAnchor(1, ReadingTextKind.ORIGINAL, 26),
                viewModel.pendingPositionTarget.value?.position?.anchor
            )
            assertEquals(sourceCard, awaitItem())
            assertEquals("lives", viewModel.highlightedContextCard.value?.word)
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
            val handle = SavedStateHandle(mapOf("contextCardId" to sourceCard.id, "word" to "lives"))
            val viewModel = fixture.create(handle)

            viewModel.highlightedContextCard.test {
                assertNull(awaitItem())
                viewModel.readingErrors.test {
                    viewModel.openReadingSession(article.id)
                    assertEquals(ReadingError.CONTEXT_SOURCE_UNAVAILABLE, awaitItem())
                    runCurrent()
                    assertEquals(loadedArticle, viewModel.article.value)
                    assertEquals(
                        ReadingAnchor(textKind = ReadingTextKind.TITLE),
                        viewModel.pendingPositionTarget.value?.position?.anchor
                    )
                    assertNull(viewModel.highlightedParagraph.value)
                    assertNull(viewModel.highlightedContextCard.value)
                }
                expectNoEvents()
            }
        }
    }

    private fun TestScope.loadedViewModel(): ReadingViewModel {
        val viewModel = fixture.create()
        viewModel.loadArticle(article.id)
        runCurrent()
        return viewModel
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
