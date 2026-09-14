package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookReadingProgressEntity
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 目录页 ViewModel。
 *
 * 重点测「继续阅读」的目标解析，因为它是唯一可能把用户送到**不属于这本书的文章**
 * 的地方：重新导入同一本书会产生新的 articleId，旧进度里的 id 依然存在于 articles
 * 表（属于上一次导入的残留或别的书），直接跳过去不会崩，只会静默打开错的内容。
 */
class BookTocViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun init_loadsBookAndChaptersAndClearsLoading() = runTest {
        val repo = repository(
            book = book(),
            chapters = listOf(chapter(articleId = 11L, index = 0), chapter(articleId = 12L, index = 1))
        )

        val vm = viewModel(repo)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1L, state.book?.id)
        assertEquals(listOf(11L, 12L), state.chapters.map { it.articleId })
        assertFalse(state.isLoading)
    }

    @Test
    fun init_missingBook_stopsLoadingInsteadOfSpinningForever() = runTest {
        // 书被删后 id 仍可能留在返回栈里。此时必须停下加载态让界面显示空态，
        // 否则用户看到的是永久转圈，只能杀进程。
        val repo = repository(book = null, chapters = emptyList())

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertNull(vm.uiState.value.book)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun resumeTargetArticleId_withProgress_returnsProgressChapter() = runTest {
        val repo = repository(
            book = book(),
            chapters = listOf(chapter(articleId = 11L, index = 0), chapter(articleId = 12L, index = 1)),
            progress = progress(chapterArticleId = 12L)
        )

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertEquals(12L, vm.resumeTargetArticleId())
    }

    @Test
    fun resumeTargetArticleId_progressPointsOutsideThisBook_fallsBackToFirstChapter() = runTest {
        // 这是本类存在的理由：进度里的 999 不在当前章节列表中（书被重新导入过）。
        // 不做这层校验就会打开一篇不属于这本书的文章，而且没有任何报错。
        val repo = repository(
            book = book(),
            chapters = listOf(chapter(articleId = 11L, index = 0), chapter(articleId = 12L, index = 1)),
            progress = progress(chapterArticleId = 999L)
        )

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertEquals(11L, vm.resumeTargetArticleId())
    }

    @Test
    fun resumeTargetArticleId_noProgress_returnsFirstChapter() = runTest {
        val repo = repository(
            book = book(),
            chapters = listOf(chapter(articleId = 21L, index = 0)),
            progress = null
        )

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertEquals(21L, vm.resumeTargetArticleId())
    }

    @Test
    fun resumeTargetArticleId_noChapters_returnsNull() = runTest {
        val repo = repository(book = book(), chapters = emptyList())

        val vm = viewModel(repo)
        advanceUntilIdle()

        assertNull(vm.resumeTargetArticleId())
    }

    private fun viewModel(repo: BookRepository) = BookTocViewModel(
        repo,
        SavedStateHandle(mapOf(BookTocViewModel.ARG_BOOK_ID to 1L))
    )

    private fun repository(
        book: BookEntity?,
        chapters: List<BookChapterEntity>,
        progress: BookReadingProgressEntity? = null
    ): BookRepository = mockk(relaxed = true) {
        every { getChapters(1L) } returns flowOf(chapters)
        coEvery { getBookById(1L) } returns book
        coEvery { getProgress(1L) } returns progress
    }

    private fun book() = BookEntity(
        id = 1L,
        title = "Book",
        contentFingerprint = "fp",
        sourceFormat = "epub3",
        chapterCount = 2,
        totalChars = 100
    )

    private fun chapter(articleId: Long, index: Int) = BookChapterEntity(
        id = index + 1L,
        bookId = 1L,
        articleId = articleId,
        chapterIndex = index,
        sourceHref = "ch$index.xhtml"
    )

    private fun progress(chapterArticleId: Long) = BookReadingProgressEntity(
        bookId = 1L,
        chapterArticleId = chapterArticleId,
        paragraphIndex = 0,
        paragraphOffset = 0,
        updatedAt = 0L
    )
}
