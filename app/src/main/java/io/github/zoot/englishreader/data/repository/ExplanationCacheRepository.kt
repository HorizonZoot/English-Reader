package io.github.zoot.englishreader.data.repository

import androidx.room.withTransaction
import io.github.zoot.englishreader.data.dao.ExplanationCacheDao
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 解释缓存仓库
 *
 * 封装缓存数据访问逻辑
 */
@Singleton
class ExplanationCacheRepository internal constructor(
    private val cacheDao: ExplanationCacheDao,
    private val database: EnglishReaderDatabase,
    private val currentTimeMillis: () -> Long
) {
    @Inject
    constructor(
        cacheDao: ExplanationCacheDao,
        database: EnglishReaderDatabase
    ) : this(cacheDao, database, System::currentTimeMillis)

    suspend fun getCachedExplanation(cacheKey: String): ExplanationCacheEntity? {
        val expireBefore = ExplanationCachePolicy.expireBefore(currentTimeMillis())
        return cacheDao.getCachedExplanation(cacheKey, expireBefore)
    }

    suspend fun insertCache(cache: ExplanationCacheEntity) {
        val expireBefore = ExplanationCachePolicy.expireBefore(currentTimeMillis())
        database.withTransaction {
            cacheDao.deleteExpiredCache(expireBefore)
            cacheDao.insertCache(cache)
            cacheDao.trimToNewest(ExplanationCachePolicy.MAX_CACHE_ENTRIES)
        }
    }

    suspend fun clearAllCache(): Int = cacheDao.clearAllCache()
}
