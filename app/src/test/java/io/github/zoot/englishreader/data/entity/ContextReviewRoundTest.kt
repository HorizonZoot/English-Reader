package io.github.zoot.englishreader.data.entity

import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.util.contextEntry
import io.github.zoot.englishreader.util.contextRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextReviewRoundTest {
    @Test
    fun current_deletedRatedCard_keepsEarnedRetryAndAnswerCount() {
        val graded = (1L..4L).map { id ->
            contextEntry(id).let { it.copy(item = it.item.copy(grade = ContextGrade.REMEMBERED)) }
        }
        val first = graded.first()
        val retry = first.copy(item = first.item.copy(itemToken = "retry", grade = null,
            isRetry = true, revealed = true, eligibleAfterCount = 4))
        val before = contextRound(graded + retry)
        val after = before.copy(entries = before.entries.filter { it.card.id != 2L })
        assertEquals(4, after.answeredCount())
        assertEquals(retry, after.current())
    }

    @Test
    fun current_unorderedRelation_respectsSavedOrderAndRevision() {
        val first = contextEntry(1)
        val second = contextEntry(2)
        val third = contextEntry(3)
        val round = contextRound(listOf(third, first, second))
        assertEquals(first, round.current())
        assertEquals(second, round.copy(entries = listOf(third, first.copy(card = first.card.copy(answerRevision = 1)), second)).current())
        assertEquals(third, round.copy(entries = listOf(third, first.copy(item = first.item.copy(skipped = true)), second.copy(item = second.item.copy(grade = ContextGrade.REMEMBERED)))).current())
    }

    @Test
    fun current_forgottenRetry_requiresThreeOtherCommittedAnswers() {
        val first = contextEntry(1)
        val forgotten = first.copy(item = first.item.copy(grade = ContextGrade.FORGOTTEN))
        val retry = first.copy(item = first.item.copy(itemToken = "retry-1", position = 10, isRetry = true, eligibleAfterCount = 4))
        val others = (2L..5L).map { contextEntry(it) }
        (0..3).forEach { answeredOthers ->
            val entries = others.mapIndexed { index, entry ->
                if (index < answeredOthers) entry.copy(item = entry.item.copy(grade = ContextGrade.REMEMBERED)) else entry
            }
            val round = contextRound(listOf(forgotten, retry) + entries)
            val expected = if (answeredOthers == 3) retry else others[answeredOthers]
            assertEquals(expected, round.current())
        }
    }

    @Test
    fun current_eligibleRetries_precedePrimariesInPersistedRetryOrder() {
        val first = contextEntry(1)
        val second = contextEntry(2)
        val firstRetry = first.copy(item = first.item.copy(itemToken = "retry-1", position = 10, isRetry = true, eligibleAfterCount = 2))
        val secondRetry = second.copy(item = second.item.copy(itemToken = "retry-2", position = 11, isRetry = true, eligibleAfterCount = 2))
        val graded = listOf(first, second).map { it.copy(item = it.item.copy(grade = ContextGrade.FORGOTTEN)) }
        val round = contextRound(listOf(secondRetry, contextEntry(3), firstRetry) + graded)
        assertEquals(firstRetry, round.current())
    }

    @Test
    fun current_insufficientOrSkippedInterveningCards_doesNotForceRetry() {
        val first = contextEntry(1)
        val retry = first.copy(item = first.item.copy(isRetry = true, eligibleAfterCount = 4))
        val skipped = contextEntry(2).let { it.copy(item = it.item.copy(skipped = true)) }
        val round = contextRound(listOf(first.copy(item = first.item.copy(grade = ContextGrade.FORGOTTEN)), retry, skipped))
        assertNull(round.current())
        assertEquals(1, round.answeredCount())
    }

    @Test
    fun current_recreatedRound_preservesRevealedTurnAndCommittedGrades() {
        val first = contextEntry(1).let { it.copy(item = it.item.copy(grade = ContextGrade.FUZZY)) }
        val second = contextEntry(2, revealed = true)
        val original = contextRound(listOf(first, second))
        val restored = ContextReviewRound(original.session.copy(), original.entries.map { it.copy(item = it.item.copy(), card = it.card.copy()) })
        assertEquals(second, restored.current())
        assertEquals(1, restored.answeredCount())
    }
}
