package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "context_cards",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["articleId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index("articleId"),
        Index("dueDay"),
        Index(
            value = ["originArticleId", "contentFingerprint", "paragraphIndex", "sentenceStartOffset",
                "sentenceEndOffset", "targetStartOffset", "targetEndOffset", "word"],
            unique = true,
            name = "index_context_cards_occurrence"
        )
    ]
)
data class ContextCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: Long?,
    val originArticleId: Long,
    val sourceTitle: String,
    val contentFingerprint: String,
    val paragraphIndex: Int,
    val sentenceStartOffset: Int,
    val sentenceEndOffset: Int,
    val sentence: String,
    val targetStartOffset: Int,
    val targetEndOffset: Int,
    val word: String,
    val dictionaryWord: String,
    val meaning: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dueDay: Long,
    val pendingStep: Int = 0,
    val intervalDays: Int = 0,
    val lastReviewedAt: Long? = null,
    val answerRevision: Long = 0
)
