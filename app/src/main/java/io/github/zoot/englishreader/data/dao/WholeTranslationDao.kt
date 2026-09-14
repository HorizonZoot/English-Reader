package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.TranslationTaskArticleEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import io.github.zoot.englishreader.model.TranslationFailureReason
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

    @Query("SELECT content FROM articles WHERE id = :articleId")
    suspend fun getArticleContent(articleId: Long): String?

    // ---- 写入原语 ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskRow(task: WholeTranslationTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskArticles(articles: List<TranslationTaskArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegments(segments: List<TranslationSegmentEntity>)

    @Query(
        "UPDATE whole_translation_tasks SET status = :status, failureReason = :failureReason, " +
            "updatedAt = :now WHERE taskId = :taskId"
    )
    suspend fun updateTaskStatus(
        taskId: Long,
        status: String,
        failureReason: String?,
        now: Long
    )

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
            "AND status = 'translating' AND sourceFingerprint = :sourceFingerprint"
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
            "AND status = 'translating'"
    )
    suspend fun checkpointFailure(
        taskId: Long,
        articleId: Long,
        paragraphIndex: Int,
        failureReason: String,
        now: Long
    ): Int

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
            "AND (leaseExpiresAt IS NULL OR leaseExpiresAt <= :now)"
    )
    suspend fun reclaimExpiredLeases(taskId: Long, now: Long): Int

    @Query("UPDATE articles SET translation = :translation WHERE id = :articleId")
    suspend fun updateArticleTranslation(articleId: Long, translation: String): Int

    @Query("DELETE FROM whole_translation_tasks WHERE taskId = :taskId")
    suspend fun deleteTask(taskId: Long)

    // ---- 事务 ----

    /**
     * 原子创建任务及其完整快照。
     *
     * 任一步失败整体回滚。半个任务比没有任务更糟：段落行不全会让「预计请求数」和进度分母
     * 都算错，而这种错误在 UI 上看起来完全正常。
     *
     * @param articles article ID → 正文指纹，键的迭代顺序即处理顺序
     * @param segments 段落快照，顺序与 [articles] 展开后一致，不可为空
     * @return 新任务 ID
     */
    @Transaction
    suspend fun createTask(
        task: WholeTranslationTaskEntity,
        articles: List<Pair<Long, String>>,
        segments: List<TranslationSegmentEntity>,
        now: Long
    ): Long {
        require(articles.isNotEmpty()) { "task must target at least one article" }
        require(segments.isNotEmpty()) { "task must contain at least one segment" }

        val taskId = insertTaskRow(task)
        insertTaskArticles(
            articles.mapIndexed { ordinal, (articleId, fingerprint) ->
                TranslationTaskArticleEntity(
                    taskId = taskId,
                    articleId = articleId,
                    ordinal = ordinal,
                    articleFingerprint = fingerprint
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
     * 校验并一次性写入所有目标 article 的译文。
     *
     * 这是「段落可以部分成功，文章译文必须全有或全无」这条要求的落点。四道校验缺一不可：
     *
     * 1. 任务未处于终态——已完成或已取消的任务不得再次提交。
     * 2. 全部段落均为 `translated`——部分成功不写入。
     * 3. 每篇目标 article 仍存在，且**当前正文指纹**与创建任务时一致——用户在任务运行期间
     *    改了正文时，旧译文绝不能覆盖新内容。
     * 4. 段落数与当前正文的分段数一致——指纹相同则这一条必然成立，作为冗余断言保留，
     *    因为它挡住的是「指纹算法被改坏」这类回归。
     *
     * 任一校验失败即返回对应结果且**不做任何写入**；调用方据此保留文章原有译文。抛出异常
     * 同样安全：整个事务回滚。
     */
    @Transaction
    suspend fun materialize(
        taskId: Long,
        paragraphSplitter: (String) -> List<String>,
        joinParagraphs: (List<String>) -> String,
        articleFingerprint: (String) -> String,
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

        val grouped = segments.groupBy { it.articleId }
        val pending = mutableListOf<Pair<Long, String>>()

        for (target in targets) {
            val content = getArticleContent(target.articleId)
                ?: return MaterializationResult.SourceMissing
            if (articleFingerprint(content) != target.articleFingerprint) {
                return MaterializationResult.SourceChanged
            }

            val ordered = grouped[target.articleId]
                ?.sortedBy { it.paragraphIndex }
                ?: return MaterializationResult.IncompleteSegments
            if (ordered.size != paragraphSplitter(content).size) {
                return MaterializationResult.SourceChanged
            }

            pending += target.articleId to joinParagraphs(
                ordered.map { requireNotNull(it.translatedText) }
            )
        }

        // 校验全部通过后才开始写入，因此不存在「写了前两篇、第三篇校验失败」的中间状态。
        for ((articleId, translation) in pending) {
            if (updateArticleTranslation(articleId, translation) == 0) {
                return MaterializationResult.SourceMissing
            }
        }
        updateTaskStatus(taskId, "completed", null, now)
        return MaterializationResult.Applied(pending.size)
    }
}

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
}
