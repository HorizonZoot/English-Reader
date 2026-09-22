package io.github.zoot.englishreader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.ai.AiExplanationResolutionResult
import io.github.zoot.englishreader.data.ai.AiTextNormalizer
import io.github.zoot.englishreader.data.ai.ExplanationType
import io.github.zoot.englishreader.data.ai.ResolvedAiExplanationOperation
import io.github.zoot.englishreader.data.ai.ResolvedAiExplanationRequest
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.model.AppliedTranslationLayoutCodec
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.DefaultTranslationMaterializationPolicy
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.TranslationBlockAggregator
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.model.TranslationPlannerVersion
import io.github.zoot.englishreader.model.TranslationSegmentStatus
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.util.TranslationBlockPlanner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 对照分块在仓库层的端到端行为。
 *
 * 与 [WholeTranslationRepositoryTest] 分开：那个文件锁的是任务协调语义（一次 profile、逐段
 * checkpoint、失败三分法、恢复不重跑），本文件锁的是**分块**——请求按块发出、坐标被固定、发布时
 * 同时写出译文与布局、旧任务仍按整段读完。
 *
 * 用真实 Room 而非 mock DAO：这里要证明的多数事实都是「库里存成了什么」，mock 会让断言退化成对
 * mock 配置的复述。分句注入确定性假实现，理由见 [TranslationBlockPlannerTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeTranslationBlockPathTest {

    private lateinit var db: EnglishReaderDatabase
    private lateinit var executor: AiExecutor
    private lateinit var resolver: AiExplanationRequestResolver
    private val requestedInputs = mutableListOf<String>()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        executor = mockk()
        resolver = mockk()
        coEvery { resolver.resolveActiveProfileSnapshot() } returns
            ProfileResolutionResult.Available(PROFILE)
        val inputSlot = slot<AiExplanationInput>()
        every { resolver.resolveWithProfile(any(), capture(inputSlot)) } answers {
            AiExplanationResolutionResult.Ready(
                operationFor(AiTextNormalizer.normalizeInput(inputSlot.captured.text))
            )
        }
        every { resolver.mapProfileFailure(any()) } returns null
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 用户报告的问题 ----

    /**
     * 只有单换行的正文必须按行切块，而不是整篇一个请求。
     *
     * 这正是用户报告的现象：`ParagraphAligner` 只认空行，于是整篇是「一段」，译文全部堆在原文
     * 之后。修好后这里应当看到三次请求，而非一次。
     */
    @Test
    fun start_singleNewlineContent_requestsEachLineAsItsOwnBlock() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.\nThird sentence.")
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)

        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        assertEquals(
            listOf("First sentence.", "Second sentence.", "Third sentence."),
            requestedInputs
        )
    }

    /** 同一篇文章的译文必须仍能按原段落切回，否则回落到整段对照时会错位。 */
    @Test
    fun start_singleNewlineContent_writesTranslationThatSplitsBackIntoOneParagraph() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)

        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        val translation = db.articleDao().getArticleById(articleId)?.translation
        assertEquals("译:First sentence.\n译:Second sentence.", translation)
        // 原文是一个空行段落，译文也必须是一段：块间用单换行正是为了这一点。
        assertEquals(1, ParagraphAligner.splitParagraphs(requireNotNull(translation)).size)
    }

    // ---- 坐标固定与发布 ----

    @Test
    fun start_blockTask_persistsBlockCoordinatesAndVersion() = runTest {
        val content = "First sentence.\nSecond sentence."
        val articleId = insertArticle(content)
        succeedWith { "译" }
        val repo = repository(this)

        val started = repo.start(WholeTranslationScope.CurrentArticle(articleId))
            as WholeTranslationStartResult.Started
        advanceUntilIdle()

        val target = db.wholeTranslationDao().getTaskArticles(started.taskId).single()
        assertEquals(TranslationPlannerVersion.BLOCK_V1, target.plannerVersion)
        assertEquals(TranslationSegmentationMode.AUTO.toStableToken(), target.segmentationMode)

        val segments = db.wholeTranslationDao().getSegments(started.taskId)
        assertEquals(listOf(0, 1), segments.map { it.paragraphIndex })
        assertTrue("每块都必须带齐源坐标", segments.all { it.sourceParagraphIndex == 0 })
        assertEquals(listOf(0, 16), segments.map { it.sourceStartOffset })
        assertEquals(listOf(16, content.length), segments.map { it.sourceEndOffset })
    }

    /** 发布必须同时写出布局，且布局要能被解码器接受——否则阅读层拿不到对照。 */
    @Test
    fun start_blockTaskCompletes_publishesDecodableLayout() = runTest {
        val content = "First sentence.\nSecond sentence."
        val articleId = insertArticle(content)
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)

        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        val state = requireNotNull(db.wholeTranslationDao().getArticleTranslationState(articleId))
        val translation = requireNotNull(db.articleDao().getArticleById(articleId)?.translation)
        assertEquals(TranslationFingerprint.forArticle(content), state.appliedSourceFingerprint)
        assertEquals(TranslationFingerprint.forTranslation(translation), state.appliedTranslationFingerprint)

        val layout = requireNotNull(
            CODEC.decode(state.appliedPlan, ParagraphAligner.splitParagraphs(content))
        )
        // 用发布的坐标把原文与译文都切回来，确认两侧一一对应。
        val paragraphTranslation =
            requireNotNull(TranslationBlockAggregator.paragraphTranslation(translation, 0))
        val pairs = layout.blocks.map {
            content.substring(it.sourceStartOffset, it.sourceEndOffset).trim() to
                paragraphTranslation.substring(it.translationStartOffset, it.translationEndOffset).trim()
        }
        assertEquals(
            listOf("First sentence." to "译:First sentence.", "Second sentence." to "译:Second sentence."),
            pairs
        )
    }

    /** 布局的生命周期与任务解耦：删掉任务后对照仍然可读。 */
    @Test
    fun deleteTask_afterPublishing_keepsAppliedLayout() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译" }
        val repo = repository(this)
        val started = repo.start(WholeTranslationScope.CurrentArticle(articleId))
            as WholeTranslationStartResult.Started
        advanceUntilIdle()

        db.wholeTranslationDao().deleteTask(started.taskId)

        val state = requireNotNull(db.wholeTranslationDao().getArticleTranslationState(articleId))
        assertNotNull("已发布布局必须比任务活得久", state.appliedPlan)
    }

    // ---- 偏好 ----

    @Test
    fun savePreferredMode_preserveMode_plansOneBlockPerSourceParagraph() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译" }
        val repo = repository(this)

        repo.savePreferredMode(articleId, TranslationSegmentationMode.PRESERVE)
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        // 保留原段落：整篇只有一个空行段落，因此只发一次请求。
        assertEquals(listOf("First sentence.\nSecond sentence."), requestedInputs)
    }

    /** 改偏好不得动已发布的对照，也不得重新发起请求。 */
    @Test
    fun savePreferredMode_afterPublishing_keepsAppliedLayoutAndIssuesNoRequest() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译" }
        val repo = repository(this)
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()
        val published = requireNotNull(
            db.wholeTranslationDao().getArticleTranslationState(articleId)?.appliedPlan
        )
        requestedInputs.clear()

        repo.savePreferredMode(articleId, TranslationSegmentationMode.LINE)
        advanceUntilIdle()

        val state = requireNotNull(db.wholeTranslationDao().getArticleTranslationState(articleId))
        assertEquals("偏好写入不得覆盖已发布布局", published, state.appliedPlan)
        assertEquals(TranslationSegmentationMode.LINE.toStableToken(), state.preferredMode)
        assertTrue("改偏好不得发出任何请求", requestedInputs.isEmpty())
    }

    /** 发布不得覆盖用户刚改的偏好。 */
    @Test
    fun start_publishing_doesNotOverwriteUserPreferredMode() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译" }
        val repo = repository(this)
        repo.savePreferredMode(articleId, TranslationSegmentationMode.PRESERVE)

        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        assertEquals(
            TranslationSegmentationMode.PRESERVE.toStableToken(),
            db.wholeTranslationDao().getArticleTranslationState(articleId)?.preferredMode
        )
    }

    @Test
    fun start_lineModeExceedingBlockLimit_rejectsWithoutTaskOrRequest() = runTest {
        val lines = TranslationBlockPlanner.MAX_TRANSLATION_BLOCKS_PER_ARTICLE + 1
        val articleId = insertArticle((1..lines).joinToString("\n") { "Line $it." })
        succeedWith { "译" }
        val repo = repository(this)
        repo.savePreferredMode(articleId, TranslationSegmentationMode.LINE)

        val result = repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        val rejected = result as WholeTranslationStartResult.TooManyBlocks
        assertEquals(articleId, rejected.articleId)
        assertEquals(lines, rejected.actualBlocks)
        assertNull(db.wholeTranslationDao().findResumableTask("article:$articleId"))
        assertTrue("超限必须在发请求之前判定", requestedInputs.isEmpty())
    }

    // ---- 旧任务兼容 ----

    /**
     * 升级前建好的任务必须继续按整段读完，且**不重新请求**已成功的段落。
     *
     * 这是兼容策略的支点：旧行没有块坐标，若被当作块解释，段落序号会被读成块序号，用户已经付费
     * 换来的译文就对到了错误的位置上。
     */
    @Test
    fun resume_legacyTask_translatesRemainingParagraphsWithoutRerunningSucceeded() = runTest {
        val content = "Alpha one.\n\nBeta two."
        val articleId = insertArticle(content)
        val paragraphs = ParagraphAligner.splitParagraphs(content)
        val now = 1L
        val taskId = db.wholeTranslationDao().createTask(
            task = WholeTranslationTaskEntity(
                scopeKey = "article:$articleId",
                status = WholeTranslationTaskStatus.PAUSED.toStableToken(),
                createdAt = now,
                updatedAt = now
            ),
            articles = listOf(
                io.github.zoot.englishreader.data.dao.TranslationTaskTarget(
                    articleId = articleId,
                    articleFingerprint = TranslationFingerprint.forArticle(content),
                    segmentationMode = TranslationSegmentationMode.PRESERVE.toStableToken(),
                    plannerVersion = TranslationPlannerVersion.LEGACY
                )
            ),
            segments = paragraphs.mapIndexed { index, paragraph ->
                TranslationSegmentEntity(
                    taskId = 0,
                    articleId = articleId,
                    paragraphIndex = index,
                    sourceFingerprint = TranslationFingerprint.forParagraph(paragraph),
                    status = if (index == 0) {
                        TranslationSegmentStatus.TRANSLATED.toStableToken()
                    } else {
                        TranslationSegmentStatus.UNTRANSLATED.toStableToken()
                    },
                    translatedText = if (index == 0) "旧译文" else null,
                    updatedAt = now
                )
            },
            now = now
        )
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)

        repo.resume(taskId)
        advanceUntilIdle()

        // 只请求未完成的那一段，已付费的第 0 段不再请求。
        assertEquals(listOf("Beta two."), requestedInputs)
        assertEquals("旧译文\n\n译:Beta two.", db.articleDao().getArticleById(articleId)?.translation)
        // legacy 目标不产出布局：阅读层据此走整段对照。
        assertNull(db.wholeTranslationDao().getArticleTranslationState(articleId)?.appliedPlan)
    }

    // ---- 冲突 ----

    /** 同一篇文章被两个范围覆盖时必须能被发现，否则后提交的发布会让另一份译文与布局对不上。 */
    @Test
    fun findOverlappingTasks_currentArticleTaskForSameArticle_isReported() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译" }
        val repo = repository(this)
        val started = repo.start(WholeTranslationScope.CurrentArticle(articleId))
            as WholeTranslationStartResult.Started

        val overlapping = db.wholeTranslationDao().findOverlappingTasks(listOf(articleId))

        assertEquals(listOf(started.taskId), overlapping.map { it.taskId })
        assertTrue(
            "排除自身后不应报告冲突",
            db.wholeTranslationDao().findOverlappingTasks(listOf(articleId), started.taskId).isEmpty()
        )
    }

    /**
     * 另一个范围已覆盖这篇文章时，必须拒绝建任务且**一次请求都不发**。
     *
     * 这是冲突检查真正要防的事：两个任务都会跑到发布阶段，后提交的那个用自己的分块覆盖译文与
     * 布局，而另一个已发布的布局仍指向旧译文的坐标——用户看到一半新一半旧的对照，两边还都计过费。
     *
     * 断言里最重要的一条是 `requestedInputs` 为空：拒绝必须发生在发出任何付费请求之前。
     */
    @Test
    fun start_articleAlreadyCoveredByAnotherScope_isRejectedWithoutTaskOrRequest() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        // 第一个任务永不完成，才能保持在「进行中」状态构成冲突。
        coEvery { executor.execute(any(), any()) } answers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            AiClientResult.Failure(AiError.Offline)
        }
        val repo = repository(this)
        val first = repo.start(WholeTranslationScope.CurrentArticle(articleId))
            as WholeTranslationStartResult.Started
        advanceUntilIdle()
        requestedInputs.clear()

        // 整书范围覆盖同一篇：scopeKey 不同，所以不会被当作同源任务接续，会一直走到建任务。
        bindToBook(articleId)
        val conflicted = repo.start(WholeTranslationScope.Chapter(bookId = 9, articleIds = listOf(articleId)))
        advanceUntilIdle()

        assertEquals(WholeTranslationStartResult.Conflict(first.taskId, "article:$articleId"), conflicted)
        assertTrue("冲突必须在发出任何付费请求之前拒绝", requestedInputs.isEmpty())
        assertNull("不得建出整书任务", db.wholeTranslationDao().findResumableTask("book:9"))
        // 既有任务不被代码擅自取消：那些块里已有付费成功的，取消等于让用户白付。
        assertEquals(
            WholeTranslationTaskStatus.PAUSED,
            WholeTranslationTaskStatus.fromStableToken(db.wholeTranslationDao().getTask(first.taskId)?.status)
        )
    }

    /** 既有任务进入终态后不再构成冲突，否则用户取消旧任务后仍然无法重新开始。 */
    @Test
    fun start_afterCancellingConflictingTask_succeeds() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)
        val first = repo.start(WholeTranslationScope.CurrentArticle(articleId))
            as WholeTranslationStartResult.Started
        advanceUntilIdle()
        // 完成态同样不构成冲突，这里显式取消以覆盖用户手动放弃的那条路径。
        repo.cancel(first.taskId)
        advanceUntilIdle()
        requestedInputs.clear()

        bindToBook(articleId)
        val second = repo.start(WholeTranslationScope.Chapter(bookId = 9, articleIds = listOf(articleId)))
        advanceUntilIdle()

        assertTrue("取消既有任务后必须能重新开始", second is WholeTranslationStartResult.Started)
        assertEquals(listOf("First sentence.", "Second sentence."), requestedInputs)
    }

    // ---- 正文编辑 ----

    /**
     * 编辑正文必须连带清除已发布布局，但保留分块偏好。
     *
     * 布局里的 offset 是按编辑前的正文算的。留着它，阅读层会拿旧坐标去裁新正文——错位的对照还算
     * 轻，切在代理对中间就是乱码。而偏好是用户对这篇文章的选择，与正文改了什么无关。
     */
    @Test
    fun saveEdit_changingContent_clearsAppliedLayoutButKeepsPreferredMode() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)
        repo.savePreferredMode(articleId, TranslationSegmentationMode.LINE)
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()
        assertNotNull(
            "前置条件：发布后应当存在布局",
            db.wholeTranslationDao().getArticleTranslationState(articleId)?.appliedPlan
        )

        val edited = db.articleDao().saveEdit(
            original = ArticleEditSnapshot(
                articleId = articleId,
                title = "T",
                content = "First sentence.\nSecond sentence."
            ),
            title = "T",
            content = "Only one sentence now.",
            positionTimestamp = NOW + 1
        )

        assertEquals(ArticleEditResult.Saved, edited)
        val state = db.wholeTranslationDao().getArticleTranslationState(articleId)
        assertNull("布局必须随正文编辑一起清除", state?.appliedPlan)
        assertNull(state?.appliedSourceFingerprint)
        assertNull(state?.appliedTranslationFingerprint)
        assertNull(state?.appliedTaskId)
        assertEquals(
            "偏好与正文无关，不应被编辑清掉",
            TranslationSegmentationMode.LINE.toStableToken(),
            state?.preferredMode
        )
        // 译文本身也已失效，由既有的编辑逻辑置空。
        assertNull(db.articleDao().getArticleById(articleId)?.translation)
    }

    /** 只改标题不触碰译文，也不该清掉布局。 */
    @Test
    fun saveEdit_titleOnly_keepsAppliedLayout() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()
        val before = db.wholeTranslationDao().getArticleTranslationState(articleId)

        db.articleDao().saveEdit(
            original = ArticleEditSnapshot(
                articleId = articleId,
                title = "T",
                content = "First sentence.\nSecond sentence."
            ),
            title = "New title",
            content = "First sentence.\nSecond sentence.",
            positionTimestamp = NOW + 1
        )

        val after = db.wholeTranslationDao().getArticleTranslationState(articleId)
        assertEquals(before?.appliedPlan, after?.appliedPlan)
        assertEquals(before?.appliedTranslationFingerprint, after?.appliedTranslationFingerprint)
        assertNotNull(db.articleDao().getArticleById(articleId)?.translation)
    }

    // ---- 发布时的锚点换算 ----

    /**
     * 发布新译文必须同时改写持久的中文阅读锚点。
     *
     * 旧的 characterOffset 是针对**上一份**译文字符串算的。新译文是另一次模型输出，长度与断句都不同，
     * 沿用旧偏移会落在无关的字符上，甚至越界。换算目标是对应块的原文起点——那一定是用户读过的位置附近，
     * 且坐标系是正文，不随译文变化。
     */
    @Test
    fun start_publishing_convertsStoredTranslationAnchorToSourceCoordinates() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        // 先造一个指向旧译文的中文锚点，偏移刻意取得比新译文更长，沿用它必然越界。
        db.articleDao().saveReadingPosition(
            ReadingPositionEntity(
                articleId = articleId,
                paragraphIndex = 0,
                textKind = ReadingTextKind.TRANSLATION.name,
                characterOffset = 999,
                updatedAt = NOW - 1
            )
        )
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)

        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        val stored = db.articleDao().getReadingPosition(articleId)
        assertEquals(
            "中文锚点必须换算回正文坐标系",
            ReadingTextKind.ORIGINAL.name,
            stored?.textKind
        )
        assertEquals(0, stored?.paragraphIndex)
        // 旧布局不存在（此前是整段对照），因此退回原段起点。
        assertEquals(0, stored?.characterOffset)
        // 时间戳必须前进，否则后续的 saveReadingPosition 会把换算结果当成旧写入丢弃。
        assertTrue("换算后的时间戳必须晚于原锚点", (stored?.updatedAt ?: 0) >= NOW)
    }

    /** 正文锚点的坐标系是正文，发布不改正文，因此必须逐字不动。 */
    @Test
    fun start_publishing_leavesOriginalAnchorUntouched() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        db.articleDao().saveReadingPosition(
            ReadingPositionEntity(
                articleId = articleId,
                paragraphIndex = 0,
                textKind = ReadingTextKind.ORIGINAL.name,
                characterOffset = 7,
                updatedAt = NOW - 1
            )
        )
        succeedWith { "译:${it.trim()}" }
        val repo = repository(this)

        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        val stored = db.articleDao().getReadingPosition(articleId)
        assertEquals(ReadingTextKind.ORIGINAL.name, stored?.textKind)
        assertEquals(7, stored?.characterOffset)
    }

    @Test
    fun preview_thenStart_persistsExactlyThePreviewWithoutReplanningOrEarlyProfileAccess() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        var splits = 0
        val repo = WholeTranslationRepository(db.wholeTranslationDao(), db.articleDao(), resolver, executor,
            backgroundScope, { NOW }, POLICY, { splits++; splitSentences(it) })
        val preview = (repo.preview(WholeTranslationScope.CurrentArticle(articleId)) as
            io.github.zoot.englishreader.model.WholeTranslationPreviewResult.Ready).preview
        assertEquals(1, preview.option.paragraphCount)
        assertEquals(2, preview.option.blockCount)
        io.mockk.coVerify(exactly = 0) { resolver.resolveActiveProfileSnapshot() }
        val count = splits
        val started = repo.start(preview) as WholeTranslationStartResult.Started
        assertEquals(count, splits)
        assertEquals(preview.plans.single().blocks.map { it.startOffset },
            db.wholeTranslationDao().getSegments(started.taskId).map { it.sourceStartOffset })
        repo.cancel(started.taskId)
    }

    @Test
    fun start_preferenceChangedAfterPreview_rejectsWithoutTaskOrPaidWork() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        val repo = repository(this)
        val preview = (repo.preview(WholeTranslationScope.CurrentArticle(articleId)) as
            io.github.zoot.englishreader.model.WholeTranslationPreviewResult.Ready).preview
        repo.savePreferredMode(articleId, TranslationSegmentationMode.PRESERVE)
        assertEquals(WholeTranslationStartResult.SourceChanged, repo.start(preview))
        assertNull(db.wholeTranslationDao().findResumableTask("article:$articleId"))
        io.mockk.coVerify(exactly = 0) { resolver.resolveActiveProfileSnapshot() }
    }

    @Test
    fun readingJoin_afterTaskDeletion_readsPublishedLayoutAndRejectsMismatchedTranslation() = runTest {
        val articleId = insertArticle("First sentence.\nSecond sentence.")
        succeedWith { "译:$it" }
        val task = (repository(this).start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        db.wholeTranslationDao().deleteTask(task)
        val reader = ArticleRepository(db.articleDao(), backgroundScope, CODEC)
        val published = requireNotNull(reader.getReadingArticle(articleId))
        assertNotNull(published.layout)
        assertEquals(4, io.github.zoot.englishreader.model.ReadingTranslationProjection.project(
            ParagraphAligner.splitParagraphs(published.article.content), published.article.translation, published.layout, true
        ).size)
        db.articleDao().updateArticle(published.article.copy(translation = "Different translation"))
        val fallback = requireNotNull(reader.getReadingArticle(articleId))
        assertNull(fallback.layout)
        assertEquals("Different translation", fallback.article.translation)
    }

    @Test
    fun publication_layoutOnlyChange_convertsAnchorAndRejectsLateOldSave() = runTest {
        val content = "First sentence.\nSecond sentence."
        val articleId = insertArticle(content)
        val repo = repository(this)
        val reader = ArticleRepository(db.articleDao(), backgroundScope, CODEC)
        succeedWith { "译" }
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()
        val old = requireNotNull(reader.getReadingArticle(articleId))
        val oldPosition = io.github.zoot.englishreader.model.ReadingPosition(articleId,
            io.github.zoot.englishreader.model.ReadingAnchor(0, ReadingTextKind.TRANSLATION, 2))
        reader.saveReadingPosition(oldPosition, content, old.publication)
        val timestamp = requireNotNull(db.articleDao().getReadingPosition(articleId)).updatedAt
        repo.savePreferredMode(articleId, TranslationSegmentationMode.PRESERVE)
        succeedWith { "译\n译" }
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()
        val fresh = requireNotNull(reader.getReadingArticle(articleId))
        assertEquals(old.article.translation, fresh.article.translation)
        val converted = requireNotNull(db.articleDao().getReadingPosition(articleId))
        assertEquals(ReadingTextKind.ORIGINAL.name, converted.textKind)
        assertEquals(16, converted.characterOffset)
        assertTrue(converted.updatedAt > timestamp)
        reader.saveReadingPosition(oldPosition, content, old.publication)
        assertEquals(converted, db.articleDao().getReadingPosition(articleId))
        val accepted = oldPosition.copy(anchor = io.github.zoot.englishreader.model.ReadingAnchor(0, characterOffset = 18))
        reader.saveReadingPosition(accepted, content, fresh.publication)
        assertEquals(18, db.articleDao().getReadingPosition(articleId)?.characterOffset)
    }

    @Test
    fun resume_preUpgradeOverlappingTasks_areBlockedBeforeProfileOrRequest() = runTest {
        val articleId = insertArticle("First sentence.")
        val dao = db.wholeTranslationDao()
        val repo = repository(this)
        val first = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        repo.pause(first)
        advanceUntilIdle()
        // 模拟升级前数据库已有重叠任务；新 createTask 刻意不允许构造这种状态。
        val second = dao.insertTaskRow(requireNotNull(dao.getTask(first)).copy(taskId = 0, scopeKey = "legacy-overlap"))
        dao.insertTaskArticles(dao.getTaskArticles(first).map { it.copy(taskId = second) })
        dao.insertSegments(dao.getSegments(first).map { it.copy(taskId = second) })
        assertEquals(first, repo.conflictFor(second)?.taskId)
        repo.resume(second)
        advanceUntilIdle()
        assertTrue(requestedInputs.isEmpty())
        io.mockk.coVerify(exactly = 0) { resolver.resolveActiveProfileSnapshot() }
    }

    private suspend fun bindToBook(articleId: Long) {
        db.bookDao().insertBookRow(io.github.zoot.englishreader.data.entity.BookEntity(
            id = 9, title = "Book", contentFingerprint = "book", sourceFormat = "epub3", chapterCount = 1, totalChars = 32
        ))
        db.bookDao().insertChapterRelation(io.github.zoot.englishreader.data.entity.BookChapterEntity(
            bookId = 9, articleId = articleId, chapterIndex = 0, sourceHref = "chapter.xhtml"
        ))
    }

    // ---- helpers ----

    private fun repository(scope: CoroutineScope) = WholeTranslationRepository(
        dao = db.wholeTranslationDao(),
        articleDao = db.articleDao(),
        requestResolver = resolver,
        executor = executor,
        applicationScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher((scope as TestScope).testScheduler)
        ),
        clock = { NOW },
        materializationPolicy = POLICY,
        sentenceSplitter = ::splitSentences
    )

    private suspend fun insertArticle(content: String): Long =
        db.articleDao().insertArticle(ArticleEntity(title = "T", content = content))

    private fun succeedWith(translate: (String) -> String) {
        coEvery { executor.execute(any(), any()) } answers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            AiClientResult.Success(translate(text))
        }
    }

    private fun operationFor(text: String): ResolvedAiExplanationOperation =
        ResolvedAiExplanationOperation(
            ResolvedAiExplanationRequest(
                profile = PROFILE,
                normalizedModelId = "m",
                normalizedInput = text,
                outputLanguageTag = "zh-CN",
                explanationType = ExplanationType.PARAGRAPH_TRANSLATION,
                promptVersion = "paragraph-translation-v1",
                preparedMessages = emptyList()
            ),
            "key:$text"
        )

    /** 确定性分句：在 `.` 之后断句，句后空白留在句内。与规划器的纯逻辑测试同一实现。 */
    private fun splitSentences(text: String): List<SentenceRange> {
        val ranges = mutableListOf<SentenceRange>()
        var start = 0
        var index = 0
        var position = 0
        while (position < text.length) {
            if (text[position] == '.') {
                var end = position + 1
                while (end < text.length && text[end].isWhitespace()) end++
                ranges += SentenceRange(index++, text.substring(start, end), start, end)
                start = end
                position = end
            } else {
                position++
            }
        }
        if (start < text.length) {
            ranges += SentenceRange(index, text.substring(start), start, text.length)
        }
        return ranges
    }

    private companion object {
        const val NOW = 1_000_000L
        val CODEC = AppliedTranslationLayoutCodec(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        )
        val POLICY = DefaultTranslationMaterializationPolicy(CODEC)
        val PROFILE = ResolvedAiProfile(
            profileId = "p",
            providerTemplate = AiProviderTemplate.OPENAI_COMPATIBLE,
            baseUrl = "https://example.invalid",
            modelId = "m",
            authStrategy = AiAuthStrategy.API_KEY,
            temperature = 0.7,
            apiKey = "k"
        )
    }
}
