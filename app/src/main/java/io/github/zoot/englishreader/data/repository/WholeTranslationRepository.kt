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
import io.github.zoot.englishreader.data.dao.TranslationSourceChangedException
import io.github.zoot.englishreader.data.dao.TranslationTaskConflictException
import io.github.zoot.englishreader.data.dao.WholeTranslationDao
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.dao.TranslationTaskTarget
import io.github.zoot.englishreader.model.TranslationFailureReason
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.model.TranslationMaterializationPolicy
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import io.github.zoot.englishreader.util.SentenceSplitter
import io.github.zoot.englishreader.util.TranslationBlockPlanner
import io.github.zoot.englishreader.model.TranslationPlannerVersion
import io.github.zoot.englishreader.model.TranslationProcessingMode
import io.github.zoot.englishreader.model.TranslationSegment
import io.github.zoot.englishreader.model.TranslationSegmentStatus
import io.github.zoot.englishreader.model.WholeTranslationProgress
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.model.WholeTranslationPreview
import io.github.zoot.englishreader.model.WholeTranslationPreviewResult
import io.github.zoot.englishreader.data.ai.AiPromptPolicy
import io.github.zoot.englishreader.data.ai.AiPromptPreparationResult
import kotlinx.coroutines.yield
import io.github.zoot.englishreader.model.toParagraphSnapshot
import io.github.zoot.englishreader.util.ParagraphAligner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
    val progress: WholeTranslationProgress,
    val segmentationModes: Set<TranslationSegmentationMode> = emptySet()
)

sealed interface WholeTranslationStartResult {
    data class Started(val taskId: Long) : WholeTranslationStartResult

    /** 同源任务已存在且可继续；调用方应展示进度并提供「继续」。 */
    data class Existing(val taskId: Long) : WholeTranslationStartResult

    /** 范围内没有可翻译的段落。 */
    data object NoContent : WholeTranslationStartResult

    /** 预览后正文、范围或偏好变化，需要重新确认，不启动付费请求。 */
    data object SourceChanged : WholeTranslationStartResult

    /**
     * 某篇文章按当前分块方式会切出过多块。
     *
     * 单独成类而不是复用 [Rejected]：这是**本地**判定，发生在 profile、凭据与网络之前，而且用户
     * 有一个明确的出路——改用「保留原段落」。混进通用拒绝里只会显示成一句无从下手的错误。
     */
    data class TooManyBlocks(
        val articleId: Long,
        val actualBlocks: Int,
        val maxBlocks: Int
    ) : WholeTranslationStartResult

