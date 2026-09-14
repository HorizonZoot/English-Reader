package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 文章实体
 *
 * 存储用户导入的英文文章
 * 从 PoC 项目迁移并优化
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 文章标题 */
    val title: String,

    /** 文章内容（完整文本） */
    val content: String,

    /** 中文译文（可选，逐句对照） */
    val translation: String? = null,

    /** 文章来源（可选） */
    val source: String? = null,

    /** 创建时间（时间戳） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后阅读时间（时间戳，可选） */
    val lastReadAt: Long? = null
)
