package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
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
        val repository = ArticleRepository(dao, backgroundScope, mockk())
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
    fun saveEdit_pendingOldPosition_finishesBeforeResetAndUsesLaterTimestamp() = runTest {
        val dao = mockk<ArticleDao>()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val timestamps = mutableListOf<Long>()
        val original = ArticleEditSnapshot(42, "Title", "Old body.")
        coEvery { dao.latestReadingTimestamp() } returns null
        coEvery { dao.saveReadingPositionIfContent(any(), "Old body.") } coAnswers {
            started.complete(Unit)
            gate.await()
            timestamps += firstArg<ReadingPositionEntity>().updatedAt
            events += "old-position"
        }
        coEvery { dao.saveEdit(original, "Title", "New body.", any()) } coAnswers {
            timestamps += arg<Long>(3)
            events += "edit-reset"
            ArticleEditResult.Saved
        }
        val repository = ArticleRepository(dao, backgroundScope, mockk())
        val oldSave = launch {
            repository.saveReadingPosition(ReadingPosition(42, ReadingAnchor(3)), original.content)
        }
        runCurrent()
        assertTrue(started.isCompleted)
        val edit = async { repository.saveEdit(original, "Title", "New body.") }
        runCurrent()
        assertFalse(edit.isCompleted)
        assertTrue(events.isEmpty())

        gate.complete(Unit)
        oldSave.join()
        assertEquals(ArticleEditResult.Saved, edit.await())
        assertEquals(listOf("old-position", "edit-reset"), events)
        assertTrue(timestamps.last() > timestamps.first())
    }

    @Test
    fun save_clockBehindPersistedRecord_continuesMonotonicWriteOrder() = runTest {
        val dao = mockk<ArticleDao>()
        val persistedTime = System.currentTimeMillis() + 86_400_000
        val saves = mutableListOf<ReadingPositionEntity>()
        coEvery { dao.latestReadingTimestamp() } returns persistedTime
        coEvery { dao.saveReadingPosition(any()) } coAnswers { saves += firstArg<ReadingPositionEntity>() }
        val repository = ArticleRepository(dao, backgroundScope, mockk())

        repository.saveReadingPosition(ReadingPosition(42, ReadingAnchor(1)))
        repository.saveReadingPosition(ReadingPosition(43, ReadingAnchor(2)))

        assertEquals(listOf(42L, 43L), saves.map { it.articleId })
        assertTrue(saves.first().updatedAt > persistedTime)
        assertTrue(saves.last().updatedAt > saves.first().updatedAt)
    }
}
