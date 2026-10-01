package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "excerpts",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["articleId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index("articleId"),
        Index(value = ["originArticleId", "paragraphIndex", "startOffset", "endOffset", "text"], unique = true)
    ]
)
data class ExcerptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: Long?,
    val originArticleId: Long,
    val sourceTitle: String,
    val contentFingerprint: String,
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun toString(): String = "ExcerptEntity(id=$id, length=${text.length})"
}
