package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.zoot.englishreader.data.entity.ArticleTranslationStateEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.TranslationTaskArticleEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import io.github.zoot.englishreader.model.AppliedTranslationLayout
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.TranslationBlockAggregator
import io.github.zoot.englishreader.model.TranslationBlockCoverage
import io.github.zoot.englishreader.model.TranslationBlockRange
import io.github.zoot.englishreader.model.TranslationFailureReason
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.model.TranslationMaterializationPolicy
import io.github.zoot.englishreader.model.TranslationPlannerVersion
import io.github.zoot.englishreader.model.TranslatedBlock
import kotlinx.coroutines.flow.Flow

/**
 * 全文翻译任务的持久化边界。
 *
 * 三个事务方法承载了本模块的全部正确性要求：
 * - [createTask]：范围快照原子入库，不留半个任务。
 * - [claimSegment]：领取一个段落并打 lease，串行化「谁在处理这一段」。
 * - [materialize]：校验通过后一次性写入所有目标 article 的译文。
 *
 * 单独调用下面的写入原语会破坏这些保证；它们只为事务方法而存在。
 */
@Dao
interface WholeTranslationDao {

    // ---- 查询 ----

    @Query("SELECT * FROM whole_translation_tasks WHERE taskId = :taskId")
    suspend fun getTask(taskId: Long): WholeTranslationTaskEntity?

    /**
     * 观察任务行。
     *
     * 与 [observeSegments] 分开是必要的：materialize 只改 `whole_translation_tasks` 与
     * `articles`，不碰任何段落行。若 UI 只订阅段落表，任务完成这个最重要的状态变化就永远
     * 到不了它——进度停在 N/N，文案还写着「正在翻译」。
     */
    @Query("SELECT * FROM whole_translation_tasks WHERE taskId = :taskId")
    fun observeTask(taskId: Long): Flow<WholeTranslationTaskEntity?>

