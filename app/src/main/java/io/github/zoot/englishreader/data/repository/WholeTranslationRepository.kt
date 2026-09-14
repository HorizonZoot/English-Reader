package io.github.zoot.englishreader.data.repository

import android.util.Log
import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.ai.AiExplanationResolutionResult
import io.github.zoot.englishreader.data.ai.AiOperationCompletionGate
import io.github.zoot.englishreader.data.ai.categoryName
import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.MaterializationResult
import io.github.zoot.englishreader.data.dao.WholeTranslationDao
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import io.github.zoot.englishreader.model.TranslationFailureReason
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.model.TranslationOutputAssembler
import io.github.zoot.englishreader.model.TranslationProcessingMode
import io.github.zoot.englishreader.model.TranslationSegment
import io.github.zoot.englishreader.model.TranslationSegmentStatus
import io.github.zoot.englishreader.model.WholeTranslationProgress
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.model.toParagraphSnapshot
import io.github.zoot.englishreader.util.ParagraphAligner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 面向 UI 的安全任务视图；不含正文、译文、profile 或凭据。 */
data class WholeTranslationTaskView(
    val taskId: Long,
    val scopeKey: String,
    val status: WholeTranslationTaskStatus,
    val failureReason: TranslationFailureReason?,
    val progress: WholeTranslationProgress
)

sealed interface WholeTranslationStartResult {
    data class Started(val taskId: Long) : WholeTranslationStartResult

    /** 同源任务已存在且可继续；调用方应展示进度并提供「继续」。 */
    data class Existing(val taskId: Long) : WholeTranslationStartResult

    /** 范围内没有可翻译的段落。 */
    data object NoContent : WholeTranslationStartResult

    data class Rejected(val error: AiError) : WholeTranslationStartResult {
        override fun toString(): String = "Rejected(error=${error.categoryName})"
    }
}

/**
 * 全文翻译任务的执行与恢复协调器。
 *
 * 职责边界：
 * - 创建范围快照（委托 DAO 原子写入）；
 * - 以 application scope 拥有处理协程，UI 观察者脱离不取消它；
 * - 每个任务解析**一次** profile，随后每段复用同一快照；
 * - 顺序处理段落，逐段 checkpoint，按失败三分法决定继续/跳过/中止；
 * - 全部成功后调用事务性 materialization。
 *
 * 刻意**不**经过 [AiExplanationOperationRegistry]：那是「同一语义键的在途操作只跑一份」的
 * UI 级 single-flight，服务于用户反复点同一句。全文翻译的段落各不相同，key 几乎不会碰撞，
 * 而它自己的并发控制已由 DAO 的条件写 claim 承担。多套一层 registry 只会让取消语义变得
 * 有两个入口。缓存复用仍然生效——[AiExecutor] 就是 `CachedAiExecutor`。
 */
