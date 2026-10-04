package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.core.ContextReviewPolicy
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.ContextReviewItemEntity
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.entity.ContextReviewSessionEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.model.PreparedStudyMeaning
import io.github.zoot.englishreader.model.StudyWord
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
abstract class ContextCardDao {
    @Query("SELECT * FROM context_cards ORDER BY createdAt DESC, id DESC")
    abstract fun observeCards(): Flow<List<ContextCardEntity>>

    @Query("SELECT * FROM context_cards WHERE id = :id")
    abstract suspend fun card(id: Long): ContextCardEntity?

    @Query("SELECT * FROM vocabulary WHERE id = :id")
    abstract suspend fun vocabulary(id: Long): VocabularyEntity?

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
        SELECT * FROM context_cards WHERE :mode = 'LEARN' OR dueDay <= :today
        ORDER BY CASE WHEN :mode = 'LEARN' THEN lastReviewedAt IS NOT NULL ELSE lastReviewedAt IS NULL END,
            CASE WHEN :mode = 'LEARN' THEN lastReviewedAt ELSE dueDay END, createdAt, id LIMIT :limit
    """)
    abstract suspend fun studyCards(today: Long, mode: VocabularyStudyMode, limit: Int): List<ContextCardEntity>

    @Query("""
        SELECT * FROM vocabulary WHERE :mode = 'LEARN' OR dueDay <= :today
        ORDER BY CASE WHEN :mode = 'LEARN' THEN lastReviewedAt IS NOT NULL ELSE lastReviewedAt IS NULL END,
            CASE WHEN :mode = 'LEARN' THEN lastReviewedAt ELSE dueDay END, createdAt, id LIMIT :limit
    """)
    abstract suspend fun studyVocabulary(today: Long, mode: VocabularyStudyMode, limit: Int): List<VocabularyEntity>

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
    open suspend fun startOrResume(
        today: Long,
        now: Long,
        startNext: Boolean = false,
        mode: VocabularyStudyMode = VocabularyStudyMode.DUE,
        meanings: List<PreparedStudyMeaning> = emptyList()
    ): ContextReviewRound {
        val previous = round()
        if (previous?.session?.localDay == today &&
            (previous.current() != null || (!startNext && previous.session.mode == mode))
        ) return previous
        previous?.let { skipPendingSession(it.session.sessionToken) }
        val words = (
            studyCards(today, mode, ContextReviewPolicy.ROUND_SIZE).map(StudyWord::Context) +
                studyVocabulary(today, mode, ContextReviewPolicy.ROUND_SIZE).map { StudyWord.Legacy(it) }
            ).sortedWith(
                compareBy<StudyWord> {
                    if (mode == VocabularyStudyMode.LEARN) it.lastReviewedAt != null else it.lastReviewedAt == null
                }.thenBy {
                    if (mode == VocabularyStudyMode.LEARN) it.lastReviewedAt ?: Long.MIN_VALUE else it.dueDay
                }.thenBy { it.createdAt }.thenBy { it.key }
            ).take(ContextReviewPolicy.ROUND_SIZE)
        val token = UUID.randomUUID().toString()
        putSession(ContextReviewSessionEntity(
            sessionToken = token, localDay = today, createdAt = now, initialCount = words.size, mode = mode
        ))
        insertItems(words.mapIndexed { index, word ->
            val meaning = meanings.firstOrNull {
                it.key == word.key && it.word == word.word && it.answerRevision == word.answerRevision
            }?.meaning ?: word.meaning
            ContextReviewItemEntity(
                itemToken = UUID.randomUUID().toString(), sessionToken = token,
                cardId = (word as? StudyWord.Context)?.card?.id,
                vocabularyId = (word as? StudyWord.Legacy)?.vocabulary?.id,
                answerRevision = word.answerRevision, meaningSnapshot = meaning, position = index,
                updatesSchedule = word.lastReviewedAt == null || word.dueDay <= today
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

    @Query("UPDATE context_review_items SET skipped = 1 WHERE itemToken = :token AND grade IS NULL")
    abstract suspend fun markSkipped(token: String): Int

    @Transaction
    open suspend fun skip(token: String, today: Long): Boolean {
        val saved = round() ?: return false
        if (saved.session.localDay != today || saved.current()?.item?.itemToken != token) return false
        return markSkipped(token) == 1
    }

    @Query("""
        UPDATE vocabulary SET dueDay = :dueDay, pendingStep = :pendingStep,
        intervalDays = :intervalDays, lastReviewedAt = :now
        WHERE id = :id AND answerRevision = :revision
    """)
    abstract suspend fun updateVocabularySchedule(
        id: Long, revision: Long, dueDay: Long, pendingStep: Int, intervalDays: Int, now: Long
    ): Int

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
        val word = current.studyWord() ?: return false
        if (item.itemToken != token || !item.revealed || item.meaningSnapshot.isBlank()) return false
        val schedule = if (item.isRetry || !item.updatesSchedule) null
            else ContextReviewPolicy.schedule(today, word.pendingStep, grade)
        if (schedule != null) {
            val updated = when (word) {
                is StudyWord.Context -> updateSchedule(
                    word.card.id, item.answerRevision, schedule.dueDay, schedule.pendingStep, schedule.intervalDays, now
                )
                is StudyWord.Legacy -> updateVocabularySchedule(
                    word.vocabulary.id, item.answerRevision, schedule.dueDay, schedule.pendingStep, schedule.intervalDays, now
                )
            }
            check(updated == 1)
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
