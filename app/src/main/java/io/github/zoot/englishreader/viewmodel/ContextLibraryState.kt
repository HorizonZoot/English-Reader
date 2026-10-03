package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import java.util.UUID

data class ContextLibraryState(
    val cards: List<ContextCardEntity> = emptyList(),
    val round: ContextReviewRound? = null,
    val today: Long = 0,
    val loading: Boolean = true,
    val failed: Boolean = false
) {
    val dueCount: Int get() = cards.count { it.dueDay <= today }
    val canResume: Boolean get() = round?.takeIf { it.session.localDay == today }?.current() != null
}

data class ContextMeaningEdit(
    val card: ContextCardEntity,
    val meaning: String,
    val saving: Boolean = false,
    val requestId: String = UUID.randomUUID().toString()
)
