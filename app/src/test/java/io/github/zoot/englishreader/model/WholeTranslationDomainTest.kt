package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.TimeoutPhase
import io.github.zoot.englishreader.util.ParagraphAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 域模型：范围、段落快照、指纹、状态机、重试资格与进度计数。
 *
 * 这些是纯 Kotlin 断言，不涉及 Room、AI 或 Android。
 */
class WholeTranslationDomainTest {

    // ---- 范围 ----

    @Test
    fun currentArticleScope_singleArticle_exposesStableKeyAndOrder() {
        val scope = WholeTranslationScope.CurrentArticle(articleId = 7)
        assertEquals(listOf(7L), scope.articleIds)
        assertEquals("article:7", scope.scopeKey)
    }

    @Test
    fun chapterScope_orderedArticles_preservesReadingOrder() {
        val scope = WholeTranslationScope.Chapter(bookId = 3, articleIds = listOf(10, 11, 12))
        assertEquals(listOf(10L, 11L, 12L), scope.articleIds)
        assertEquals("book:3", scope.scopeKey)
    }

    @Test
    fun articleScopeAndChapterScope_sameUnderlyingId_doNotCollide() {
        // 两种范围的 key 必须不同，否则「当前文章」的任务会被误认为是同源的整章任务。
        val article = WholeTranslationScope.CurrentArticle(articleId = 5)
        val chapter = WholeTranslationScope.Chapter(bookId = 5, articleIds = listOf(5))
        assertNotEquals(article.scopeKey, chapter.scopeKey)
    }

