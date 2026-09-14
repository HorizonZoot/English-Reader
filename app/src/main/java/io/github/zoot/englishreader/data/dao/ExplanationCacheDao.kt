package io.github.zoot.englishreader.data.dao

import androidx.room.*
import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity

/**
 * AI 解释缓存数据访问对象
 */
@Dao
interface ExplanationCacheDao {

    @Query(
        "SELECT * FROM explanation_cache " +
            "WHERE cacheKey = :cacheKey AND createdAt >= :expireBefore"
    )
    suspend fun getCachedExplanation(
        cacheKey: String,
        expireBefore: Long
    ): ExplanationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(cache: ExplanationCacheEntity)

    @Query("DELETE FROM explanation_cache WHERE createdAt < :expireBefore")
    suspend fun deleteExpiredCache(expireBefore: Long): Int

    @Query(
        """
        DELETE FROM explanation_cache
        WHERE cacheKey IN (
            SELECT cacheKey FROM explanation_cache
            ORDER BY createdAt DESC, cacheKey DESC
            LIMIT -1 OFFSET :keep
        )
        """
    )
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM explanation_cache")
    suspend fun clearAllCache(): Int
}
