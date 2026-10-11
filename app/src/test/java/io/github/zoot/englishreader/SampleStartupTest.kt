package io.github.zoot.englishreader

import android.content.Context
import android.content.SharedPreferences
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.util.TestDataGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class SampleStartupTest {
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context> {
        every { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) } returns prefs
    }
    private val repository = mockk<ArticleRepository>()

    @Test
    fun refreshSampleArticlesIfNeeded_staleVersion_readsAndWritesOffCallerThreadInOrder() = runTest {
        val events = mutableListOf<String>()
        val threads = mutableListOf<String>()
        fun record(event: String) {
            events += event
            threads += Thread.currentThread().name
        }
        every { context.getSharedPreferences(any(), any()) } answers { record("open"); prefs }
        every { prefs.getInt("sample_data_version", 0) } answers { record("read"); 0 }
        coEvery { repository.refreshSampleArticles(any(), any()) } coAnswers {
            assertEquals(TestDataGenerator.SAMPLE_SOURCES, firstArg<List<String>>())
            assertEquals(
                TestDataGenerator.getSampleArticles().map { it.title to it.content },
                secondArg<List<ArticleEntity>>().map { it.title to it.content }
            )
            record("refresh")
        }
        every { prefs.edit() } answers { record("edit"); editor }
        every { editor.putInt("sample_data_version", TestDataGenerator.SAMPLE_DATA_VERSION) } answers { record("version"); editor }
        every { editor.apply() } answers { record("apply") }

        Executors.newSingleThreadExecutor { Thread(it, "sample-startup-io") }.asCoroutineDispatcher().use {
            refreshSampleArticlesIfNeeded(context, repository, it)
        }

        assertEquals(listOf("open", "read", "refresh", "edit", "version", "apply"), events)
        assertTrue(threads.all { it == "sample-startup-io" })
    }

    @Test
    fun refreshSampleArticlesIfNeeded_currentOrNewerVersion_doesNotRewriteSamplesOrVersion() = runTest {
        for (version in listOf(TestDataGenerator.SAMPLE_DATA_VERSION, TestDataGenerator.SAMPLE_DATA_VERSION + 1)) {
            every { prefs.getInt("sample_data_version", 0) } returns version
            refreshSampleArticlesIfNeeded(context, repository, StandardTestDispatcher(testScheduler))
        }
        coVerify(exactly = 0) { repository.refreshSampleArticles(any(), any()) }
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun refreshSampleArticlesIfNeeded_refreshFailsOrCancels_neverMarksVersionSuccessful() = runTest {
        every { prefs.getInt("sample_data_version", 0) } returns 0
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            coEvery { repository.refreshSampleArticles(any(), any()) } throws failure
            assertPropagated(failure, runCatching {
                refreshSampleArticlesIfNeeded(context, repository, StandardTestDispatcher(testScheduler))
            }.exceptionOrNull())
        }
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun refreshSampleArticlesIfNeeded_cancelledDuringRefresh_rejectsLateSuccessfulCompletion() = runTest {
        every { prefs.getInt("sample_data_version", 0) } returns 0
        val pending = CompletableDeferred<Unit>()
        coEvery { repository.refreshSampleArticles(any(), any()) } coAnswers {
            withContext(NonCancellable) { pending.await() }
        }
        val job = launch {
            refreshSampleArticlesIfNeeded(context, repository, StandardTestDispatcher(testScheduler))
        }
        runCurrent()
        coVerify(exactly = 1) { repository.refreshSampleArticles(any(), any()) }
        job.cancel()
        pending.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun refreshSampleArticlesIfNeeded_preferencesFail_propagatesWithoutRefreshing() = runTest {
        val failure = IllegalStateException("read failed")
        every { prefs.getInt("sample_data_version", 0) } throws failure
        assertPropagated(failure, runCatching {
            refreshSampleArticlesIfNeeded(context, repository, StandardTestDispatcher(testScheduler))
        }.exceptionOrNull())
        coVerify(exactly = 0) { repository.refreshSampleArticles(any(), any()) }
        verify(exactly = 0) { prefs.edit() }
    }

    private fun assertPropagated(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }
}
