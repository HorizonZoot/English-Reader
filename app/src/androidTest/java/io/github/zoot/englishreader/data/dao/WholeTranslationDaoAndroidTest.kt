package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.zoot.englishreader.model.AppliedTranslationLayoutCodec
import io.github.zoot.englishreader.model.DefaultTranslationMaterializationPolicy
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.model.TranslationPlannerVersion
import io.github.zoot.englishreader.model.TranslationSegmentStatus
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import io.github.zoot.englishreader.util.ParagraphAligner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WholeTranslationDao] 的真实 Room 验证。
 *
 * 必须用 androidTest：本模块的正确性几乎全部依赖真实 SQLite 的行为——条件写的影响行数、
 * `@Transaction` 的回滚、外键级联。用 mock DAO 只能证明「方法被调用了」，证明不了
 * 「并发领取只有一个成功」或「校验失败后文章译文没被动过」。
 */
@RunWith(AndroidJUnit4::class)
class WholeTranslationDaoAndroidTest {

    private lateinit var db: EnglishReaderDatabase
    private lateinit var dao: WholeTranslationDao
    private lateinit var articleDao: ArticleDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java).build()
        dao = db.wholeTranslationDao()
        articleDao = db.articleDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 快照创建 ----

    @Test
    fun createTask_writesTaskArticlesAndSegmentsAtomically() = runBlocking {
        val articleId = insertArticle(TWO_PARAGRAPHS)
        val taskId = createTask(articleId)

        assertNotNull("task row must exist", dao.getTask(taskId))

        val targets = dao.getTaskArticles(taskId)
        assertEquals(1, targets.size)
        assertEquals(0, targets.single().ordinal)
        assertEquals(
            TranslationFingerprint.forArticle(TWO_PARAGRAPHS),
            targets.single().articleFingerprint
        )

        val segments = dao.getSegments(taskId)
        assertEquals(2, segments.size)
        assertEquals(listOf(0, 1), segments.map { it.paragraphIndex })
        assertTrue(segments.all { it.status == "untranslated" })
        assertTrue(segments.all { it.translatedText == null })
    }

    /**
     * 章节范围的 article 顺序必须持久化。
     *
     * 进程重启后 `WholeTranslationScope.articleIds` 已不存在；若顺序只靠 articleId 大小
     * 隐式恢复，章节顺序与 articleId 顺序不一致的书就会按错误顺序处理。
     */
    @Test
    fun createTask_multipleArticles_persistsProcessingOrder() = runBlocking {
        val first = insertArticle(ONE_PARAGRAPH)
        val second = insertArticle(TWO_PARAGRAPHS)

        // 刻意反序传入：ordinal 必须反映传入顺序，而非 articleId 升序
        val taskId = dao.createTask(
            task = taskRow(scopeKey = "book:7", bookId = 7),
            articles = listOf(
                legacyTarget(second, TWO_PARAGRAPHS),
                legacyTarget(first, ONE_PARAGRAPH)
            ),
            segments = segmentsFor(second, TWO_PARAGRAPHS) + segmentsFor(first, ONE_PARAGRAPH),
            now = NOW
        )

        val targets = dao.getTaskArticles(taskId)
        assertEquals(listOf(second, first), targets.map { it.articleId })
        assertEquals(listOf(0, 1), targets.map { it.ordinal })
    }

    @Test
    fun findResumableTask_terminalStatuses_areExcluded() = runBlocking {
        for (status in listOf("completed", "cancelled", "failed")) {
            val articleId = insertArticle(ONE_PARAGRAPH)
            val taskId = createTask(articleId)
            assertNotNull(dao.findResumableTask("article:$articleId"))
            dao.updateTaskStatus(taskId, status, if (status == "failed") "configuration" else null, NOW)
            if (status == "failed") {
                assertNotNull("failed task must stay resumable", dao.findResumableTask("article:$articleId"))
            } else {
                assertNull("$status task must not be resumable", dao.findResumableTask("article:$articleId"))
            }
        }
    }

    @Test
    fun terminalTask_lateWorkerWritesAndResume_cannotResurrectOrChangeCheckpoint() = runBlocking {
        for (status in listOf("completed", "cancelled")) {
            val articleId = insertArticle(TWO_PARAGRAPHS)
            val taskId = createTask(articleId)
            dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
            dao.updateTaskStatus(taskId, status, null, NOW)
            val segments = dao.getSegments(taskId)

            for (next in listOf("running", "paused", "failed", "cancelled")) {
                assertEquals("$status -> $next", 0, dao.updateTaskStatus(taskId, next, null, NOW + 1))
            }
            assertEquals(false, dao.beginTask(taskId, NOW + 1))
            assertEquals(0, dao.tryClaimSegment(taskId, articleId, 1, NOW + LEASE, NOW, 0))
            assertEquals(0, dao.checkpointSuccess(taskId, articleId, 0, fingerprintOf(TWO_PARAGRAPHS, 0), "迟到", NOW))
            assertEquals(0, dao.checkpointFailure(taskId, articleId, 0, "transient_network", NOW))
            assertEquals(0, dao.reclaimExpiredLeases(taskId, NOW + LEASE + 1))
            assertNull(dao.claimSegment(taskId, true, LEASE, NOW + LEASE + 1))
            assertEquals(segments, dao.getSegments(taskId))
            assertEquals(status, dao.getTask(taskId)?.status)
        }
    }

    @Test
    fun createTask_sourceChangedAfterRead_doesNotLeaveTaskOrCheckpoint() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val original = articleDao.getArticleById(articleId)!!
        articleDao.updateArticle(original.copy(content = "Edited."))
        var rejected = false
        try {
            dao.createTask(
                taskRow("article:$articleId"),
                listOf(legacyTarget(articleId, ONE_PARAGRAPH)),
                segmentsFor(articleId, ONE_PARAGRAPH), NOW
            )
        } catch (_: TranslationSourceChangedException) {
            rejected = true
        }
        assertTrue(rejected)
        assertNull(dao.findResumableTask("article:$articleId"))
        for (table in listOf("whole_translation_tasks", "translation_task_articles", "translation_segments")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table must stay empty", 0, cursor.getInt(0))
            }
        }
        assertEquals("Edited.", articleDao.getArticleById(articleId)?.content)
    }

    @Test
    fun findResumableTaskForSources_changedBody_cancelsWithoutResumingOrChangingCheckpoints() = runBlocking {
        val articleId = insertArticle(TWO_PARAGRAPHS)
        val taskId = createTask(articleId)
        val original = articleDao.getArticleById(articleId)!!
        val segments = dao.getSegments(taskId)
        assertEquals("paused", dao.getTask(taskId)?.status)
        articleDao.updateArticle(original.copy(content = "Edited."))

        assertNull(dao.findResumableTaskForSources("article:$articleId", listOf(articleId), NOW + 1))
        assertEquals("cancelled", dao.getTask(taskId)?.status)
        assertEquals(segments, dao.getSegments(taskId))
        assertNull(articleDao.getArticleById(articleId)?.translation)
    }

    // ---- 领取与 lease ----

    /**
     * 同一段落只能被领取一次。
     *
     * 这是防重复计费的核心：两条流程若都认为自己领到了，这一段会被请求两次。
     */
    @Test
    fun tryClaimSegment_alreadyClaimedWithLiveLease_affectsNoRows() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)

        val first = dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
        assertEquals("first claim must win", 1, first)

        val second = dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
        assertEquals("second claim must lose", 0, second)

        assertEquals(1, dao.getSegments(taskId).single().attemptCount)
    }

    @Test
    fun tryClaimSegment_translatedSegment_isNeverClaimable() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)

        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
        dao.checkpointSuccess(taskId, articleId, 0, fingerprintOf(ONE_PARAGRAPH, 0), "译文", NOW)

        // 两种模式都不得重新领取已成功的段落
        assertEquals(0, dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0))
        assertEquals(0, dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 1))
    }

    @Test
    fun tryClaimSegment_failedSegment_requiresRetryMode() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)

        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
        dao.checkpointFailure(taskId, articleId, 0, "transient_network", NOW)

        assertEquals("resume must skip failed", 0, dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0))
        assertEquals("retry must include failed", 1, dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 1))
    }

    /** 进程被杀留下的 `translating` 段落必须能重新处理，否则任务永远卡在最后几段。 */
    @Test
    fun claimSegment_expiredLease_reclaimsAndReassigns() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)

        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)

        val later = NOW + LEASE + 1
        val claimed = dao.claimSegment(taskId, includeFailed = false, leaseDurationMs = LEASE, now = later)

        assertNotNull("expired lease must become claimable", claimed)
        assertEquals(0, claimed!!.paragraphIndex)
        // attemptCount 累计而不重置：它用于识别反复卡住的段落
        assertEquals(2, dao.getSegments(taskId).single().attemptCount)
    }

    @Test
    fun reclaimExpiredLeases_liveLease_isUntouched() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)
        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)

        val reclaimed = dao.reclaimExpiredLeases(taskId, NOW + 1)

        assertEquals("live lease must not be reclaimed", 0, reclaimed)
        assertEquals("translating", dao.getSegments(taskId).single().status)
    }

    @Test
    fun claimSegment_allTranslated_returnsNull() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)
        translateAll(taskId, articleId, ONE_PARAGRAPH)

        assertNull(dao.claimSegment(taskId, includeFailed = true, leaseDurationMs = LEASE, now = NOW))
    }

    @Test
    fun resetConfigurationFailures_runningTask_resetsOnlyConfigurationAndPreservesAttempts() = runBlocking {
        val content = "Done.\n\nAuth.\n\nOversized."
        val articleId = insertArticle(content)
        val taskId = createTask(articleId)
        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
        dao.checkpointSuccess(taskId, articleId, 0, fingerprintOf(content, 0), "已完成", NOW)
        dao.tryClaimSegment(taskId, articleId, 1, NOW + LEASE, NOW, 0)
        dao.checkpointFailure(taskId, articleId, 1, "configuration", NOW)
        dao.tryClaimSegment(taskId, articleId, 2, NOW + LEASE, NOW, 0)
        dao.checkpointFailure(taskId, articleId, 2, "paragraph_too_long", NOW)
        val before = dao.getSegments(taskId)
        val otherTask = createTask(articleId)
        dao.tryClaimSegment(otherTask, articleId, 0, NOW + LEASE, NOW, 0)
        dao.checkpointFailure(otherTask, articleId, 0, "configuration", NOW)
        val otherSegments = dao.getSegments(otherTask)
        assertTrue(dao.beginTask(taskId, NOW + 1))

        assertEquals(1, dao.resetConfigurationFailures(taskId, NOW + 2))

        assertEquals(
            before.map { segment ->
                if (segment.paragraphIndex == 1) segment.copy(
                    status = "untranslated", failureReason = null, leaseExpiresAt = null, updatedAt = NOW + 2
                ) else segment
            },
            dao.getSegments(taskId)
        )
        assertEquals(otherSegments, dao.getSegments(otherTask))
    }

    @Test
    fun resetConfigurationFailures_taskNotRunning_leavesCheckpointUntouched() = runBlocking {
        for (status in listOf("paused", "failed", "cancelled", "completed")) {
            val articleId = insertArticle(ONE_PARAGRAPH)
            val taskId = createTask(articleId)
            dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
            dao.checkpointFailure(taskId, articleId, 0, "configuration", NOW)
            dao.updateTaskStatus(taskId, status, null, NOW)
            val before = dao.getSegments(taskId)

            assertEquals(status, 0, dao.resetConfigurationFailures(taskId, NOW + 1))
            assertEquals(before, dao.getSegments(taskId))
        }
    }

    // ---- checkpoint ----

    /**
     * lease 过期后被别人领走的段落，旧流程的迟到结果不能覆盖新的处理。
     */
    @Test
    fun checkpointSuccess_withoutHeldLease_affectsNoRows() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)

        // 未领取就写入
        val written = dao.checkpointSuccess(
            taskId, articleId, 0, fingerprintOf(ONE_PARAGRAPH, 0), "迟到译文", NOW
        )

        assertEquals(0, written)
        assertEquals("untranslated", dao.getSegments(taskId).single().status)
    }

    /** 源文本已变的段落，其译文不得写入 checkpoint。 */
    @Test
    fun checkpointSuccess_fingerprintMismatch_affectsNoRows() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)
        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)

        val written = dao.checkpointSuccess(
            taskId, articleId, 0, "fingerprint-of-different-text", "错配译文", NOW
        )

        assertEquals(0, written)
        assertNull(dao.getSegments(taskId).single().translatedText)
    }

    @Test
    fun checkpointSuccess_heldLease_persistsTranslationAndClearsLease() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)
        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)

        val written = dao.checkpointSuccess(
            taskId, articleId, 0, fingerprintOf(ONE_PARAGRAPH, 0), "第一段译文", NOW
        )

        assertEquals(1, written)
        val segment = dao.getSegments(taskId).single()
        assertEquals("translated", segment.status)
        assertEquals("第一段译文", segment.translatedText)
        assertNull("lease must be released", segment.leaseExpiresAt)
        assertNull(segment.failureReason)
    }

    // ---- materialize ----

    /**
     * 部分成功**绝不**写入文章译文。
     *
     * 这是整个任务最重要的一条：半篇译文比没有译文更糟，用户无法分辨哪几段是旧的。
     */
    @Test
    fun materialize_partialSegments_leavesArticleTranslationUnchanged() = runBlocking {
        val articleId = insertArticle(TWO_PARAGRAPHS)
        val taskId = createTask(articleId)

        // 只完成第一段
        dao.tryClaimSegment(taskId, articleId, 0, NOW + LEASE, NOW, 0)
        dao.checkpointSuccess(taskId, articleId, 0, fingerprintOf(TWO_PARAGRAPHS, 0), "第一段", NOW)

        val result = materialize(taskId)

        assertEquals(MaterializationResult.IncompleteSegments, result)
        assertNull("article translation must stay untouched", articleDao.getArticleById(articleId)?.translation)
        assertEquals("task must not be completed", "paused", dao.getTask(taskId)?.status)
    }

    @Test
    fun materialize_allSegmentsTranslated_writesJoinedTranslation() = runBlocking {
        val articleId = insertArticle(TWO_PARAGRAPHS)
        val taskId = createTask(articleId)
        translateAll(taskId, articleId, TWO_PARAGRAPHS)

        val result = materialize(taskId)

        assertEquals(MaterializationResult.Applied(1), result)
        assertEquals("译文0\n\n译文1", articleDao.getArticleById(articleId)?.translation)
        assertEquals("completed", dao.getTask(taskId)?.status)
    }

    // 「写入的译文能被 ParagraphAligner 按段配回」不在这里重复：它是 TranslationOutputAssembler
    // 的性质，与 Room 无关，已由 WholeTranslationDomainTest.join_*_roundTripsThroughParagraphAligner
    // 覆盖。本类只证明 materialize 写入的字符串就是 join 的输出（上一条用例）。

    /** 任务运行期间用户改了正文时，旧译文绝不能覆盖新内容。 */
    @Test
    fun materialize_sourceChangedAfterSnapshot_refusesAndPreservesTranslation() = runBlocking {
        val articleId = insertArticle(TWO_PARAGRAPHS)
        val taskId = createTask(articleId)
        translateAll(taskId, articleId, TWO_PARAGRAPHS)

        val original = articleDao.getArticleById(articleId)!!
        articleDao.updateArticle(
            original.copy(content = "Rewritten first.\n\nRewritten second.", translation = "旧译文")
        )

        val result = materialize(taskId)

        assertEquals(MaterializationResult.SourceChanged, result)
        assertEquals("旧译文", articleDao.getArticleById(articleId)?.translation)
    }

    @Test
    fun materialize_targetArticleDeleted_refusesWithSourceMissing() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)
        translateAll(taskId, articleId, ONE_PARAGRAPH)

        articleDao.deleteArticlesByIds(listOf(articleId))

        assertEquals(MaterializationResult.SourceMissing, materialize(taskId))
    }

    @Test
    fun materialize_terminalTask_isNotEligible() = runBlocking {
        val articleId = insertArticle(ONE_PARAGRAPH)
        val taskId = createTask(articleId)
        translateAll(taskId, articleId, ONE_PARAGRAPH)
        dao.updateTaskStatus(taskId, "cancelled", null, NOW)

        assertEquals(MaterializationResult.TaskNotEligible, materialize(taskId))
        assertNull(articleDao.getArticleById(articleId)?.translation)
    }

    @Test
    fun materialize_missingTask_reportsTaskMissing() = runBlocking {
        assertEquals(MaterializationResult.TaskMissing, materialize(9999))
    }

    /**
     * 多篇文章必须全有或全无。
     *
     * 第二篇被删时，第一篇也不能写入——否则用户得到一本半数章节有译文的书，
     * 且没有任何提示说明剩下的为什么没有。
     */
    @Test
    fun materialize_oneTargetInvalid_writesNoArticleAtAll() = runBlocking {
        val first = insertArticle(ONE_PARAGRAPH)
        val second = insertArticle(TWO_PARAGRAPHS)
        val taskId = dao.createTask(
            task = taskRow(scopeKey = "book:3", bookId = 3),
            articles = listOf(
                legacyTarget(first, ONE_PARAGRAPH),
                legacyTarget(second, TWO_PARAGRAPHS)
            ),
            segments = segmentsFor(first, ONE_PARAGRAPH) + segmentsFor(second, TWO_PARAGRAPHS),
            now = NOW
        )
        translateAll(taskId, first, ONE_PARAGRAPH)
        translateAll(taskId, second, TWO_PARAGRAPHS)

        articleDao.deleteArticlesByIds(listOf(second))

        assertEquals(MaterializationResult.SourceMissing, materialize(taskId))
        assertNull(
            "first article must not receive a translation when the second is invalid",
            articleDao.getArticleById(first)?.translation
        )
    }

    // ---- 级联清理 ----

    /** 删任务必须带走它的 article 目标与段落 checkpoint，否则库里会堆积孤儿行。 */
    @Test
    fun deleteTask_cascadesArticlesAndSegments() = runBlocking {
        val articleId = insertArticle(TWO_PARAGRAPHS)
        val taskId = createTask(articleId)

        dao.deleteTask(taskId)

        assertNull(dao.getTask(taskId))
        assertTrue(dao.getTaskArticles(taskId).isEmpty())
        assertTrue(dao.getSegments(taskId).isEmpty())
    }

    // ---- helpers ----

    private suspend fun insertArticle(content: String): Long =
        articleDao.insertArticle(ArticleEntity(title = "T", content = content))

    private fun taskRow(scopeKey: String, bookId: Long? = null) = WholeTranslationTaskEntity(
        scopeKey = scopeKey,
        bookId = bookId,
        status = "paused",
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun segmentsFor(articleId: Long, content: String): List<TranslationSegmentEntity> =
        ParagraphAligner.splitParagraphs(content).mapIndexed { index, text ->
            TranslationSegmentEntity(
                taskId = 0,
                articleId = articleId,
                paragraphIndex = index,
                sourceFingerprint = TranslationFingerprint.forParagraph(text),
                status = TranslationSegmentStatus.UNTRANSLATED.toStableToken(),
                updatedAt = NOW
            )
        }

    private suspend fun createTask(articleId: Long): Long {
        val content = articleDao.getArticleById(articleId)!!.content
        return dao.createTask(
            task = taskRow(scopeKey = "article:$articleId"),
            articles = listOf(legacyTarget(articleId, content)),
            segments = segmentsFor(articleId, content),
            now = NOW
        )
    }

    private fun fingerprintOf(content: String, paragraphIndex: Int): String =
        TranslationFingerprint.forParagraph(
            ParagraphAligner.splitParagraphs(content)[paragraphIndex]
        )

    /** 把某篇文章的全部段落走完「领取 → 成功」，译文固定为 `译文<index>`。 */
    private suspend fun translateAll(taskId: Long, articleId: Long, content: String) {
        ParagraphAligner.splitParagraphs(content).forEachIndexed { index, text ->
            dao.tryClaimSegment(taskId, articleId, index, NOW + LEASE, NOW, 1)
            dao.checkpointSuccess(
                taskId = taskId,
                articleId = articleId,
                paragraphIndex = index,
                sourceFingerprint = TranslationFingerprint.forParagraph(text),
                translatedText = "译文$index",
                now = NOW
            )
        }
    }

    private suspend fun materialize(taskId: Long): MaterializationResult = dao.materialize(
        taskId = taskId,
        policy = POLICY,
        now = NOW
    )

    /**
     * v7 之前形态的目标：一行一个空行段落，没有块坐标。
     *
     * 本文件的既有用例全部验证 legacy 语义，因此统一走这个构造。块路径的用例在
     * `WholeTranslationBlockPathTest` 里，用真实规划器产出坐标，不在这里手写。
     */
    private fun legacyTarget(articleId: Long, content: String) = TranslationTaskTarget(
        articleId = articleId,
        articleFingerprint = TranslationFingerprint.forArticle(content),
        segmentationMode = TranslationSegmentationMode.PRESERVE.toStableToken(),
        plannerVersion = TranslationPlannerVersion.LEGACY
    )

    private companion object {
        const val NOW = 1_000_000L
        const val LEASE = 60_000L
        const val ONE_PARAGRAPH = "Only paragraph."
        const val TWO_PARAGRAPHS = "First paragraph.\n\nSecond paragraph."

        /**
         * 与生产同一套纯计算。
         *
         * 不换成假实现：materialize 的正确性大半就落在这些计算上（按目标版本分派、坐标完整性、
         * 聚合与坐标同源），替换掉等于把要验的东西验掉了。
         */
        val POLICY = DefaultTranslationMaterializationPolicy(
            AppliedTranslationLayoutCodec(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())
        )
    }
}
