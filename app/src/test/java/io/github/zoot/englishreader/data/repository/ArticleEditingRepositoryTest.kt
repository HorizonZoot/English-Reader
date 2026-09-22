package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArticleEditingRepositoryTest {
    @Test
    fun saveEdit_invalidChangedBody_rejectsBeforeDatabaseWork() = runTest {
        val dao = mockk<ArticleDao>()
        val repository = ArticleRepository(dao, backgroundScope, mockk())
        val original = ArticleEditSnapshot(7, "Title", "Original.")

        assertEquals(
            ArticleEditResult.InvalidContent(ImportFailure.EmptyContent),
            repository.saveEdit(original, "New title", " \n ")
        )
        coVerify(exactly = 0) { dao.latestReadingTimestamp() }
        coVerify(exactly = 0) { dao.saveEdit(any(), any(), any(), any()) }
    }

    @Test
    fun saveEdit_titleOnly_preservesLegacyBodyWithoutRevalidatingIt() = runTest {
        val dao = mockk<ArticleDao>()
        val original = ArticleEditSnapshot(7, "Title", "A".repeat(40_001))
        coEvery { dao.latestReadingTimestamp() } returns null
        coEvery { dao.saveEdit(original, "Renamed", original.content, any()) } returns ArticleEditResult.Saved
        val repository = ArticleRepository(dao, backgroundScope, mockk())

        assertEquals(ArticleEditResult.Saved, repository.saveEdit(original, "  Renamed  ", original.content))
        coVerify(exactly = 1) { dao.saveEdit(original, "Renamed", original.content, any()) }
    }

    @Test
    fun saveEdit_changedBody_trimsEdgesWithoutRewritingInternalParagraphs() = runTest {
        val dao = mockk<ArticleDao>()
        val original = ArticleEditSnapshot(7, "Title", "Original.")
        coEvery { dao.latestReadingTimestamp() } returns null
        coEvery { dao.saveEdit(original, "Title", "First.\n\nSecond.", any()) } returns ArticleEditResult.Saved
        val repository = ArticleRepository(dao, backgroundScope, mockk())

        assertEquals(
            ArticleEditResult.Saved,
            repository.saveEdit(original, "  ", " \nFirst.\n\nSecond.\n ")
        )
        coVerify(exactly = 1) { dao.saveEdit(original, "Title", "First.\n\nSecond.", any()) }
    }

    @Test
    fun snapshot_toString_redactsUserTitleAndContent() {
        val rendered = ArticleEditSnapshot(7, "Private title", "Private body").toString()
        assertFalse(rendered.contains("Private title"))
        assertFalse(rendered.contains("Private body"))
    }
}