    /**
     * 找同源的可继续任务。
     *
     * 排除终态（`completed` / `cancelled`）：那些任务不该被「继续」，否则用户每次进入阅读页
     * 都会看到一个已完成的任务要求继续。`failed` 保留在候选内——它因配置问题中止，用户修好
     * 凭据后应当能接着已完成的段落往下走，而不是从零重来。
     */
    @Query(
        "SELECT * FROM whole_translation_tasks " +
            "WHERE scopeKey = :scopeKey AND status NOT IN ('completed', 'cancelled') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun findResumableTask(scopeKey: String): WholeTranslationTaskEntity?

    @Query(
        "SELECT * FROM translation_task_articles WHERE taskId = :taskId ORDER BY ordinal ASC"
    )
    suspend fun getTaskArticles(taskId: Long): List<TranslationTaskArticleEntity>

    @Query(
        "SELECT * FROM translation_segments WHERE taskId = :taskId " +
            "ORDER BY articleId ASC, paragraphIndex ASC"
    )
    suspend fun getSegments(taskId: Long): List<TranslationSegmentEntity>

    /**
     * 观察进度。
     *
     * 返回整行而非计数聚合：UI 需要区分「已翻译/失败/未翻译」的分项数量，且
     * `WholeTranslationProgress.from` 已经是这份映射的唯一实现，让它继续持有分类逻辑，
     * 比在 SQL 里再写一遍 status token 比较更不容易与域模型分叉。
     */
    @Query(
        "SELECT * FROM translation_segments WHERE taskId = :taskId " +
            "ORDER BY articleId ASC, paragraphIndex ASC"
    )
    fun observeSegments(taskId: Long): Flow<List<TranslationSegmentEntity>>

    @Query("SELECT articleId FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getBookArticleIds(bookId: Long): List<Long>

    @Query("SELECT translation FROM articles WHERE id = :articleId")
    suspend fun getArticleTranslation(articleId: Long): String?

    @Query("SELECT content FROM articles WHERE id = :articleId")
    suspend fun getArticleContent(articleId: Long): String?

    @Query("SELECT * FROM article_translation_state WHERE articleId = :articleId")
    suspend fun getArticleTranslationState(articleId: Long): ArticleTranslationStateEntity?

    @Query("SELECT * FROM reading_positions WHERE articleId = :articleId")
    suspend fun getReadingPosition(articleId: Long): ReadingPositionEntity?

    /**
     * 发布新译文时改写阅读锚点。
     *
     * 只在 [materialize] 内部使用，且只改 TRANSLATION 锚点。保留 `updatedAt` 的单调契约：传入的
     * 时间戳来自发布事务，晚于任何已提交的位置写入，因此不会被 `ArticleDao.saveReadingPosition`
     * 的「较旧写入不覆盖」判断挡掉。
     */
    @Query(
        "UPDATE reading_positions SET paragraphIndex = :paragraphIndex, textKind = :textKind, " +
            "characterOffset = :characterOffset, updatedAt = :now WHERE articleId = :articleId"
    )
    suspend fun updateReadingAnchor(
        articleId: Long,
        paragraphIndex: Int,
        textKind: String,
        characterOffset: Int,
        now: Long
    ): Int

    /**
     * 找出与给定 article 集合相交、且仍可继续的任务。
     *
     * 冲突检测的依据。同一篇文章被「当前页面」和「整本书」两个任务同时覆盖时，两者的分块方式
     * 可能不同，而它们最终都会写 `articles.translation` 与同一份布局——后提交的那个会让先提交
     * 的译文与布局对不上，用户看到的是一半新一半旧的对照。必须在发请求之前就发现。
     *
     * 排除 [exceptTaskId] 让恢复自身时不会把自己当成冲突。
     */
    @Query(
        "SELECT DISTINCT t.* FROM whole_translation_tasks t " +
            "INNER JOIN translation_task_articles a ON a.taskId = t.taskId " +
            "WHERE a.articleId IN (:articleIds) AND t.taskId != :exceptTaskId " +
            "AND t.status NOT IN ('completed', 'cancelled') " +
            "ORDER BY t.createdAt DESC"
    )
    suspend fun findOverlappingTasks(
        articleIds: List<Long>,
        exceptTaskId: Long = 0
    ): List<WholeTranslationTaskEntity>

    // ---- 写入原语 ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskRow(task: WholeTranslationTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskArticles(articles: List<TranslationTaskArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegments(segments: List<TranslationSegmentEntity>)

    @Query(
        "UPDATE whole_translation_tasks SET status = :status, failureReason = :failureReason, " +
            "updatedAt = :now WHERE taskId = :taskId AND status NOT IN ('completed', 'cancelled')"
    )
    suspend fun updateTaskStatus(
        taskId: Long,
        status: String,
        failureReason: String?,
        now: Long
    ): Int

    /**
     * 领取一个可处理段落并打 lease。
     *
     * 条件写而非「先查后改」：两条并发的处理流程若各自先 SELECT 再 UPDATE，会同时认为自己
     * 领到了同一段，于是这一段被请求两次——每次都可能计费。把资格判断放进 WHERE 让 SQLite
     * 的行锁来串行化，第二条流程的 UPDATE 影响 0 行，据此得知没领到。
     *
     * @return 实际更新的行数；0 表示该段已被别人领走或已不符合资格
     */
    @Query(
        "UPDATE translation_segments SET status = 'translating', " +
            "leaseExpiresAt = :leaseExpiresAt, attemptCount = attemptCount + 1, updatedAt = :now " +
            "WHERE taskId = :taskId AND articleId = :articleId AND paragraphIndex = :paragraphIndex " +
            "AND EXISTS (SELECT 1 FROM whole_translation_tasks WHERE taskId = :taskId " +
            "AND status NOT IN ('completed', 'cancelled')) " +
            "AND (status = 'untranslated' " +
            "  OR (status = 'translating' AND (leaseExpiresAt IS NULL OR leaseExpiresAt <= :now)) " +
            "  OR (status = 'failed' AND :includeFailed = 1))"
    )
    suspend fun tryClaimSegment(
        taskId: Long,
        articleId: Long,
        paragraphIndex: Int,
        leaseExpiresAt: Long,
        now: Long,
        includeFailed: Int
    ): Int

    /**
     * 记录段落成功。
     *
     * WHERE 里带 `status = 'translating'` 与 `sourceFingerprint`：前者保证只有持有 lease 的
     * 流程能写入结果——lease 过期后段落被别人领走，旧流程的迟到结果不能覆盖新的处理；后者
     * 保证段落源文本未变。两个条件缺一，都会让一份属于旧文本的译文写进当前 checkpoint。
     */
    @Query(
        "UPDATE translation_segments SET status = 'translated', translatedText = :translatedText, " +
            "failureReason = NULL, leaseExpiresAt = NULL, updatedAt = :now " +
            "WHERE taskId = :taskId AND articleId = :articleId AND paragraphIndex = :paragraphIndex " +
            "AND status = 'translating' AND sourceFingerprint = :sourceFingerprint " +
            "AND EXISTS (SELECT 1 FROM whole_translation_tasks WHERE taskId = :taskId " +
            "AND status NOT IN ('completed', 'cancelled'))"
    )
    suspend fun checkpointSuccess(
        taskId: Long,
        articleId: Long,
        paragraphIndex: Int,
        sourceFingerprint: String,
        translatedText: String,
        now: Long
    ): Int

    /** 记录段落失败。同样要求持有 lease，理由见 [checkpointSuccess]。 */
    @Query(
        "UPDATE translation_segments SET status = 'failed', failureReason = :failureReason, " +
            "leaseExpiresAt = NULL, updatedAt = :now " +
            "WHERE taskId = :taskId AND articleId = :articleId AND paragraphIndex = :paragraphIndex " +
            "AND status = 'translating' " +
            "AND EXISTS (SELECT 1 FROM whole_translation_tasks WHERE taskId = :taskId " +
            "AND status NOT IN ('completed', 'cancelled'))"
    )
    suspend fun checkpointFailure(
        taskId: Long,
        articleId: Long,
        paragraphIndex: Int,
        failureReason: String,
        now: Long
    ): Int

    /** 新 profile 已解析后，显式重试才恢复配置失败；终态或暂停任务不接受迟到的恢复。 */
    @Query(
        "UPDATE translation_segments SET status = 'untranslated', failureReason = NULL, " +
            "leaseExpiresAt = NULL, updatedAt = :now " +
            "WHERE taskId = :taskId AND status = 'failed' AND failureReason = 'configuration' " +
            "AND EXISTS (SELECT 1 FROM whole_translation_tasks WHERE taskId = :taskId AND status = 'running')"
    )
    suspend fun resetConfigurationFailures(taskId: Long, now: Long): Int

    /**
     * 回收过期 lease。
     *
     * 回收成 `untranslated` 而非 `failed`：进程被杀不是段落的失败，它从未得到结论。判成
     * `failed` 会让这些段落只在「重试失败项」里出现，而用户看到的是一个从未失败过的任务
     * 突然有了失败计数。
     *
     * 不重置 `attemptCount`：它是累计尝试次数，用于识别反复卡住的段落。
     */
    @Query(
        "UPDATE translation_segments SET status = 'untranslated', leaseExpiresAt = NULL, " +
            "updatedAt = :now " +
            "WHERE taskId = :taskId AND status = 'translating' " +
            "AND (leaseExpiresAt IS NULL OR leaseExpiresAt <= :now) " +
            "AND EXISTS (SELECT 1 FROM whole_translation_tasks WHERE taskId = :taskId " +
            "AND status NOT IN ('completed', 'cancelled'))"
    )
    suspend fun reclaimExpiredLeases(taskId: Long, now: Long): Int

    @Query("UPDATE articles SET translation = :translation WHERE id = :articleId")
    suspend fun updateArticleTranslation(articleId: Long, translation: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticleTranslationState(state: ArticleTranslationStateEntity): Long

    /**
     * 只更新分块偏好，**不触碰**任何已发布字段。
     *
     * 与 [updateAppliedLayout] 严格分开，不能合成一个 upsert 整行的写入。改偏好只应影响下一次
     * 启动；若顺带写了已发布字段（哪怕是写回读到的旧值），就会与一个刚刚提交完成的发布事务竞争，
     * 把用户刚翻好的对照打回整段模式。反向同理：发布时不得覆盖用户并发改的偏好。
     */
    @Query(
        "UPDATE article_translation_state SET preferredMode = :mode, updatedAt = :now " +
            "WHERE articleId = :articleId"
    )
    suspend fun updatePreferredMode(articleId: Long, mode: String, now: Long): Int

    /** 只更新已发布布局字段，不触碰 `preferredMode`；理由见 [updatePreferredMode]。 */
    @Query(
        "UPDATE article_translation_state SET appliedPlan = :plan, " +
            "appliedSourceFingerprint = :sourceFingerprint, " +
            "appliedTranslationFingerprint = :translationFingerprint, " +
            "appliedTaskId = :taskId, updatedAt = :now WHERE articleId = :articleId"
    )
    suspend fun updateAppliedLayout(
        articleId: Long,
        plan: String?,
        sourceFingerprint: String?,
        translationFingerprint: String?,
        taskId: Long?,
        now: Long
    ): Int

    /**
     * 保存分块偏好，行不存在时创建。
     *
     * 先 INSERT IGNORE 再 UPDATE，而不是 `@Upsert` 整行：`@Upsert` 在冲突时会替换全部列，等于
     * 用构造这一行时的已发布字段（通常是 null）覆盖真正的发布结果。
     */
    @Transaction
    suspend fun savePreferredMode(articleId: Long, mode: String, now: Long) {
        insertArticleTranslationState(
            ArticleTranslationStateEntity(articleId = articleId, preferredMode = mode, updatedAt = now)
        )
        updatePreferredMode(articleId, mode, now)
    }

    /**
     * 写入已发布布局，行不存在时创建。
     *
     * [fallbackMode] 只在**新建行**时作为 `preferredMode` 的初值——首次发布前用户可能从未显式保存
     * 过偏好，这一行要能凭发布本身建起来。行已存在时绝不覆盖 `preferredMode`：那是用户可能刚改过
     * 的值，而本次发布用的是任务快照里固定的模式，两者不一定相同，覆盖会让用户的修改凭空消失。
     *
     * 同理不用 `@Upsert`：冲突时它替换整行，会把 `preferredMode` 一起写掉。
     */
    @Transaction
    suspend fun upsertAppliedLayout(
        articleId: Long,
        plan: String?,
        sourceFingerprint: String?,
        translationFingerprint: String?,
        taskId: Long?,
        fallbackMode: String,
        now: Long
    ) {
        insertArticleTranslationState(
            ArticleTranslationStateEntity(
                articleId = articleId,
                preferredMode = fallbackMode,
                updatedAt = now
            )
        )
        updateAppliedLayout(articleId, plan, sourceFingerprint, translationFingerprint, taskId, now)
    }

    /**
     * 正文被编辑后清除已发布布局，保留用户的分块偏好。
     *
     * 坐标是按编辑前的正文算的，编辑后它们指向的已经是别的字符；继续裁切会显示错位的对照。清除后
     * 阅读层回落到整段展示，直到下一次成功发布。偏好留着：那是用户对这篇文章的选择，与正文改动无关。
     */
    @Query(
        "UPDATE article_translation_state SET appliedPlan = NULL, " +
            "appliedSourceFingerprint = NULL, appliedTranslationFingerprint = NULL, " +
            "appliedTaskId = NULL, updatedAt = :now WHERE articleId = :articleId"
    )
    suspend fun clearAppliedLayout(articleId: Long, now: Long): Int

    @Query("DELETE FROM whole_translation_tasks WHERE taskId = :taskId")
    suspend fun deleteTask(taskId: Long)

    // ---- 事务 ----

    /** scopeKey 不足以证明同源：编辑后的文章不能继续旧段落快照。 */
    @Transaction
    suspend fun findResumableTaskForSources(
        scopeKey: String,
        articleIds: List<Long>,
        now: Long
    ): WholeTranslationTaskEntity? {
        while (true) {
            val task = findResumableTask(scopeKey) ?: return null
            val targets = getTaskArticles(task.taskId)
            if (targets.map { it.articleId } == articleIds && taskSourcesMatch(task.taskId)) return task
            updateTaskStatus(task.taskId, "cancelled", null, now)
        }
    }

    @Transaction
    suspend fun taskSourcesMatch(taskId: Long): Boolean {
        val targets = getTaskArticles(taskId)
        if (targets.isEmpty()) return false
        return targets.all { target ->
            val content = getArticleContent(target.articleId)
            content != null && TranslationFingerprint.forArticle(content) == target.articleFingerprint
        }
    }

    /** 终态和源快照检查与 running 写入不可分开，否则旧恢复动作能复活已取消任务。 */
    @Transaction
    suspend fun beginTask(taskId: Long, now: Long): Boolean {
        val task = getTask(taskId) ?: return false
        if (task.status == "completed" || task.status == "cancelled") return false
        if (!taskSourcesMatch(taskId)) {
            updateTaskStatus(taskId, "cancelled", null, now)
            return false
        }
        findOverlappingTasks(getTaskArticles(taskId).map { it.articleId }, taskId).firstOrNull()?.let {
            throw TranslationTaskConflictException(it.taskId, it.scopeKey)
        }
        return updateTaskStatus(taskId, "running", null, now) > 0
    }

    /**
     * 原子创建任务及其完整快照。
     *
     * 任一步失败整体回滚。半个任务比没有任务更糟：段落行不全会让「预计请求数」和进度分母
     * 都算错，而这种错误在 UI 上看起来完全正常。
     *
     * @param articles 目标文章快照，列表顺序即处理顺序，不可为空
     * @param segments 段落/块快照，顺序与 [articles] 展开后一致，不可为空
     * @return 新任务 ID
     */
    @Transaction
    suspend fun createTask(
        task: WholeTranslationTaskEntity,
        articles: List<TranslationTaskTarget>,
        segments: List<TranslationSegmentEntity>,
        now: Long
    ): Long {
        require(articles.isNotEmpty()) { "task must target at least one article" }
        require(segments.isNotEmpty()) { "task must contain at least one segment" }
        if (task.bookId != null && getBookArticleIds(task.bookId) != articles.map { it.articleId }) {
            throw TranslationSourceChangedException()
        }

        // 冲突检查必须在**本事务内**，与插入不可分开：分成两步会留下一个窗口，两个覆盖同一文章的
        // 任务各自检查时都没看到对方，于是双双建成。此后两者都会跑到 materialize，后提交的那个
        // 用自己的分块覆盖 articles.translation 和布局，而另一个已发布的布局仍指向旧译文的坐标——
        // 用户看到的是一半新一半旧的对照，且两次请求都已计费。
        //
        // 抛出而非返回 null：调用方必须显式处理「先继续或取消既有任务」，静默取消另一个范围的任务
        // 会让用户为那些段落白付一次钱。
        findOverlappingTasks(articles.map { it.articleId }).firstOrNull()?.let {
            throw TranslationTaskConflictException(it.taskId, it.scopeKey)
        }
        // 版本在这里就拦：未知版本的行一旦写进去，worker 与 materialize 都只能拒绝执行，
        // 用户看到的是一个永远不动的任务。
        require(articles.all { TranslationPlannerVersion.isSupported(it.plannerVersion) }) {
            "unsupported planner version"
        }
        for (target in articles) {
            val content = getArticleContent(target.articleId) ?: throw TranslationSourceChangedException()
            if (TranslationFingerprint.forArticle(content) != target.articleFingerprint) {
                throw TranslationSourceChangedException()
            }
            if (TranslationPlannerVersion.isBlock(target.plannerVersion)) {
                val mode = io.github.zoot.englishreader.model.TranslationSegmentationMode.fromStableToken(
                    getArticleTranslationState(target.articleId)?.preferredMode
                ) ?: io.github.zoot.englishreader.model.TranslationSegmentationMode.AUTO
                if (mode.toStableToken() != target.segmentationMode) throw TranslationSourceChangedException()
            }
        }

        val taskId = insertTaskRow(task)
        insertTaskArticles(
            articles.mapIndexed { ordinal, target ->
                TranslationTaskArticleEntity(
                    taskId = taskId,
                    articleId = target.articleId,
                    ordinal = ordinal,
                    articleFingerprint = target.articleFingerprint,
                    segmentationMode = target.segmentationMode,
                    plannerVersion = target.plannerVersion
                )
            }
        )
        insertSegments(segments.map { it.copy(taskId = taskId, updatedAt = now) })
        return taskId
    }

    /**
     * 领取下一个可处理段落。
     *
     * 先回收过期 lease 再按顺序尝试领取，两步在同一事务内：分开会出现「回收后、领取前」的
     * 窗口，另一条流程可能刚好在这时把回收出来的段落领走，于是本次返回 null 而任务看起来
     * 无事可做。
     *
     * @param includeFailed 是否把可重试的 `failed` 段落也纳入领取范围
     * @param exclude 本轮已处理过的 `(articleId, paragraphIndex)`，跳过它们。让调用方在一次
     *   运行里对每段最多尝试一次，否则可重试失败的段落会被刚写完 failed 就立即再领走。
     * @return 领到的段落；没有可处理段落时为 null
     */
    @Transaction
    suspend fun claimSegment(
        taskId: Long,
        includeFailed: Boolean,
        leaseDurationMs: Long,
        now: Long,
        exclude: Set<Pair<Long, Int>> = emptySet()
    ): TranslationSegmentEntity? {
        reclaimExpiredLeases(taskId, now)

        val candidates = getSegments(taskId)
        for (segment in candidates) {
            if (segment.articleId to segment.paragraphIndex in exclude) continue
            // SQL 只看得到 status 字面量，看不到失败类别。PERMANENT（段落超长）与 FATAL（配置）
            // 的失败在重试模式下也不得领取：重试前者只会原样再撞一次上限，后者要用户先修
            // 配置。资格判定与域模型 isEligibleFor 保持同一来源。
            if (segment.status == "failed") {
                val reason = TranslationFailureReason.fromStableToken(segment.failureReason)
                if (!includeFailed || reason?.category?.isRetryable != true) continue
            }
            val claimed = tryClaimSegment(
                taskId = taskId,
                articleId = segment.articleId,
                paragraphIndex = segment.paragraphIndex,
                leaseExpiresAt = now + leaseDurationMs,
                now = now,
                includeFailed = if (includeFailed) 1 else 0
            )
            if (claimed > 0) {
                return segment.copy(
                    status = "translating",
                    leaseExpiresAt = now + leaseDurationMs,
                    attemptCount = segment.attemptCount + 1,
                    updatedAt = now
                )
            }
        }
        return null
    }

    /**
     * 校验并一次性写入所有目标 article 的译文与对照布局。
     *
     * 这是「段落可以部分成功，文章译文必须全有或全无」这条要求的落点。校验缺一不可：
     *
     * 1. 任务未处于终态——已完成或已取消的任务不得再次提交。
     * 2. 全部段落均为 `translated`——部分成功不写入。
     * 3. 每篇目标 article 仍存在，且**当前正文指纹**与创建任务时一致——用户在任务运行期间
     *    改了正文时，旧译文绝不能覆盖新内容。
     * 4. 行数/坐标与当前正文自洽，按该目标的版本分别校验（见下）。
     *
     * **分派按每篇目标文章的 `plannerVersion`，不是按整个任务**：整本书翻译是一个任务，而分块
     * 方式是按文章保存的偏好，同一任务完全可能同时含 legacy 与 block 目标。按任务取一个版本会
     * 让其余目标的坐标被错误解释，译文整体错位——而错位不抛异常。
     *
     * 未知版本一律拒绝。宁可让用户重建任务，也不要拿猜出来的坐标去写覆盖性的译文。
     *
     * 任一校验失败即返回对应结果且**不做任何写入**；调用方据此保留文章原有译文与旧布局。抛出
     * 异常同样安全：整个事务回滚。
     */
    @Transaction
    suspend fun materialize(
        taskId: Long,
        policy: TranslationMaterializationPolicy,
        now: Long
    ): MaterializationResult {
        val task = getTask(taskId) ?: return MaterializationResult.TaskMissing
        if (task.status == "completed" || task.status == "cancelled") {
            return MaterializationResult.TaskNotEligible
        }

        val segments = getSegments(taskId)
        if (segments.isEmpty()) return MaterializationResult.TaskNotEligible
        if (segments.any { it.status != "translated" }) {
            return MaterializationResult.IncompleteSegments
        }

        val targets = getTaskArticles(taskId)
        if (targets.isEmpty()) return MaterializationResult.TaskNotEligible
        if (targets.any { !TranslationPlannerVersion.isSupported(it.plannerVersion) }) {
            return MaterializationResult.UnsupportedPlannerVersion
        }

        val grouped = segments.groupBy { it.articleId }
        val pending = mutableListOf<PendingPublication>()

        for (target in targets) {
            val content = getArticleContent(target.articleId)
                ?: return MaterializationResult.SourceMissing
            if (policy.articleFingerprint(content) != target.articleFingerprint) {
                return MaterializationResult.SourceChanged
            }

            val ordered = grouped[target.articleId]
                ?.sortedBy { it.paragraphIndex }
                ?: return MaterializationResult.IncompleteSegments
            val paragraphs = policy.splitParagraphs(content)

            val prepared = if (TranslationPlannerVersion.isBlock(target.plannerVersion)) {
                prepareBlockPublication(target, ordered, paragraphs, policy)
            } else {
                prepareLegacyPublication(target, ordered, paragraphs, policy)
            }
            when (prepared) {
                is PreparedPublication.Failed -> return prepared.result
                is PreparedPublication.Ready -> pending += prepared.publication
            }
        }

        // 校验全部通过后才开始写入，因此不存在「写了前两篇、第三篇校验失败」的中间状态。
        for (publication in pending) {
            // 锚点换算必须在覆盖布局**之前**读到旧布局，否则旧的中文 offset 就再也无从解释了。
            // 三者（译文、布局、锚点）同事务提交：分开做会留下一个窗口，期间恢复阅读位置会拿旧的
            // 中文偏移去索引新译文——那是另一次模型输出，长度与断句都不同，落点与用户读到的地方无关。
            val previousState = getArticleTranslationState(publication.articleId)
            val previousTranslation = getArticleTranslation(publication.articleId)
            val previousLayout = policy.decodeLayout(previousState?.appliedPlan, publication.paragraphs)?.takeIf {
                previousTranslation != null && it.articleFingerprint == publication.sourceFingerprint &&
                    previousState?.appliedSourceFingerprint == publication.sourceFingerprint &&
                    previousState.appliedTranslationFingerprint == policy.translationFingerprint(previousTranslation) &&
                    it.matchesText(publication.paragraphs, policy.splitParagraphs(previousTranslation))
            }
            check(updateArticleTranslation(publication.articleId, publication.translation) > 0) {
                "Article disappeared during translation publication"
            }
            upsertAppliedLayout(
                articleId = publication.articleId,
                plan = publication.appliedPlan,
                sourceFingerprint = publication.sourceFingerprint,
                translationFingerprint = policy.translationFingerprint(publication.translation),
                taskId = taskId,
                fallbackMode = publication.segmentationMode,
                now = now
            )
            if (previousTranslation != publication.translation || previousState?.appliedPlan != publication.appliedPlan) {
                convertStoredAnchor(publication.articleId, previousLayout, policy, now)
            }
        }
        updateTaskStatus(taskId, "completed", null, now)
        return MaterializationResult.Applied(pending.size)
    }

    /**
     * 把已持久化的阅读锚点换算到新发布的译文上。
     *
     * 只动 TRANSLATION 锚点，且只在确实发生改变时写库。ORIGINAL、TITLE、SOURCE 的坐标系是正文，
     * 而发布不改正文，原样留着即可——多写一次不仅无谓，还会把 `updatedAt` 推到发布时刻，让用户在
     * 发布前最后一次滚动的位置被判成「更旧」而丢弃。
     *
     * 转换目标是该块的**原文起点**，不是新译文里的某个中文偏移。旧偏移是针对旧译文那一串字符算的，
     * 新译文是另一次模型输出，按比例缩放或直接沿用都会落在无关的字符上；退回原文起点则一定在用户
     * 读过的地方附近。
     */
    private suspend fun convertStoredAnchor(
        articleId: Long,
        previousLayout: AppliedTranslationLayout?,
        policy: TranslationMaterializationPolicy,
        now: Long
    ) {
        val stored = getReadingPosition(articleId) ?: return
        if (stored.textKind != ReadingTextKind.TRANSLATION.name) return
        val converted = policy.convertAnchor(
            ReadingAnchor(
                paragraphIndex = stored.paragraphIndex.coerceAtLeast(0),
                textKind = ReadingTextKind.TRANSLATION,
                characterOffset = stored.characterOffset.coerceAtLeast(0)
            ),
            previousLayout
        )
        updateReadingAnchor(
            articleId = articleId,
            paragraphIndex = converted.paragraphIndex,
            textKind = converted.textKind.name,
            characterOffset = converted.characterOffset,
            now = maxOf(now, stored.updatedAt + 1)
        )
    }

    /**
     * legacy 目标：一行一个空行段落，没有块坐标。
     *
     * 保留原有的「行数 == 当前分段数」断言。指纹相同时它必然成立，留着是为了挡「指纹算法被改坏」
     * 这类回归。[appliedPlan] 为 null，阅读层据此走整段对照。
     */
    private fun prepareLegacyPublication(
        target: TranslationTaskArticleEntity,
        ordered: List<TranslationSegmentEntity>,
        paragraphs: List<String>,
        policy: TranslationMaterializationPolicy
    ): PreparedPublication {
        if (ordered.size != paragraphs.size || ordered.withIndex().any { (index, segment) ->
                segment.paragraphIndex != index || segment.sourceFingerprint != TranslationFingerprint.forParagraph(paragraphs[index])
            }
        ) {
            return PreparedPublication.Failed(MaterializationResult.SourceChanged)
        }
        return PreparedPublication.Ready(
            PendingPublication(
                articleId = target.articleId,
                translation = policy.joinParagraphs(ordered.map { requireNotNull(it.translatedText) }),
                appliedPlan = null,
                sourceFingerprint = target.articleFingerprint,
                segmentationMode = target.segmentationMode,
                paragraphs = paragraphs
            )
        )
    }

    /**
     * block 目标：按持久坐标聚合，并产出这一版的对照布局。
     *
     * **替换**了 legacy 的行数断言：块数与段落数本来就不相等，拿它比较会让每个真正分了块的目标
     * 都被误判成「正文已变」。这里改为要求坐标完整铺满每个原段落，由
     * `TranslationBlockAggregator` 在聚合前统一校验——它比行数更强，能挡住洞、重叠与越界。
     *
     * 布局与译文在同一次聚合里产出，不分两步算。
     */
    private fun prepareBlockPublication(
        target: TranslationTaskArticleEntity,
        ordered: List<TranslationSegmentEntity>,
        paragraphs: List<String>,
        policy: TranslationMaterializationPolicy
    ): PreparedPublication {
        val blocks = ordered.map { segment ->
            val sourceParagraphIndex = segment.sourceParagraphIndex
            val start = segment.sourceStartOffset
            val end = segment.sourceEndOffset
            // block 版本的行必须带齐三个坐标。缺任何一个说明版本与数据不符，此时任何补全都是编造。
            if (sourceParagraphIndex == null || sourceParagraphIndex < 0 || start == null || start < 0 || end == null || end <= start) {
                return PreparedPublication.Failed(MaterializationResult.MissingBlockCoordinates)
            }
            val paragraph = paragraphs.getOrNull(sourceParagraphIndex)
                ?: return PreparedPublication.Failed(MaterializationResult.SourceChanged)
            if (end > paragraph.length || segment.sourceFingerprint != TranslationFingerprint.forBlock(paragraph.substring(start, end))) {
                return PreparedPublication.Failed(MaterializationResult.SourceChanged)
            }
            TranslatedBlock(
                range = TranslationBlockRange(
                    blockIndex = segment.paragraphIndex,
                    sourceParagraphIndex = sourceParagraphIndex,
                    startOffset = start,
                    endOffset = end
                ),
                translatedText = requireNotNull(segment.translatedText)
            )
        }

        val aggregated = policy.aggregate(paragraphs, blocks)
        if (aggregated !is TranslationBlockAggregator.Result.Aggregated) {
            return PreparedPublication.Failed(MaterializationResult.SourceChanged)
        }
        val layout = AppliedTranslationLayout(
            layoutVersion = AppliedTranslationLayout.VERSION_V1,
            segmentationMode = target.segmentationMode,
            articleFingerprint = target.articleFingerprint,
            blocks = aggregated.blocks
        )
        return PreparedPublication.Ready(
            PendingPublication(
                articleId = target.articleId,
                translation = aggregated.translation,
                appliedPlan = policy.encodeLayout(layout),
                sourceFingerprint = target.articleFingerprint,
                segmentationMode = target.segmentationMode,
                paragraphs = paragraphs
            )
        )
    }
}

/** 一篇目标文章校验通过、等待写入的内容。 */
private data class PendingPublication(
    val articleId: Long,
    val translation: String,
    val appliedPlan: String?,
    val sourceFingerprint: String,
    val segmentationMode: String,

    /**
     * 当前正文的分段结果。
     *
     * 带着走而不是在写入时重算：解码**旧**布局需要它，而旧布局的坐标是按正文算的。正文在本事务内
     * 已校验过指纹未变，所以准备阶段算出的这份与写入时一致；重算一遍只是多一次分段，还多一个两边
     * 可能分叉的地方。
     */
    val paragraphs: List<String>
) {
    /** 译文是用户内容，不进日志。 */
    override fun toString(): String =
        "PendingPublication(articleId=$articleId, translation=[REDACTED], " +
            "hasLayout=${appliedPlan != null}, segmentationMode=$segmentationMode)"
}

/** 单篇目标的准备结果。失败时携带要返回给调用方的具体原因。 */
private sealed interface PreparedPublication {
    data class Ready(val publication: PendingPublication) : PreparedPublication

    data class Failed(val result: MaterializationResult) : PreparedPublication
}

/**
 * 建任务时一篇目标文章的完整快照。
 *
 * 取代原先的 `Pair<Long, String>`：现在每个目标还要带自己的分块方式与版本，而整书任务里各章
 * 的取值可以不同。四个同类型字段挤在一个 Pair/Triple 里，调用处只能靠位置区分，写反了不会
 * 报错——只会让一份按块切出来的 checkpoint 被当作整段读取。
 */
data class TranslationTaskTarget(
    val articleId: Long,
    val articleFingerprint: String,
    val segmentationMode: String,
    val plannerVersion: String
)

/** 建任务前读取的源快照已过期；抛出以回滚事务，不能留下半份任务。 */
class TranslationSourceChangedException : IllegalStateException("Translation source changed")

/**
 * 已有未完成任务覆盖同一篇文章；抛出以回滚事务。
 *
 * 只携带 taskId 与 scopeKey，不带正文或译文：这个异常会冒泡到 UI 层用于组织提示文案。
 * [scopeKey] 让界面能说清冲突来自「当前页面」还是「整本书」，用户据此决定继续哪一个。
 */
class TranslationTaskConflictException(
    val taskId: Long,
    val scopeKey: String
) : IllegalStateException("Translation task conflict")

/** [WholeTranslationDao.materialize] 的结果。除 [Applied] 外均未做任何写入。 */
sealed interface MaterializationResult {

    /** 已写入 [articleCount] 篇文章的译文，任务标记为完成。 */
    data class Applied(val articleCount: Int) : MaterializationResult

    data object TaskMissing : MaterializationResult

    /** 任务已处于终态，或没有目标/段落。 */
    data object TaskNotEligible : MaterializationResult

    /** 还有段落未成功。 */
    data object IncompleteSegments : MaterializationResult

    /** 目标 article 已被删除。 */
    data object SourceMissing : MaterializationResult

    /** 目标 article 正文已改变，旧译文不再适用。 */
    data object SourceChanged : MaterializationResult

    /**
     * 某个目标的 `plannerVersion` 不是本版本能解释的。
     *
     * 与 [SourceChanged] 分开：正文没变，是坐标语义未知。可能来自更新的版本写入后用户降级，
     * 或数据被外部改动。此时**不能**猜一套语义去发布——猜错就是把译文整体对到错误的位置上。
     */
    data object UnsupportedPlannerVersion : MaterializationResult

    /**
     * 标为 `block-v1` 的行缺少块坐标。
     *
     * 说明版本标记与实际数据不符。任何补全都是编造坐标，因此拒绝发布并保留旧译文。
     */
    data object MissingBlockCoordinates : MaterializationResult
}
