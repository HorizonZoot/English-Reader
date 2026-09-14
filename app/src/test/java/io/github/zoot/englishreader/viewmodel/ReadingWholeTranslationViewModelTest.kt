package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.repository.WholeTranslationStartResult
import io.github.zoot.englishreader.data.repository.WholeTranslationTaskView
import io.github.zoot.englishreader.model.WholeTranslationPrimaryAction
import io.github.zoot.englishreader.model.WholeTranslationProgress
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationScopeChoice
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * [ReadingViewModel] 的全文翻译 intent：范围快照、状态推导、代际守卫与 observer 脱离。
 *
 * 不重复 [io.github.zoot.englishreader.data.repository.WholeTranslationRepositoryTest] 的
 * 协调语义——那里已用真实 Room 证明了不重跑、三分法与取消。这里只断言 ViewModel 把用户意图
 * 正确翻译成 repository 调用，以及 sheet 状态对用户可见的部分。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingWholeTranslationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fixture: ReadingViewModelFixture
    private lateinit var viewModel: ReadingViewModel

    @Before
    fun setUp() {
        fixture = ReadingViewModelFixture()
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns null
        viewModel = fixture.create()
    }

    // ---- 打开范围选择 ----

    @Test
    fun openWholeTranslation_standaloneArticle_offersOnlyCurrentScopeWithParagraphCount() = runTest {
        stubStandalone(ARTICLE_ID, "One.\n\nTwo.\n\nThree.")
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertEquals(ARTICLE_ID, state.articleId)
        assertEquals(WholeTranslationScopeChoice.CURRENT_ARTICLE, state.selected)
        assertEquals(3, state.currentArticleOption.paragraphCount)
        assertNull("standalone article must not offer chapter scope", state.chapterOption)
    }

    @Test
    fun openWholeTranslation_bookChapter_offersChapterScopeWithSummedParagraphs() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(30L, ARTICLE_ID, 32L))
        coEvery { fixture.articleRepository.getArticleById(30L) } returns ArticleEntity(30L, "a", "P.\n\nQ.")
        coEvery { fixture.articleRepository.getArticleById(32L) } returns ArticleEntity(32L, "c", "R.")
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        val chapter = requireNotNull(state.chapterOption)
        assertEquals(3, chapter.articleCount)
        // 2 + 1 (current "Body text.") + 1
        assertEquals(4, chapter.paragraphCount)
    }

    @Test
    fun openWholeTranslation_existingResumableTask_skipsChooserAndTracks() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val existing = view(taskId = 9L, status = WholeTranslationTaskStatus.PAUSED, translated = 1, total = 3)
        coEvery { fixture.wholeTranslationRepository.findResumable(WholeTranslationScope.CurrentArticle(ARTICLE_ID)) } returns existing
        every { fixture.wholeTranslationRepository.observe(9L) } returns MutableStateFlow(existing)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking
        assertEquals(9L, state.taskId)
        assertEquals(WholeTranslationPrimaryAction.RESUME, state.primaryAction)
    }

    @Test
    fun openWholeTranslation_whileArticleLoading_isIgnored() = runTest {
        // 未加载任何文章
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        assertEquals(WholeTranslationSheetState.Hidden, viewModel.wholeTranslationState.value)
    }

    // ---- 范围选择与开始 ----

    @Test
    fun selectWholeTranslationScope_chapterWithoutOption_isIgnored() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.selectWholeTranslationScope(WholeTranslationScopeChoice.CHAPTER)

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertEquals(WholeTranslationScopeChoice.CURRENT_ARTICLE, state.selected)
    }

    @Test
    fun startWholeTranslation_currentScope_startsRepositoryWithCurrentArticleAndTracks() = runTest {
        stubStandalone(ARTICLE_ID, "One.\n\nTwo.")
        val running = view(taskId = 11L, status = WholeTranslationTaskStatus.RUNNING, translated = 0, total = 2)
        coEvery { fixture.wholeTranslationRepository.start(any()) } returns WholeTranslationStartResult.Started(11L)
        every { fixture.wholeTranslationRepository.observe(11L) } returns MutableStateFlow(running)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.wholeTranslationRepository.start(WholeTranslationScope.CurrentArticle(ARTICLE_ID)) }
        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking
        assertEquals(11L, state.taskId)
        assertEquals(WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND, state.primaryAction)
    }

    @Test
    fun startWholeTranslation_chapterScope_passesOrderedChapterArticleIds() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(30L, ARTICLE_ID, 32L))
        coEvery { fixture.articleRepository.getArticleById(30L) } returns ArticleEntity(30L, "a", "P.")
        coEvery { fixture.articleRepository.getArticleById(32L) } returns ArticleEntity(32L, "c", "R.")
        coEvery { fixture.wholeTranslationRepository.start(any()) } returns WholeTranslationStartResult.Started(12L)
        every { fixture.wholeTranslationRepository.observe(12L) } returns
            MutableStateFlow(view(12L, WholeTranslationTaskStatus.RUNNING, 0, 3))
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.selectWholeTranslationScope(WholeTranslationScopeChoice.CHAPTER)
        viewModel.startWholeTranslation()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fixture.wholeTranslationRepository.start(WholeTranslationScope.Chapter(7L, listOf(30L, ARTICLE_ID, 32L)))
        }
    }

    @Test
    fun startWholeTranslation_noContent_showsRejected() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        coEvery { fixture.wholeTranslationRepository.start(any()) } returns WholeTranslationStartResult.NoContent
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.Rejected
        assertEquals(AiError.NoContent, state.error)
    }

    // ---- 跟踪态推导 ----

    @Test
    fun tracking_pausedWithFailures_offersRetryFailed() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val paused = view(taskId = 5L, status = WholeTranslationTaskStatus.PAUSED, translated = 2, total = 3, failed = 1)
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns paused
        every { fixture.wholeTranslationRepository.observe(5L) } returns MutableStateFlow(paused)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking
        assertEquals(WholeTranslationPrimaryAction.RETRY_FAILED, state.primaryAction)

        viewModel.retryFailedWholeTranslation()
        advanceUntilIdle()
        coVerify(exactly = 1) { fixture.wholeTranslationRepository.retryFailed(5L) }
    }

    @Test
    fun tracking_completed_reloadsArticleSoBilingualParagraphsAppear() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val flow = MutableStateFlow(view(taskId = 6L, status = WholeTranslationTaskStatus.RUNNING, translated = 0, total = 1))
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns flow.value
        every { fixture.wholeTranslationRepository.observe(6L) } returns flow
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        // 完成后 Room 里的文章已带译文
        coEvery { fixture.articleRepository.getArticleById(ARTICLE_ID) } returns
            ArticleEntity(ARTICLE_ID, "t", "One.", translation = "一。")
        flow.value = view(taskId = 6L, status = WholeTranslationTaskStatus.COMPLETED, translated = 1, total = 1)
        advanceUntilIdle()

        assertEquals("一。", viewModel.article.value?.translation)
        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking
        assertEquals(WholeTranslationPrimaryAction.DONE, state.primaryAction)
    }

    // ---- 脱离与守卫 ----

    /** 关闭 sheet 只解除观察；付费任务不受影响（不调用 cancel）。 */
    @Test
    fun dismissWholeTranslation_hidesSheetWithoutCancellingTask() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val running = view(taskId = 8L, status = WholeTranslationTaskStatus.RUNNING, translated = 0, total = 1)
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns running
        every { fixture.wholeTranslationRepository.observe(8L) } returns MutableStateFlow(running)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()
        assertTrue(viewModel.wholeTranslationState.value is WholeTranslationSheetState.Tracking)

        viewModel.dismissWholeTranslation()

        assertEquals(WholeTranslationSheetState.Hidden, viewModel.wholeTranslationState.value)
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.cancel(any()) }
    }

    @Test
    fun cancelWholeTranslation_tracking_cancelsRepositoryTask() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val running = view(taskId = 8L, status = WholeTranslationTaskStatus.RUNNING, translated = 0, total = 1)
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns running
        every { fixture.wholeTranslationRepository.observe(8L) } returns MutableStateFlow(running)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.cancelWholeTranslation()
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.wholeTranslationRepository.cancel(8L) }
    }

    /** 换文章后，旧 sheet 的异步结果不得落到新文章上。 */
    @Test
    fun openWholeTranslation_articleChangesBeforeResolution_dropsStaleState() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        stubStandalone(OTHER_ID, "Other.")
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        // 在 open 的协程完成前切换文章
        viewModel.loadArticle(OTHER_ID)
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value
        assertTrue(
            "stale ChoosingScope for the old article must not surface: $state",
            state == WholeTranslationSheetState.Hidden ||
                (state as? WholeTranslationSheetState.ChoosingScope)?.articleId != ARTICLE_ID
        )
    }

    // ---- helpers ----

    private fun stubStandalone(id: Long, content: String) {
        coEvery { fixture.articleRepository.getArticleById(id) } returns ArticleEntity(id, "t", content)
        coEvery { fixture.bookRepository.findChapterByArticleId(id) } returns null
    }

    private fun stubChapter(id: Long, bookId: Long, siblings: List<Long>) {
        coEvery { fixture.articleRepository.getArticleById(id) } returns ArticleEntity(id, "ch", "Body text.")
        val chapters = siblings.mapIndexed { index, articleId ->
            BookChapterEntity(id = index + 1L, bookId = bookId, articleId = articleId, chapterIndex = index, sourceHref = "c$index.xhtml", navigationTitle = "Ch $index")
        }
        coEvery { fixture.bookRepository.findChapterByArticleId(id) } returns chapters.first { it.articleId == id }
        coEvery { fixture.bookRepository.getChaptersOnce(bookId) } returns chapters
    }

    private fun view(taskId: Long, status: WholeTranslationTaskStatus, translated: Int, total: Int, failed: Int = 0) =
        WholeTranslationTaskView(
            taskId = taskId,
            scopeKey = "article:$ARTICLE_ID",
            status = status,
            failureReason = null,
            progress = WholeTranslationProgress(
                total = total,
                translated = translated,
                translating = 0,
                failed = failed,
                untranslated = total - translated - failed
            )
        )

    private companion object {
        const val ARTICLE_ID = 31L
        const val OTHER_ID = 99L
    }
}
