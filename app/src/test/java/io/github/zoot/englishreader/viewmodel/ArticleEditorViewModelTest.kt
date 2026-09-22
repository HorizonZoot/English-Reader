package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.ArticleEditorDialog
import io.github.zoot.englishreader.model.ArticleEditorError
import io.github.zoot.englishreader.model.ArticleEditorEvent
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleEditorViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())
    private val articles = mockk<ArticleRepository>()
    private val books = mockk<BookRepository>()
    private val article = ArticleEntity(id = 7L, title = "Original title", content = "First paragraph.\n\nSecond paragraph.")

    @Test
    fun load_standaloneArticle_prefillsUnchangedDraft() = runTest {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals(article.title, vm.uiState.value.title)
        assertEquals(article.content, vm.uiState.value.content)
        assertEquals(snapshot(), vm.uiState.value.original)
        assertFalse(vm.uiState.value.isDirty)
        assertFalse(vm.uiState.value.canSave)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun load_missingArticle_stopsWithoutEditableContent() = runTest {
        val vm = viewModel()
        coEvery { articles.getArticleById(article.id) } returns null
        advanceUntilIdle()

        assertEquals(ArticleEditorError.NotFound, vm.uiState.value.error)
        assertFalse(vm.uiState.value.canEdit)
        coVerify(exactly = 0) { books.findChapterByArticleId(any()) }
    }

    @Test
    fun load_bookChapter_rejectsEvenWhenArticleSourceLooksStandalone() = runTest {
        val vm = viewModel()
        coEvery { books.findChapterByArticleId(article.id) } returns BookChapterEntity(
            id = 2L, bookId = 3L, articleId = article.id, chapterIndex = 0, sourceHref = "chapter.xhtml"
        )
        advanceUntilIdle()

        assertEquals(ArticleEditorError.NotStandalone, vm.uiState.value.error)
        assertFalse(vm.uiState.value.canEdit)
        vm.updateContent("Must not become an editable chapter.")
        vm.requestSave()
        assertEquals("", vm.uiState.value.content)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
    }

    @Test
    fun load_storageFailure_canRetryWithoutKeepingLoading() = runTest {
        val vm = viewModel()
        coEvery { articles.getArticleById(article.id) } throws IOException("private details")
        advanceUntilIdle()
        assertEquals(ArticleEditorError.LoadFailed, vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)

        coEvery { articles.getArticleById(article.id) } returns article
        vm.retryLoad()
        advanceUntilIdle()
        assertEquals(article.content, vm.uiState.value.content)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun save_titleOnly_waitsForSuccessAndRejectsDuplicateOrConcurrentDraftChanges() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val completion = CompletableDeferred<ArticleEditResult>()
        coEvery { articles.saveEdit(any(), any(), any()) } coAnswers { completion.await() }
        vm.updateTitle("  Edited title  ")

        vm.events.test {
            vm.requestSave()
            vm.requestSave()
            assertTrue(vm.uiState.value.isSaving)
            assertNull(vm.uiState.value.dialog)
            runCurrent()
            vm.updateContent("Blocked during save.")
            vm.requestBack()
            assertEquals(article.content, vm.uiState.value.content)
            expectNoEvents()
            coVerify(exactly = 1) { articles.saveEdit(snapshot(), "Edited title", article.content) }

            completion.complete(ArticleEditResult.Saved)
            advanceUntilIdle()
            assertEquals(ArticleEditorEvent.NAVIGATE_BACK, awaitItem())
            assertFalse(vm.uiState.value.isSaving)
            vm.requestSave()
            vm.requestBack()
            expectNoEvents()
        }
        coVerify(exactly = 1) { articles.saveEdit(any(), any(), any()) }
    }

    @Test
    fun save_titleOnly_preservesUnchangedSourceWhitespace() = runTest {
        val source = article.copy(content = "  Original text.\n")
        val vm = viewModel(source)
        advanceUntilIdle()
        vm.updateTitle("New title")
        vm.requestSave()
        assertNull(vm.uiState.value.dialog)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            articles.saveEdit(ArticleEditSnapshot(source.id, source.title, source.content), "New title", source.content)
        }
    }

    @Test
    fun save_titleOnly_preservesLegacyBodyOutsideCurrentBudget() = runTest {
        val source = article.copy(content = "A".repeat(ImportBudget.MAX_IMPORT_CHARS + 1))
        val vm = viewModel(source)
        advanceUntilIdle()
        vm.updateTitle("New title")
        vm.requestSave()
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.dialog)
        coVerify(exactly = 1) {
            articles.saveEdit(ArticleEditSnapshot(source.id, source.title, source.content), "New title", source.content)
        }
        vm.events.test { assertEquals(ArticleEditorEvent.NAVIGATE_BACK, awaitItem()) }
    }

    @Test
    fun save_bodyChange_requiresConfirmationAndNewDraftInvalidatesOldConfirmation() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.updateContent("First edit.")
        vm.requestSave()
        assertEquals(ArticleEditorDialog.ConfirmContentSave, vm.uiState.value.dialog)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }

        vm.updateContent("Second edit.")
        vm.confirmContentSave()
        advanceUntilIdle()
        assertNull(vm.uiState.value.dialog)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }

        vm.requestSave()
        vm.confirmContentSave()
        vm.confirmContentSave()
        advanceUntilIdle()
        coVerify(exactly = 1) { articles.saveEdit(snapshot(), article.title, "Second edit.") }
    }

    @Test
    fun save_cancelConfirmation_keepsDraftAndDoesNotWrite() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.updateContent("New content.")
        vm.requestSave()
        vm.dismissDialog()
        vm.confirmContentSave()
        advanceUntilIdle()

        assertEquals("New content.", vm.uiState.value.content)
        assertTrue(vm.uiState.value.isDirty)
        assertNull(vm.uiState.value.dialog)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
        vm.events.test { expectNoEvents() }
    }

    @Test
    fun save_invalidTitleOrContent_doesNotReachRepository() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.updateTitle(" ")
        vm.requestSave()
        assertEquals(ArticleEditorError.TitleRequired, vm.uiState.value.error)

        vm.updateTitle(article.title)
        vm.updateContent("x".repeat(ImportBudget.MAX_IMPORT_CHARS + 1))
        vm.requestSave()
        val failure = (vm.uiState.value.error as ArticleEditorError.InvalidContent).failure
        assertEquals(ImportFailure.ContentTooLong(ImportBudget.MAX_IMPORT_CHARS + 1, ImportBudget.MAX_IMPORT_CHARS), failure)
        assertNull(vm.uiState.value.dialog)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
    }

    @Test
    fun save_storageFailure_keepsDraftAndEmitsNoSuccess() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { articles.saveEdit(any(), any(), any()) } throws IOException("private disk path")
        vm.updateTitle("Unsaved title")
        vm.requestSave()
        advanceUntilIdle()

        assertEquals("Unsaved title", vm.uiState.value.title)
        assertEquals(snapshot(), vm.uiState.value.original)
        assertEquals(ArticleEditorError.SaveFailed, vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSaving)
        vm.events.test { expectNoEvents() }
    }

    @Test
    fun save_repositoryRejections_preserveDraftAndExposeTypedFailure() = runTest {
        val cases = listOf(
            ArticleEditResult.NotFound to ArticleEditorError.NotFound,
            ArticleEditResult.NotStandalone to ArticleEditorError.NotStandalone,
            ArticleEditResult.Conflict to ArticleEditorError.Conflict,
            ArticleEditResult.InvalidContent(ImportFailure.EmptyContent) to ArticleEditorError.InvalidContent(ImportFailure.EmptyContent)
        )
        for ((result, error) in cases) {
            val vm = viewModel()
            advanceUntilIdle()
            coEvery { articles.saveEdit(any(), any(), any()) } returns result
            vm.updateTitle("Unsaved title")
            vm.requestSave()
            advanceUntilIdle()
            assertEquals(error, vm.uiState.value.error)
            assertEquals("Unsaved title", vm.uiState.value.title)
            assertFalse(vm.uiState.value.isSaving)
            vm.events.test { expectNoEvents() }
        }
    }

    @Test
    fun save_cancellation_clearsBusyWithoutMappingToFailureOrSuccess() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val completion = CompletableDeferred<ArticleEditResult>()
        coEvery { articles.saveEdit(any(), any(), any()) } coAnswers { completion.await() }
        vm.updateTitle("Pending")
        vm.requestSave()
        runCurrent()
        assertTrue(vm.uiState.value.isSaving)

        completion.cancel(CancellationException("test cancellation"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.error)
        assertEquals("Pending", vm.uiState.value.title)
        vm.events.test { expectNoEvents() }
    }

    @Test
    fun back_dirtyDraft_requiresDiscardAndCancelKeepsDraft() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.updateTitle("Unsaved")
        vm.events.test {
            vm.requestBack()
            assertEquals(ArticleEditorDialog.ConfirmDiscard, vm.uiState.value.dialog)
            expectNoEvents()
            vm.dismissDialog()
            assertEquals("Unsaved", vm.uiState.value.title)
            vm.requestBack()
            vm.confirmDiscard()
            vm.confirmDiscard()
            assertEquals(ArticleEditorEvent.NAVIGATE_BACK, awaitItem())
            expectNoEvents()
        }
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
    }

    @Test
    fun back_unchangedDraft_exitsWithoutConfirmationOrSave() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.requestBack()
        assertNull(vm.uiState.value.dialog)
        vm.events.test { assertEquals(ArticleEditorEvent.NAVIGATE_BACK, awaitItem()) }
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
    }

    @Test
    fun paragraphPreview_requiresApplyAndSeparateSaveAndCancelChangesNothing() = runTest {
        val vm = viewModel(article.copy(content = "First.\r\nSecond."))
        advanceUntilIdle()
        vm.requestParagraphPreview()
        val preview = vm.uiState.value.dialog as ArticleEditorDialog.ParagraphPreview
        assertEquals("First.\n\nSecond.", preview.content)
        assertEquals(2, preview.paragraphCount)
        assertEquals("First.\r\nSecond.", vm.uiState.value.content)
        assertFalse(vm.uiState.value.isDirty)

        vm.dismissDialog()
        assertEquals("First.\r\nSecond.", vm.uiState.value.content)
        vm.requestParagraphPreview()
        vm.applyParagraphPreview()
        assertEquals("First.\n\nSecond.", vm.uiState.value.content)
        assertTrue(vm.uiState.value.isDirty)
        assertNull(vm.uiState.value.dialog)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
        vm.requestSave()
        assertEquals(ArticleEditorDialog.ConfirmContentSave, vm.uiState.value.dialog)
    }

    @Test
    fun paragraphPreview_changedDraft_ignoresStaleApply() = runTest {
        val vm = viewModel(article.copy(content = "First.\nSecond."))
        advanceUntilIdle()
        vm.requestParagraphPreview()
        vm.updateContent("A newer draft.")
        vm.applyParagraphPreview()
        assertEquals("A newer draft.", vm.uiState.value.content)
        assertNull(vm.uiState.value.dialog)
        coVerify(exactly = 0) { articles.saveEdit(any(), any(), any()) }
    }

    @Test
    fun stateAndPreview_stringRepresentation_redactsUserContent() = runTest {
        val vm = viewModel(article.copy(content = "private first line\nprivate second line"))
        advanceUntilIdle()
        vm.requestParagraphPreview()
        val diagnostic = vm.uiState.value.toString() + vm.uiState.value.dialog.toString()
        assertFalse(diagnostic.contains("private first line"))
        assertFalse(diagnostic.contains(article.title))
    }

    private fun snapshot() = ArticleEditSnapshot(article.id, article.title, article.content)

    private fun viewModel(loaded: ArticleEntity = article): ArticleEditorViewModel {
        coEvery { articles.getArticleById(article.id) } returns loaded
        coEvery { books.findChapterByArticleId(article.id) } returns null
        coEvery { articles.saveEdit(any(), any(), any()) } returns ArticleEditResult.Saved
        return ArticleEditorViewModel(articles, books, SavedStateHandle(mapOf(ArticleEditorViewModel.ARG_ARTICLE_ID to article.id)))
    }
}
