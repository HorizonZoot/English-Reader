package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.core.ExcerptRange
import io.github.zoot.englishreader.data.dao.ExcerptDao
import io.github.zoot.englishreader.data.dao.ExcerptWriteResult
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptRepositoryTest {
    private val dao = mockk<ExcerptDao>()
    private val repository = ExcerptRepository(dao)
    private val article = ArticleEntity(7, "Source", "First paragraph.\n\nAlpha  beta, gamma.")
    private val excerpt = ExcerptEntity(
        id = 4, articleId = 7, originArticleId = 7, sourceTitle = "Source",
        contentFingerprint = ExcerptRepository.fingerprint(article.content),
        paragraphIndex = 1, startOffset = 7, endOffset = 18, text = "beta, gamma", createdAt = 10
    )

    @Test
    fun save_exactSlice_preservesTextAndSourceCoordinates() = runTest {
        val saved = slot<ExcerptEntity>()
        coEvery { dao.saveResolvingOverlap(capture(saved), article.content, "Alpha  beta, gamma.", null) } returns ExcerptWriteResult.SAVED
        assertEquals(ExcerptSaveResult.SAVED, repository.save(article, 1, ExcerptRange(7, 18)))
        assertEquals(excerpt.copy(id = 0, createdAt = saved.captured.createdAt), saved.captured)
    }

    @Test
    fun save_duplicateOrChangedSource_returnsTypedResult() = runTest {
        coEvery { dao.saveResolvingOverlap(any(), article.content, "Alpha  beta, gamma.", null) } returnsMany
            listOf(ExcerptWriteResult.ALREADY_EXISTS, ExcerptWriteResult.SOURCE_CHANGED)
        assertEquals(ExcerptSaveResult.ALREADY_EXISTS, repository.save(article, 1, ExcerptRange(7, 18)))
        assertEquals(ExcerptSaveResult.SOURCE_CHANGED, repository.save(article, 1, ExcerptRange(7, 18)))
    }

    @Test
    fun save_invalidOrBlankRange_doesNotTouchDatabase() = runTest {
        assertEquals(ExcerptSaveResult.SOURCE_CHANGED, repository.save(article, 9, ExcerptRange(0, 2)))
        assertEquals(ExcerptSaveResult.SOURCE_CHANGED, repository.save(article, 1, ExcerptRange(0, 99)))
        assertEquals(ExcerptSaveResult.SOURCE_CHANGED, repository.save(article, 1, ExcerptRange(5, 7)))
        coVerify(exactly = 0) { dao.saveResolvingOverlap(any(), any(), any(), any()) }
    }

    @Test
    fun save_cancelled_propagatesCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { dao.saveResolvingOverlap(any(), any(), any(), any()) } throws cancellation
        val thrown = try {
            repository.save(article, 1, ExcerptRange(7, 18))
            null
        } catch (error: CancellationException) { error }
        assertTrue(thrown === cancellation)
    }

    @Test
    fun hasSource_existingArticle_doesNotRejectEditedBody() = runTest {
        coEvery { dao.articleExists(7) } returns true
        assertTrue(repository.hasSource(excerpt.copy(contentFingerprint = "old fingerprint")))
        assertFalse(repository.hasSource(excerpt.copy(articleId = null)))
        coEvery { dao.articleExists(7) } returns false
        assertFalse(repository.hasSource(excerpt))
    }

    @Test
    fun sourceAnchor_exactSource_restoresOriginalOffsetOnly() {
        assertEquals(ReadingAnchor(1, ReadingTextKind.ORIGINAL, 7),
            ExcerptRepository.sourceAnchor(excerpt, 7, article.content))
        assertNull(ExcerptRepository.sourceAnchor(excerpt, 8, article.content))
        assertNull(ExcerptRepository.sourceAnchor(excerpt.copy(articleId = null), 7, article.content))
        assertNull(ExcerptRepository.sourceAnchor(excerpt, 7, article.content + " changed"))
        assertNull(ExcerptRepository.sourceAnchor(excerpt.copy(text = "different"), 7, article.content))
        assertNull(ExcerptRepository.sourceAnchor(excerpt.copy(endOffset = 99), 7, article.content))
    }

    @Test
    fun toString_doesNotExposeExcerptOrSourceTitle() {
        assertFalse(excerpt.toString().contains(excerpt.text))
        assertFalse(excerpt.toString().contains(excerpt.sourceTitle))
    }
}
