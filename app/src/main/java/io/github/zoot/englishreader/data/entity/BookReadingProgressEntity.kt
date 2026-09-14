package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍阅读位置，每本书一行。
 *
 * 存「章节 + 段落索引 + 段落内偏移」而非全书百分比：字号、译文开关、屏幕尺寸变化后，
 * 百分比与像素位置都会漂移，而段落索引在同一份正文里是稳定的。
 */
@Entity(
    tableName = "book_reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterArticleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chapterArticleId"])]
)
data class BookReadingProgressEntity(
    /** 书籍 id，同时作为主键——一本书只有一个当前位置 */
    @PrimaryKey
    val bookId: Long,

    /** 当前所在章节的 article id */
    val chapterArticleId: Long,

    /** 章节内段落索引，0-based */
    val paragraphIndex: Int,

    /** v4 的段内像素偏移；v5 起字符锚点由 ReadingPositionEntity 保存，此值写 0。 */
    val paragraphOffset: Int,

    /** 更新时间（时间戳） */
    val updatedAt: Long = System.currentTimeMillis()
)
