package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.repository.WholeTranslationStartResult
import io.github.zoot.englishreader.data.repository.WholeTranslationTaskView
import io.github.zoot.englishreader.model.WholeTranslationPrimaryAction
import io.github.zoot.englishreader.model.WholeTranslationPreview
import io.github.zoot.englishreader.model.WholeTranslationPreviewResult
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import io.github.zoot.englishreader.util.TranslationBlockPlanner
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.model.WholeTranslationProgress
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationScopeChoice
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        coEvery { fixture.wholeTranslationRepository.preview(any()) } coAnswers {
            val scope = firstArg<WholeTranslationScope>()
            val articles = scope.articleIds.map { requireNotNull(fixture.articleRepository.getArticleById(it)) }
            val plans = articles.map { article ->
                (TranslationBlockPlanner.plan(article.id, article.content, TranslationSegmentationMode.AUTO) { text ->
                    listOf(SentenceRange(0, text, 0, text.length))
                } as TranslationBlockPlanner.Result.Planned).plan
            }
            WholeTranslationPreviewResult.Ready(WholeTranslationPreview(scope, plans, articles.any { !it.translation.isNullOrBlank() }))
        }
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
    fun openWholeTranslation_otherChapterTooManyBlocks_keepsCurrentScopeStartable() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(ARTICLE_ID, OTHER_ID))
        val chapterScope = WholeTranslationScope.Chapter(7L, listOf(ARTICLE_ID, OTHER_ID))
        coEvery { fixture.wholeTranslationRepository.preview(chapterScope) } returns
            WholeTranslationPreviewResult.TooManyBlocks(OTHER_ID, 1_201, 1_200)
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns
            WholeTranslationStartResult.Started(11L)
        every { fixture.wholeTranslationRepository.observe(11L) } returns
            MutableStateFlow(view(11L, WholeTranslationTaskStatus.RUNNING, 0, 1))
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertEquals(WholeTranslationScopeChoice.CURRENT_ARTICLE, state.selected)
        assertEquals(1, state.currentArticleOption.blockCount)
        assertNull(state.chapterOption)
        assertFalse(state.isPreviewing)
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) }

        viewModel.selectWholeTranslationScope(WholeTranslationScopeChoice.CHAPTER)
        assertEquals(state, viewModel.wholeTranslationState.value)
        viewModel.startWholeTranslation()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fixture.wholeTranslationRepository.start(match<WholeTranslationPreview> {
                it.scope == WholeTranslationScope.CurrentArticle(ARTICLE_ID)
            })
        }
        assertEquals(11L, (viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking).taskId)
    }

    @Test
    fun openWholeTranslation_chapterPreviewRejected_keepsCurrentScopeStartable() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(ARTICLE_ID, OTHER_ID))
        coEvery {
            fixture.wholeTranslationRepository.preview(WholeTranslationScope.Chapter(7L, listOf(ARTICLE_ID, OTHER_ID)))
        } returns WholeTranslationPreviewResult.Rejected(AiError.NoContent)
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns
            WholeTranslationStartResult.Started(11L)
        every { fixture.wholeTranslationRepository.observe(11L) } returns
            MutableStateFlow(view(11L, WholeTranslationTaskStatus.RUNNING, 0, 1))
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertEquals(WholeTranslationScopeChoice.CURRENT_ARTICLE, state.selected)
        assertNull(state.chapterOption)
        assertFalse(state.isPreviewing)
        viewModel.startWholeTranslation()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fixture.wholeTranslationRepository.start(match<WholeTranslationPreview> {
                it.scope == WholeTranslationScope.CurrentArticle(ARTICLE_ID)
            })
        }
        assertEquals(11L, (viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking).taskId)
    }

    @Test
    fun selectWholeTranslationMode_selectedChapterBecomesUnavailable_fallsBackToCurrentScope() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(ARTICLE_ID, OTHER_ID))
        coEvery { fixture.articleRepository.getArticleById(OTHER_ID) } returns
            ArticleEntity(OTHER_ID, "other", "Other.", translation = "其他。")
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()
        viewModel.selectWholeTranslationScope(WholeTranslationScopeChoice.CHAPTER)
        val selected = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertEquals(WholeTranslationScopeChoice.CHAPTER, selected.selected)
        assertTrue(selected.hasTranslation)
        val previewGate = CompletableDeferred<WholeTranslationPreviewResult>()
        coEvery {
            fixture.wholeTranslationRepository.preview(WholeTranslationScope.Chapter(7L, listOf(ARTICLE_ID, OTHER_ID)))
        } coAnswers { previewGate.await() }

        viewModel.selectWholeTranslationMode(TranslationSegmentationMode.PRESERVE)
        advanceUntilIdle()
        assertTrue((viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope).isPreviewing)
        assertFalse(previewGate.isCompleted)
        previewGate.complete(WholeTranslationPreviewResult.TooManyBlocks(OTHER_ID, 1_201, 1_200))
        advanceUntilIdle()

        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertEquals(WholeTranslationScopeChoice.CURRENT_ARTICLE, state.selected)
        assertNull(state.chapterOption)
        assertFalse(state.hasTranslation)
        assertFalse(state.isPreviewing)
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) }
    }

    @Test
    fun openWholeTranslation_currentArticleTooManyBlocks_keepsPreserveRecovery() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(ARTICLE_ID, OTHER_ID))
        coEvery { fixture.wholeTranslationRepository.preview(WholeTranslationScope.CurrentArticle(ARTICLE_ID)) } returns
            WholeTranslationPreviewResult.TooManyBlocks(ARTICLE_ID, 1_201, 1_200)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        assertEquals(
            WholeTranslationSheetState.TooManyBlocks(ARTICLE_ID, 1_201, 1_200, canPreserve = true),
            viewModel.wholeTranslationState.value
        )
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) }
    }

    @Test
    fun openWholeTranslation_currentArticleRejected_preservesError() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(ARTICLE_ID, OTHER_ID))
        coEvery { fixture.wholeTranslationRepository.preview(WholeTranslationScope.CurrentArticle(ARTICLE_ID)) } returns
            WholeTranslationPreviewResult.Rejected(AiError.NoContent)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()

        viewModel.openWholeTranslation()
        advanceUntilIdle()

        assertEquals(
            WholeTranslationSheetState.Rejected(ARTICLE_ID, AiError.NoContent),
            viewModel.wholeTranslationState.value
        )
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) }
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
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns WholeTranslationStartResult.Started(11L)
        every { fixture.wholeTranslationRepository.observe(11L) } returns MutableStateFlow(running)
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.wholeTranslationRepository.start(match<WholeTranslationPreview> { it.scope == WholeTranslationScope.CurrentArticle(ARTICLE_ID) }) }
        val state = viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking
        assertEquals(11L, state.taskId)
        assertEquals(WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND, state.primaryAction)
    }

    @Test
    fun startWholeTranslation_repeatedTaps_stayBlockedUntilFirstTaskEmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = fixture.create()
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(ARTICLE_ID))
        val startGate = CompletableDeferred<Unit>()
        val observationGate = CompletableDeferred<Unit>()
        var starts = 0
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } coAnswers {
            starts++
            startGate.await()
            WholeTranslationStartResult.Started(11L)
        }
        every { fixture.wholeTranslationRepository.observe(11L) } returns flow {
            observationGate.await()
            emit(view(11L, WholeTranslationTaskStatus.RUNNING, 0, 1))
        }
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        viewModel.startWholeTranslation()
        viewModel.selectWholeTranslationScope(WholeTranslationScopeChoice.CHAPTER)
        val starting = viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope
        assertTrue(starting.isStarting)
        assertEquals(WholeTranslationScopeChoice.CURRENT_ARTICLE, starting.selected)
        runCurrent()
        assertEquals(1, starts)
        assertFalse(startGate.isCompleted)

        startGate.complete(Unit)
        runCurrent()
        assertTrue((viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope).isStarting)
        assertFalse(observationGate.isCompleted)
        viewModel.startWholeTranslation()
        runCurrent()
        assertEquals(1, starts)

        observationGate.complete(Unit)
        runCurrent()
        assertEquals(11L, (viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking).taskId)
        coVerify(exactly = 1) { fixture.wholeTranslationRepository.start(match<WholeTranslationPreview> { it.scope == WholeTranslationScope.CurrentArticle(ARTICLE_ID) }) }
    }

    @Test
    fun startWholeTranslation_cancelledPreparation_releasesStartingState() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } throws CancellationException()
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        assertFalse((viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope).isStarting)
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns WholeTranslationStartResult.Started(12L)
        every { fixture.wholeTranslationRepository.observe(12L) } returns
            MutableStateFlow(view(12L, WholeTranslationTaskStatus.RUNNING, 0, 1))
        viewModel.startWholeTranslation()
        advanceUntilIdle()
        assertEquals(12L, (viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking).taskId)
    }

    @Test
    fun startWholeTranslation_storageFailure_rejectsAndAllowsReopening() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } throws java.io.IOException()
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        assertEquals(
            WholeTranslationSheetState.Rejected(ARTICLE_ID, AiError.Unknown),
            viewModel.wholeTranslationState.value
        )
        viewModel.openWholeTranslation()
        advanceUntilIdle()
        assertFalse((viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope).isStarting)
    }

    @Test
    fun startWholeTranslation_observationFailure_doesNotLeaveStartingState() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns WholeTranslationStartResult.Started(11L)
        every { fixture.wholeTranslationRepository.observe(11L) } returns flow { throw java.io.IOException() }
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        assertEquals(
            WholeTranslationSheetState.Rejected(ARTICLE_ID, AiError.Unknown),
            viewModel.wholeTranslationState.value
        )
    }

    @Test
    fun startWholeTranslation_observationCancelledBeforeFirstEmission_releasesStartingState() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns WholeTranslationStartResult.Started(11L)
        every { fixture.wholeTranslationRepository.observe(11L) } returns flow { throw CancellationException() }
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        viewModel.startWholeTranslation()
        advanceUntilIdle()

        assertFalse((viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope).isStarting)
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.cancel(any()) }
    }

    @Test
    fun startWholeTranslation_oldFailureAfterReopen_doesNotUnlockNewStart() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        var starts = 0
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } coAnswers {
            if (++starts == 1) {
                oldGate.await()
                throw java.io.IOException()
            }
            newGate.await()
            WholeTranslationStartResult.Started(12L)
        }
        every { fixture.wholeTranslationRepository.observe(12L) } returns
            MutableStateFlow(view(12L, WholeTranslationTaskStatus.RUNNING, 0, 1))
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()
        viewModel.startWholeTranslation()
        advanceUntilIdle()
        assertFalse(oldGate.isCompleted)

        viewModel.dismissWholeTranslation()
        viewModel.openWholeTranslation()
        advanceUntilIdle()
        viewModel.startWholeTranslation()
        advanceUntilIdle()
        assertFalse(newGate.isCompleted)
        oldGate.complete(Unit)
        advanceUntilIdle()

        assertTrue((viewModel.wholeTranslationState.value as WholeTranslationSheetState.ChoosingScope).isStarting)
        viewModel.startWholeTranslation()
        assertEquals(2, starts)
        newGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(12L, (viewModel.wholeTranslationState.value as WholeTranslationSheetState.Tracking).taskId)
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.cancel(any()) }
    }

    @Test
    fun startWholeTranslation_chapterScope_passesOrderedChapterArticleIds() = runTest {
        stubChapter(ARTICLE_ID, bookId = 7L, siblings = listOf(30L, ARTICLE_ID, 32L))
        coEvery { fixture.articleRepository.getArticleById(30L) } returns ArticleEntity(30L, "a", "P.")
        coEvery { fixture.articleRepository.getArticleById(32L) } returns ArticleEntity(32L, "c", "R.")
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns WholeTranslationStartResult.Started(12L)
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
            fixture.wholeTranslationRepository.start(match<WholeTranslationPreview> { it.scope == WholeTranslationScope.Chapter(7L, listOf(30L, ARTICLE_ID, 32L)) })
        }
    }

    @Test
    fun startWholeTranslation_noContent_showsRejected() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        coEvery { fixture.wholeTranslationRepository.start(any<WholeTranslationPreview>()) } returns WholeTranslationStartResult.NoContent
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
    fun tracking_completed_observesArticleSoBilingualParagraphsAppear() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val article = MutableStateFlow(ArticleEntity(ARTICLE_ID, "t", "One."))
        every { fixture.articleRepository.observeArticle(ARTICLE_ID) } returns article
        val flow = MutableStateFlow(view(taskId = 6L, status = WholeTranslationTaskStatus.RUNNING, translated = 0, total = 1))
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns flow.value
        every { fixture.wholeTranslationRepository.observe(6L) } returns flow
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.openWholeTranslation()
        advanceUntilIdle()

        article.value = article.value.copy(translation = "一。")
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
    fun dismissWholeTranslation_completionStillUpdatesArticleWithoutReopeningSheet() = runTest {
        stubStandalone(ARTICLE_ID, "One.")
        val article = MutableStateFlow(ArticleEntity(ARTICLE_ID, "t", "One."))
        every { fixture.articleRepository.observeArticle(ARTICLE_ID) } returns article
        val task = MutableStateFlow(view(8L, WholeTranslationTaskStatus.RUNNING, 0, 1))
        coEvery { fixture.wholeTranslationRepository.findResumable(any()) } returns task.value
        every { fixture.wholeTranslationRepository.observe(8L) } returns task
        viewModel.loadArticle(ARTICLE_ID)
        advanceUntilIdle()
        viewModel.consumePositionTarget(requireNotNull(viewModel.pendingPositionTarget.value))
        viewModel.openWholeTranslation()
        advanceUntilIdle()
        assertTrue(viewModel.wholeTranslationState.value is WholeTranslationSheetState.Tracking)
        viewModel.dismissWholeTranslation()
        advanceUntilIdle()

        article.value = article.value.copy(translation = "一。")
        task.value = view(8L, WholeTranslationTaskStatus.COMPLETED, 1, 1)
        advanceUntilIdle()

        assertEquals("一。", viewModel.article.value?.translation)
        assertNull(viewModel.pendingPositionTarget.value)
        assertEquals(WholeTranslationSheetState.Hidden, viewModel.wholeTranslationState.value)
        coVerify(exactly = 0) { fixture.wholeTranslationRepository.cancel(any()) }
        coVerify(exactly = 1) { fixture.articleRepository.getArticleById(ARTICLE_ID) }
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
