package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.model.StudyWord

data class ContextLibraryState(
    val words: List<StudyWord> = emptyList(),
    val round: ContextReviewRound? = null,
    val today: Long = 0,
    val loading: Boolean = true,
    val failed: Boolean = false
) {
    val dueCount: Int get() = words.count { it.dueDay <= today }
    val canResume: Boolean get() = round?.takeIf { it.session.localDay == today }?.current() != null
}
