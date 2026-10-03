package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ContextCardEntity
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
        assertEquals(13L, first.current()!!.card.id)
        assertFalse(first.entries.any { it.card.id == 14L })
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
        val schedule = dao.card(first.card.id)!!
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
        assertEquals(first.card.id, retry.card.id)
        assertTrue(dao.reveal(retry.item.itemToken, today))
        assertTrue(dao.rate(retry.item.itemToken, ContextGrade.FORGOTTEN, today, 120))
        assertEquals(schedule, dao.card(first.card.id))
        assertEquals(1, dao.round()!!.entries.count { it.item.cardId == first.card.id && it.item.isRetry })
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
    fun editMeaning_invalidatesOldAnswerAndResetsFutureDue_withoutDeletingHistory() = runBlocking {
        (1L..4L).forEach { dao.insertCard(card(it)) }
        val first = dao.startOrResume(today, 100).current()!!
        dao.reveal(first.item.itemToken, today)
        dao.rate(first.item.itemToken, ContextGrade.FORGOTTEN, today, 101)
        assertTrue(dao.editMeaning(first.card.id, first.card.answerRevision, "financial institution", today, 102))
        assertFalse(dao.editMeaning(first.card.id, first.card.answerRevision, "stale", today, 103))
        val edited = dao.card(first.card.id)!!
        assertEquals(today + 1, edited.dueDay)
        assertNull(edited.lastReviewedAt)
        assertEquals(0, edited.pendingStep)
        val entries = dao.round()!!.entries.filter { it.card.id == first.card.id }
        assertEquals("river side", entries.single { it.item.grade != null }.item.meaningSnapshot)
        assertTrue(entries.single { it.item.isRetry }.item.skipped)
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
        assertNull(dao.card(unanswered.card.id)!!.lastReviewedAt)
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
        assertEquals(11L, next.current()!!.card.id)
        assertEquals(0, next.answeredCount())
    }
}
