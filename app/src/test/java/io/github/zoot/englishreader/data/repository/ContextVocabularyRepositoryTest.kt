package io.github.zoot.englishreader.data.repository

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
import org.junit.Test
import java.time.ZonedDateTime

class ContextVocabularyRepositoryTest {
    private val dao = mockk<ContextCardDao>()
    private var now = ZonedDateTime.parse("2026-10-03T23:55:00+08:00[Asia/Shanghai]")
    private val repository = VocabularyRepository(mockk(), dao, ContextReviewClock { now })
    private val sentence = "The bank is beside the other bank."
    private val selection = ContextWordSelection(
        ArticleEntity(id = 7, title = "A story", content = sentence),
        0, SentenceRange(0, sentence, 0, sentence.length), 29, 33
    )

    @Test
    fun saveContextCard_explicitOccurrence_preservesSnapshotAndStartsTomorrow() = runTest {
        val captured = slot<ContextCardEntity>()
        coEvery { dao.insertForContent(capture(captured), sentence) } returns 12L
        assertEquals(ContextCardSaveResult.SAVED, repository.saveContextCard(selection, "bank", "  n. 河岸  "))
        val card = captured.captured
        assertEquals(sentence, card.sentence)
        assertEquals("bank", card.word)
        assertEquals(29, card.targetStartOffset)
        assertEquals("n. 河岸", card.meaning)
        assertEquals(now.toLocalDate().plusDays(1).toEpochDay(), card.dueDay)
        assertEquals(0, card.pendingStep)
        assertEquals("A story", card.sourceTitle)
        assertEquals(29, VocabularyRepository.contextSourceAnchor(card, 7, sentence)?.characterOffset)
        assertNull(VocabularyRepository.contextSourceAnchor(card, 7, "$sentence Changed."))
        assertNull(VocabularyRepository.contextSourceAnchor(card.copy(articleId = null), 7, sentence))
    }

    @Test
    fun saveContextCard_emptyMeaningOrInvalidOffsets_doesNotPersistAnIncompleteCard() = runTest {
        assertEquals(ContextCardSaveResult.INVALID_MEANING, repository.saveContextCard(selection, "bank", " \n "))
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