    @Test
    fun scope_invalidIdentityOrDuplicates_rejects() {
        assertThrows(IllegalArgumentException::class.java) {
            WholeTranslationScope.CurrentArticle(articleId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WholeTranslationScope.Chapter(bookId = 0, articleIds = listOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WholeTranslationScope.Chapter(bookId = 1, articleIds = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WholeTranslationScope.Chapter(bookId = 1, articleIds = listOf(2, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WholeTranslationScope.Chapter(bookId = 1, articleIds = listOf(2, -3))
        }
    }

    // ---- 段落快照 ----

    @Test
    fun toParagraphSnapshot_multiParagraphArticle_indexesFromZeroInOrder() {
        val scope = WholeTranslationScope.CurrentArticle(articleId = 1)
        val snapshot = scope.toParagraphSnapshot(
            mapOf(1L to "First para.\n\nSecond para.\n\nThird para.")
        )

        assertEquals(3, snapshot.size)
        assertEquals(listOf(0, 1, 2), snapshot.map { it.paragraphIndex })
        assertEquals(
            listOf("First para.", "Second para.", "Third para."),
            snapshot.map { it.text }
        )
        assertTrue(snapshot.all { it.articleId == 1L })
    }

    @Test
    fun toParagraphSnapshot_paragraphIndices_matchParagraphAlignerSegmentation() {
        // 快照与渲染必须逐段对应。分段规则只有 ParagraphAligner 一个实现，这里断言快照
        // 确实用的是它——否则 checkpoint 会把译文写到错位的段落上，且运行时无从发现。
        val content = "One.\n\n\n  Two with hard\nwrap.  \n\nThree."
        val scope = WholeTranslationScope.CurrentArticle(articleId = 2)

        val snapshot = scope.toParagraphSnapshot(mapOf(2L to content))
        val rendered = ParagraphAligner.splitParagraphs(content)

        assertEquals(rendered, snapshot.map { it.text })
    }

    @Test
    fun toParagraphSnapshot_blankAndWhitespaceOnlyParagraphs_areDropped() {
        val scope = WholeTranslationScope.CurrentArticle(articleId = 1)
        val snapshot = scope.toParagraphSnapshot(
            mapOf(1L to "\n\n   \n\nReal content.\n\n   \n\n")
        )

        assertEquals(1, snapshot.size)
        assertEquals("Real content.", snapshot.single().text)
        assertEquals(0, snapshot.single().paragraphIndex)
    }

    @Test
    fun toParagraphSnapshot_emptyArticleContent_producesNoSegments() {
        val scope = WholeTranslationScope.CurrentArticle(articleId = 1)
        assertTrue(scope.toParagraphSnapshot(mapOf(1L to "   \n\n  ")).isEmpty())
    }

    @Test
    fun toParagraphSnapshot_chapterScope_restartsParagraphIndexPerArticle() {
        // 段落索引是 article 内的序号，不是跨章的全局序号：Room 的唯一键是
        // (task, article, paragraphIndex)，全局编号会让第二章的 checkpoint 对不上它的段落。
        val scope = WholeTranslationScope.Chapter(bookId = 1, articleIds = listOf(20, 21))
        val snapshot = scope.toParagraphSnapshot(
            mapOf(
                20L to "Ch1 p1.\n\nCh1 p2.",
                21L to "Ch2 p1."
            )
        )

        assertEquals(
            listOf(20L to 0, 20L to 1, 21L to 0),
            snapshot.map { it.articleId to it.paragraphIndex }
        )
    }

    @Test
    fun toParagraphSnapshot_missingArticle_skipsItWithoutFailing() {
        // 范围快照与正文读取之间文章可能已被删除；少一篇优于让用户面对崩溃。
        val scope = WholeTranslationScope.Chapter(bookId = 1, articleIds = listOf(30, 31, 32))
        val snapshot = scope.toParagraphSnapshot(
            mapOf(
                30L to "Kept.",
                32L to "Also kept."
            )
        )

        assertEquals(listOf(30L, 32L), snapshot.map { it.articleId })
    }

    @Test
    fun paragraphSnapshot_identicalTextInDifferentArticles_sharesParagraphFingerprint() {
        // 段落指纹只覆盖文本，所以相同文本指纹相同；区分靠 (articleId, paragraphIndex)。
        val scope = WholeTranslationScope.Chapter(bookId = 1, articleIds = listOf(40, 41))
        val snapshot = scope.toParagraphSnapshot(
            mapOf(40L to "Same text.", 41L to "Same text.")
        )

        assertEquals(
            snapshot[0].sourceFingerprint,
            snapshot[1].sourceFingerprint
        )
        assertNotEquals(snapshot[0].articleId, snapshot[1].articleId)
    }

    @Test
    fun translationParagraph_invalidIdentityOrBlankText_rejects() {
        assertThrows(IllegalArgumentException::class.java) {
            TranslationParagraph(0, 0, "Text.", "fp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationParagraph(1, -1, "Text.", "fp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationParagraph(1, 0, "   ", "fp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TranslationParagraph(1, 0, "Text.", "  ")
        }
    }

    @Test
    fun translationParagraph_toString_redactsSourceText() {
        val rendered = TranslationParagraph(1, 0, "secret paragraph", "fp").toString()
        assertFalse(rendered.contains("secret paragraph"))
        assertTrue(rendered.contains("[REDACTED]"))
    }

    // ---- 指纹 ----

    @Test
    fun forArticle_sameContent_isStableAcrossCalls() {
        val content = "Body text.\n\nSecond."
        assertEquals(
            TranslationFingerprint.forArticle(content),
            TranslationFingerprint.forArticle(content)
        )
    }

    @Test
    fun forArticle_blankLineOnlyEdit_changesFingerprint() {
        // 用完整原始 content 而非拼接段落的理由：只改动空行时段落文本不变，但实际分段
        // 可能已经变了。指纹必须捕获这种编辑，否则旧任务会写进结构已变的文章。
        val before = "One.\n\nTwo."
        val after = "One.\n\n\n\nTwo."
        assertNotEquals(
            TranslationFingerprint.forArticle(before),
            TranslationFingerprint.forArticle(after)
        )
    }

    /** 两个指纹函数都必须区分不同输入；这是 SHA-256 的基本性质，一条即可。 */
    @Test
    fun fingerprints_differentInput_produceDifferentValues() {
        assertNotEquals(
            TranslationFingerprint.forArticle("Original."),
            TranslationFingerprint.forArticle("Original edited.")
        )
        assertNotEquals(
            TranslationFingerprint.forParagraph("Para one."),
            TranslationFingerprint.forParagraph("Para two.")
        )
    }

    @Test
    fun forArticleAndForParagraph_sameText_doNotCollide() {
        // 两者作用域不同，必须有独立前缀：单段文章的两个指纹若相等，
        // 文章级完整性校验就会被段落级指纹意外满足。
        val text = "Identical text."
        assertNotEquals(
            TranslationFingerprint.forArticle(text),
            TranslationFingerprint.forParagraph(text)
        )
    }

    // ---- 稳定 token ----

    @Test
    fun segmentStatus_stableTokens_roundTrip() {
        TranslationSegmentStatus.entries.forEach { status ->
            assertEquals(
                status,
                TranslationSegmentStatus.fromStableToken(status.toStableToken())
            )
        }
    }

    @Test
    fun segmentStatus_unknownToken_readsAsUntranslated() {
        // 宁可重做一次，也不要让恢复流程崩在解析上。
        assertEquals(
            TranslationSegmentStatus.UNTRANSLATED,
            TranslationSegmentStatus.fromStableToken("bogus")
        )
        assertEquals(
            TranslationSegmentStatus.UNTRANSLATED,
            TranslationSegmentStatus.fromStableToken(null)
        )
    }

    @Test
    fun taskStatus_stableTokens_roundTrip() {
        WholeTranslationTaskStatus.entries.forEach { status ->
            assertEquals(
                status,
                WholeTranslationTaskStatus.fromStableToken(status.toStableToken())
            )
        }
    }

    @Test
    fun taskStatus_unknownToken_readsAsPaused() {
        // 读作 PAUSED 而非 RUNNING：无法识别的任务应当等待用户决定，不能自行开始发请求。
        assertEquals(
            WholeTranslationTaskStatus.PAUSED,
            WholeTranslationTaskStatus.fromStableToken("bogus")
        )
    }

    @Test
    fun taskStatus_terminalStates_excludeResumableOnes() {
        assertTrue(WholeTranslationTaskStatus.COMPLETED.isTerminal)
        assertTrue(WholeTranslationTaskStatus.CANCELLED.isTerminal)
        // FAILED 不是终态：用户修好配置后应当能继续，而不是重建整个任务。
        assertFalse(WholeTranslationTaskStatus.FAILED.isTerminal)
        assertFalse(WholeTranslationTaskStatus.PAUSED.isTerminal)
        assertFalse(WholeTranslationTaskStatus.RUNNING.isTerminal)
    }

    @Test
    fun failureReason_stableTokens_roundTrip() {
        TranslationFailureReason.entries.forEach { reason ->
            assertEquals(reason, TranslationFailureReason.fromStableToken(reason.toStableToken()))
        }
        assertEquals(null, TranslationFailureReason.fromStableToken("bogus"))
        assertEquals(null, TranslationFailureReason.fromStableToken(null))
    }

    // ---- 失败分类 ----

    @Test
    fun failureCategory_permanentAndFatal_differInTaskScope() {
        // 两者都「重试无用」，但影响范围完全不同：一个超长段落不该让整章翻不了。
        assertFalse(TranslationFailureCategory.PERMANENT.abortsTask)
        assertTrue(TranslationFailureCategory.FATAL.abortsTask)
        assertFalse(TranslationFailureCategory.RETRYABLE.abortsTask)

        assertTrue(TranslationFailureCategory.RETRYABLE.isRetryable)
        assertFalse(TranslationFailureCategory.PERMANENT.isRetryable)
        assertFalse(TranslationFailureCategory.FATAL.isRetryable)
    }

    @Test
    fun from_credentialAndAuthErrors_abortWholeTask() {
        // 把 401 判成 PERMANENT 会让任务对着无效凭据把剩下的段落全试一遍，每次都可能计费。
        listOf(
            AiError.NoActiveProfile,
            AiError.ProfileNotFound,
            AiError.CredentialMissing,
            AiError.CredentialStorageUnavailable,
            AiError.InvalidEndpoint,
            AiError.HttpAuth(401),
            AiError.HttpNotFound()
        ).forEach { error ->
            val reason = TranslationFailureReason.from(error)
            assertEquals(
                "$error must be CONFIGURATION",
                TranslationFailureReason.CONFIGURATION,
                reason
            )
            assertTrue("$error must abort the task", reason.category.abortsTask)
        }
    }

    @Test
    fun from_oversizedInput_isPermanentButDoesNotAbortTask() {
        // 413 是这一段的体积撞上远端上限，换 profile 不解决；跳过这一段，继续其余段落。
        listOf(
            AiError.InputTooLong(actualChars = 9000, maxChars = 8000),
            AiError.PayloadTooLarge()
        ).forEach { error ->
            val reason = TranslationFailureReason.from(error)
            assertEquals(TranslationFailureReason.PARAGRAPH_TOO_LONG, reason)
            assertFalse("$error must not abort the task", reason.category.abortsTask)
            assertFalse("$error must not be retried", reason.category.isRetryable)
        }
    }

    @Test
    fun from_transientNetworkErrors_areRetryable() {
        listOf(
            AiError.Offline,
            AiError.DnsFailure,
            AiError.TlsFailure,
            AiError.Timeout(TimeoutPhase.CONNECT),
            AiError.Timeout(TimeoutPhase.READ),
            AiError.Timeout(TimeoutPhase.CALL),
            AiError.RequestTimeout(),
            AiError.RateLimited(retryAfterSeconds = 30),
            AiError.Server(503)
        ).forEach { error ->
            val reason = TranslationFailureReason.from(error)
            assertEquals(
                "$error must be TRANSIENT_NETWORK",
                TranslationFailureReason.TRANSIENT_NETWORK,
                reason
            )
            assertTrue("$error must be retryable", reason.category.isRetryable)
        }
    }

    @Test
    fun from_emptyOrMalformedResponse_isRetryableProviderIssue() {
        listOf(AiError.NoContent, AiError.MalformedResponse).forEach { error ->
            val reason = TranslationFailureReason.from(error)
            assertEquals(TranslationFailureReason.PROVIDER_RESPONSE, reason)
            assertTrue(reason.category.isRetryable)
        }
    }

    @Test
    fun from_unknownError_isRetryableAndDoesNotAbortTask() {
        // Unknown 是兜底分类，判成 FATAL 会让一次偶发抖动废掉整个任务的剩余进度。
        listOf(AiError.Unknown, AiError.UnexpectedHttp(418)).forEach { error ->
            val reason = TranslationFailureReason.from(error)
            assertEquals(TranslationFailureReason.UNKNOWN, reason)
            assertTrue(reason.category.isRetryable)
            assertFalse(reason.category.abortsTask)
        }
    }

    // ---- 段落 checkpoint ----

    @Test
    fun translatedSegment_blankTranslation_rejects() {
        // 允许「成功但译文为空」会让最终写入把空串当译文提交，用户看到空白对照且无从判断。
        assertThrows(IllegalArgumentException::class.java) {
            segment(status = TranslationSegmentStatus.TRANSLATED, translatedText = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            segment(status = TranslationSegmentStatus.TRANSLATED, translatedText = "   ")
        }
    }

    @Test
    fun nonTranslatedSegment_missingTranslation_isAccepted() {
        val pending = segment(status = TranslationSegmentStatus.UNTRANSLATED)
        assertEquals(null, pending.translatedText)
    }

    @Test
    fun segment_invalidIdentityOrAttemptCount_rejects() {
        assertThrows(IllegalArgumentException::class.java) {
            segment(articleId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            segment(paragraphIndex = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            segment(attemptCount = -1)
        }
    }

    @Test
    fun segment_toString_redactsTranslatedText() {
        val rendered = segment(
            status = TranslationSegmentStatus.TRANSLATED,
            translatedText = "secret translation"
        ).toString()
        assertFalse(rendered.contains("secret translation"))
        assertTrue(rendered.contains("[REDACTED]"))
    }

    // ---- lease ----

    @Test
    fun isLeaseExpired_translatingSegment_comparesAgainstLeaseDeadline() {
        val leased = segment(
            status = TranslationSegmentStatus.TRANSLATING,
            leaseExpiresAt = 1_000L
        )
        assertFalse(leased.isLeaseExpired(now = 999L))
        // 边界包含：正好到期即视为过期，否则恰好等于截止时刻的段落无人处理。
        assertTrue(leased.isLeaseExpired(now = 1_000L))
        assertTrue(leased.isLeaseExpired(now = 1_001L))
    }

    @Test
    fun isLeaseExpired_translatingSegmentWithoutLease_isExpired() {
        // 进程被杀后段落会永久停在 TRANSLATING；缺失 lease 必须视为可回收，否则任务卡死。
        val orphaned = segment(status = TranslationSegmentStatus.TRANSLATING, leaseExpiresAt = null)
        assertTrue(orphaned.isLeaseExpired(now = 0L))
    }

    @Test
    fun isLeaseExpired_nonTranslatingStatuses_areNeverExpired() {
        listOf(
            TranslationSegmentStatus.UNTRANSLATED,
            TranslationSegmentStatus.FAILED
        ).forEach { status ->
            assertFalse(segment(status = status, leaseExpiresAt = 0L).isLeaseExpired(now = 9_999L))
        }
        assertFalse(
            segment(
                status = TranslationSegmentStatus.TRANSLATED,
                translatedText = "done",
                leaseExpiresAt = 0L
            ).isLeaseExpired(now = 9_999L)
        )
    }

    // ---- 重试资格 ----

    @Test
    fun isEligibleFor_translatedSegment_isNeverReprocessed() {
        // 整个可恢复语义的核心不变量：已成功的段落绝不重新请求，否则「恢复」变成重复付费。
        val done = segment(
            status = TranslationSegmentStatus.TRANSLATED,
            translatedText = "done"
        )
        TranslationProcessingMode.entries.forEach { mode ->
            assertFalse(
                "TRANSLATED must never be eligible in $mode",
                done.isEligibleFor(mode, now = Long.MAX_VALUE)
            )
        }
    }

    @Test
    fun isEligibleFor_untranslatedSegment_isEligibleInEveryMode() {
        val pending = segment(status = TranslationSegmentStatus.UNTRANSLATED)
        TranslationProcessingMode.entries.forEach { mode ->
            assertTrue(pending.isEligibleFor(mode, now = 0L))
        }
    }

    @Test
    fun isEligibleFor_expiredLease_isReclaimedButLiveLeaseIsNot() {
        val leased = segment(
            status = TranslationSegmentStatus.TRANSLATING,
            leaseExpiresAt = 5_000L
        )
        // 未过期的 lease 表示另一个 worker 正在处理它，重复领取会重复付费。
        assertFalse(leased.isEligibleFor(TranslationProcessingMode.RESUME, now = 4_999L))
        assertTrue(leased.isEligibleFor(TranslationProcessingMode.RESUME, now = 5_001L))
    }

    @Test
    fun isEligibleFor_retryableFailure_onlyInRetryMode() {
        val failed = segment(
            status = TranslationSegmentStatus.FAILED,
            failureReason = TranslationFailureReason.TRANSIENT_NETWORK
        )
        // RESUME 不该自动重试失败项：用户没按重试就不额外花钱。
        assertFalse(failed.isEligibleFor(TranslationProcessingMode.RESUME, now = 0L))
        assertTrue(failed.isEligibleFor(TranslationProcessingMode.RETRY_FAILED, now = 0L))
    }

    /**
     * 不可重试的失败类别一律被重试排除。
     *
     * 三种 reason 的判据完全相同（同一 Arrange、同一断言），因此合并为一条遍历。失败消息带上
     * reason，红的时候仍能一眼看出是哪一类退化了。
     */
    @Test
    fun isEligibleFor_nonRetryableFailures_areExcludedFromRetry() {
        mapOf(
            // 重试一个超长段落只会原样再撞一次远端上限，纯粹白花钱
            TranslationFailureReason.PARAGRAPH_TOO_LONG to "permanent",
            // 配置问题必须由用户先修好；重试只是对着同一个坏凭据再撞一次
            TranslationFailureReason.CONFIGURATION to "fatal",
            // 未分类失败无从判断是否可重试，保守排除
            null to "unclassified"
        ).forEach { (reason, label) ->
            val failed = segment(
                status = TranslationSegmentStatus.FAILED,
                failureReason = reason
            )
            assertFalse(
                "$label failure must be excluded from retry",
                failed.isEligibleFor(TranslationProcessingMode.RETRY_FAILED, now = 0L)
            )
        }
    }

    @Test
    fun isEligibleFor_retryMode_alsoCoversUnprocessedSegments() {
        // 任务因 FATAL 中止时，失败项与尚未轮到的段落并存。只挑 FAILED 会让后者永远留在
        // 未翻译状态，用户反复按重试也无法让任务完成。
        val segments = listOf(
            segment(paragraphIndex = 0, status = TranslationSegmentStatus.TRANSLATED, translatedText = "done"),
            segment(paragraphIndex = 1, status = TranslationSegmentStatus.FAILED, failureReason = TranslationFailureReason.TRANSIENT_NETWORK),
            segment(paragraphIndex = 2, status = TranslationSegmentStatus.UNTRANSLATED)
        )

        val eligible = segments.filter { it.isEligibleFor(TranslationProcessingMode.RETRY_FAILED, now = 0L) }

        assertEquals(listOf(1, 2), eligible.map { it.paragraphIndex })
    }

    // ---- 进度 ----

    @Test
    fun from_mixedSegments_countsEachStatusExactlyOnce() {
        val progress = WholeTranslationProgress.from(
            listOf(
                segment(paragraphIndex = 0, status = TranslationSegmentStatus.TRANSLATED, translatedText = "a"),
                segment(paragraphIndex = 1, status = TranslationSegmentStatus.TRANSLATED, translatedText = "b"),
                segment(paragraphIndex = 2, status = TranslationSegmentStatus.TRANSLATING, leaseExpiresAt = 1L),
                segment(paragraphIndex = 3, status = TranslationSegmentStatus.FAILED, failureReason = TranslationFailureReason.TRANSIENT_NETWORK),
                segment(paragraphIndex = 4, status = TranslationSegmentStatus.UNTRANSLATED)
            )
        )

        assertEquals(5, progress.total)
        assertEquals(2, progress.translated)
        assertEquals(1, progress.translating)
        assertEquals(1, progress.failed)
        assertEquals(1, progress.untranslated)
        // 各状态计数必须正好覆盖总数，不重不漏。
        assertEquals(
            progress.total,
            progress.translated + progress.translating + progress.failed + progress.untranslated
        )
    }

    @Test
    fun isFullyTranslated_allSegmentsSucceeded_isTrue() {
        val progress = WholeTranslationProgress.from(
            listOf(
                segment(paragraphIndex = 0, status = TranslationSegmentStatus.TRANSLATED, translatedText = "a"),
                segment(paragraphIndex = 1, status = TranslationSegmentStatus.TRANSLATED, translatedText = "b")
            )
        )
        assertTrue(progress.isFullyTranslated)
        assertFalse(progress.hasFailures)
    }

    @Test
    fun isFullyTranslated_anySegmentOutstanding_isFalse() {
        // 这是最终事务性写入的门槛。差一段就不能碰 ArticleEntity.translation。
        listOf(
            segment(paragraphIndex = 1, status = TranslationSegmentStatus.UNTRANSLATED),
            segment(paragraphIndex = 1, status = TranslationSegmentStatus.TRANSLATING, leaseExpiresAt = 1L),
            segment(
                paragraphIndex = 1,
                status = TranslationSegmentStatus.FAILED,
                failureReason = TranslationFailureReason.TRANSIENT_NETWORK
            )
        ).forEach { outstanding ->
            val progress = WholeTranslationProgress.from(
                listOf(
                    segment(paragraphIndex = 0, status = TranslationSegmentStatus.TRANSLATED, translatedText = "a"),
                    outstanding
                )
            )
            assertFalse(
                "outstanding ${outstanding.status} must block completion",
                progress.isFullyTranslated
            )
        }
    }

    @Test
    fun isFullyTranslated_emptySegmentList_isFalse() {
        // 空任务不算完成：否则一个段落全被过滤掉的任务会立刻「成功」并写入空译文。
        val progress = WholeTranslationProgress.from(emptyList())
        assertEquals(0, progress.total)
        assertFalse(progress.isFullyTranslated)
        assertFalse(progress.hasFailures)
    }

    // ---- Sheet 主按钮推导 ----
    //
    // primaryAction 是纯函数，直接测它。ViewModel 与 Compose 测试各自只验一条路径，
    // 用来证明推导结果确实传到了 UI，不在那里穷举四种状态。

    @Test
    fun primaryAction_derivesFromStatusAndFailures() {
        fun tracking(status: WholeTranslationTaskStatus, failed: Int, translated: Int = 1, total: Int = 3) =
            WholeTranslationSheetState.Tracking(
                taskId = 1,
                scopeKey = "article:1",
                status = status,
                progress = WholeTranslationProgress(total, translated, 0, failed, total - translated - failed),
                failureReason = null
            )

        // 运行中：不管有没有失败，都只能「后台继续」——重试按钮会与正在跑的 worker 抢段落
        assertEquals(WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND, tracking(WholeTranslationTaskStatus.RUNNING, failed = 0).primaryAction)
        assertEquals(WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND, tracking(WholeTranslationTaskStatus.RUNNING, failed = 1).primaryAction)
        // 完成：只剩关闭
        assertEquals(WholeTranslationPrimaryAction.DONE, tracking(WholeTranslationTaskStatus.COMPLETED, failed = 0, translated = 3).primaryAction)
        // 暂停/失败：有失败项就重试，否则继续
        assertEquals(WholeTranslationPrimaryAction.RETRY_FAILED, tracking(WholeTranslationTaskStatus.PAUSED, failed = 1).primaryAction)
        assertEquals(WholeTranslationPrimaryAction.RESUME, tracking(WholeTranslationTaskStatus.PAUSED, failed = 0).primaryAction)
        assertEquals(WholeTranslationPrimaryAction.RETRY_FAILED, tracking(WholeTranslationTaskStatus.FAILED, failed = 1).primaryAction)
        assertEquals(WholeTranslationPrimaryAction.RESUME, tracking(WholeTranslationTaskStatus.FAILED, failed = 0).primaryAction)
    }

    // ---- DAO SQL 字面量锁定 ----
    //
    // WholeTranslationDao 的 @Query 里把状态写成了 SQL 字面量（'translating'、'completed' 等），
    // Room 不允许在 SQL 里引用 Kotlin 常量。于是 toStableToken() 与那些字面量之间存在一条
    // 编译器看不见的耦合：改了 token 而没改 SQL，条件写会静默影响 0 行，任务卡死而没有任何
    // 错误。round-trip 测试抓不到这种改动——重命名后 round-trip 照样成立。这里把字面值钉死，
    // 改 token 的人会先在这里撞红，从而知道要同步改 DAO。

    @Test
    fun segmentStatus_stableTokens_matchDaoSqlLiterals() {
        assertEquals("untranslated", TranslationSegmentStatus.UNTRANSLATED.toStableToken())
        assertEquals("translating", TranslationSegmentStatus.TRANSLATING.toStableToken())
        assertEquals("translated", TranslationSegmentStatus.TRANSLATED.toStableToken())
        assertEquals("failed", TranslationSegmentStatus.FAILED.toStableToken())
    }

    @Test
    fun taskStatus_stableTokens_matchDaoSqlLiterals() {
        // findResumableTask 的 NOT IN ('completed', 'cancelled') 与 materialize 的终态判断
        // 都直接写了这两个字面量。
        assertEquals("paused", WholeTranslationTaskStatus.PAUSED.toStableToken())
        assertEquals("running", WholeTranslationTaskStatus.RUNNING.toStableToken())
        assertEquals("completed", WholeTranslationTaskStatus.COMPLETED.toStableToken())
        assertEquals("failed", WholeTranslationTaskStatus.FAILED.toStableToken())
        assertEquals("cancelled", WholeTranslationTaskStatus.CANCELLED.toStableToken())
    }

    @Test
    fun failureReason_stableTokens_arePersistenceStable() {
        // failureReason 列不参与 SQL 条件，但它是持久化身份：改 token 会让已存行的原因读成 null，
        // 于是可重试的失败项在重试时被 isEligibleFor 排除。
        assertEquals("transient_network", TranslationFailureReason.TRANSIENT_NETWORK.toStableToken())
        assertEquals("provider_response", TranslationFailureReason.PROVIDER_RESPONSE.toStableToken())
        assertEquals("paragraph_too_long", TranslationFailureReason.PARAGRAPH_TOO_LONG.toStableToken())
        assertEquals("configuration", TranslationFailureReason.CONFIGURATION.toStableToken())
        assertEquals("unknown", TranslationFailureReason.UNKNOWN.toStableToken())
    }

    // ---- TranslationOutputAssembler ----
    //
    // 这组测试守的是段落对照显示的正确性。它们必须走**真实的** ParagraphAligner.align，
    // 因为要证明的性质是「join 的输出能被 align 原样切回来」——用假的分段实现验证这个
    // 性质等于什么都没验证。

    /** align 只需要一个满足 AlignedParagraph 契约的分句器；本组测试不关心句子切分。 */
    private val wholeParagraphSplitter: (String) -> List<SentenceRange> = { text ->
        listOf(SentenceRange(index = 0, text = text, startOffset = 0, endOffset = text.length))
    }

    @Test
    fun join_multipleParagraphs_roundTripsThroughParagraphAligner() {
        val content = "First English.\n\nSecond English.\n\nThird English."
        val translations = listOf("第一段。", "第二段。", "第三段。")

        val aligned = ParagraphAligner.align(
            content = content,
            translation = TranslationOutputAssembler.join(translations),
            sentenceSplitter = wholeParagraphSplitter
        )

        assertEquals(3, aligned.size)
        assertEquals(translations, aligned.map { it.chinese })
    }

    /**
     * 段内空行必须被折叠，否则该段之后的所有译文错位一格。
     *
     * 这是 [TranslationOutputAssembler] 存在的全部理由：模型在译文里输出空行是常见行为，
     * 而 align 按空行切分。若不折叠，这里会切出 4 段译文，第 2、3 段英文分别配到
     * 「第二段上半」和「第二段下半」，第 3 段英文拿到本属第 2 段的内容。
     */
    @Test
    fun join_paragraphContainingBlankLine_doesNotShiftLaterParagraphs() {
        val content = "One.\n\nTwo.\n\nThree."
        val translations = listOf("第一段。", "第二段上半。\n\n第二段下半。", "第三段。")

        val aligned = ParagraphAligner.align(
            content = content,
            translation = TranslationOutputAssembler.join(translations),
            sentenceSplitter = wholeParagraphSplitter
        )

        assertEquals(3, aligned.size)
        // 段内空行折叠为单个换行：内容全部保留，但不再是段落分隔符。
        assertEquals("第二段上半。\n第二段下半。", aligned[1].chinese)
        // 真正要守的是这一条：最后一段仍拿到属于自己的译文，没有前移。
        assertEquals("第三段。", aligned[2].chinese)
    }

    @Test
    fun normalizeParagraph_surroundingWhitespaceAndBlankLineRuns_collapse() {
        assertEquals("译文。", TranslationOutputAssembler.normalizeParagraph("  译文。\n\n  "))
        // 多个连续空行同样折叠为一个换行，不留下能被 align 当作分隔符的空行。
        assertEquals("上。\n下。", TranslationOutputAssembler.normalizeParagraph("上。\n\n\n\n下。"))
        // 段内单换行是硬折行，不是分隔符，原样保留。
        assertEquals("上。\n下。", TranslationOutputAssembler.normalizeParagraph("上。\n下。"))
    }

    /**
     * 空占位符没有能存活的形式，因此是契约违约而非静默接受。
     *
     * `join(["A", "", "C"])` 会产出 `"A\n\n\n\nC"`，贪婪的分隔符正则把四个换行吞成一个，
     * 切回来只有 2 段。抛出会中止 materialization 事务，保住文章原有译文。
     */
    @Test
    fun join_paragraphNormalizingToEmpty_rejects() {
        assertThrows(IllegalArgumentException::class.java) {
            TranslationOutputAssembler.join(listOf("首段。", "   ", "末段。"))
        }
    }

    private fun segment(
        articleId: Long = 1,
        paragraphIndex: Int = 0,
        status: TranslationSegmentStatus = TranslationSegmentStatus.UNTRANSLATED,
        translatedText: String? = null,
        failureReason: TranslationFailureReason? = null,
        attemptCount: Int = 0,
        leaseExpiresAt: Long? = null
    ) = TranslationSegment(
        articleId = articleId,
        paragraphIndex = paragraphIndex,
        sourceFingerprint = "fp-$articleId-$paragraphIndex",
        status = status,
        translatedText = translatedText,
        failureReason = failureReason,
        attemptCount = attemptCount,
        leaseExpiresAt = leaseExpiresAt
    )
}