    /**
     * 另一个未完成任务已覆盖同一篇文章。
     *
     * 不静默取消那个任务，也不静默接续它：两者的分块方式可能不同，取消会让已付费的段落作废，
     * 而直接接续会让用户以为自己选的范围生效了。调用方应当先让用户决定继续或显式取消既有任务。
     */
    data class Conflict(val taskId: Long, val scopeKey: String) : WholeTranslationStartResult

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
    private val clock: () -> Long,
    private val materializationPolicy: TranslationMaterializationPolicy,
    /**
     * 段落局部分句，供分块规划使用。
     *
     * 做成可覆盖的参数而不是写死 `SentenceSplitter::split`：后者依赖 Android ICU，写死会让分块
     * 路径只能在设备或 Robolectric 下测，也让测试无法用确定性边界断言块长。默认值仍是生产实现，
     * 因此调用方不传就得到真实行为；真实 ICU 的接线由规划器自己的 Robolectric 用例覆盖。
     */
    private val sentenceSplitter: (String) -> List<SentenceRange> = SentenceSplitter::split
) {

    @Inject
    internal constructor(
        dao: WholeTranslationDao,
        articleDao: ArticleDao,
        requestResolver: AiExplanationRequestResolver,
        executor: AiExecutor,
        @ApplicationCoroutineScope applicationScope: CoroutineScope,
        materializationPolicy: TranslationMaterializationPolicy
    ) : this(
        dao,
        articleDao,
        requestResolver,
        executor,
        applicationScope,
        System::currentTimeMillis,
        materializationPolicy
    )


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
        val task = dao.findResumableTaskForSources(scope.scopeKey, scope.articleIds, clock()) ?: return null
        return task.toView(dao.getSegments(task.taskId).map { it.toDomain() })
    }

    /**
     * 开始或接续一个任务。
     *
     * 同源可继续任务存在时返回 [WholeTranslationStartResult.Existing] 而非新建：新建会让
     * 已成功的段落全部重新请求——正是本功能要避免的重复付费。
     */
    suspend fun start(scope: WholeTranslationScope): WholeTranslationStartResult {
        findResumable(scope)?.let { return WholeTranslationStartResult.Existing(it.taskId) }
        return when (val result = preview(scope)) {
            is WholeTranslationPreviewResult.Ready -> start(result.preview)
            is WholeTranslationPreviewResult.TooManyBlocks -> WholeTranslationStartResult.TooManyBlocks(
                result.articleId, result.actualBlocks, result.maxBlocks
            )
            is WholeTranslationPreviewResult.Rejected -> if (result.error == AiError.NoContent) {
                WholeTranslationStartResult.NoContent
            } else WholeTranslationStartResult.Rejected(result.error)
        }
    }

    suspend fun start(preview: WholeTranslationPreview): WholeTranslationStartResult {
        val result = try {
            mutex.withLock { createOrFindTask(preview) }
        } catch (conflict: TranslationTaskConflictException) {
            return WholeTranslationStartResult.Conflict(conflict.taskId, conflict.scopeKey)
        } catch (_: TranslationSourceChangedException) {
            return WholeTranslationStartResult.SourceChanged
        }
        if (result is WholeTranslationStartResult.Started) launchWorker(result.taskId, TranslationProcessingMode.RESUME)
        return result
    }

    /** 本地预览不解析 profile，不读凭据，不访问缓存或网络。 */
    suspend fun preview(scope: WholeTranslationScope): WholeTranslationPreviewResult {
        val plans = mutableListOf<io.github.zoot.englishreader.model.TranslationBlockPlan>()
        var hasTranslation = false
        for (articleId in scope.articleIds) {
            yield()
            val article = articleDao.getArticleById(articleId)
                ?: return WholeTranslationPreviewResult.Rejected(AiError.NoContent)
            hasTranslation = hasTranslation || !article.translation.isNullOrBlank()
            when (val result = planBlocks(articleId, article.content, preferredMode(articleId))) {
                TranslationBlockPlanner.Result.NoContent -> return WholeTranslationPreviewResult.Rejected(AiError.NoContent)
                is TranslationBlockPlanner.Result.TooManyBlocks -> return WholeTranslationPreviewResult.TooManyBlocks(
                    articleId, result.actualBlocks, result.maxBlocks
                )
                is TranslationBlockPlanner.Result.Planned -> {
                    for (block in result.plan.blocks) {
                        val prepared = AiPromptPolicy.prepare(AiExplanationInput.ParagraphTranslation(block.text))
                        if (prepared is AiPromptPreparationResult.Rejected) return WholeTranslationPreviewResult.Rejected(prepared.error)
                    }
                    plans += result.plan
                }
            }
        }
        return WholeTranslationPreviewResult.Ready(WholeTranslationPreview(scope, plans.toList(), hasTranslation))
    }

    /**
     * 建任务，每篇目标按它自己保存的分块方式规划。
     *
     * 逐篇读偏好而不是整个任务取一个：整书翻译是一个任务，而分块方式是文章级设置，用户完全可能
     * 只对某一章改过。取一个值会让其余章节按别人的方式切块。
     *
     * 规划结果**立刻固定**进 checkpoint。分块依据里有 ICU 分句，而 ICU 的边界随系统版本变化，
     * 恢复时重算可能得到不同的块，已付费成功的译文就对不上了。
     */
    private suspend fun createOrFindTask(preview: WholeTranslationPreview): WholeTranslationStartResult {
        val scope = preview.scope
        dao.findResumableTaskForSources(scope.scopeKey, scope.articleIds, clock())?.let {
            return WholeTranslationStartResult.Existing(it.taskId)
        }
        if (preview.plans.map { it.articleId } != scope.articleIds) return WholeTranslationStartResult.SourceChanged
        val targets = mutableListOf<TranslationTaskTarget>()
        val segments = mutableListOf<TranslationSegmentEntity>()
        val now = clock()
        for (plan in preview.plans) {
            if (preferredMode(plan.articleId) != plan.mode) return WholeTranslationStartResult.SourceChanged
            targets += TranslationTaskTarget(plan.articleId, plan.articleFingerprint, plan.mode.toStableToken(), plan.plannerVersion)
            segments += plan.blocks.map { block ->
                TranslationSegmentEntity(
                    taskId = 0,
                    articleId = plan.articleId,
                    paragraphIndex = block.blockIndex,
                    sourceFingerprint = block.sourceFingerprint,
                    status = TranslationSegmentStatus.UNTRANSLATED.toStableToken(),
                    updatedAt = now,
                    sourceParagraphIndex = block.sourceParagraphIndex,
                    sourceStartOffset = block.startOffset,
                    sourceEndOffset = block.endOffset
                )
            }
        }
        if (segments.isEmpty()) return WholeTranslationStartResult.NoContent

        val taskId = dao.createTask(
            task = WholeTranslationTaskEntity(
                scopeKey = scope.scopeKey,
                bookId = (scope as? WholeTranslationScope.Chapter)?.bookId,
                status = WholeTranslationTaskStatus.PAUSED.toStableToken(),
                createdAt = now,
                updatedAt = now
            ),
            articles = targets,
            segments = segments,
            now = now
        )
        return WholeTranslationStartResult.Started(taskId)
    }

    /** 没有保存过偏好、或存的是本版本不认识的 token 时用自动：它是默认体验，也不需要用户先做设置。 */
    private suspend fun preferredMode(articleId: Long): TranslationSegmentationMode =
        dao.getArticleTranslationState(articleId)
            ?.let { TranslationSegmentationMode.fromStableToken(it.preferredMode) }
            ?: TranslationSegmentationMode.AUTO

    private fun planBlocks(
        articleId: Long,
        content: String,
        mode: TranslationSegmentationMode
    ): TranslationBlockPlanner.Result =
        TranslationBlockPlanner.plan(articleId, content, mode, sentenceSplitter)

    /**
     * 保存文章的分块偏好。
     *
     * 只影响**下一次显式启动**：不重排已发布的对照，也不碰正在跑的任务。改个设置就重新翻一遍等于
     * 让用户为一次点击付费。
     */
    suspend fun savePreferredMode(articleId: Long, mode: TranslationSegmentationMode) {
        dao.savePreferredMode(articleId, mode.toStableToken(), clock())
    }

    suspend fun conflictFor(taskId: Long): WholeTranslationStartResult.Conflict? {
        val articleIds = dao.getTaskArticles(taskId).map { it.articleId }
        return dao.findOverlappingTasks(articleIds, taskId).firstOrNull()?.let {
            WholeTranslationStartResult.Conflict(it.taskId, it.scopeKey)
        }
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
     *
     * 终态写入与 worker 移除必须在同一把锁内。分成两步（先移除、再从锁外写状态）会留下一个
     * 窗口：此刻 `workers` 里已没有活跃任务，而数据库仍写着 `failed`/`paused`，于是这个窗口内
     * 到达的 [retryFailed] 或 [resume] 会通过 [launchWorker] 的 `isActive` 检查新建 worker，
     * 对一个用户刚取消的任务发出付费请求。该任务已是终态，[WholeTranslationDao.materialize]
     * 永远不会写入译文，所以这笔钱是纯浪费。
     *
     * 纳入锁内后不再存在「无活跃 worker 且状态未落终态」的时刻：先取锁的 [launchWorker] 要么
     * 看到活跃任务而返回，要么（worker 已完成时）新建的 worker 会在
     * [WholeTranslationDao.beginTask] 读到 `cancelled` 并直接退出；后取锁的则一定读到终态。
     * 若取消与重试本就按「先重试、后取消」到达，取消仍会停掉那个已在运行的 worker——与
     * 「取消正在运行的任务会停止后续请求」的既有语义一致。
     */
    suspend fun cancel(taskId: Long) {
        mutex.withLock {
            // 写入失败也必须停掉 worker：否则用户已经取消，付费请求却继续跑。
            try {
                dao.updateTaskStatus(taskId, WholeTranslationTaskStatus.CANCELLED.toStableToken(), null, clock())
            } finally {
                workers.remove(taskId)?.cancel()
            }
        }
    }

    /** 暂停：停止 worker 但保持可继续。 */
    suspend fun pause(taskId: Long) {
        mutex.withLock {
            try {
                dao.updateTaskStatus(taskId, WholeTranslationTaskStatus.PAUSED.toStableToken(), null, clock())
            } finally {
                workers.remove(taskId)?.cancel()
            }
        }
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
        try {
            coroutineScope {
                if (!dao.beginTask(taskId, clock())) return@coroutineScope
                val worker = currentCoroutineContext().job
                // 编辑会在文章事务内取消任务；观察持久终态，让在途请求也收到取消，而不是只挡最终写回。
                val cancellationObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                    dao.observeTask(taskId).first { it == null || it.status == "cancelled" }
                    worker.cancel()
                }
                try {
                    currentCoroutineContext().ensureActive()

                    // 版本检查排在 profile 解析**之前**。目标的版本决定每一行的 offset 该怎么解释；
                    // 本版本不认识的语义无论如何都不能继续，所以在触及凭据与网络之前就该停下。放到
                    // 后面只会让一个注定要中止的任务先去读一次加密凭据。
                    val targets = dao.getTaskArticles(taskId).associateBy { it.articleId }
                    if (targets.isEmpty() ||
                        targets.values.any { !TranslationPlannerVersion.isSupported(it.plannerVersion) }
                    ) {
                        abort(taskId, TranslationFailureReason.CONFIGURATION)
                        return@coroutineScope
                    }

                    // 每任务解析一次 profile。这里失败是 FATAL：没有凭据什么都发不出去。
                    val profile = when (val resolution = requestResolver.resolveActiveProfileSnapshot()) {
                        is ProfileResolutionResult.Available -> resolution.profile
                        else -> {
                            val error = requestResolver.mapProfileFailure(resolution) ?: AiError.Unknown
                            abort(taskId, TranslationFailureReason.from(error))
                            return@coroutineScope
                        }
                    }
                    if (mode == TranslationProcessingMode.RETRY_FAILED) {
                        dao.resetConfigurationFailures(taskId, clock())
                    }

                    // 每个段落在一次 worker 运行里最多处理一次，失败项不能在同轮无限重领、重复计费。
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

                        val text = targets[segment.articleId]?.let { target ->
                            resolveSegmentText(segment, target.plannerVersion)
                        }
                        if (text == null || text.fingerprint != segment.sourceFingerprint) {
                            dao.checkpointFailure(taskId, segment.articleId, segment.paragraphIndex,
                                TranslationFailureReason.CONFIGURATION.toStableToken(), clock())
                            abort(taskId, TranslationFailureReason.CONFIGURATION)
                            return@coroutineScope
                        }
                        currentCoroutineContext().ensureActive()
                        if (dao.getTask(taskId)?.status != "running") return@coroutineScope

                        val outcome = translate(profile, text.text)
                        currentCoroutineContext().ensureActive()
                        when (outcome) {
                            is AiClientResult.Success -> {
                                if (dao.checkpointSuccess(
                                    taskId, segment.articleId, segment.paragraphIndex,
                                    segment.sourceFingerprint, outcome.text, clock()
                                ) == 0) return@coroutineScope
                            }
                            is AiClientResult.Failure -> {
                                val reason = TranslationFailureReason.from(outcome.error)
                                if (dao.checkpointFailure(taskId, segment.articleId, segment.paragraphIndex,
                                    reason.toStableToken(), clock()) == 0) return@coroutineScope
                                if (reason.category.abortsTask) {
                                    abort(taskId, reason)
                                    return@coroutineScope
                                }
                            }
                        }
                    }
                    finish(taskId)
                } finally {
                    cancellationObserver.cancel()
                }
            }
        } catch (cancellation: CancellationException) {
            // 显式取消/暂停或编辑已更新任务状态；进程退出由下次 claim 的 lease 回收接管。
            throw cancellation
        } catch (_: Exception) {
            Log.e(TAG, "stage=task_run category=${AiError.Unknown.categoryName}")
            try {
                abort(taskId, TranslationFailureReason.UNKNOWN)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Log.e(TAG, "stage=task_abort category=${AiError.Unknown.categoryName}")
            }
        }
    }

    /**
     * 按目标的版本取出这一行要翻译的文本及其指纹。
     *
     * 指纹与文本一起返回，而不是让调用方自己算：两种版本用的是**不同前缀**的指纹
     * (`forParagraph` / `forBlock`)，在调用处选前缀等于把版本分派复制一份，而复制出来的那份
     * 迟早会与这里分叉。分叉的后果是校验恒不通过（任务卡住）或恒通过（把错误文本送去翻译）。
     *
     * `block-v1` 的行必须带齐三个坐标。缺任何一个、或区间已越出当前段落，都返回 null 让调用方按
     * 「源已变」中止——此时任何补全都是编造坐标。
     */
    private suspend fun resolveSegmentText(
        segment: TranslationSegmentEntity,
        plannerVersion: String
    ): SegmentText? {
        val paragraphs = dao.getArticleContent(segment.articleId)
            ?.let { ParagraphAligner.splitParagraphs(it) }
            ?: return null

        if (!TranslationPlannerVersion.isBlock(plannerVersion)) {
            val paragraph = paragraphs.getOrNull(segment.paragraphIndex) ?: return null
            return SegmentText(paragraph, TranslationFingerprint.forParagraph(paragraph))
        }

        val paragraphIndex = segment.sourceParagraphIndex ?: return null
        val start = segment.sourceStartOffset ?: return null
        val end = segment.sourceEndOffset ?: return null
        val paragraph = paragraphs.getOrNull(paragraphIndex) ?: return null
        if (start < 0 || end <= start || end > paragraph.length) return null
        val block = paragraph.substring(start, end)
        return SegmentText(block, TranslationFingerprint.forBlock(block))
    }

    /** 一行的待翻译文本与按其版本算出的指纹。 */
    private data class SegmentText(val text: String, val fingerprint: String) {
        /** 正文是用户内容，不进日志。 */
        override fun toString(): String = "SegmentText(text=[REDACTED], fingerprint=$fingerprint)"
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
            policy = materializationPolicy,
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

    private suspend fun WholeTranslationTaskEntity.toView(segments: List<TranslationSegment>) =
        WholeTranslationTaskView(
            taskId = taskId,
            scopeKey = scopeKey,
            status = WholeTranslationTaskStatus.fromStableToken(status),
            failureReason = TranslationFailureReason.fromStableToken(failureReason),
            progress = WholeTranslationProgress.from(segments),
            segmentationModes = dao.getTaskArticles(taskId).mapNotNull {
                TranslationSegmentationMode.fromStableToken(it.segmentationMode)
            }.toSet()
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
