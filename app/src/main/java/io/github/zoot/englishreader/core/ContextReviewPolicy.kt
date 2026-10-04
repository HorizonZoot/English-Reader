package io.github.zoot.englishreader.core

enum class ContextGrade { FORGOTTEN, FUZZY, REMEMBERED }

enum class VocabularyStudyMode { LEARN, DUE }

data class ContextReviewSchedule(val dueDay: Long, val pendingStep: Int, val intervalDays: Int)

object ContextReviewPolicy {
    const val ROUND_SIZE = 10
    const val RETRY_SPACING = 3
    private val intervals = intArrayOf(1, 3, 7, 14, 30, 60)

    fun schedule(today: Long, pendingStep: Int, grade: ContextGrade): ContextReviewSchedule {
        val step = pendingStep.coerceIn(intervals.indices)
        val days = if (grade == ContextGrade.REMEMBERED) intervals[step] else 1
        val nextStep = when (grade) {
            ContextGrade.REMEMBERED -> (step + 1).coerceAtMost(intervals.lastIndex)
            ContextGrade.FUZZY -> (step - 1).coerceAtLeast(0)
            ContextGrade.FORGOTTEN -> 0
        }
        return ContextReviewSchedule(today + days, nextStep, days)
    }
}
