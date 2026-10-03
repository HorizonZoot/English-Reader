package io.github.zoot.englishreader.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextReviewPolicyTest {
    @Test
    fun schedule_remembered_usesPendingStepBeforeAdvancingAndCapsAtSixtyDays() {
        val expectedDays = listOf(1, 3, 7, 14, 30, 60, 60)
        var step = 0
        expectedDays.forEach { days ->
            val result = ContextReviewPolicy.schedule(20_000, step, ContextGrade.REMEMBERED)
            assertEquals(20_000L + days, result.dueDay)
            assertEquals(days, result.intervalDays)
            step = result.pendingStep
        }
        assertEquals(5, step)
    }

    @Test
    fun schedule_fuzzyAndForgotten_alwaysTomorrowWithDifferentStepRecovery() {
        (0..5).forEach { step ->
            val fuzzy = ContextReviewPolicy.schedule(20_000, step, ContextGrade.FUZZY)
            assertEquals(ContextReviewSchedule(20_001, (step - 1).coerceAtLeast(0), 1), fuzzy)
            assertEquals(ContextReviewSchedule(20_001, 0, 1), ContextReviewPolicy.schedule(20_000, step, ContextGrade.FORGOTTEN))
        }
    }
}
