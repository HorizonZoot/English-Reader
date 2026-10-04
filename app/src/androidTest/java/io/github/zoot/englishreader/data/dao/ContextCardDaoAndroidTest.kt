package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.model.PreparedStudyMeaning
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextCardDaoAndroidTest {
    private lateinit var db: EnglishReaderDatabase
    private lateinit var dao: ContextCardDao
    private val today = 20_000L
    private val content = "The bank is quiet."

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), EnglishReaderDatabase::class.java).build()
        dao = db.contextCardDao()
        db.openHelper.writableDatabase.execSQL("INSERT INTO articles (id, title, content, createdAt) VALUES (7, 'A story', '$content', 1)")
    }

    @After
    fun tearDown() { db.close() }

    private fun card(id: Long = 1, dueDay: Long = today) = ContextCardEntity(
        id = id, articleId = 7, originArticleId = 7, sourceTitle = "A story",
        contentFingerprint = "fingerprint-$id", paragraphIndex = 0,
        sentenceStartOffset = 0, sentenceEndOffset = content.length, sentence = content,
        targetStartOffset = 4, targetEndOffset = 8, word = "bank", dictionaryWord = "bank",
        meaning = "river side", createdAt = id, updatedAt = id, dueDay = dueDay
    )

    @Test
    fun learn_mixedSourcesAndFuturePractice_resumesModeAndOnlyUpdatesEligibleSchedules() = runBlocking {
        dao.insertCard(card(1))
        val future = card(2, today + 9).copy(lastReviewedAt = 10, intervalDays = 9, pendingStep = 2)
        dao.insertCard(future)
        val fresh = VocabularyEntity(id = 1, word = "new", definitions = "新", createdAt = 3)
        val due = VocabularyEntity(id = 2, word = "due", definitions = "到期", createdAt = 4,
            dueDay = today, lastReviewedAt = 5, intervalDays = 3, pendingStep = 1)
        db.vocabularyDao().insertVocabularyIfAbsent(fresh)
        db.vocabularyDao().insertVocabularyIfAbsent(due)
        val round = dao.startOrResume(today, 100, mode = VocabularyStudyMode.LEARN)
        assertEquals(4, round.entries.size)
        assertEquals(listOf("context:1", "vocabulary:1", "vocabulary:2", "context:2"),
            round.entries.sortedBy { it.item.position }.map { it.studyWord()!!.key })
        assertEquals(false, round.entries.single { it.item.cardId == 2L }.item.updatesSchedule)
        val first = round.current()!!.item
        dao.reveal(first.itemToken, today)
        val resumed = dao.startOrResume(today, 101, mode = VocabularyStudyMode.DUE)
        assertEquals(round.session.sessionToken, resumed.session.sessionToken)
        assertEquals(VocabularyStudyMode.LEARN, resumed.session.mode)
        assertTrue(resumed.current()!!.item.revealed)
        repeat(4) {
            val item = dao.round()!!.current()!!.item
            dao.reveal(item.itemToken, today)
            assertTrue(dao.rate(item.itemToken, ContextGrade.REMEMBERED, today, 102L + it))
            assertFalse(dao.rate(item.itemToken, ContextGrade.FORGOTTEN, today, 110L + it))
        }
        assertEquals(future, dao.card(2))
        assertEquals(today + 1, dao.vocabulary(1)!!.dueDay)
        assertEquals(today + 3, dao.vocabulary(2)!!.dueDay)
        assertEquals(4, dao.round()!!.answeredCount())
        val nextMode = dao.startOrResume(today, 120, mode = VocabularyStudyMode.DUE)
        assertNotEquals(round.session.sessionToken, nextMode.session.sessionToken)
        assertNull(nextMode.current())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM context_review_items WHERE grade IS NOT NULL").use {
            assertTrue(it.moveToFirst())
            assertEquals(4, it.getInt(0))
        }
    }

    @Test
    fun legacy_blankAnswer_skipsWithoutProgressAndUsesLaterDictionarySnapshot() = runBlocking {
        val word = VocabularyEntity(id = 1, word = "unknown", createdAt = 1)
        db.vocabularyDao().insertVocabularyIfAbsent(word)
        val old = dao.startOrResume(today, 100).current()!!.item
        assertTrue(dao.reveal(old.itemToken, today))
        assertFalse(dao.rate(old.itemToken, ContextGrade.REMEMBERED, today, 101))
        assertTrue(dao.skip(old.itemToken, today))
        assertFalse(dao.skip(old.itemToken, today))
        assertEquals(word, dao.vocabulary(1))
        assertEquals(0, dao.round()!!.answeredCount())
        assertNull(dao.round()!!.current())
        assertTrue(dao.round()!!.entries.single().item.skipped)
        assertNull(dao.round()!!.entries.single().item.grade)

        val next = dao.startOrResume(today, 103, startNext = true, meanings = listOf(
            PreparedStudyMeaning("vocabulary:1", "unknown", 0, "dictionary answer")
        )).current()!!.item
        assertEquals("dictionary answer", next.meaningSnapshot)
        assertEquals(0L, next.answerRevision)
        assertEquals(word, dao.vocabulary(1))
        assertFalse(next.revealed)
        assertFalse(dao.rate(old.itemToken, ContextGrade.REMEMBERED, today, 104))
        assertFalse(dao.rate(next.itemToken, ContextGrade.REMEMBERED, today, 104))
        assertTrue(dao.reveal(next.itemToken, today))
        assertTrue(dao.rate(next.itemToken, ContextGrade.REMEMBERED, today, 105))
        assertEquals(1, dao.round()!!.answeredCount())
        assertNull(dao.vocabulary(1)!!.studyMeaning)
    }

    @Test
    fun sourceDeletion_duplicateLegacyWord_keepsLearnedRowAndHistory() = runBlocking {
        val orphan = VocabularyEntity(id = 1, word = "bank", definitions = "saved", createdAt = 1)
        val learned = orphan.copy(id = 2, articleId = 7, lastReviewedAt = 10, dueDay = today)
        db.vocabularyDao().insertVocabularyIfAbsent(orphan)
        db.vocabularyDao().insertVocabularyIfAbsent(learned)
        val item = dao.startOrResume(today, 100).current()!!.item
        assertEquals(2L, item.vocabularyId)
        dao.reveal(item.itemToken, today)
        dao.rate(item.itemToken, ContextGrade.REMEMBERED, today, 101)
        val saved = dao.vocabulary(2)!!
        db.articleDao().deleteArticleById(7)
        assertEquals(saved.copy(articleId = null), dao.vocabulary(2))
        assertEquals(orphan, dao.vocabulary(1))
        assertEquals(ContextGrade.REMEMBERED, dao.round()!!.entries.single { it.item.vocabularyId == 2L }.item.grade)
    }

    @Test
    fun save_exactDuplicate_doesNotOverwrite_andChangedSourceCannotInsert() = runBlocking {
        val original = card()
        assertEquals(1L, dao.insertForContent(original, content))
        assertEquals(-1L, dao.insertForContent(original.copy(id = 0, meaning = "changed", dueDay = today + 1), content))
        assertEquals(original, dao.card(1))
        assertNull(dao.insertForContent(card(2), "outdated source"))
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE id = 7")
        assertEquals(original.copy(articleId = null), dao.card(1))
    }

    @Test
    fun round_dueOrderingAndResume_usesTenLearnedFirstAndRetainsReveal() = runBlocking {
        (1L..12L).forEach { dao.insertCard(card(it)) }
        dao.insertCard(card(13, today - 1).copy(lastReviewedAt = 9))
        dao.insertCard(card(14, today + 1))
        val first = dao.startOrResume(today, 100)
        assertEquals(10, first.entries.size)
        assertEquals(13L, first.current()!!.item.cardId)
        assertFalse(first.entries.any { it.item.cardId == 14L })
        val token = first.current()!!.item.itemToken
        assertTrue(dao.reveal(token, today))
        val resumed = dao.startOrResume(today, 200)
        assertEquals(first.session.sessionToken, resumed.session.sessionToken)
        assertEquals(token, resumed.current()!!.item.itemToken)
        assertTrue(resumed.current()!!.item.revealed)
    }

    @Test
    fun rate_revealGateDuplicateAndRetry_onlyPrimaryChangesSchedule() = runBlocking {
        (1L..5L).forEach { dao.insertCard(card(it).copy(pendingStep = 3)) }
        val first = dao.startOrResume(today, 100).current()!!
        assertFalse(dao.rate(first.item.itemToken, ContextGrade.FORGOTTEN, today, 101))
        assertTrue(dao.reveal(first.item.itemToken, today))
        assertTrue(dao.rate(first.item.itemToken, ContextGrade.FORGOTTEN, today, 102))
        assertFalse(dao.rate(first.item.itemToken, ContextGrade.REMEMBERED, today, 103))
        val schedule = dao.card(checkNotNull(first.item.cardId))!!
        assertEquals(today + 1, schedule.dueDay)
        assertEquals(0, schedule.pendingStep)
        repeat(3) {
            val other = dao.round()!!.current()!!
            assertFalse(other.item.isRetry)
            assertTrue(dao.reveal(other.item.itemToken, today))
            assertTrue(dao.rate(other.item.itemToken, ContextGrade.REMEMBERED, today, 110L + it))
        }
        val retry = dao.round()!!.current()!!
        assertTrue(retry.item.isRetry)
        assertEquals(first.item.cardId, retry.item.cardId)
        assertTrue(dao.reveal(retry.item.itemToken, today))
        assertTrue(dao.rate(retry.item.itemToken, ContextGrade.FORGOTTEN, today, 120))
        assertEquals(schedule, dao.card(checkNotNull(first.item.cardId)))
        assertEquals(1, dao.round()!!.entries.count { it.item.cardId == first.item.cardId && it.item.isRetry })
    }

    @Test
    fun rate_writeFails_rollsBackScheduleAndRetainsRevealedTurn() = runBlocking {
        val original = card()
        dao.insertCard(original)
        val token = dao.startOrResume(today, 100).current()!!.item.itemToken
        dao.reveal(token, today)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_review BEFORE UPDATE OF grade ON context_review_items BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            dao.rate(token, ContextGrade.REMEMBERED, today, 101)
            fail("history failure must propagate")
        } catch (_: android.database.SQLException) {
            assertEquals(original, dao.card(original.id))
            assertEquals(token, dao.round()!!.current()!!.item.itemToken)
            assertTrue(dao.round()!!.current()!!.item.revealed)
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_review")
        assertTrue(dao.rate(token, ContextGrade.REMEMBERED, today, 102))
    }

    @Test
    fun preparedAnswers_sourceIdentityWordAndRevisionGuardSnapshotsWithoutChangingSavedData() = runBlocking {
        val original = card().copy(meaning = "", answerRevision = 2)
        val legacy = VocabularyEntity(id = 1, word = "bank", studyMeaning = "saved manual answer", answerRevision = 3)
        dao.insertCard(original)
        db.vocabularyDao().insertVocabularyIfAbsent(legacy)
        val round = dao.startOrResume(today, 100, mode = VocabularyStudyMode.LEARN, meanings = listOf(
            PreparedStudyMeaning("context:1", "other", 2, "wrong word"),
            PreparedStudyMeaning("context:1", "bank", 1, "stale revision"),
            PreparedStudyMeaning("context:1", "bank", 2, "automatic dictionary answer"),
            PreparedStudyMeaning("vocabulary:1", "bank", 2, "stale legacy revision")
        ))
        assertEquals("automatic dictionary answer", round.entries.single { it.item.cardId == 1L }.item.meaningSnapshot)
        assertEquals("saved manual answer", round.entries.single { it.item.vocabularyId == 1L }.item.meaningSnapshot)
        assertEquals(original, dao.card(1))
        assertEquals(legacy, dao.vocabulary(1))
        val first = round.current()!!.item
        assertTrue(dao.reveal(first.itemToken, today))
        val resumed = dao.startOrResume(today, 101, mode = VocabularyStudyMode.LEARN, meanings = listOf(
            PreparedStudyMeaning("context:1", "bank", 2, "later dictionary answer")
        ))
        assertEquals(round.session.sessionToken, resumed.session.sessionToken)
        assertEquals(first.meaningSnapshot, resumed.current()!!.item.meaningSnapshot)
        assertTrue(resumed.current()!!.item.revealed)
    }

    @Test
    fun dayChange_expiresOldTurnsAndRetry_withoutGradingUnansweredCards() = runBlocking {
        (1L..4L).forEach { dao.insertCard(card(it)) }
        val old = dao.startOrResume(today, 100)
        val first = old.current()!!
        dao.reveal(first.item.itemToken, today)
        dao.rate(first.item.itemToken, ContextGrade.FORGOTTEN, today, 101)
        val unanswered = dao.round()!!.current()!!
        val next = dao.startOrResume(today + 1, 200)
        assertNotEquals(old.session.sessionToken, next.session.sessionToken)
        assertFalse(next.entries.any { it.item.isRetry })
        assertNull(dao.card(checkNotNull(unanswered.item.cardId))!!.lastReviewedAt)
        assertFalse(dao.reveal(unanswered.item.itemToken, today + 1))
        db.openHelper.readableDatabase.query("SELECT grade, skipped FROM context_review_items WHERE sessionToken = ?", arrayOf(old.session.sessionToken)).use { cursor ->
            var historyCount = 0
            while (cursor.moveToNext()) {
                if (cursor.isNull(0)) assertEquals(1, cursor.getInt(1)) else historyCount++
            }
            assertEquals(1, historyCount)
        }
    }

    @Test
    fun deleteCard_answeredOtherCard_keepsRevealedRetryAndCommittedProgress() = runBlocking {
        (1L..4L).forEach { dao.insertCard(card(it)) }
        dao.startOrResume(today, 100)
        repeat(4) { index ->
            val item = dao.round()!!.current()!!.item
            dao.reveal(item.itemToken, today)
            dao.rate(item.itemToken, if (index == 0) ContextGrade.FORGOTTEN else ContextGrade.REMEMBERED, today, 101L + index)
        }
        val retry = dao.round()!!.current()!!.item
        assertTrue(retry.isRetry)
        dao.reveal(retry.itemToken, today)
        dao.deleteCard(2)
        val resumed = dao.startOrResume(today, 110)
        assertEquals(4, resumed.answeredCount())
        assertEquals(retry.itemToken, resumed.current()!!.item.itemToken)
        assertTrue(resumed.current()!!.item.revealed)
        assertTrue(dao.rate(retry.itemToken, ContextGrade.REMEMBERED, today, 111))
        assertEquals(5, dao.round()!!.answeredCount())
    }

    @Test
    fun deleteCard_unansweredCard_doesNotEarnRetrySpacing() = runBlocking {
        (1L..4L).forEach { dao.insertCard(card(it)) }
        val first = dao.startOrResume(today, 100).current()!!.item
        dao.reveal(first.itemToken, today)
        dao.rate(first.itemToken, ContextGrade.FORGOTTEN, today, 101)
        dao.deleteCard(2)
        repeat(2) {
            val item = dao.round()!!.current()!!.item
            dao.reveal(item.itemToken, today)
            dao.rate(item.itemToken, ContextGrade.REMEMBERED, today, 102L + it)
        }
        assertEquals(3, dao.round()!!.answeredCount())
        assertNull(dao.round()!!.current())
    }

    @Test
    fun startOrResume_finishedSameDay_keepsCompletionUntilExplicitNextGroup() = runBlocking {
        (1L..11L).forEach { dao.insertCard(card(it)) }
        val original = dao.startOrResume(today, 100)
        repeat(10) {
            val item = dao.round()!!.current()!!.item
            dao.reveal(item.itemToken, today)
            dao.rate(item.itemToken, ContextGrade.REMEMBERED, today, 101L + it)
        }
        val resumed = dao.startOrResume(today, 120)
        assertEquals(original.session.sessionToken, resumed.session.sessionToken)
        assertNull(resumed.current())
        val next = dao.startOrResume(today, 121, startNext = true)
        assertNotEquals(original.session.sessionToken, next.session.sessionToken)
        assertEquals(11L, next.current()!!.item.cardId)
        assertEquals(0, next.answeredCount())
    }
}
