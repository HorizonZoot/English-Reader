package io.github.zoot.englishreader.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 解释缓存实体
 *
 * 缓存 LLM 对句子/单词的解释，避免重复调用 API
 * 使用 CacheKeyFactory 生成唯一键
 */
@Entity(tableName = "explanation_cache")
data class ExplanationCacheEntity(
    @PrimaryKey
    val cacheKey: String,

    /** 解释内容 */
    val explanation: String,

    /** 创建时间（时间戳） */
    val createdAt: Long = System.currentTimeMillis()
)
