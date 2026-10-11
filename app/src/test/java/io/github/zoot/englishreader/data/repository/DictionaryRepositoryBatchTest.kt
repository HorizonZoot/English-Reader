package io.github.zoot.englishreader.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.dictionary.DictionaryMutationLock
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.remote.dictionary.DictionaryApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DictionaryRepositoryBatchTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val dao = mockk<DictionaryDao>()
    private val api = mockk<DictionaryApiService>()
    private val entries = linkedMapOf<String, DictionaryEntry>()
    private val queries = mutableListOf<List<String>>()
    private lateinit var repository: DictionaryRepository

    @Before
    fun setUp() {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getInt(any(), any()) } returns 999
        every { prefs.getBoolean(any(), any()) } returns true
        coEvery { dao.getCount() } returns 20
        coEvery { dao.lookup(any()) } answers { entries[firstArg<String>()] }
        coEvery { dao.lookupBatch(any()) } answers {
            val keys = firstArg<List<String>>()
            queries += keys.toList()
            // SQL does not promise input order.
            keys.mapNotNull(entries::get).reversed()
        }
        repository = DictionaryRepository(context, dao, api, DictionaryMutationLock())
    }

    private fun entry(word: String) = DictionaryEntry(word, "/$word/", "meaning:$word", null)

    @Test
    fun lookupOfflineBatch_emptyInput_doesNotInitializeOrQuery() = runTest {
        assertTrue(repository.lookupOfflineBatch(emptyList()).isEmpty())
        coVerify(exactly = 0) { dao.getCount(); dao.lookupBatch(any()); api.getWordDefinition(any()) }
    }

    @Test
    fun lookupOfflineBatch_normalizationAndDuplicates_shareOneQueryAndKeepNormalizedKeys() = runTest {
        entries["don't"] = entry("don't")
        val result = repository.lookupOfflineBatch(listOf(" DON’T ", "don't", "Don't", "unknown"))

        assertEquals(setOf("don't", "unknown"), result.keys)
        assertEquals(entries["don't"], result["don't"]?.entry)
        assertNull(result["unknown"])
        assertEquals(listOf(listOf("don't", "unknown")), queries)
        coVerify(exactly = 0) { dao.lookup(any()); api.getWordDefinition(any()) }
    }

    @Test
    fun lookupOfflineBatch_exactInflectedAmbiguousAndMissing_matchesSingleWordSemantics() = runTest {
        listOf("running", "run", "use", "us", "live", "life", "leaf", "leave", "child").forEach {
            entries[it] = entry(it)
        }
        val words = listOf("Running", "USES", "lives", "leaves", "children", "zzzzs", "", "unknown")
        val expected = words.associate { it.trim().lowercase() to repository.lookupOffline(it) }

        assertEquals(expected, repository.lookupOfflineBatch(words))
        // Exact match running must win over run even though it is inflected.
        assertTrue(queries.drop(1).flatten().none { it == "run" })
    }

    @Test
    fun lookupOfflineBatch_ambiguousWithOnlySecondBase_matchesSingleWordSemantics() = runTest {
        entries["life"] = entry("life")
        assertEquals(mapOf("lives" to repository.lookupOffline("lives")),
            repository.lookupOfflineBatch(listOf("lives")))
    }

    @Test
    fun lookupOfflineBatch_sharedCandidateAndExactHit_doesNotQuerySameCandidateAgain() = runTest {
        entries["run"] = entry("run")
        val result = repository.lookupOfflineBatch(listOf("run", "runs", "running"))

        assertEquals(listOf("run", "run", "run"), result.values.map { it?.entry?.word })
        assertEquals(1, queries.flatten().count { it == "run" })
        assertTrue(queries.all { it.size == it.distinct().size })
    }

    @Test
    fun lookupOfflineBatch_thousandExactWords_usesFiveQueriesInsteadOfThousand() = runTest {
        val words = (1..1000).map { "word$it" }
        words.forEach { entries[it] = entry(it) }

        val result = repository.lookupOfflineBatch(words + words)

        assertEquals(1000, result.size)
        assertTrue(result.values.all { it != null })
        assertEquals(5, queries.size)
        assertTrue(queries.all { it.size <= 200 })
        coVerify(exactly = 1) { dao.getCount() }
        coVerify(exactly = 0) { dao.lookup(any()); api.getWordDefinition(any()) }
    }

    @Test
    fun lookupOfflineBatch_manyInflections_boundsEverySqlQuery() = runTest {
        val words = (1..1001).map { "word${it}ing" }
        assertEquals(words.toSet(), repository.lookupOfflineBatch(words).keys)
        assertTrue(queries.all { it.size <= 800 })
        assertTrue(queries.size < 20)
    }

    @Test
    fun lookupOfflineBatch_queryFailure_failsOpenForChunkAndContinuesNextChunk() = runTest {
        val words = (1..201).map { "word$it" }
        entries["word201"] = entry("word201")
        coEvery { dao.lookupBatch(match { "word1" in it }) } throws IllegalStateException("unavailable")

        val result = repository.lookupOfflineBatch(words)

        assertEquals(201, result.size)
        assertNull(result["word1"])
        assertEquals(entries["word201"], result["word201"]?.entry)
        coVerify(exactly = 0) { api.getWordDefinition(any()) }
    }

    @Test
    fun lookupOfflineBatch_initializationFailure_stillUsesExistingEntries() = runTest {
        entries["word"] = entry("word")
        coEvery { dao.getCount() } throws IllegalStateException("unavailable")

        assertEquals(entries["word"], repository.lookupOfflineBatch(listOf("word"))["word"]?.entry)
        coVerify(exactly = 0) { api.getWordDefinition(any()) }
    }

    @Test
    fun lookupOfflineBatch_initializationCancelled_propagatesWithoutQuerying() = runTest {
        val cancelled = CancellationException("cancelled")
        coEvery { dao.getCount() } throws cancelled
        assertCancellation(cancelled, runCatching { repository.lookupOfflineBatch(listOf("word")) }.exceptionOrNull())
        coVerify(exactly = 0) { dao.lookupBatch(any()) }
    }

    @Test
    fun lookupOfflineBatch_candidateQueryCancelled_propagatesAndStopsFurtherChunks() = runTest {
        val cancelled = CancellationException("cancelled")
        coEvery { dao.lookupBatch(match { "run" in it }) } throws cancelled
        val words = listOf("running") + (1..200).map { "word$it" }

        assertCancellation(cancelled, runCatching { repository.lookupOfflineBatch(words) }.exceptionOrNull())
        coVerify(exactly = 0) { dao.lookupBatch(match { "word200" in it }) }
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue(actual is CancellationException)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }
}
