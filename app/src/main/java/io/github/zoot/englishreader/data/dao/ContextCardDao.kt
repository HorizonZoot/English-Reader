package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.core.ContextReviewPolicy
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.ContextReviewItemEntity
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.entity.ContextReviewSessionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
abstract class ContextCardDao {
    @Query("SELECT * FROM context_cards ORDER BY createdAt DESC, id DESC")
    abstract fun observeCards(): Flow<List<ContextCardEntity>>

    @Query("SELECT * FROM context_cards WHERE id = :id")
    abstract suspend fun card(id: Long): ContextCardEntity?

    @Query("SELECT content FROM articles WHERE id = :articleId")
    abstract suspend fun articleContent(articleId: Long): String?

    @Query("""
        SELECT * FROM context_cards WHERE originArticleId = :articleId
        AND contentFingerprint = :fingerprint AND paragraphIndex = :paragraphIndex
        AND sentenceStartOffset = :sentenceStart AND sentenceEndOffset = :sentenceEnd
        AND targetStartOffset = :targetStart AND targetEndOffset = :targetEnd LIMIT 1
    """)
    abstract suspend fun cardAtOccurrence(
        articleId: Long,
        fingerprint: String,
        paragraphIndex: Int,
        sentenceStart: Int,
        sentenceEnd: Int,
        targetStart: Int,
        targetEnd: Int
    ): ContextCardEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCard(card: ContextCardEntity): Long

    @Transaction
    open suspend fun insertForContent(card: ContextCardEntity, expectedContent: String): Long? {
        val sourceId = card.articleId ?: return null
        if (articleContent(sourceId) != expectedContent) return null
        return insertCard(card)
    }

    @Query("DELETE FROM context_cards WHERE id = :id")
    abstract suspend fun deleteCard(id: Long): Int

    @Query("""
        UPDATE context_cards SET meaning = :meaning, answerRevision = answerRevision + 1,
        dueDay = :tomorrow, pendingStep = 0, intervalDays = 0, lastReviewedAt = NULL, updatedAt = :now
        WHERE id = :id AND answerRevision = :revision
    """)
    abstract suspend fun updateMeaning(id: Long, revision: Long, meaning: String, tomorrow: Long, now: Long): Int

    @Query("UPDATE context_review_items SET skipped = 1 WHERE cardId = :cardId AND grade IS NULL")
    abstract suspend fun skipPendingCard(cardId: Long)

    @Transaction
    open suspend fun editMeaning(id: Long, revision: Long, meaning: String, today: Long, now: Long): Boolean {
        if (meaning.isBlank()) return false
        val existing = card(id) ?: return false
        if (existing.answerRevision != revision) return false
        if (existing.meaning == meaning) return true
        if (updateMeaning(id, revision, meaning, today + 1, now) != 1) return false
        skipPendingCard(id)
        return true
    }

    @Query("""
        SELECT * FROM context_cards WHERE dueDay <= :today
        ORDER BY CASE WHEN lastReviewedAt IS NULL THEN 1 ELSE 0 END,
            dueDay, createdAt, id LIMIT :limit
    """)
    abstract suspend fun dueCards(today: Long, limit: Int): List<ContextCardEntity>

    @Transaction
    @Query("SELECT * FROM context_review_session WHERE id = 1")
    abstract fun observeRound(): Flow<ContextReviewRound?>

    @Transaction
    @Query("SELECT * FROM context_review_session WHERE id = 1")
    abstract suspend fun round(): ContextReviewRound?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSession(session: ContextReviewSessionEntity)

    @Insert
    abstract suspend fun insertItems(items: List<ContextReviewItemEntity>)

    @Query("UPDATE context_review_items SET skipped = 1 WHERE sessionToken = :token AND grade IS NULL")
    abstract suspend fun skipPendingSession(token: String)

    @Transaction
    open suspend fun startOrResume(today: Long, now: Long, startNext: Boolean = false): ContextReviewRound {
        val previous = round()
        if (previous?.session?.localDay == today && (!startNext || previous.current() != null)) return previous
        previous?.let { skipPendingSession(it.session.sessionToken) }
        val cards = dueCards(today, ContextReviewPolicy.ROUND_SIZE)
        val token = UUID.randomUUID().toString()
        putSession(ContextReviewSessionEntity(sessionToken = token, localDay = today, createdAt = now, initialCount = cards.size))
        insertItems(cards.mapIndexed { index, card ->
            ContextReviewItemEntity(
                itemToken = UUID.randomUUID().toString(), sessionToken = token, cardId = card.id,
                answerRevision = card.answerRevision, meaningSnapshot = card.meaning, position = index
            )
        })
        return checkNotNull(round())
    }

    @Query("UPDATE context_review_items SET revealed = 1 WHERE itemToken = :token AND grade IS NULL AND skipped = 0")
    abstract suspend fun markRevealed(token: String): Int

    @Transaction
    open suspend fun reveal(token: String, today: Long): Boolean {
        val round = round() ?: return false
        if (round.session.localDay != today || round.current()?.item?.itemToken != token) return false
        return markRevealed(token) == 1
    }

    @Query("""
        UPDATE context_cards SET dueDay = :dueDay, pendingStep = :pendingStep,
        intervalDays = :intervalDays, lastReviewedAt = :now, updatedAt = :now
        WHERE id = :cardId AND answerRevision = :revision
    """)
    abstract suspend fun updateSchedule(
        cardId: Long, revision: Long, dueDay: Long, pendingStep: Int, intervalDays: Int, now: Long
    ): Int

    @Query("""
        UPDATE context_review_items SET grade = :grade, ratedAt = :now, ratedDay = :today,
        appliedIntervalDays = :intervalDays, nextPendingStep = :pendingStep
        WHERE itemToken = :token AND revealed = 1 AND grade IS NULL AND skipped = 0
    """)
    abstract suspend fun markRated(
        token: String, grade: ContextGrade, today: Long, now: Long, intervalDays: Int?, pendingStep: Int?
    ): Int

    @Query("UPDATE context_review_session SET answerCount = answerCount + 1 WHERE sessionToken = :token")
    abstract suspend fun incrementAnswerCount(token: String): Int

    @Transaction
    open suspend fun rate(token: String, grade: ContextGrade, today: Long, now: Long): Boolean {
        val round = round() ?: return false
        if (round.session.localDay != today) return false
        val current = round.current() ?: return false
        val item = current.item
        val card = current.card
        if (item.itemToken != token || !item.revealed) return false
        val schedule = if (item.isRetry) null else ContextReviewPolicy.schedule(today, card.pendingStep, grade)
        if (schedule != null) {
            check(updateSchedule(card.id, item.answerRevision, schedule.dueDay, schedule.pendingStep, schedule.intervalDays, now) == 1)
        }
        check(markRated(token, grade, today, now, schedule?.intervalDays, schedule?.pendingStep) == 1)
        check(incrementAnswerCount(round.session.sessionToken) == 1)
        if (grade == ContextGrade.FORGOTTEN && !item.isRetry) {
            insertItems(listOf(item.copy(
                itemToken = UUID.randomUUID().toString(),
                isRetry = true,
                eligibleAfterCount = round.answeredCount() + 1 + ContextReviewPolicy.RETRY_SPACING,
                revealed = false
            )))
        }
        return true
    }
}
