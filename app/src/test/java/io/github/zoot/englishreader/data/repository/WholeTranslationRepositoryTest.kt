package io.github.zoot.englishreader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.WholeTranslationDao
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.model.AppliedTranslationLayoutCodec
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.DefaultTranslationMaterializationPolicy
import io.github.zoot.englishreader.model.TranslationFailureReason
import io.github.zoot.englishreader.model.TranslationMaterializationPolicy
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 * [WholeTranslationRepository] 的协调语义：一次 profile 解析、逐段 checkpoint、失败三分法、
 * 恢复不重跑、observer 脱离不取消付费任务。
 *
 * 用真实 Room（Robolectric）而非 mock DAO：这里要证明的是「重试后 TRANSLATED 段落没有再次
 * 请求」，证据是 executor 的调用次数与库里的 checkpoint 状态一致。mock DAO 会让这条断言
 * 变成对 mock 配置的复述。AI 侧则 mock 到 [AiExecutor]/[AiExplanationRequestResolver]：
 * provider 契约、缓存与错误分类各有自己的测试，这里不重复。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeTranslationRepositoryTest {

    private lateinit var db: EnglishReaderDatabase
    private lateinit var executor: AiExecutor
    private lateinit var resolver: AiExplanationRequestResolver

    /** executor 收到的规范化段落文本，按调用顺序。 */
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
            val text = AiTextNormalizer.normalizeInput(inputSlot.captured.text)
            AiExplanationResolutionResult.Ready(operationFor(text))
        }
        every { resolver.mapProfileFailure(any()) } answers {
            when (firstArg<ProfileResolutionResult>()) {
                is ProfileResolutionResult.Available -> null
                ProfileResolutionResult.NoActiveProfile -> AiError.NoActiveProfile
                ProfileResolutionResult.Missing -> AiError.CredentialMissing
                else -> AiError.Unknown
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 正常路径 ----

    @Test
    fun start_allSegmentsSucceed_materializesArticleTranslation() = runTest {
        val articleId = insertArticle("Alpha.\n\nBeta.\n\nGamma.")
        succeedWith { "译:$it" }
        val repo = repository(this)

        val result = repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        assertTrue(result is WholeTranslationStartResult.Started)
        assertEquals(listOf("Alpha.", "Beta.", "Gamma."), requestedInputs)
        assertEquals("译:Alpha.\n\n译:Beta.\n\n译:Gamma.", db.articleDao().getArticleById(articleId)?.translation)
        assertEquals(WholeTranslationTaskStatus.COMPLETED, taskStatus((result as WholeTranslationStartResult.Started).taskId))
    }

    @Test
    fun start_existingResumableTask_returnsExistingWithoutNewRequests() = runTest {
        val articleId = insertArticle("One.\n\nTwo.")
        // 第一段成功后中止，留下可继续任务
        failThenAbort(succeedFirst = 1)
        val repo = repository(this)
        val first = repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started
        advanceUntilIdle()
        val requestsAfterFirstRun = requestedInputs.size

        val second = repo.start(WholeTranslationScope.CurrentArticle(articleId))

        assertEquals(WholeTranslationStartResult.Existing(first.taskId), second)
        assertEquals("start on existing task must not issue requests", requestsAfterFirstRun, requestedInputs.size)
    }

    @Test
    fun start_articleWithoutParagraphs_reportsNoContent() = runTest {
        val articleId = insertArticle("   \n\n  ")
        val repo = repository(this)

        assertEquals(WholeTranslationStartResult.NoContent, repo.start(WholeTranslationScope.CurrentArticle(articleId)))
        assertTrue(requestedInputs.isEmpty())
    }

    // ---- 恢复与重试：核心不变量 ----

    /** 已成功的段落绝不重新请求——这是整个「可恢复」语义的全部意义。 */
    @Test
    fun retryFailed_translatedSegments_areNeverRequestedAgain() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.")
        // 第一轮：A 成功，B 瞬时失败，C 成功
        var call = 0
        coEvery { executor.execute(any(), any()) } answers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            call++
            if (text == "B." && call <= 3) AiClientResult.Failure(AiError.Offline)
            else AiClientResult.Success("译:$text")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        assertEquals(listOf("A.", "B.", "C."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.PAUSED, taskStatus(taskId))
        assertNull("partial success must not materialize", db.articleDao().getArticleById(articleId)?.translation)

        requestedInputs.clear()
        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertEquals("only the failed segment may be re-requested", listOf("B."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.COMPLETED, taskStatus(taskId))
        assertEquals("译:A.\n\n译:B.\n\n译:C.", db.articleDao().getArticleById(articleId)?.translation)
    }

    /** 可重试失败在一次运行里只尝试一次，不会对同一段无限循环。 */
    @Test
    fun retryFailed_segmentKeepsFailing_isAttemptedOncePerRun() = runTest {
        val articleId = insertArticle("Only.")
        coEvery { executor.execute(any(), any()) } answers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            AiClientResult.Failure(AiError.Offline)
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        assertEquals(1, requestedInputs.size)

        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertEquals("one retry run must issue exactly one more request", 2, requestedInputs.size)
        assertEquals(WholeTranslationTaskStatus.PAUSED, taskStatus(taskId))
    }

    @Test
    fun retryFailed_permanentFailure_isSkippedNotRetried() = runTest {
        val articleId = insertArticle("Huge.\n\nFine.")
        coEvery { executor.execute(any(), any()) } answers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            if (text == "Huge.") AiClientResult.Failure(AiError.PayloadTooLarge()) else AiClientResult.Success("译:$text")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        // PERMANENT 不中止任务：Fine. 仍被处理
        assertEquals(listOf("Huge.", "Fine."), requestedInputs)

        requestedInputs.clear()
        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertTrue("permanent failure must not be re-requested", requestedInputs.isEmpty())
        assertEquals(TranslationFailureReason.PARAGRAPH_TOO_LONG, segmentFailure(taskId, articleId, 0))
    }

    @Test
    fun retryFailed_correctedCredentials_resumesOriginalTaskWithoutRepeatingSuccesses() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.")
        failThenAbort(succeedFirst = 1)
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        assertEquals(listOf("A.", "B."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))

        val corrected = PROFILE.copy(apiKey = "corrected-test-key")
        coEvery { resolver.resolveActiveProfileSnapshot() } returns ProfileResolutionResult.Available(corrected)
        every { resolver.resolveWithProfile(any(), any()) } answers {
            val profile = firstArg<ResolvedAiProfile>()
            val text = AiTextNormalizer.normalizeInput(secondArg<AiExplanationInput>().text)
            AiExplanationResolutionResult.Ready(operationFor(text, profile))
        }
        val profiles = mutableListOf<ResolvedAiProfile>()
        coEvery { executor.execute(any(), any()) } answers {
            val request = firstArg<ResolvedAiExplanationOperation>().request
            requestedInputs += request.normalizedInput
            profiles += request.profile
            AiClientResult.Success("译:${request.normalizedInput}")
        }
        requestedInputs.clear()

        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertEquals(listOf("B.", "C."), requestedInputs)
        assertTrue(profiles.all { it === corrected })
        assertEquals(WholeTranslationTaskStatus.COMPLETED, taskStatus(taskId))
        assertEquals("译\n\n译:B.\n\n译:C.", db.articleDao().getArticleById(articleId)?.translation)
        assertEquals(listOf(1, 2, 1), db.wholeTranslationDao().getSegments(taskId).map { it.attemptCount })
    }

    @Test
    fun retryFailed_configurationStillMissing_preservesFailedSegmentsWithoutRequesting() = runTest {
        val articleId = insertArticle("A.\n\nB.")
        failThenAbort(succeedFirst = 0)
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        val segments = db.wholeTranslationDao().getSegments(taskId)
        requestedInputs.clear()
        coEvery { resolver.resolveActiveProfileSnapshot() } returns ProfileResolutionResult.Missing

        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertTrue(requestedInputs.isEmpty())
        assertEquals(segments, db.wholeTranslationDao().getSegments(taskId))
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))
    }

    @Test
    fun retryFailed_authStillInvalid_attemptsFailedSegmentOnceAndAborts() = runTest {
        val articleId = insertArticle("A.\n\nB.")
        failThenAbort(succeedFirst = 0)
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        requestedInputs.clear()

        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertEquals(listOf("A."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))
        assertEquals(listOf(2, 0), db.wholeTranslationDao().getSegments(taskId).map { it.attemptCount })
    }

    @Test
    fun retryFailed_sourceEdited_cancelsBeforeResettingConfigurationFailure() = runTest {
        val articleId = insertArticle("Original.")
        failThenAbort(succeedFirst = 0)
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        val segments = db.wholeTranslationDao().getSegments(taskId)
        val original = db.articleDao().getArticleById(articleId)!!
        db.articleDao().updateArticle(original.copy(content = "Edited."))
        requestedInputs.clear()

        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertTrue(requestedInputs.isEmpty())
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(taskId))
        assertEquals(segments, db.wholeTranslationDao().getSegments(taskId))
    }

    // ---- 失败三分法 ----

    @Test
    fun run_fatalAuthFailure_abortsTaskWithoutRequestingRemainingSegments() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.")
        coEvery { executor.execute(any(), any()) } answers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            AiClientResult.Failure(AiError.HttpAuth(401))
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        // 401 后剩余段落一个都不该发：每一个都是对着坏凭据的潜在计费请求
        assertEquals(listOf("A."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))
        assertEquals(TranslationFailureReason.CONFIGURATION, taskFailure(taskId))
    }

    @Test
    fun run_noActiveProfile_abortsBeforeAnyRequest() = runTest {
        val articleId = insertArticle("A.")
        coEvery { resolver.resolveActiveProfileSnapshot() } returns ProfileResolutionResult.NoActiveProfile
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        assertTrue(requestedInputs.isEmpty())
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))
        assertEquals(TranslationFailureReason.CONFIGURATION, taskFailure(taskId))
    }

    /** profile 只解析一次；300 段不该读 300 次凭据。 */
    @Test
    fun run_multipleSegments_resolvesProfileExactlyOnce() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.\n\nD.")
        succeedWith { "译:$it" }
        var resolutions = 0
        coEvery { resolver.resolveActiveProfileSnapshot() } answers {
            resolutions++
            ProfileResolutionResult.Available(PROFILE)
        }
        val repo = repository(this)
        repo.start(WholeTranslationScope.CurrentArticle(articleId))
        advanceUntilIdle()

        assertEquals(4, requestedInputs.size)
        assertEquals(1, resolutions)
    }

    @Test
    fun run_truncatedParagraph_preservesOldTranslationUntilExplicitRetryCompletes() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.")
        val article = db.articleDao().getArticleById(articleId)!!
        db.articleDao().updateArticle(article.copy(translation = "旧译文"))
        coEvery { executor.execute(any(), any()) } answers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            if (text == "B.") AiClientResult.Failure(AiError.ResponseTruncated)
            else AiClientResult.Success("译:$text")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        assertEquals(listOf("A.", "B.", "C."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.PAUSED, taskStatus(taskId))
        assertEquals("旧译文", db.articleDao().getArticleById(articleId)?.translation)
        val failed = db.wholeTranslationDao().getSegments(taskId)[1]
        assertEquals("failed", failed.status)
        assertNull(failed.translatedText)
        assertEquals(TranslationFailureReason.PROVIDER_RESPONSE, segmentFailure(taskId, articleId, 1))

        requestedInputs.clear()
        succeedWith { "译:$it" }
        repo.retryFailed(taskId)
        advanceUntilIdle()

        assertEquals(listOf("B."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.COMPLETED, taskStatus(taskId))
        assertEquals("译:A.\n\n译:B.\n\n译:C.", db.articleDao().getArticleById(articleId)?.translation)
    }

    // ---- 源变化 ----

    @Test
    fun run_sourceEditedMidTask_abortsAndPreservesOldTranslation() = runTest {
        val articleId = insertArticle("Old one.\n\nOld two.")
        db.articleDao().updateArticle(db.articleDao().getArticleById(articleId)!!.copy(translation = "旧译文"))
        // 第一段成功后，用户改了正文
        coEvery { executor.execute(any(), any()) } coAnswers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            if (text == "Old one.") {
                val current = db.articleDao().getArticleById(articleId)!!
                db.articleDao().updateArticle(current.copy(content = "Edited one.\n\nEdited two."))
            }
            AiClientResult.Success("译:$text")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        assertEquals("second segment must not be requested against edited source", listOf("Old one."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))
        assertEquals("旧译文", db.articleDao().getArticleById(articleId)?.translation)
    }

    // ---- 观察 ----

    /**
     * 只改任务行、不动任何段落行时，observer 也必须收到新状态。
     *
     * 这是一个曾经存在的缺陷的判据：`observe` 原本只订阅 `translation_segments`，而
     * materialize 只写任务表与 `articles`，于是任务完成时 UI 永远停在「N/N，正在翻译」。
     *
     * 这里直接改任务行而不是跑完整个 worker：worker 完成后的 materialize 写入落在 Room 的
     * query dispatcher 上，不受 `advanceUntilIdle` 的虚拟时间驱动，断言会依赖真实线程时序而
     * 变得不稳定。缺陷本身与 worker 无关——它是「observe 订阅了哪些表」的问题，所以判据
     * 只需要一次任务行写入。
     *
     * collector 必须跑在 [UnconfinedTestDispatcher] 上：Room 的 Flow 在 `queryExecutor` 的
     * dispatcher（这里是 `Runnable::run` 立即执行）上发射，用默认的 StandardTestDispatcher
     * 收集会一条都收不到，失败信息是 `saw []`，看起来像生产代码不发射，实际是测试没接上。
     */
    @Test
    fun observe_taskRowOnlyChange_reachesObserver() = runTest {
        val articleId = insertArticle("A.\n\nB.")
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        repo.pause(taskId)
        advanceUntilIdle()

        val seen = mutableListOf<WholeTranslationTaskStatus>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observe(taskId).collect { view -> view?.let { seen += it.status } }
        }
        advanceUntilIdle()
        val beforeCount = seen.size

        // 只写任务表：段落行一行不变，正是 materialize 的写入形状
        db.wholeTranslationDao().updateTaskStatus(taskId, "completed", null, NOW)
        advanceUntilIdle()
        collector.cancel()

        assertTrue("task-row-only change must re-emit, saw $seen", seen.size > beforeCount)
        assertEquals(WholeTranslationTaskStatus.COMPLETED, seen.last())
    }

    @Test
    fun observe_taskDeleted_emitsNull() = runTest {
        val articleId = insertArticle("A.")
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        val seen = mutableListOf<WholeTranslationTaskView?>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observe(taskId).collect { seen += it }
        }
        advanceUntilIdle()
        db.wholeTranslationDao().deleteTask(taskId)
        advanceUntilIdle()
        collector.cancel()

        assertNull("deleted task must surface as null so the sheet can hide", seen.last())
    }

    // ---- 所有权与取消 ----

    /** 显式取消停止 worker，且不再发请求；已成功的 checkpoint 保留。 */
    @Test
    fun cancel_runningTask_stopsFurtherRequestsAndKeepsCheckpoints() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.")
        val gate = CompletableDeferred<Unit>()
        coEvery { executor.execute(any(), any()) } coAnswers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            if (text == "B.") gate.await()
            AiClientResult.Success("译:$text")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        // 推进到 B. 挂起
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("A.", "B."), requestedInputs)

        repo.cancel(taskId)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("C. must never be requested after cancel", listOf("A.", "B."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(taskId))
        assertEquals("译:A.", db.wholeTranslationDao().getSegments(taskId).first { it.paragraphIndex == 0 }.translatedText)
        assertNull(db.articleDao().getArticleById(articleId)?.translation)
        assertNull("cancelled task must not be resumable", repo.findResumable(WholeTranslationScope.CurrentArticle(articleId)))
    }

    @Test
    fun cancel_pendingStatusWrite_blocksRetryUntilCancellationIsPersisted() = runTest {
        val stored = db.wholeTranslationDao()
        val cancellationEntered = CompletableDeferred<Unit>()
        val cancellationGate = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        val delayed = object : WholeTranslationDao by stored {
            override suspend fun updateTaskStatus(taskId: Long, status: String, failureReason: String?, now: Long): Int {
                if (status == "cancelled") {
                    cancellationEntered.complete(Unit)
                    cancellationGate.await()
                }
                return stored.updateTaskStatus(taskId, status, failureReason, now)
            }
        }
        val articleId = insertArticle("Original.")
        failThenAbort(succeedFirst = 0)
        val repo = WholeTranslationRepository(delayed, db.articleDao(), resolver, executor, this, { NOW }, POLICY)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(taskId))
        requestedInputs.clear()
        coEvery { executor.execute(any(), any()) } coAnswers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            remoteGate.await()
            AiClientResult.Success("译文")
        }

        try {
            val cancellation = launch { repo.cancel(taskId) }
            runCurrent()
            assertTrue(cancellationEntered.isCompleted)
            assertTrue(!cancellation.isCompleted)
            val retry = launch { repo.retryFailed(taskId) }
            runCurrent()

            assertTrue("retry must not send a paid request while cancellation is being persisted", requestedInputs.isEmpty())
            cancellationGate.complete(Unit)
            cancellation.join()
            retry.join()
            advanceUntilIdle()

            assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(taskId))
            assertTrue(requestedInputs.isEmpty())
            assertNull(db.articleDao().getArticleById(articleId)?.translation)
        } finally {
            cancellationGate.complete(Unit)
            remoteGate.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun resume_alreadyRunning_doesNotStartSecondWorker() = runTest {
        val articleId = insertArticle("A.\n\nB.")
        val gate = CompletableDeferred<Unit>()
        coEvery { executor.execute(any(), any()) } coAnswers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            gate.await()
            AiClientResult.Success("译")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        testScheduler.advanceUntilIdle()

        repo.resume(taskId)
        repo.resume(taskId)
        testScheduler.advanceUntilIdle()

        // 若起了第二个 worker，A. 会被领走两次（lease 未过期时 claim 失败，但仍会出现重复尝试）
        assertEquals(listOf("A."), requestedInputs)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun resume_changedSource_cancelsStaleTaskAndNewStartUsesFreshSnapshot() = runTest {
        val articleId = insertArticle("Old one.\n\nOld two.")
        val scope = WholeTranslationScope.CurrentArticle(articleId)
        failThenAbort(succeedFirst = 1)
        val repo = repository(this)
        val oldTask = (repo.start(scope) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        val checkpoint = db.wholeTranslationDao().getSegments(oldTask)
        val article = db.articleDao().getArticleById(articleId)!!
        db.articleDao().updateArticle(article.copy(content = "New one.\n\nNew two."))
        requestedInputs.clear()

        repo.resume(oldTask)
        advanceUntilIdle()
        assertTrue("stale resume must not send a request", requestedInputs.isEmpty())
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(oldTask))
        assertNull(repo.findResumable(scope))
        assertEquals(checkpoint, db.wholeTranslationDao().getSegments(oldTask))

        succeedWith { "译:$it" }
        val fresh = repo.start(scope) as WholeTranslationStartResult.Started
        advanceUntilIdle()
        assertTrue(fresh.taskId != oldTask)
        assertEquals(listOf("New one.", "New two."), requestedInputs)
        assertEquals("译:New one.\n\n译:New two.", db.articleDao().getArticleById(articleId)?.translation)
    }

    @Test
    fun start_sourceChangesBetweenReadAndCreate_rejectsWithoutTaskOrRequest() = runTest {
        val articleId = insertArticle("Original.")
        val reader = mockk<ArticleDao>()
        coEvery { reader.getArticleById(articleId) } coAnswers {
            val opened = db.articleDao().getArticleById(articleId)!!
            db.articleDao().updateArticle(opened.copy(content = "Edited."))
            opened
        }
        val repo = WholeTranslationRepository(
            db.wholeTranslationDao(), reader, resolver, executor, backgroundScope, { NOW }, POLICY
        )

        assertEquals(WholeTranslationStartResult.SourceChanged,
            repo.start(WholeTranslationScope.CurrentArticle(articleId)))
        assertNull(db.wholeTranslationDao().findResumableTask("article:$articleId"))
        assertTrue(requestedInputs.isEmpty())
    }

    @Test
    fun resume_terminalTasks_neverResolveProfileOrIssueNewRequests() = runTest {
        val repo = repository(this)
        succeedWith { "译:$it" }
        val completedArticle = insertArticle("Completed.")
        val completed = (repo.start(WholeTranslationScope.CurrentArticle(completedArticle)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        val cancelledArticle = insertArticle("Cancelled.")
        val cancelled = (repo.start(WholeTranslationScope.CurrentArticle(cancelledArticle)) as WholeTranslationStartResult.Started).taskId
        repo.cancel(cancelled)
        advanceUntilIdle()
        requestedInputs.clear()
        var resolutions = 0
        coEvery { resolver.resolveActiveProfileSnapshot() } answers {
            resolutions++
            ProfileResolutionResult.Available(PROFILE)
        }

        for (taskId in listOf(completed, cancelled)) {
            repo.resume(taskId)
            repo.retryFailed(taskId)
        }
        advanceUntilIdle()

        assertEquals(0, resolutions)
        assertTrue(requestedInputs.isEmpty())
        assertEquals(WholeTranslationTaskStatus.COMPLETED, taskStatus(completed))
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(cancelled))
    }

    @Test
    fun edit_pendingRequestThenOriginalBodyRestored_lateResultCannotReviveOldTask() = runTest {
        val articleId = insertArticle("A.\n\nB.\n\nC.")
        val original = db.articleDao().getArticleById(articleId)!!
        val gate = CompletableDeferred<Unit>()
        coEvery { executor.execute(any(), any()) } coAnswers {
            val text = firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            requestedInputs += text
            if (text == "B.") withContext(NonCancellable) { gate.await() }
            AiClientResult.Success("译:$text")
        }
        val repo = repository(this)
        val taskId = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        assertEquals(listOf("A.", "B."), requestedInputs)
        val editor = ArticleRepository(db.articleDao(), backgroundScope, AppliedTranslationLayoutCodec(Moshi.Builder().build()))
        assertEquals(ArticleEditResult.Saved,
            editor.saveEdit(ArticleEditSnapshot(articleId, original.title, original.content), original.title, "Edited."))
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(taskId))
        assertTrue("the old remote call must still be held", !gate.isCompleted)
        assertEquals(ArticleEditResult.Saved,
            editor.saveEdit(ArticleEditSnapshot(articleId, original.title, "Edited."), original.title, original.content))

        gate.complete(Unit)
        advanceUntilIdle()
        repo.resume(taskId)
        advanceUntilIdle()

        assertEquals(listOf("A.", "B."), requestedInputs)
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(taskId))
        assertEquals(original.content, db.articleDao().getArticleById(articleId)?.content)
        assertNull(db.articleDao().getArticleById(articleId)?.translation)
        val segments = db.wholeTranslationDao().getSegments(taskId)
        assertEquals("译:A.", segments.first().translatedText)
        assertNull(segments[1].translatedText)
        assertEquals("untranslated", segments[2].status)
    }

    @Test
    fun start_concurrentSameScope_createsOneTaskWhileFirstSnapshotIsPending() = runTest {
        val articleId = insertArticle("Original.")
        val scope = WholeTranslationScope.CurrentArticle(articleId)
        val snapshotStarted = CompletableDeferred<Unit>()
        val snapshotGate = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        var snapshotReads = 0
        val reader = mockk<ArticleDao>()
        coEvery { reader.getArticleById(articleId) } coAnswers {
            snapshotReads++
            snapshotStarted.complete(Unit)
            snapshotGate.await()
            db.articleDao().getArticleById(articleId)
        }
        coEvery { executor.execute(any(), any()) } coAnswers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            remoteGate.await()
            AiClientResult.Success("译文")
        }
        val repo = WholeTranslationRepository(db.wholeTranslationDao(), reader, resolver, executor, this, { NOW }, POLICY)
        val first = async { repo.start(scope) }
        advanceUntilIdle()
        assertTrue(snapshotStarted.isCompleted)
        val second = async { repo.start(scope) }
        runCurrent()
        assertEquals(2, snapshotReads)
        assertTrue(!first.isCompleted && !second.isCompleted)

        snapshotGate.complete(Unit)
        val created = first.await() as WholeTranslationStartResult.Started
        assertEquals(WholeTranslationStartResult.Existing(created.taskId), second.await())
        advanceUntilIdle()
        assertEquals(listOf("Original."), requestedInputs)
        remoteGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(WholeTranslationTaskStatus.COMPLETED, taskStatus(created.taskId))
    }

    @Test
    fun findResumable_changedSource_filtersBeforeAnyResume() = runTest {
        val articleId = insertArticle("Old.")
        val scope = WholeTranslationScope.CurrentArticle(articleId)
        failThenAbort(succeedFirst = 0)
        val repo = repository(this)
        val task = (repo.start(scope) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(task))
        val checkpoint = db.wholeTranslationDao().getSegments(task)
        val article = db.articleDao().getArticleById(articleId)!!
        db.articleDao().updateArticle(article.copy(content = "Edited."))
        requestedInputs.clear()

        assertNull(repo.findResumable(scope))
        assertEquals(WholeTranslationTaskStatus.CANCELLED, taskStatus(task))
        assertEquals(checkpoint, db.wholeTranslationDao().getSegments(task))
        assertTrue(requestedInputs.isEmpty())
    }

    @Test
    fun run_beginTaskFailure_isCaughtBeforePaidWork() = runTest {
        val stored = db.wholeTranslationDao()
        val failing = object : WholeTranslationDao by stored {
            override suspend fun beginTask(taskId: Long, now: Long): Boolean = throw IOException()
        }
        val articleId = insertArticle("Original.")
        val repo = WholeTranslationRepository(failing, db.articleDao(), resolver, executor, this, { NOW }, POLICY)
        val task = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(task))
        assertEquals(TranslationFailureReason.UNKNOWN, taskFailure(task))
        assertTrue(requestedInputs.isEmpty())
    }

    @Test
    fun run_observerInitialFailure_isCaughtBeforePaidWork() = runTest {
        val stored = db.wholeTranslationDao()
        val failing = object : WholeTranslationDao by stored {
            override fun observeTask(taskId: Long): Flow<WholeTranslationTaskEntity?> = flow { throw IOException() }
        }
        val articleId = insertArticle("Original.")
        val repo = WholeTranslationRepository(failing, db.articleDao(), resolver, executor, this, { NOW }, POLICY)
        val task = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()

        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(task))
        assertEquals(TranslationFailureReason.UNKNOWN, taskFailure(task))
        assertTrue(requestedInputs.isEmpty())
    }

    @Test
    fun run_observerFailsDuringRequest_discardsLateResultAndFailsSafely() = runTest {
        val stored = db.wholeTranslationDao()
        val failObserver = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        val failing = object : WholeTranslationDao by stored {
            override fun observeTask(taskId: Long): Flow<WholeTranslationTaskEntity?> = flow {
                emit(stored.getTask(taskId))
                failObserver.await()
                throw IOException()
            }
        }
        coEvery { executor.execute(any(), any()) } coAnswers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            withContext(NonCancellable) { remoteGate.await() }
            AiClientResult.Success("迟到译文")
        }
        val articleId = insertArticle("First.\n\nSecond.")
        val repo = WholeTranslationRepository(failing, db.articleDao(), resolver, executor, this, { NOW }, POLICY)
        val task = (repo.start(WholeTranslationScope.CurrentArticle(articleId)) as WholeTranslationStartResult.Started).taskId
        advanceUntilIdle()
        assertEquals(listOf("First."), requestedInputs)
        failObserver.complete(Unit)
        runCurrent()
        assertTrue(!remoteGate.isCompleted)
        remoteGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(WholeTranslationTaskStatus.FAILED, taskStatus(task))
        assertEquals(TranslationFailureReason.UNKNOWN, taskFailure(task))
        assertEquals(listOf("First."), requestedInputs)
        assertTrue(stored.getSegments(task).all { it.translatedText == null })
        assertNull(db.articleDao().getArticleById(articleId)?.translation)
    }

    // ---- helpers ----

    private fun repository(scope: CoroutineScope) = WholeTranslationRepository(
        dao = db.wholeTranslationDao(),
        articleDao = db.articleDao(),
        requestResolver = resolver,
        executor = executor,
        applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher((scope as kotlinx.coroutines.test.TestScope).testScheduler)),
        clock = { NOW },
        materializationPolicy = POLICY
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

    private fun failThenAbort(succeedFirst: Int) {
        var n = 0
        coEvery { executor.execute(any(), any()) } answers {
            requestedInputs += firstArg<ResolvedAiExplanationOperation>().request.normalizedInput
            if (n++ < succeedFirst) AiClientResult.Success("译") else AiClientResult.Failure(AiError.HttpAuth(401))
        }
    }

    private suspend fun taskStatus(taskId: Long) =
        WholeTranslationTaskStatus.fromStableToken(db.wholeTranslationDao().getTask(taskId)?.status)

    private suspend fun taskFailure(taskId: Long) =
        TranslationFailureReason.fromStableToken(db.wholeTranslationDao().getTask(taskId)?.failureReason)

    private suspend fun segmentFailure(taskId: Long, articleId: Long, index: Int) =
        TranslationFailureReason.fromStableToken(
            db.wholeTranslationDao().getSegments(taskId)
                .first { it.articleId == articleId && it.paragraphIndex == index }.failureReason
        )

    private fun operationFor(text: String, profile: ResolvedAiProfile = PROFILE): ResolvedAiExplanationOperation {
        val request = ResolvedAiExplanationRequest(
            profile = profile,
            normalizedModelId = "m",
            normalizedInput = text,
            outputLanguageTag = "zh-CN",
            explanationType = ExplanationType.PARAGRAPH_TRANSLATION,
            promptVersion = "paragraph-translation-v1",
            preparedMessages = emptyList()
        )
        return ResolvedAiExplanationOperation(request, "key:$text")
    }

    private companion object {
        const val NOW = 1_000_000L

        /**
         * 与生产同一套纯计算，只是自带 Moshi。
         *
         * 刻意不在这里替换成假实现：materialization 的正确性大半落在这些计算上（按目标版本分派、
         * 坐标完整性、聚合与坐标同源），换成假的等于把要测的东西测掉了。
         */
        val POLICY: TranslationMaterializationPolicy = DefaultTranslationMaterializationPolicy(
            AppliedTranslationLayoutCodec(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())
        )
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
