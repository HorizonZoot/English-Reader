package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ArticleSummary
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.ui.component.IMPORT_STATUS_TEST_TAG
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.ArticleListUiEvent
import io.github.zoot.englishreader.viewmodel.ArticleListViewModel
import io.github.zoot.englishreader.viewmodel.ImportOutcome
import io.github.zoot.englishreader.viewmodel.ImportState
import io.github.zoot.englishreader.viewmodel.LibraryItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticleListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val article = ArticleSummary(
        id = 7L,
        title = "The Art of Noticing",
        createdAt = 0
    )
    private val book = BookEntity(
        id = 7L,
        title = "A Walk Through Wonder",
        contentFingerprint = "book-fingerprint",
        sourceFormat = "epub3",
        chapterCount = 12,
        totalChars = 5_000
    )
    private val libraryItemsState = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val importState = MutableStateFlow<ImportState>(ImportState.Idle)
    private val uiEvents = Channel<ArticleListUiEvent>(Channel.BUFFERED)
    private val activeEventCollectors = AtomicInteger()
    private val acknowledgedImports = mutableListOf<String>()
    private var screenVisible by mutableStateOf(true)
    private val openedArticles = mutableListOf<Long>()
    private val openedBooks = mutableListOf<Long>()
    private val viewModel = mockk<ArticleListViewModel>(relaxed = true).also { model ->
        every { model.libraryItems } returns libraryItemsState
        every { model.importState } returns importState
        every { model.uiEvent } returns uiEvents.receiveAsFlow()
            .onStart { activeEventCollectors.incrementAndGet() }
            .onCompletion { activeEventCollectors.decrementAndGet() }
        every { model.acknowledgeImport(any()) } answers {
            val id = firstArg<String>()
            acknowledgedImports += id
            val current = importState.value
            if (current is ImportState.Finished && current.importId == id) {
                importState.compareAndSet(current, ImportState.Idle)
            }
            Unit
        }
    }

    @Test
    fun library_sameNumericArticleAndBookIds_opensEachCorrectDestination() {
        render(items = listOf(LibraryItem.Article(article), LibraryItem.Book(book)))

        openTitle(article.title)
        openTitle(book.title)

        composeRule.runOnIdle {
            assertEquals(listOf(article.id), openedArticles)
            assertEquals(listOf(book.id), openedBooks)
        }
    }

    @Test
    fun articleDelete_menuAndCancelKeepArticle_confirmDispatchesOnce() {
        render(items = listOf(LibraryItem.Article(article), LibraryItem.Book(book)))

        openMore(article.title)
        assertNothingDeletedOrOpened()
        composeRule.onNodeWithText(string(R.string.article_delete_title)).performClick()
        val confirmation = string(R.string.article_delete_confirmation, article.title)
        composeRule.onNodeWithText(confirmation).assertIsDisplayed()
        assertNothingDeletedOrOpened()

        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        assertNothingDeletedOrOpened()

        openMore(article.title)
        composeRule.onNodeWithText(string(R.string.article_delete_title)).performClick()
        confirmDeleteTwiceBeforeRecomposition()

        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        verify(exactly = 1) { viewModel.deleteArticle(article) }
        verify(exactly = 0) { viewModel.deleteBook(any()) }
    }

    @Test
    fun bookDelete_menuAndCancelKeepBook_confirmDispatchesOnce() {
        render(items = listOf(LibraryItem.Article(article), LibraryItem.Book(book)))

        openMore(book.title)
        assertNothingDeletedOrOpened()
        composeRule.onNodeWithText(string(R.string.book_delete_title)).performClick()
        val confirmation = string(R.string.book_delete_confirmation, book.title)
        composeRule.onNodeWithText(confirmation).assertIsDisplayed()
        assertNothingDeletedOrOpened()

        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        assertNothingDeletedOrOpened()

        openMore(book.title)
        composeRule.onNodeWithText(string(R.string.book_delete_title)).performClick()
        confirmDeleteTwiceBeforeRecomposition()

        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        verify(exactly = 1) { viewModel.deleteBook(book) }
        verify(exactly = 0) { viewModel.deleteArticle(any()) }
    }

    @Test
    fun import_emptyLibrary_opensExistingImportChoices() {
        render(items = emptyList())

        composeRule.onNodeWithText(string(R.string.articles_empty)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.import_article))
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithText(string(R.string.import_choose_method)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_from_file)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_paste_text)).assertIsDisplayed()
        verify(exactly = 0) { viewModel.importFromFile(any()) }
        verify(exactly = 0) { viewModel.importFromPaste(any(), any()) }
    }

    @Test
    fun import_busyThenIdle_blocksRepeatedEntryAndEnablesItAfterCompletion() {
        render(items = emptyList(), isImporting = true)

        composeRule.onNodeWithContentDescription(string(R.string.library_importing))
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithText(string(R.string.import_choose_method)).assertDoesNotExist()

        composeRule.runOnIdle { importState.value = ImportState.Idle }
        composeRule.onNodeWithContentDescription(string(R.string.import_article))
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText(string(R.string.import_choose_method)).assertIsDisplayed()
    }

    @Test
    fun library_longTitleAndLargeFontInNarrowDarkLayout_keepsOpenAndMoreActionsReachable() {
        val longArticle = article.copy(
            title = "A Long Article Title About Finding Time to Read and Notice Everyday Details"
        )
        render(
            items = listOf(LibraryItem.Article(longArticle)),
            darkTheme = true,
            width = 280.dp,
            fontScale = 1.8f
        )

        openTitle(longArticle.title)
        composeRule.runOnIdle { assertEquals(listOf(longArticle.id), openedArticles) }

        openMore(longArticle.title)
        composeRule.onNodeWithText(string(R.string.article_delete_title))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(string(R.string.action_cancel)).assertIsDisplayed().performClick()
        verify(exactly = 0) { viewModel.deleteArticle(any()) }
    }

    // ---- 导入状态指示器 ----

    /**
     * 快速导入（TXT / 粘贴是毫秒级的）**完全不出现**指示器。
     *
     * 这是出现阈值存在的唯一理由：`isImporting` 为所有导入路径共用，立即显示会让覆盖层
     * 在粘贴导入时闪一下，比没有动画更廉价。
     *
     * 时钟必须手动推进：`autoAdvance` 开着时断言前的同步会自行推进时钟，可能越过阈值，
     * 于是这条用例的成败取决于时序而非行为。
     */
    @Test
    fun importStatus_fastImport_neverShowsTheIndicator() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)

        // 导入仍在进行时逐帧检查；只看结束后的隐藏态会漏掉「先闪现、再隐藏」。
        val startedAt = composeRule.mainClock.currentTime
        while (composeRule.mainClock.currentTime - startedAt < APPEAR_THRESHOLD_MS - 100) {
            composeRule.mainClock.advanceTimeBy(FRAME_MS)
            composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
        }
        importState.value = ImportState.Finished("import-a", ImportOutcome.SUCCESS)
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)

        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
    }

    /**
     * 慢导入超过阈值后出现「正在导入…」。
     *
     * **必须关掉 `autoAdvance`**：指示器里的圆弧是 `rememberInfiniteTransition`，
     * 组合永远不会 idle，自动推进时钟的断言会一直等下去。
     */
    @Test
    fun importStatus_slowImport_showsIndicatorAfterThreshold() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)

        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)
        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertExists()
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertExists()
    }

    /** 成功时切到「导入完成」，随后自行消失，不需要用户操作。 */
    @Test
    fun importStatus_success_showsDoneThenDisappearsOnItsOwn() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertExists()

        val completedAt = composeRule.mainClock.currentTime
        importState.value = ImportState.Finished("import-a", ImportOutcome.SUCCESS)
        uiEvents.trySend(ArticleListUiEvent.BookImportSucceeded(book.id, book.title, 12))
        composeRule.mainClock.advanceTimeBy(GLYPH_SETTLE_MS)

        composeRule.onNodeWithText(string(R.string.import_status_succeeded)).assertExists()
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertDoesNotExist()

        // 从成功事件起最多 400ms；另留两帧给状态重组和退场动画起帧，不叠加字形等待。
        val finishDeadlineMs = 400L + 2 * FRAME_MS
        while (composeRule.mainClock.currentTime - completedAt < finishDeadlineMs) {
            composeRule.mainClock.advanceTimeBy(FRAME_MS)
            composeRule.onNodeWithText(string(R.string.import_status_importing)).assertDoesNotExist()
            // 淡出期间节点仍在组合中，每一帧都必须保留成功内容。
            if (composeRule.onAllNodesWithTag(IMPORT_STATUS_TEST_TAG).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithText(string(R.string.import_status_succeeded)).assertExists()
            }
        }
        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
    }

    /** 失败时切到「导入失败」，既有的错误 Snackbar 流程不受影响。 */
    @Test
    fun importStatus_failure_showsFailedGlyph() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)

        importState.value = ImportState.Finished("import-a", ImportOutcome.FAILURE)
        uiEvents.trySend(ArticleListUiEvent.ImportFailed(ImportFailure.InvalidEpub))
        composeRule.mainClock.advanceTimeBy(GLYPH_SETTLE_MS)

        composeRule.onNodeWithText(string(R.string.import_status_failed)).assertExists()
    }

    /**
     * 取消路径：导入结束却**没有**终态事件，指示器必须自己收掉。
     *
     * `importFromFile` 对 `CancellationException` 原样上抛、不发事件（用户中途离开就是这条
     * 路）。只靠终态事件收尾会在屏幕中央留下一个永远空转的圆弧，而它看起来就像导入卡死了。
     */
    @Test
    fun importStatus_cancelledWithoutTerminalEvent_stopsSpinningInsteadOfHangingForever() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)
        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertExists()

        // 不发任何 uiEvent，只让导入态落下。
        importState.value = ImportState.Idle
        composeRule.mainClock.advanceTimeBy(TERMINAL_TOTAL_MS)

        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.import_status_succeeded)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.import_status_failed)).assertDoesNotExist()
    }

    @Test
    fun importStatus_finishedWithoutNotification_doesNotFlashLoadingAgain() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)

        importState.value = ImportState.Finished("import-a", ImportOutcome.SUCCESS)
        composeRule.mainClock.advanceTimeBy(GLYPH_SETTLE_MS)
        composeRule.onNodeWithText(string(R.string.import_status_succeeded)).assertExists()

        // 收尾播完，再越过一整个出现阈值：不能又冒出「正在导入…」。
        composeRule.mainClock.advanceTimeBy(TERMINAL_TOTAL_MS + APPEAR_THRESHOLD_MS + FRAME_MS)
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertDoesNotExist()
        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun importStatus_oldSnackbarArrivesWhileNextImportRuns_keepsNextSpinner() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        uiEvents.trySend(ArticleListUiEvent.DeleteFailed)
        val firstMessage = string(R.string.error_delete_article_failed)
        advanceUiUntil { composeRule.onAllNodesWithText(firstMessage).fetchSemanticsNodes().size == 1 }
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.import_status_importing)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(firstMessage).assertExists()
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertExists()

        composeRule.runOnIdle {
            importState.value = ImportState.Finished("import-a", ImportOutcome.SUCCESS)
            importState.value = ImportState.Running("import-b")
            uiEvents.trySend(ArticleListUiEvent.BookImportSucceeded(book.id, book.title, 12))
        }
        advanceUiUntil { composeRule.onAllNodesWithTag(IMPORT_STATUS_TEST_TAG).fetchSemanticsNodes().isEmpty() }
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.import_status_importing)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertExists()
        dismissSnackbar(firstMessage)
        val completedMessage = string(R.string.book_import_succeeded, book.title, 12)
        advanceUiUntil { composeRule.onAllNodesWithText(completedMessage).fetchSemanticsNodes().size == 1 }

        composeRule.onNodeWithText(completedMessage).assertExists()
        composeRule.onNodeWithText(string(R.string.import_status_importing)).assertExists()
        composeRule.onNodeWithText(string(R.string.import_status_succeeded)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.library_importing)).assertIsNotEnabled()
        assertEquals(ImportState.Running("import-b"), importState.value)
    }

    @Test
    fun importStatus_nextImportFinishesBeforeCollection_doesNotBorrowPreviousVisibility() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)
        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertExists()

        composeRule.runOnIdle {
            importState.value = ImportState.Finished("import-a", ImportOutcome.SUCCESS)
            importState.value = ImportState.Running("import-b")
            importState.value = ImportState.Finished("import-b", ImportOutcome.SUCCESS)
        }
        repeat(8) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText(string(R.string.import_status_succeeded)).assertDoesNotExist()
        }

        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
        assertEquals(listOf("import-b"), acknowledgedImports)
        assertEquals(ImportState.Idle, importState.value)
    }

    @Test
    fun importStatus_firstCollectionIsFinished_acknowledgesWithoutAnimationOrNotification() {
        composeRule.mainClock.autoAdvance = false
        render(
            items = emptyList(),
            initialImportState = ImportState.Finished("already-finished", ImportOutcome.SUCCESS)
        )
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + TERMINAL_TOTAL_MS)

        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.book_import_succeeded, book.title, 12)).assertDoesNotExist()
        assertEquals(listOf("already-finished"), acknowledgedImports)
        assertEquals(ImportState.Idle, importState.value)
    }

    @Test
    fun importStatus_recreatedDuringTerminalAnimation_doesNotReplayAnimationOrSnackbar() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList(), isImporting = true)
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.import_status_importing)).fetchSemanticsNodes().size == 1
        }
        composeRule.runOnIdle {
            importState.value = ImportState.Finished("import-a", ImportOutcome.SUCCESS)
            uiEvents.trySend(ArticleListUiEvent.BookImportSucceeded(book.id, book.title, 12))
        }
        val message = string(R.string.book_import_succeeded, book.title, 12)
        advanceUiUntil { composeRule.onAllNodesWithText(message).fetchSemanticsNodes().size == 1 }
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.import_status_succeeded)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(message).assertExists()
        assertEquals(emptyList<String>(), acknowledgedImports)

        recreateScreen()
        advanceUiUntil { acknowledgedImports == listOf("import-a") }
        composeRule.mainClock.advanceTimeBy(APPEAR_THRESHOLD_MS + FRAME_MS)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(IMPORT_STATUS_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(message).assertDoesNotExist()
        assertEquals(listOf("import-a"), acknowledgedImports)
    }

    @Test
    fun uiEvents_queuedBehindSnackbar_remainInViewModelAcrossCollectorGaps() {
        composeRule.mainClock.autoAdvance = false
        render(items = emptyList())
        uiEvents.trySend(ArticleListUiEvent.DeleteFailed)
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.error_delete_article_failed)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(string(R.string.error_delete_article_failed)).assertExists()
        uiEvents.trySend(ArticleListUiEvent.BookImportSucceeded(1, "First queued book", 2))
        uiEvents.trySend(ArticleListUiEvent.BookImportSucceeded(2, "Second queued book", 3))

        recreateScreen()
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.book_import_succeeded, "First queued book", 2)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(string(R.string.book_import_succeeded, "First queued book", 2)).assertExists()
        recreateScreen()
        advanceUiUntil {
            composeRule.onAllNodesWithText(string(R.string.book_import_succeeded, "Second queued book", 3)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(string(R.string.book_import_succeeded, "Second queued book", 3)).assertExists()
        recreateScreen()
        composeRule.onNodeWithText(string(R.string.book_import_succeeded, "Second queued book", 3)).assertDoesNotExist()
    }

    private fun recreateScreen() {
        composeRule.runOnIdle { screenVisible = false }
        advanceUiUntil { activeEventCollectors.get() == 0 }
        composeRule.runOnIdle { screenVisible = true }
        advanceUiUntil { activeEventCollectors.get() == 1 }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun advanceUiUntil(condition: () -> Boolean) {
        val deadline = composeRule.mainClock.currentTime + 1_000
        while (true) {
            composeRule.waitForIdle()
            if (condition()) return
            assertTrue("UI condition did not settle within 1s of virtual time", composeRule.mainClock.currentTime < deadline)
            composeRule.mainClock.advanceTimeByFrame()
        }
    }

    private fun dismissSnackbar(message: String) {
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
                (hasText(message) or hasAnyDescendant(hasText(message)))
        ).performSemanticsAction(SemanticsActions.Dismiss) { dismiss -> assertTrue(dismiss()) }
    }

    private fun openTitle(title: String) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).assertIsDisplayed().performClick()
    }

    private fun openMore(title: String) {
        val description = string(R.string.library_more_actions, title)
        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription(description))
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed().performClick()
    }

    private fun confirmDeleteTwiceBeforeRecomposition() {
        // 两次确认共享尚未重组的按钮，页面仍只能派发一次删除。
        composeRule.onNodeWithText(string(R.string.action_delete))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { click ->
                click()
                click()
            }
    }

    private fun assertNothingDeletedOrOpened() {
        verify(exactly = 0) { viewModel.deleteArticle(any()) }
        verify(exactly = 0) { viewModel.deleteBook(any()) }
        composeRule.runOnIdle {
            assertEquals(emptyList<Long>(), openedArticles)
            assertEquals(emptyList<Long>(), openedBooks)
        }
    }

    private fun render(
        items: List<LibraryItem>,
        isImporting: Boolean = false,
        darkTheme: Boolean = false,
        width: Dp = 360.dp,
        fontScale: Float = 1f,
        initialImportState: ImportState = if (isImporting) ImportState.Running("import-a") else ImportState.Idle
    ) {
        libraryItemsState.value = items
        importState.value = initialImportState
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                EnglishReaderTheme(darkTheme = darkTheme) {
                    Box(modifier = Modifier.width(width).fillMaxHeight()) {
                        if (screenVisible) {
                            ArticleListScreen(
                                onArticleClick = { openedArticles += it },
                                onBookClick = { openedBooks += it },
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }
    }

    private fun string(resourceId: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resourceId, *args)

    private companion object {
        /** 与 `ImportStatusOverlay` 的 `APPEAR_DELAY_MS` 对齐；那个常量是 private。 */
        const val APPEAR_THRESHOLD_MS = 250L

        /** 越过一帧，让阈值到点后的重组真正发生。 */
        const val FRAME_MS = 16L

        /** 字形落定（`GLYPH_MS`）加一帧。 */
        const val GLYPH_SETTLE_MS = 176L

        /** 停留 + 淡出，足够覆盖整段收尾。 */
        const val TERMINAL_TOTAL_MS = 600L
    }
}
