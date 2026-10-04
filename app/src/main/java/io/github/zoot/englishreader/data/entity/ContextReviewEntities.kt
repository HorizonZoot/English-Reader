package io.github.zoot.englishreader.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.model.StudyWord

@Entity(tableName = "context_review_session")
data class ContextReviewSessionEntity(
    @PrimaryKey val id: Int = 1,
    val sessionToken: String,
    val localDay: Long,
    val createdAt: Long,
    val initialCount: Int,
    val answerCount: Int = 0,
    @ColumnInfo(defaultValue = "'DUE'") val mode: VocabularyStudyMode = VocabularyStudyMode.DUE
)

@Entity(
    tableName = "context_review_items",
    foreignKeys = [
        ForeignKey(
            entity = ContextCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocabularyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("cardId"),
        Index("vocabularyId"),
        Index(value = ["sessionToken", "position"]),
        Index(value = ["sessionToken", "cardId", "isRetry"], unique = true),
        Index(value = ["sessionToken", "vocabularyId", "isRetry"], unique = true)
    ]
)
data class ContextReviewItemEntity(
    @PrimaryKey val itemToken: String,
    val sessionToken: String,
    val cardId: Long?,
    val answerRevision: Long,
    val meaningSnapshot: String,
    val position: Int,
    val isRetry: Boolean = false,
    val eligibleAfterCount: Int = 0,
    val revealed: Boolean = false,
    val grade: ContextGrade? = null,
    val ratedAt: Long? = null,
    val ratedDay: Long? = null,
    val appliedIntervalDays: Int? = null,
    val nextPendingStep: Int? = null,
    val skipped: Boolean = false,
    val vocabularyId: Long? = null,
    @ColumnInfo(defaultValue = "1") val updatesSchedule: Boolean = true
) {
    init {
        require((cardId != null) != (vocabularyId != null))
    }
}

data class ContextReviewEntry(
    @Embedded val item: ContextReviewItemEntity,
    @Relation(parentColumn = "cardId", entityColumn = "id") val card: ContextCardEntity?,
    @Relation(parentColumn = "vocabularyId", entityColumn = "id") val vocabulary: VocabularyEntity? = null
) {
    fun studyWord(): StudyWord? = when {
        card != null && vocabulary == null -> StudyWord.Context(card)
        vocabulary != null && card == null -> StudyWord.Legacy(vocabulary)
        else -> null
    }
}

data class ContextReviewRound(
    @Embedded val session: ContextReviewSessionEntity,
    @Relation(
        parentColumn = "sessionToken",
        entityColumn = "sessionToken",
        entity = ContextReviewItemEntity::class
    ) val entries: List<ContextReviewEntry>
) {
    fun answeredCount(): Int = session.answerCount

    fun current(): ContextReviewEntry? {
        val answered = answeredCount()
        return entries.asSequence()
            .filter { !it.item.skipped && it.item.grade == null }
            .filter { it.item.answerRevision == it.studyWord()?.answerRevision }
            .filter { !it.item.isRetry || answered >= it.item.eligibleAfterCount }
            .minWithOrNull(
                compareBy<ContextReviewEntry> { !it.item.isRetry }.thenBy { it.item.position }
            )
    }
}
