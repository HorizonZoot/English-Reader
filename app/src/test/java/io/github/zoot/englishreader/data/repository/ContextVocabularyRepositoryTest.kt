package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.model.PreparedStudyMeaning
import io.github.zoot.englishreader.util.contextRound
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.dao.ContextCardDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.model.ContextCardSaveResult
import io.github.zoot.englishreader.model.ContextReviewClock
import io.github.zoot.englishreader.model.ContextWordSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

class ContextVocabularyRepositoryTest {
    private val dao = mockk<ContextCardDao>()
    private var now = ZonedDateTime.parse("2026-10-03T23:55:00+08:00[Asia/Shanghai]")
    private val dictionary = mockk<DictionaryRepository>()
    private val repository = VocabularyRepository(mockk(), dao, ContextReviewClock { now }, dictionary)
    private val sentence = "The bank is beside the other bank."
    private val selection = ContextWordSelection(
        ArticleEntity(id = 7, title = "A story", content = sentence),
        0, SentenceRange(0, sentence, 0, sentence.length), 29, 33
    )

    @Before
    fun setUp() {
        coEvery { dao.studyCards(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun startLearn_savedEditedAndOfflineMeanings_prepareBoundedRevisionSnapshots() = runTest {
        val today = repository.currentReviewDay()
        val words = listOf(
            VocabularyEntity(id = 1, word = "edited", definitions = "old", studyMeaning = "manual", answerRevision = 2),
            VocabularyEntity(id = 2, word = "saved", definitions = """[{"definition":"saved answer"}]"""),
            VocabularyEntity(id = 3, word = "offline"),
            VocabularyEntity(id = 4, word = "missing")
        )
        coEvery { dao.round() } returns null
        coEvery { dao.studyVocabulary(today, VocabularyStudyMode.LEARN, 10) } returns words
        coEvery { dictionary.lookupOffline("offline") } returns OfflineLookupResult(
            DictionaryEntry(word = "offline", phonetic = null, chinese = "离线释义", english = null)
        )
        coEvery { dictionary.lookupOffline("missing") } returns null
        val prepared = slot<List<PreparedStudyMeaning>>()
        val result = contextRound(mode = VocabularyStudyMode.LEARN)
        coEvery { dao.startOrResume(today, any(), false, VocabularyStudyMode.LEARN, capture(prepared)) } returns result
        assertSame(result, repository.startOrResumeContextReview(VocabularyStudyMode.LEARN))
        assertEquals(listOf("manual", "saved answer", "离线释义", ""), prepared.captured.map { it.meaning })
        assertEquals(listOf("vocabulary:1", "vocabulary:2", "vocabulary:3", "vocabulary:4"), prepared.captured.map { it.key })
        assertEquals(2L, prepared.captured.first().answerRevision)
        coVerify(exactly = 0) { dictionary.lookupOffline("edited"); dictionary.lookupOffline("saved") }
    }

    @Test
    fun startLearn_emptyContextMeaning_usesSavedHeadwordWithoutChangingCard() = runTest {
        val card = io.github.zoot.englishreader.util.contextCard().copy(
            word = "banks", dictionaryWord = "bank", meaning = "", answerRevision = 3
        )
        coEvery { dao.round() } returns null
        coEvery { dao.studyCards(any(), any(), any()) } returns listOf(card)
        coEvery { dao.studyVocabulary(any(), any(), any()) } returns emptyList()
        coEvery { dictionary.lookupOffline("bank") } returns OfflineLookupResult(
            DictionaryEntry("bank", null, "", "a financial institution")
        )
        val prepared = slot<List<PreparedStudyMeaning>>()
        val result = contextRound(mode = VocabularyStudyMode.LEARN)
        coEvery { dao.startOrResume(any(), any(), false, VocabularyStudyMode.LEARN, capture(prepared)) } returns result
        assertSame(result, repository.startOrResumeContextReview(VocabularyStudyMode.LEARN))
        assertEquals(listOf(PreparedStudyMeaning("context:1", "banks", 3, "a financial institution")), prepared.captured)
        coVerify(exactly = 0) { dictionary.lookupOffline("banks"); dao.insertCard(any()) }
    }

    @Test
    fun startLearn_unfinishedDueRound_doesNotRefetchAnswerOrDiscardSnapshot() = runTest {
        val previous = contextRound(day = repository.currentReviewDay())
        coEvery { dao.round() } returns previous
        coEvery { dao.startOrResume(any(), any(), false, VocabularyStudyMode.LEARN, emptyList()) } returns previous
        assertSame(previous, repository.startOrResumeContextReview(VocabularyStudyMode.LEARN))
        coVerify(exactly = 0) {
            dao.studyCards(any(), any(), any())
            dao.studyVocabulary(any(), any(), any())
            dictionary.lookupOffline(any())
        }
    }

    @Test
    fun startLearn_cancelledOfflineLookup_neverStartsPartialRound() = runTest {
        coEvery { dao.round() } returns null
        coEvery { dao.studyVocabulary(any(), any(), any()) } returns listOf(VocabularyEntity(id = 1, word = "missing"))
        val cancelled = CancellationException("cancelled")
        coEvery { dictionary.lookupOffline(any()) } throws cancelled
        try {
            repository.startOrResumeContextReview(VocabularyStudyMode.LEARN)
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        coVerify(exactly = 0) { dao.startOrResume(any(), any(), any(), any(), any()) }
    }

    @Test
    fun saveContextCard_explicitOccurrence_preservesSnapshotAndCanLearnToday() = runTest {
        val captured = slot<ContextCardEntity>()
        coEvery { dao.insertForContent(capture(captured), sentence) } returns 12L
        assertEquals(ContextCardSaveResult.SAVED, repository.saveContextCard(selection, "bank", "  n. 河岸  "))
        val card = captured.captured
        assertEquals(sentence, card.sentence)
        assertEquals("bank", card.word)
        assertEquals(29, card.targetStartOffset)
        assertEquals("n. 河岸", card.meaning)
        assertEquals(now.toLocalDate().toEpochDay(), card.dueDay)
        assertEquals(0, card.pendingStep)
        assertEquals("A story", card.sourceTitle)
        assertEquals(29, VocabularyRepository.contextSourceAnchor(card, 7, sentence)?.characterOffset)
        assertNull(VocabularyRepository.contextSourceAnchor(card, 7, "$sentence Changed."))
        assertNull(VocabularyRepository.contextSourceAnchor(card.copy(articleId = null), 7, sentence))
    }

    @Test
    fun saveContextCard_emptyMeaning_preservesWordAndSourceForLaterDictionaryLookup() = runTest {
        val captured = slot<ContextCardEntity>()
        coEvery { dao.insertForContent(capture(captured), sentence) } returns 12L
        assertEquals(ContextCardSaveResult.SAVED, repository.saveContextCard(selection, "bank", " \n "))
        assertEquals("", captured.captured.meaning)
        assertEquals("bank", captured.captured.word)
        assertEquals(29, captured.captured.targetStartOffset)
    }

    @Test
    fun saveContextCard_invalidOffsets_doesNotPersistAnIncompleteCard() = runTest {
        assertEquals(ContextCardSaveResult.SOURCE_CHANGED, repository.saveContextCard(selection.copy(wordEndOffset = 80), "bank", "河岸"))
        coVerify(exactly = 0) { dao.insertForContent(any(), any()) }
    }

    @Test
    fun saveContextCard_conflictOrChangedSource_preservesDaoResult() = runTest {
        coEvery { dao.insertForContent(any(), any()) } returns -1L
        assertEquals(ContextCardSaveResult.ALREADY_EXISTS, repository.saveContextCard(selection, "bank", "河岸"))
        coEvery { dao.insertForContent(any(), any()) } returns null
        assertEquals(ContextCardSaveResult.SOURCE_CHANGED, repository.saveContextCard(selection, "bank", "河岸"))
    }

    @Test
    fun saveContextCard_cancellation_propagates() = runTest {
        val cancelled = CancellationException("cancelled")
        coEvery { dao.insertForContent(any(), any()) } throws cancelled
        try {
            repository.saveContextCard(selection, "bank", "河岸")
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test
    fun currentReviewDay_timezoneChange_usesLocalDateNotElapsedTwentyFourHours() {
        val originalDay = repository.currentReviewDay()
        now = now.plusMinutes(10)
        assertEquals(originalDay + 1, repository.currentReviewDay())
        now = now.withZoneSameInstant(java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(originalDay, repository.currentReviewDay())
    }
}
