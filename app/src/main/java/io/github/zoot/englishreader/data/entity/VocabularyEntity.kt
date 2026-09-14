package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 生词实体
 *
 * 存储用户长按提取的生词
 * 唯一约束：同一文章同一单词只保存一次
 * 级联删除：删除文章时自动删除关联生词
 */
@Entity(
    tableName = "vocabulary",
    indices = [
        Index(value = ["word", "articleId"], unique = true),
        Index(value = ["articleId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 单词 */
    val word: String,

    /** 所属文章 ID（可选） */
    val articleId: Long? = null,

    /** 添加时间（时间戳） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 音标（从 API 获取） */
    val phonetic: String? = null,

    /** 词性和解释（JSON 格式存储） */
    val definitions: String? = null,

    /** 解释来源（api/llm） */
    val definitionSource: String? = null
)
