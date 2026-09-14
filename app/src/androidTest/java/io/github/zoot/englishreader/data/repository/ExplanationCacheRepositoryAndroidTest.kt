package io.github.zoot.englishreader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.dao.ExplanationCacheDao
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExplanationCacheRepositoryAndroidTest {

    private lateinit var database: EnglishReaderDatabase
    private lateinit var cacheDao: ExplanationCacheDao
    private lateinit var repository: ExplanationCacheRepository

    private val nowMillis = TimeUnit.DAYS.toMillis(100)
    private val expireBefore = TimeUnit.DAYS.toMillis(70)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheDao = database.explanationCacheDao()
        repository = ExplanationCacheRepository(cacheDao, database) { nowMillis }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getCachedExplanation_cutoffIsInclusiveAndOlderRowMissesBeforeCleanup() = runBlocking {
        cacheDao.insertCache(cache("at-boundary", expireBefore))
        cacheDao.insertCache(cache("one-millisecond-old", expireBefore - 1))

        assertNotNull(repository.getCachedExplanation("at-boundary"))
        assertNull(repository.getCachedExplanation("one-millisecond-old"))
        assertEquals(2, rowCount())
    }

    @Test
    fun getCachedExplanation_repeatedHitsDoNotChangeCreatedAt() = runBlocking {
        val originalCreatedAt = expireBefore + 1234
        cacheDao.insertCache(cache("stable-created-at", originalCreatedAt))

        assertNotNull(repository.getCachedExplanation("stable-created-at"))
        assertNotNull(repository.getCachedExplanation("stable-created-at"))

        assertEquals(originalCreatedAt, createdAtFor("stable-created-at"))
    }

    @Test
    fun deleteExpiredCache_strictlyDeletesOnlyRowsOlderThanCutoff() = runBlocking {
        cacheDao.insertCache(cache("at-boundary", expireBefore))
        cacheDao.insertCache(cache("expired", expireBefore - 1))

        val deleted = cacheDao.deleteExpiredCache(expireBefore)

        assertEquals(1, deleted)
        assertEquals(listOf("at-boundary"), retainedKeys())
    }

    @Test
    fun insertCache_whenFiveHundredValidRowsExist_keepsNewRowWithinCapacity() = runBlocking {
        repeat(ExplanationCachePolicy.MAX_CACHE_ENTRIES) { index ->
            cacheDao.insertCache(cache("existing-${index.toString().padStart(3, '0')}", nowMillis - 1))
        }

        repository.insertCache(cache("new-row", nowMillis))

        assertEquals(ExplanationCachePolicy.MAX_CACHE_ENTRIES, rowCount())
        assertTrue("The inserted row must participate in trimming", retainedKeys().contains("new-row"))
    }

    @Test
    fun insertCache_equalTimestampsRetainFiveHundredGreatestCacheKeys() = runBlocking {
        repeat(ExplanationCachePolicy.MAX_CACHE_ENTRIES) { index ->
            cacheDao.insertCache(cache("key-${index.toString().padStart(3, '0')}", nowMillis))
        }

        repository.insertCache(cache("key-500", nowMillis))

        val expected = (1..500).map { index -> "key-${index.toString().padStart(3, '0')}" }
        assertEquals(expected, retainedKeys())
    }

    @Test
    fun insertCache_removesExpiredRowsBeforeApplyingCapacityBound() = runBlocking {
        repeat(ExplanationCachePolicy.MAX_CACHE_ENTRIES - 1) { index ->
            cacheDao.insertCache(cache("valid-${index.toString().padStart(3, '0')}", expireBefore))
        }
        repeat(25) { index ->
            cacheDao.insertCache(cache("expired-${index.toString().padStart(3, '0')}", expireBefore - 1))
        }

        repository.insertCache(cache("new-row", nowMillis))

        val keys = retainedKeys()
        assertEquals(ExplanationCachePolicy.MAX_CACHE_ENTRIES, keys.size)
        assertTrue(keys.none { it.startsWith("expired-") })
        assertTrue(keys.contains("new-row"))
    }

    @Test
    fun clearAllCache_returnsDeletedCountAndEmptiesTable() = runBlocking {
        cacheDao.insertCache(cache("first", nowMillis))
        cacheDao.insertCache(cache("second", nowMillis))

        val deleted = repository.clearAllCache()

        assertEquals(2, deleted)
        assertEquals(0, rowCount())
    }

    private fun cache(cacheKey: String, createdAt: Long) = ExplanationCacheEntity(
        cacheKey = cacheKey,
        explanation = "explanation for $cacheKey",
        createdAt = createdAt
    )

    private fun rowCount(): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM explanation_cache")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun retainedKeys(): List<String> = database.openHelper.readableDatabase
        .query("SELECT cacheKey FROM explanation_cache ORDER BY cacheKey ASC")
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun createdAtFor(cacheKey: String): Long = database.openHelper.readableDatabase
        .query(
            "SELECT createdAt FROM explanation_cache WHERE cacheKey = ?",
            arrayOf(cacheKey)
        )
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