@Singleton
class WholeTranslationRepository internal constructor(
    private val dao: WholeTranslationDao,
    private val articleDao: ArticleDao,
    private val requestResolver: AiExplanationRequestResolver,
    private val executor: AiExecutor,
    private val applicationScope: CoroutineScope,
    private val clock: () -> Long
) {

    @Inject
    internal constructor(
        dao: WholeTranslationDao,
        articleDao: ArticleDao,
        requestResolver: AiExplanationRequestResolver,
        executor: AiExecutor,
        @ApplicationCoroutineScope applicationScope: CoroutineScope
    ) : this(dao, articleDao, requestResolver, executor, applicationScope, System::currentTimeMillis)


    private val mutex = Mutex()

    /** taskId → 处理协程。同一任务同一时刻最多一个 worker。 */
    private val workers = mutableMapOf<Long, Job>()

    /**
     * 同时观察任务行与段落行。
     *
     * 只订阅段落表会漏掉 COMPLETED：materialize 不改任何段落行。只订阅任务表会漏掉逐段进度。
     * `combine` 让任一表变化都重新发射一份完整视图。
     */
    fun observe(taskId: Long): Flow<WholeTranslationTaskView?> =
        combine(dao.observeTask(taskId), dao.observeSegments(taskId)) { task, rows ->
            task?.toView(rows.map { it.toDomain() })
        }

    suspend fun findResumable(scope: WholeTranslationScope): WholeTranslationTaskView? {
        val task = dao.findResumableTask(scope.scopeKey) ?: return null
        return task.toView(dao.getSegments(task.taskId).map { it.toDomain() })
    }

    /**
     * 开始或接续一个任务。
     *
     * 同源可继续任务存在时返回 [WholeTranslationStartResult.Existing] 而非新建：新建会让
     * 已成功的段落全部重新请求——正是本功能要避免的重复付费。
     */
    suspend fun start(scope: WholeTranslationScope): WholeTranslationStartResult {
        dao.findResumableTask(scope.scopeKey)?.let {
            return WholeTranslationStartResult.Existing(it.taskId)
        }

        val contents = scope.articleIds.mapNotNull { id ->
            articleDao.getArticleById(id)?.let { id to it.content }
        }
        val paragraphs = scope.toParagraphSnapshot(contents.toMap())
        if (paragraphs.isEmpty()) return WholeTranslationStartResult.NoContent

        val now = clock()
        val taskId = dao.createTask(
            task = WholeTranslationTaskEntity(
                scopeKey = scope.scopeKey,
                bookId = (scope as? WholeTranslationScope.Chapter)?.bookId,
                status = WholeTranslationTaskStatus.PAUSED.toStableToken(),
                createdAt = now,
                updatedAt = now
            ),
            articles = contents.map { (id, content) -> id to TranslationFingerprint.forArticle(content) },
            segments = paragraphs.map {
                TranslationSegmentEntity(
                    taskId = 0,
                    articleId = it.articleId,
                    paragraphIndex = it.paragraphIndex,
                    sourceFingerprint = it.sourceFingerprint,
                    status = TranslationSegmentStatus.UNTRANSLATED.toStableToken(),
                    updatedAt = now
                )
            },
            now = now
        )
        launchWorker(taskId, TranslationProcessingMode.RESUME)
        return WholeTranslationStartResult.Started(taskId)
    }

    /** 继续未完成项。已在运行时为 no-op。 */
    suspend fun resume(taskId: Long) = launchWorker(taskId, TranslationProcessingMode.RESUME)

    /** 只重试可重试的失败项，已成功项不再请求。 */
    suspend fun retryFailed(taskId: Long) = launchWorker(taskId, TranslationProcessingMode.RETRY_FAILED)

    /**
     * 显式取消：停止 worker 并把任务标为终态。
     *
     * 不删除段落 checkpoint——用户可能只是想暂停以省流量，删了就等于让已花的钱作废。
     * 取消后的任务不再被 [findResumable] 返回；要重来需新建任务。
     */
    suspend fun cancel(taskId: Long) {
        val job = mutex.withLock { workers.remove(taskId) }
        job?.cancel()
        dao.updateTaskStatus(taskId, WholeTranslationTaskStatus.CANCELLED.toStableToken(), null, clock())
    }

    /** 暂停：停止 worker 但保持可继续。 */
    suspend fun pause(taskId: Long) {
        val job = mutex.withLock { workers.remove(taskId) }
        job?.cancel()
        dao.updateTaskStatus(taskId, WholeTranslationTaskStatus.PAUSED.toStableToken(), null, clock())
    }

    private suspend fun launchWorker(taskId: Long, mode: TranslationProcessingMode) {
        mutex.withLock {
            if (workers[taskId]?.isActive == true) return
            val job = applicationScope.launch { runTask(taskId, mode) }
            workers[taskId] = job
            job.invokeOnCompletion {
                applicationScope.launch { mutex.withLock { if (workers[taskId] === job) workers.remove(taskId) } }
            }
        }
    }

    private suspend fun runTask(taskId: Long, mode: TranslationProcessingMode) {
        dao.updateTaskStatus(taskId, WholeTranslationTaskStatus.RUNNING.toStableToken(), null, clock())

        // 每任务解析一次 profile。这里失败是 FATAL：没有凭据什么都发不出去。
        val profile = when (val resolution = requestResolver.resolveActiveProfileSnapshot()) {
            is ProfileResolutionResult.Available -> resolution.profile
            else -> {
                val error = requestResolver.mapProfileFailure(resolution) ?: AiError.Unknown
                abort(taskId, TranslationFailureReason.from(error))
                return
            }
        }

        try {
            // 每个段落在一次 worker 运行里最多处理一次。没有这条，RETRY_FAILED 模式下一个
            // 以可重试原因失败的段落会被下一轮 claim 立即再领走——它刚被写成 failed 且仍符合
            // 资格——于是 worker 对着同一段无限循环，每一圈都是一个潜在计费请求。
            val attempted = HashSet<Pair<Long, Int>>()
            while (true) {
                val now = clock()
                val segment = dao.claimSegment(
                    taskId = taskId,
                    includeFailed = mode == TranslationProcessingMode.RETRY_FAILED,
                    leaseDurationMs = LEASE_DURATION_MS,
                    now = now,
                    exclude = attempted
                ) ?: break
                attempted += segment.articleId to segment.paragraphIndex

                val text = dao.getArticleContent(segment.articleId)
                    ?.let { ParagraphAligner.splitParagraphs(it).getOrNull(segment.paragraphIndex) }
                if (text == null || TranslationFingerprint.forParagraph(text) != segment.sourceFingerprint) {
                    // 源已变或消失：这一段永远无法按原快照完成，任务也不可能 materialize。
                    dao.checkpointFailure(taskId, segment.articleId, segment.paragraphIndex,
                        TranslationFailureReason.CONFIGURATION.toStableToken(), clock())
                    abort(taskId, TranslationFailureReason.CONFIGURATION)
                    return
                }

                val outcome = translate(profile, text)
                when (outcome) {
                    is AiClientResult.Success -> dao.checkpointSuccess(
                        taskId, segment.articleId, segment.paragraphIndex,
                        segment.sourceFingerprint, outcome.text, clock()
                    )
                    is AiClientResult.Failure -> {
                        val reason = TranslationFailureReason.from(outcome.error)
                        dao.checkpointFailure(taskId, segment.articleId, segment.paragraphIndex,
                            reason.toStableToken(), clock())
                        if (reason.category.abortsTask) {
                            abort(taskId, reason)
                            return
                        }
                    }
                }
            }
            finish(taskId)
        } catch (cancellation: CancellationException) {
            // 显式取消/暂停已由调用方更新任务状态；进程退出时状态留在 running，
            // 由下次 claimSegment 的 lease 回收接管。不在此写库：取消时 Room 可能已不可用。
            throw cancellation
        } catch (_: Exception) {
            Log.e(TAG, "stage=task_run category=${AiError.Unknown.categoryName}")
            abort(taskId, TranslationFailureReason.UNKNOWN)
        }
    }

    private suspend fun translate(
        profile: ResolvedAiProfile,
        text: String
    ): AiClientResult = try {
        when (val resolution = requestResolver.resolveWithProfile(
            profile, AiExplanationInput.ParagraphTranslation(text)
        )) {
            is AiExplanationResolutionResult.Ready ->
                executor.execute(resolution.operation, AiOperationCompletionGate())
            is AiExplanationResolutionResult.Rejected -> AiClientResult.Failure(resolution.error)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        AiClientResult.Failure(AiError.Unknown)
    }

    private suspend fun finish(taskId: Long) {
        val segments = dao.getSegments(taskId).map { it.toDomain() }
        val progress = WholeTranslationProgress.from(segments)
        if (!progress.isFullyTranslated) {
            // 有失败项：停在 PAUSED，UI 据 progress.failed 显示「重试失败项」。
            dao.updateTaskStatus(taskId, WholeTranslationTaskStatus.PAUSED.toStableToken(), null, clock())
            return
        }
        val result = dao.materialize(
            taskId = taskId,
            paragraphSplitter = ParagraphAligner::splitParagraphs,
            joinParagraphs = TranslationOutputAssembler::join,
            articleFingerprint = TranslationFingerprint::forArticle,
            now = clock()
        )
        if (result !is MaterializationResult.Applied) {
            Log.w(TAG, "stage=materialize result=${result::class.simpleName}")
            abort(taskId, TranslationFailureReason.CONFIGURATION)
        }
    }

    private suspend fun abort(taskId: Long, reason: TranslationFailureReason) {
        dao.updateTaskStatus(
            taskId, WholeTranslationTaskStatus.FAILED.toStableToken(), reason.toStableToken(), clock()
        )
    }

    private fun WholeTranslationTaskEntity.toView(segments: List<TranslationSegment>) =
        WholeTranslationTaskView(
            taskId = taskId,
            scopeKey = scopeKey,
            status = WholeTranslationTaskStatus.fromStableToken(status),
            failureReason = TranslationFailureReason.fromStableToken(failureReason),
            progress = WholeTranslationProgress.from(segments)
        )

    private fun TranslationSegmentEntity.toDomain() = TranslationSegment(
        articleId = articleId,
        paragraphIndex = paragraphIndex,
        sourceFingerprint = sourceFingerprint,
        status = TranslationSegmentStatus.fromStableToken(status),
        translatedText = translatedText,
        failureReason = TranslationFailureReason.fromStableToken(failureReason),
        attemptCount = attemptCount,
        leaseExpiresAt = leaseExpiresAt
    )

    companion object {
        private const val TAG = "WholeTranslationRepo"

        /** 单段请求的 lease。远端超时上限之上留余量，避免正常慢请求被当成僵尸回收。 */
        const val LEASE_DURATION_MS: Long = 5 * 60 * 1000
    }
}
