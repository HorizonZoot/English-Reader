package io.github.zoot.englishreader.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.remote.dictionary.DictionaryApiService
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DictionaryRepositoryEscapeRepairTest {

    private lateinit var context: Context
    private lateinit var db: EnglishReaderDatabase
    private lateinit var dao: DictionaryDao
    private lateinit var prefs: SharedPreferences
    private val api = mockk<DictionaryApiService>()

    private val installedRows = listOf(
        DictionaryEntry(
            word = "hood",
            phonetic = "/hʊd/",
            chinese = "n. 罩；风帽\\nv. 覆盖",
            english = "a covering\\r\\nfor the head"
        ),
        DictionaryEntry(
            word = "ast",
            phonetic = null,
            chinese = "abbr. 大西洋标准时间\\rn. 人名",
            english = null
        ),
        DictionaryEntry(
            word = "clean",
            phonetic = "/kliːn/",
            chinese = "adj. 干净\nv. 清理",
            english = "free from dirt"
        )
    )
    private val repairedRows = listOf(
        installedRows[0].copy(
            chinese = "n. 罩；风帽\nv. 覆盖",
            english = "a covering\nfor the head"
        ),
        installedRows[1].copy(chinese = "abbr. 大西洋标准时间\nn. 人名"),
        installedRows[2]
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Application>()
        prefs = context.getSharedPreferences(DICTIONARY_PREFS, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().putInt(KEY_DICT_VERSION, INSTALLED_VERSION).commit())
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        dao = db.dictionaryDao()
        dao.insertAll(installedRows)
    }

    @After
    fun tearDown() {
        try {
            db.close()
        } finally {
            assertTrue(prefs.edit().clear().commit())
        }
    }

    @Test
    fun ensureInitialized_version100_repairsRowsInPlaceAndPreservesVersion() = runBlocking {
        val trackingDao = DecodeTrackingDao(dao)

        createRepository(trackingDao).ensureInitialized()

        assertEquals(1, trackingDao.decodeCalls)
        assertInstalledRows(repairedRows)
        assertTrue(prefs.getBoolean(KEY_ESCAPES_REPAIRED, false))
    }

    @Test
    fun ensureInitialized_repairCompleted_newInstanceSkipsDecode() = runBlocking {
        val firstDao = DecodeTrackingDao(dao)
        createRepository(firstDao).ensureInitialized()
        assertEquals(1, firstDao.decodeCalls)
        assertTrue(prefs.getBoolean(KEY_ESCAPES_REPAIRED, false))

        val secondDao = DecodeTrackingDao(dao)
        createRepository(secondDao).ensureInitialized()

        assertEquals("the persistent marker must survive a new repository", 0, secondDao.decodeCalls)
        assertInstalledRows(repairedRows)
        assertTrue(prefs.getBoolean(KEY_ESCAPES_REPAIRED, false))
    }

    @Test
    fun ensureInitialized_repairFailure_preservesLookupAndNewInstanceRetries() = runBlocking {
        val failingDao = DecodeTrackingDao(dao, SQLiteException("repair failed"))
        val firstRepository = createRepository(failingDao)

        firstRepository.ensureInitialized()

        assertEquals(1, failingDao.decodeCalls)
        assertFalse(prefs.contains(KEY_ESCAPES_REPAIRED))
        assertInstalledRows(installedRows)
        assertEquals(OfflineLookupResult(installedRows[0]), firstRepository.lookupOffline(" HOOD "))
        assertEquals("a failed repair must not repeat on every lookup", 1, failingDao.decodeCalls)
        assertFalse(prefs.contains(KEY_ESCAPES_REPAIRED))

        val retryDao = DecodeTrackingDao(dao)
        createRepository(retryDao).ensureInitialized()

        assertEquals("the next repository must retry the failed repair", 1, retryDao.decodeCalls)
        assertInstalledRows(repairedRows)
        assertTrue(prefs.getBoolean(KEY_ESCAPES_REPAIRED, false))
    }

    @Test
    fun ensureInitialized_repairCancelled_propagatesWithoutMarkingComplete() = runBlocking {
        val trackingDao = DecodeTrackingDao(dao, CancellationException("repair cancelled"))
        val repository = createRepository(trackingDao)

        val error = runCatching { repository.ensureInitialized() }.exceptionOrNull()

        assertTrue("repair cancellation must reach the caller", error is CancellationException)
        assertEquals(1, trackingDao.decodeCalls)
        assertFalse(prefs.contains(KEY_ESCAPES_REPAIRED))
        assertInstalledRows(installedRows)

        repository.ensureInitialized()

        assertEquals("cancellation must not cache initialization success", 2, trackingDao.decodeCalls)
        assertInstalledRows(repairedRows)
        assertTrue(prefs.getBoolean(KEY_ESCAPES_REPAIRED, false))
    }

    private fun createRepository(dao: DictionaryDao) = DictionaryRepository(context, dao, api)

    private suspend fun assertInstalledRows(expected: List<DictionaryEntry>) {
        assertEquals(
            "the installed dictionary version must stay unchanged",
            INSTALLED_VERSION,
            prefs.getInt(KEY_DICT_VERSION, 0)
        )
        assertEquals("repair must preserve the complete row set", expected.size, dao.getCount())
        expected.forEach { row ->
            assertEquals("unexpected dictionary row for ${row.word}", row, dao.lookup(row.word))
        }
    }

    private class DecodeTrackingDao(
        private val delegate: DictionaryDao,
        private var nextDecodeFailure: Exception? = null
    ) : DictionaryDao by delegate {
        var decodeCalls = 0
            private set

        override suspend fun decodeLiteralEscapes(): Int {
            decodeCalls++
            val failure = nextDecodeFailure
            nextDecodeFailure = null
            if (failure != null) throw failure
            return delegate.decodeLiteralEscapes()
        }
    }

    private companion object {
        const val DICTIONARY_PREFS = "dictionary_prefs"
        const val KEY_DICT_VERSION = "dict_version"
        const val KEY_ESCAPES_REPAIRED = "literal_escapes_repaired"
        const val INSTALLED_VERSION = 100
    }
}
