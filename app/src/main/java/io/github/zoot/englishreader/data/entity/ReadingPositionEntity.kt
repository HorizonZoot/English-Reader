package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_positions",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadingPositionEntity(
    @PrimaryKey val articleId: Long,
    val paragraphIndex: Int,
    val textKind: String,
    val characterOffset: Int,
    val updatedAt: Long
)
