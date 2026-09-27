package io.github.zoot.englishreader.viewmodel

import android.net.Uri
import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ArticleSummary
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.importer.BookFormat
import io.github.zoot.englishreader.data.importer.BookMetadata
import io.github.zoot.englishreader.data.importer.ImportedBook
import io.github.zoot.englishreader.data.importer.ImportedChapter
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.repository.ArticleImporter
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookImporter
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.data.importer.ImportFormat
import io.github.zoot.englishreader.data.importer.ImportFormatProbe
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * ArticleListViewModel 单元测试
 *
 * 覆盖导入流程的关键行为：
 * - 文件导入委托 ArticleImporter，成功后写库且 source="file"
 * - 成功事件带 insertArticle 返回的真实 ID，不在解析成功时提前发
 * - ImportException 的 failure 原样透传（不再压平成单一「导入失败」）
 * - 非 ImportException 归为 SourceUnreadable
 * - 粘贴导入与文件导入共用同一套预算校验
 * - Running 同步拒绝重复导入，Finished 保留到匹配确认
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticleListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var articleRepository: ArticleRepository
    private lateinit var articleImporter: ArticleImporter
    private lateinit var bookRepository: BookRepository
    private lateinit var bookImporter: BookImporter
    private lateinit var formatProbe: ImportFormatProbe
    private lateinit var viewModel: ArticleListViewModel

    @Before
    fun setup() {
        articleRepository = mockk(relaxed = true)
        articleImporter = mockk(relaxed = true)
        bookRepository = mockk(relaxed = true)
        bookImporter = mockk(relaxed = true)
        formatProbe = mockk(relaxed = true)
        // 两条 StateFlow 都需要可收集的上游
        coEvery { articleRepository.getAllArticles() } returns flowOf(emptyList())
        coEvery { articleRepository.getStandaloneArticles() } returns flowOf(emptyList())
        coEvery { bookRepository.getAllBooks() } returns flowOf(emptyList())
        // 默认非 EPUB：既有用例全部走单篇文章路径，行为与改动前一致
        coEvery { formatProbe.detect(any()) } returns ImportFormat.PLAIN_TEXT
        viewModel = ArticleListViewModel(
            articleRepository,
            articleImporter,
            bookRepository,
            bookImporter,
            formatProbe
        )
    }

    @Test
    fun importFromFile_success_persistsFileSourceAndEmitsInsertedId() = runTest {
        val uri = mockk<Uri>()
        coEvery { articleImporter.importFromUri(uri) } returns
            ArticleImporter.ImportedArticle(title = "My Doc", content = "Hello content")
        val slot = slot<ArticleEntity>()
        coEvery { articleRepository.insertArticle(capture(slot)) } returns 42L

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            val event = awaitItem()
            assertTrue(
                "expected ImportSucceeded but was $event",
                event is ArticleListUiEvent.ImportSucceeded
            )
            event as ArticleListUiEvent.ImportSucceeded
            // 成功事件须带 insert 返回的真实 ID——解析成功不等于写库成功
            assertEquals(42L, event.articleId)
            assertEquals("My Doc", event.title)
            assertFalse(event.exceedsFullExplanationLimit)
        }

        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        assertEquals("My Doc", slot.captured.title)
        assertEquals("Hello content", slot.captured.content)
        assertEquals("file", slot.captured.source)
    }

    @Test
    fun importFromFile_longContent_flagsExplanationLimitButStillSucceeds() = runTest {
        val uri = mockk<Uri>()
        val longContent = "x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1)
        coEvery { articleImporter.importFromUri(uri) } returns
            ArticleImporter.ImportedArticle(title = "Long", content = longContent)
        coEvery { articleRepository.insertArticle(any()) } returns 7L

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            val event = awaitItem() as ArticleListUiEvent.ImportSucceeded
            // 超过 AI 解释上限只是提示：不拦截导入、不静默截断前 8000 字符
            assertTrue(event.exceedsFullExplanationLimit)
        }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_importException_propagatesSpecificFailure() = runTest {
        val uri = mockk<Uri>()
        coEvery { articleImporter.importFromUri(uri) } throws
            ImportException(ImportFailure.EncryptedEpub)

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            // 关键：DRM EPUB 不能再显示成「文件内容为空」
            assertEquals(
                ArticleListUiEvent.ImportFailed(ImportFailure.EncryptedEpub),
                awaitItem()
            )
        }

        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_incompleteEpub_reportsFailureWithoutPersistingBook() = runTest {
        val uri = mockk<Uri>()
        val releaseParser = CompletableDeferred<Unit>()
        coEvery { formatProbe.detect(uri) } returns ImportFormat.EPUB
        coEvery { bookImporter.importFromUri(uri) } coAnswers {
            releaseParser.await()
            throw ImportException(ImportFailure.InvalidEpub)
        }

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            advanceUntilIdle()
            assertTrue(viewModel.importState.value is ImportState.Running)
            releaseParser.complete(Unit)
            assertEquals(ArticleListUiEvent.ImportFailed(ImportFailure.InvalidEpub), awaitItem())
            advanceUntilIdle()
            assertTrue(viewModel.importState.value is ImportState.Finished)
            expectNoEvents()
        }
        coVerify(exactly = 0) { bookRepository.persist(any(), any()) }
        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_sourceTooLarge_carriesLimitForPreciseMessage() = runTest {
        val uri = mockk<Uri>()
        coEvery { articleImporter.importFromUri(uri) } throws
            ImportException(ImportFailure.SourceTooLarge(ImportBudget.MAX_TEXT_SOURCE_BYTES))

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            val event = awaitItem() as ArticleListUiEvent.ImportFailed
            val failure = event.failure as ImportFailure.SourceTooLarge
            assertEquals(ImportBudget.MAX_TEXT_SOURCE_BYTES, failure.limitBytes)
        }
    }

    @Test
    fun importFromFile_ioException_mapsToSourceUnreadable() = runTest {
        val uri = mockk<Uri>()
        coEvery { articleImporter.importFromUri(uri) } throws IOException("stream null")

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            assertEquals(
                ArticleListUiEvent.ImportFailed(ImportFailure.SourceUnreadable),
                awaitItem()
            )
        }

        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_failure_releasesGateAndRetainsFinishedState() = runTest {
        val uri = mockk<Uri>()
        val releaseImport = CompletableDeferred<Unit>()
        coEvery { articleImporter.importFromUri(uri) } coAnswers {
            releaseImport.await()
            throw ImportException(ImportFailure.InvalidEpub)
        }

        try {
            viewModel.importFromFile(uri)
            advanceUntilIdle()
            assertTrue(viewModel.importState.value is ImportState.Running)
        } finally {
            releaseImport.complete(Unit)
        }
        advanceUntilIdle()

        val finished = viewModel.importState.value as ImportState.Finished
        assertEquals(ImportOutcome.FAILURE, finished.outcome)
    }

    @Test
    fun importFromPaste_success_insertsArticleWithPasteSource() = runTest {
        val slot = slot<ArticleEntity>()
        coEvery { articleRepository.insertArticle(capture(slot)) } returns 1L

        viewModel.importFromPaste(title = "  Pasted  ", content = "  some pasted text  ")

        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
        assertEquals("Pasted", slot.captured.title)          // 已 trim
        assertEquals("some pasted text", slot.captured.content) // 已 trim
        assertEquals("paste", slot.captured.source)
    }

    @Test
    fun importFromPaste_blankTitle_usesUntitled() = runTest {
        val slot = slot<ArticleEntity>()
        coEvery { articleRepository.insertArticle(capture(slot)) } returns 1L

        viewModel.importFromPaste(title = "   ", content = "real content")

        assertEquals("Untitled", slot.captured.title)
    }

    @Test
    fun importFromPaste_overlongTitle_isTruncatedLikeFileImport() = runTest {
        // 标题上限原先只在 ArticleImporter 私有方法里，粘贴路径完全绕过它——
        // singleLine = true 只控制显示，不限制输入长度
        val slot = slot<ArticleEntity>()
        coEvery { articleRepository.insertArticle(capture(slot)) } returns 1L

        viewModel.importFromPaste(
            title = "T".repeat(ImportBudget.MAX_TITLE_CHARS + 5000),
            content = "real content"
        )

        assertEquals(ImportBudget.MAX_TITLE_CHARS, slot.captured.title.length)
    }

    @Test
    fun importFromPaste_emptyContent_emitsEmptyContentFailure() = runTest {
        viewModel.uiEvent.test {
            viewModel.importFromPaste(title = "T", content = "   \n\t  ")
            assertEquals(
                ArticleListUiEvent.ImportFailed(ImportFailure.EmptyContent),
                awaitItem()
            )
        }

        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromPaste_overBudget_isRejectedLikeFileImport() = runTest {
        // 改造前粘贴入口完全无上限，是最明显的漏网口——须与文件导入共用同一套校验
        val huge = "x".repeat(ImportBudget.MAX_PARAGRAPH_CHARS + 1)

        viewModel.uiEvent.test {
            viewModel.importFromPaste(title = "T", content = huge)
            val event = awaitItem() as ArticleListUiEvent.ImportFailed
            assertTrue(
                "expected ParagraphTooLong but was ${event.failure}",
                event.failure is ImportFailure.ParagraphTooLong
            )
        }

        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_databaseWriteFailure_reportsStorageFailed() = runTest {
        // 解析已成功，失败发生在写库（磁盘满、Room 异常）。报「源文件无法读取」会把用户
        // 引向检查文件本身，而真正该做的是清理空间——错误归因直接误导排查方向。
        // 断言必须是**等于 StorageFailed**：只断言「不等于 SourceUnreadable」的话，
        // 日后误映射成 InvalidEpub 之类同样会通过。
        val uri = mockk<Uri>()
        coEvery { articleImporter.importFromUri(uri) } returns
            ArticleImporter.ImportedArticle(title = "Doc", content = "content")
        coEvery { articleRepository.insertArticle(any()) } throws
            RuntimeException("SQLITE_FULL: database or disk is full")

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            assertEquals(
                ArticleListUiEvent.ImportFailed(ImportFailure.StorageFailed),
                awaitItem()
            )
        }
    }

    @Test
    fun importFromPaste_databaseWriteFailure_reportsStorageFailed() = runTest {
        // 粘贴导入连「源文件」都不存在，这条提示尤其荒谬
        coEvery { articleRepository.insertArticle(any()) } throws
            RuntimeException("SQLITE_FULL: database or disk is full")

        viewModel.uiEvent.test {
            viewModel.importFromPaste(title = "T", content = "real content")
            assertEquals(
                ArticleListUiEvent.ImportFailed(ImportFailure.StorageFailed),
                awaitItem()
            )
        }
    }

    @Test
    fun importFromFile_secondCallWhileFirstInFlight_isRejected() = runTest {
        // 两个同步意图必须共享闸门，第一次仍在执行时不能再次解析或写入。
        val uri = mockk<Uri>()
        val gate = CompletableDeferred<Unit>()
        coEvery { articleImporter.importFromUri(uri) } coAnswers {
            gate.await()
            ArticleImporter.ImportedArticle(title = "Doc", content = "content")
        }
        coEvery { articleRepository.insertArticle(any()) } returns 1L

        viewModel.importFromFile(uri)   // 第一次：挂在 gate 上
        viewModel.importFromFile(uri)   // 第二次：必须被拒
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { articleImporter.importFromUri(uri) }
        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromPaste_whileFileImportInFlight_isRejected() = runTest {
        // 两条入口共用同一个闸门，否则文件导入与粘贴导入会并发写库
        val uri = mockk<Uri>()
        val gate = CompletableDeferred<Unit>()
        coEvery { articleImporter.importFromUri(uri) } coAnswers {
            gate.await()
            ArticleImporter.ImportedArticle(title = "Doc", content = "content")
        }
        coEvery { articleRepository.insertArticle(any()) } returns 1L

        viewModel.importFromFile(uri)
        viewModel.importFromPaste(title = "T", content = "pasted body")
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_afterPreviousCompleted_isAccepted() = runTest {
        // 闸门只在进行中拦人，完成后必须放行——否则用户再也无法导入第二篇
        val uri = mockk<Uri>()
        coEvery { articleImporter.importFromUri(uri) } returns
            ArticleImporter.ImportedArticle(title = "Doc", content = "content")
        coEvery { articleRepository.insertArticle(any()) } returns 1L

        viewModel.importFromFile(uri)
        advanceUntilIdle()
        viewModel.importFromFile(uri)
        advanceUntilIdle()

        coVerify(exactly = 2) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun importFromFile_synchronousCompletion_registersRunningAndRetainsFinishedUntilAcknowledged() = runTest {
        val uri = mockk<Uri>()
        var runningId: String? = null
        coEvery { formatProbe.detect(uri) } coAnswers {
            runningId = (viewModel.importState.value as ImportState.Running).importId
            ImportFormat.PLAIN_TEXT
        }
        coEvery { articleImporter.importFromUri(uri) } returns
            ArticleImporter.ImportedArticle("Imported", "Body.")
        coEvery { articleRepository.insertArticle(any()) } returns 42L

        viewModel.importFromFile(uri)

        val finished = viewModel.importState.value as ImportState.Finished
        assertEquals(runningId, finished.importId)
        assertEquals(ImportOutcome.SUCCESS, finished.outcome)
        assertEquals(finished, viewModel.importState.first())
        assertEquals(finished, viewModel.importState.first())
        viewModel.uiEvent.test {
            assertEquals(42L, (awaitItem() as ArticleListUiEvent.ImportSucceeded).articleId)
            expectNoEvents()
        }
        viewModel.acknowledgeImport("another-import")
        assertEquals(finished, viewModel.importState.value)
        viewModel.acknowledgeImport(finished.importId)
        assertEquals(ImportState.Idle, viewModel.importState.value)
        viewModel.uiEvent.test { expectNoEvents() }
    }

    @Test
    fun acknowledgeImport_previousId_doesNotClearNextRunningOrFinishedImport() = runTest {
        coEvery { articleRepository.insertArticle(any()) } returnsMany listOf(1L, 2L)
        viewModel.importFromPaste("First", "First body.")
        val first = viewModel.importState.value as ImportState.Finished
        val uri = mockk<Uri>()
        val releaseSecond = CompletableDeferred<Unit>()
        coEvery { articleImporter.importFromUri(uri) } coAnswers {
            releaseSecond.await()
            ArticleImporter.ImportedArticle("Second", "Second body.")
        }

        viewModel.importFromFile(uri)
        val second = viewModel.importState.value as ImportState.Running
        assertNotEquals(first.importId, second.importId)
        viewModel.acknowledgeImport(first.importId)
        viewModel.acknowledgeImport(second.importId)
        assertEquals(second, viewModel.importState.value)

        releaseSecond.complete(Unit)
        advanceUntilIdle()
        val finished = ImportState.Finished(second.importId, ImportOutcome.SUCCESS)
        viewModel.acknowledgeImport(first.importId)
        assertEquals(finished, viewModel.importState.value)
        viewModel.acknowledgeImport(second.importId)
        assertEquals(ImportState.Idle, viewModel.importState.value)
        viewModel.uiEvent.test {
            assertEquals(1L, (awaitItem() as ArticleListUiEvent.ImportSucceeded).articleId)
            assertEquals(2L, (awaitItem() as ArticleListUiEvent.ImportSucceeded).articleId)
            expectNoEvents()
        }
    }

    @Test
    fun importFromFile_cancellation_clearsRunningWithoutFailureAndAllowsAnotherImport() = runTest {
        val uri = mockk<Uri>()
        val releaseImport = CompletableDeferred<Unit>()
        coEvery { articleImporter.importFromUri(uri) } coAnswers {
            releaseImport.await()
            throw CancellationException("cancelled")
        }

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            assertTrue(viewModel.importState.value is ImportState.Running)
            releaseImport.complete(Unit)
            advanceUntilIdle()
            assertEquals(ImportState.Idle, viewModel.importState.value)
            expectNoEvents()

            coEvery { articleImporter.importFromUri(uri) } returns
                ArticleImporter.ImportedArticle("Next", "Next body.")
            coEvery { articleRepository.insertArticle(any()) } returns 9L
            viewModel.importFromFile(uri)
            assertEquals(9L, (awaitItem() as ArticleListUiEvent.ImportSucceeded).articleId)
            assertTrue(viewModel.importState.value is ImportState.Finished)
        }
    }

    @Test
    fun importFromFile_epub_remainsRunningUntilBookPersistenceFinishes() = runTest {
        val uri = mockk<Uri>()
        val releasePersist = CompletableDeferred<Unit>()
        val imported = ImportedBook(
            metadata = BookMetadata("Book", null, "en", null, "fingerprint", BookFormat.EPUB3),
            chapters = listOf(
                ImportedChapter(0, "First", "1.xhtml", null, "First body."),
                ImportedChapter(1, "Second", "2.xhtml", null, "Second body.")
            ),
            toc = emptyList()
        )
        coEvery { formatProbe.detect(uri) } returns ImportFormat.EPUB
        coEvery { bookImporter.importFromUri(uri) } returns imported
        coEvery { bookRepository.persist(imported, any()) } coAnswers {
            releasePersist.await()
            77L
        }

        viewModel.uiEvent.test {
            viewModel.importFromFile(uri)
            val running = viewModel.importState.value as ImportState.Running
            expectNoEvents()
            releasePersist.complete(Unit)
            assertEquals(ArticleListUiEvent.BookImportSucceeded(77L, "Book", 2), awaitItem())
            assertEquals(
                ImportState.Finished(running.importId, ImportOutcome.SUCCESS),
                viewModel.importState.value
            )
        }
        coVerify(exactly = 0) { articleRepository.insertArticle(any()) }
    }

    @Test
    fun libraryItems_summaryAndBookUpdates_preserveOrderingWithoutCollectingFullArticles() = runTest {
        val summaries = MutableStateFlow(listOf(ArticleSummary(3, "Article", 500)))
        val book = BookEntity(
            id = 3, title = "Book", contentFingerprint = "fingerprint", sourceFormat = "epub3",
            chapterCount = 2, totalChars = 100, createdAt = 100
        )
        val books = MutableStateFlow(listOf(book))
        var fullArticleCollections = 0
        coEvery { articleRepository.getStandaloneArticles() } returns summaries
        coEvery { articleRepository.getAllArticles() } returns flow {
            fullArticleCollections++
            emit(emptyList())
        }
        coEvery { bookRepository.getAllBooks() } returns books
        val model = ArticleListViewModel(articleRepository, articleImporter, bookRepository, bookImporter, formatProbe)

        model.libraryItems.test {
            val first = awaitItem().ifEmpty { awaitItem() }
            assertEquals(listOf("article-3", "book-3"), first.map { it.listKey })
            books.value = listOf(book.copy(lastReadAt = 900))
            assertEquals(listOf("book-3", "article-3"), awaitItem().map { it.listKey })
            summaries.value = listOf(ArticleSummary(3, "Renamed article", 500))
            assertEquals("Renamed article", (awaitItem().last() as LibraryItem.Article).article.title)
            assertEquals(0, fullArticleCollections)
        }
    }

    @Test
    fun deleteArticle_failure_emitsDeleteFailed() = runTest {
        val article = ArticleSummary(id = 1, title = "t", createdAt = 0)
        coEvery { articleRepository.deleteArticleById(article.id) } throws RuntimeException("db error")

        viewModel.uiEvent.test {
            viewModel.deleteArticle(article)
            assertEquals(ArticleListUiEvent.DeleteFailed, awaitItem())
        }
    }
}
