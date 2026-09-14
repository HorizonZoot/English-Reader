package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingPosition
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleReadingPositionTest {
    @Test
    fun save_callerLeaves_writeFinishesAndReopenWaitsForSavedPosition() = runTest {
        val dao = mockk<ArticleDao>()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var stored: ReadingPositionEntity? = null
        coEvery { dao.latestReadingTimestamp() } returns null
        coEvery { dao.saveReadingPosition(any()) } coAnswers {
            val snapshot = firstArg<ReadingPositionEntity>()
            started.complete(Unit)
            gate.await()
            stored = snapshot
        }
        coEvery { dao.getReadingPosition(42) } coAnswers { stored }
        val repository = ArticleRepository(dao, backgroundScope)
        val expected = ReadingPosition(42, ReadingAnchor(3, characterOffset = 17))
        val caller = launch { repository.saveReadingPosition(expected) }
        runCurrent()
        assertTrue(started.isCompleted)
        caller.cancel()
        val reopened = async { repository.getReadingPosition(42) }
        runCurrent()
        assertFalse(reopened.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(expected, reopened.await())
    }

    @Test
    fun save_clockBehindPersistedRecord_continuesMonotonicWriteOrder() = runTest {
        val dao = mockk<ArticleDao>()
        val persistedTime = System.currentTimeMillis() + 86_400_000
        val saves = mutableListOf<ReadingPositionEntity>()
        coEvery { dao.latestReadingTimestamp() } returns persistedTime
        coEvery { dao.saveReadingPosition(any()) } coAnswers { saves += firstArg<ReadingPositionEntity>() }
        val repository = ArticleRepository(dao, backgroundScope)

        repository.saveReadingPosition(ReadingPosition(42, ReadingAnchor(1)))
        repository.saveReadingPosition(ReadingPosition(43, ReadingAnchor(2)))

        assertEquals(listOf(42L, 43L), saves.map { it.articleId })
        assertTrue(saves.first().updatedAt > persistedTime)
        assertTrue(saves.last().updatedAt > saves.first().updatedAt)
    }
}
