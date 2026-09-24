package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.ai.AiError

/** 单个段落的持久化翻译状态。 */
enum class TranslationSegmentStatus {
    UNTRANSLATED,
    TRANSLATING,
    TRANSLATED,
    FAILED;

    /**
     * @return 持久化用的稳定 token
     *
     * 与 `ExplanationType` 同样的理由：枚举名是 Kotlin 符号，重构就会变；而这个值要落进
     * Room 的 status 列，改了它等于让所有在途任务的状态变成无法识别的字符串。
     */
    fun toStableToken(): String = when (this) {
        UNTRANSLATED -> "untranslated"
        TRANSLATING -> "translating"
        TRANSLATED -> "translated"
        FAILED -> "failed"
    }

    companion object {
        /** 未知 token 读作 [UNTRANSLATED]：宁可重做一次，也不要让恢复流程崩在解析上。 */
        fun fromStableToken(token: String?): TranslationSegmentStatus =
            entries.firstOrNull { it.toStableToken() == token } ?: UNTRANSLATED
    }
}

/**
 * 失败的处理类别。
 *
 * 刻意**不是**布尔的「可否重试」。三分法回答的是两个不同的问题，合成一个布尔会立刻出错：
 *
 * - [RETRYABLE]：这次不行，下次可能行（超时、离线、限流、5xx）。继续处理其余段落。
 * - [PERMANENT]：这一段本身就不行（段落超长、请求体过大）。跳过它，继续其余段落，
 *   重试也不再包含它——重试只会原样再撞一次，白花钱。
 * - [FATAL]：整个任务的前提坏了（没有 profile、凭据缺失、401、endpoint 错误）。
 *   必须**立刻中止**整个任务。
 *
 * 关键区分在 [PERMANENT] 与 [FATAL] 之间：两者都「重试无用」，但影响范围完全不同。
 * 一个超长段落不代表其余段落也超长，把它判成 FATAL 会让用户因为一段长引文就整章翻不了；
 * 反过来，把 401 判成 PERMANENT 会让任务对着无效凭据把剩下 300 段全试一遍——每一次都是
 * 一个潜在计费请求。
 */
enum class TranslationFailureCategory {
    RETRYABLE,
    PERMANENT,
    FATAL;

    /** 是否应当中止整个任务。 */
    val abortsTask: Boolean get() = this == FATAL

    /** 「重试失败项」是否应当包含这一类。 */
    val isRetryable: Boolean get() = this == RETRYABLE
}

/**
 * 段落失败的持久化原因。
 *
 * 只保留安全分类，不保留 `AiError` 的数值元数据、原始异常或响应内容：这一行会被写进 Room
 * 并可能出现在诊断里。
 */
enum class TranslationFailureReason(
    val category: TranslationFailureCategory
) {
    /** 网络不可用、DNS、TLS、超时、限流、服务端 5xx。 */
    TRANSIENT_NETWORK(TranslationFailureCategory.RETRYABLE),

    /** 模型没返回内容或返回了无法解析的响应。 */
    PROVIDER_RESPONSE(TranslationFailureCategory.RETRYABLE),

    /** 这一段太长，超出输入预算或远端请求体上限。 */
    PARAGRAPH_TOO_LONG(TranslationFailureCategory.PERMANENT),

    /** profile、凭据、endpoint 或鉴权层面的问题，整个任务都无法继续。 */
    CONFIGURATION(TranslationFailureCategory.FATAL),

    /** 未归类失败。按可重试处理，但不中止任务。 */
    UNKNOWN(TranslationFailureCategory.RETRYABLE);

    fun toStableToken(): String = when (this) {
        TRANSIENT_NETWORK -> "transient_network"
        PROVIDER_RESPONSE -> "provider_response"
        PARAGRAPH_TOO_LONG -> "paragraph_too_long"
        CONFIGURATION -> "configuration"
        UNKNOWN -> "unknown"
    }

    companion object {
        fun fromStableToken(token: String?): TranslationFailureReason? =
            token?.let { value -> entries.firstOrNull { it.toStableToken() == value } }

        /**
         * 把 typed AI 失败映射为可持久化的原因。
         *
         * 映射按「用户能做什么」而非 HTTP 语义分组。两处值得说明：
         *
         * - [AiError.PayloadTooLarge]（413）归入 [PARAGRAPH_TOO_LONG] 而不是配置问题：
         *   413 是这一段的体积撞上了远端上限，换 profile 不解决，跳过这一段才对。
         * - [AiError.Unknown] 归入可重试而非 FATAL：它是兜底分类，既可能是一次性抖动，
         *   也可能是系统性故障。判成 FATAL 会让一次偶发抖动废掉整个任务的剩余进度，
         *   而判成可重试最坏只是让用户多按一次重试。
         */
        fun from(error: AiError): TranslationFailureReason = when (error) {
            AiError.NoActiveProfile,
            AiError.ProfileNotFound,
            AiError.CredentialMissing,
            AiError.CredentialStorageUnavailable,
            AiError.InvalidEndpoint,
            AiError.ModelUnavailable,
            is AiError.HttpAuth,
            is AiError.HttpNotFound -> CONFIGURATION

            is AiError.InputTooLong,
            is AiError.PayloadTooLarge -> PARAGRAPH_TOO_LONG

            AiError.Offline,
            AiError.DnsFailure,
            AiError.TlsFailure,
            is AiError.Timeout,
            is AiError.RequestTimeout,
            is AiError.RateLimited,
            is AiError.Server -> TRANSIENT_NETWORK

            AiError.NoContent,
            AiError.ResponseTruncated,
            AiError.MalformedResponse -> PROVIDER_RESPONSE

            is AiError.UnexpectedHttp,
            AiError.Unknown -> UNKNOWN
        }
    }
}

/** 任务整体状态。 */
enum class WholeTranslationTaskStatus {
    /** 已创建快照，尚未或已停止处理，可继续。 */
    PAUSED,
    RUNNING,

    /** 全部段落成功且译文已事务性写入目标 article。 */
    COMPLETED,

    /** 因 FATAL 失败中止，需要用户先修配置。 */
    FAILED,

    /** 用户显式取消。 */
    CANCELLED;

    /** 终态不再接受段落处理。 */
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED

    fun toStableToken(): String = when (this) {
        PAUSED -> "paused"
        RUNNING -> "running"
        COMPLETED -> "completed"
        FAILED -> "failed"
        CANCELLED -> "cancelled"
    }

    companion object {
        fun fromStableToken(token: String?): WholeTranslationTaskStatus =
            entries.firstOrNull { it.toStableToken() == token } ?: PAUSED
    }
}

/**
 * 一个段落 checkpoint 的当前状态。
 *
 * [leaseExpiresAt] 只在 [TranslationSegmentStatus.TRANSLATING] 下有意义：进程被杀时段落会
 * 永久停在 TRANSLATING，没有 lease 就再也没人处理它，任务卡死在 99%。
 */
data class TranslationSegment(
    val articleId: Long,
    val paragraphIndex: Int,
    val sourceFingerprint: String,
    val status: TranslationSegmentStatus,
    val translatedText: String? = null,
    val failureReason: TranslationFailureReason? = null,
    val attemptCount: Int = 0,
    val leaseExpiresAt: Long? = null
) {
    init {
        require(articleId > 0) { "articleId must be positive" }
        require(paragraphIndex >= 0) { "paragraphIndex must not be negative" }
        require(attemptCount >= 0) { "attemptCount must not be negative" }
        // TRANSLATED 必须真的带着译文：允许「成功但译文为空」会让最终的事务性写入
        // 把空字符串当成译文提交，用户看到的是一段空白对照，且无从判断是哪里丢的。
        if (status == TranslationSegmentStatus.TRANSLATED) {
            require(!translatedText.isNullOrBlank()) {
                "TRANSLATED segment must carry non-blank translated text"
            }
        }
    }

    /** lease 是否已过期；非 TRANSLATING 状态一律为 false。 */
    fun isLeaseExpired(now: Long): Boolean =
        status == TranslationSegmentStatus.TRANSLATING && (leaseExpiresAt ?: 0L) <= now

    /** 译文是用户内容，不进日志。 */
    override fun toString(): String =
        "TranslationSegment(articleId=$articleId, paragraphIndex=$paragraphIndex, " +
            "status=${status.toStableToken()}, translatedText=${translatedText?.let { "[REDACTED]" }}, " +
            "failureReason=${failureReason?.toStableToken()}, attemptCount=$attemptCount)"
}

/** 本轮处理要覆盖哪些段落。 */
enum class TranslationProcessingMode {
    /** 继续未完成项：UNTRANSLATED 与 lease 过期的 TRANSLATING。 */
    RESUME,

    /**
     * 重试失败项。
     *
     * 是 [RESUME] 的**超集**：除可重试的 FAILED 外，也包含尚未处理的段落。任务因 FATAL
     * 中止时通常两者并存，只挑 FAILED 会让那些还没轮到的段落永远留在未翻译状态，用户反复
     * 按重试也无法让任务完成。真正不可违反的约束是「不重跑 TRANSLATED」，而它由
     * [isEligibleFor] 对 TRANSLATED 一律返回 false 来保证。
     */
    RETRY_FAILED
}

/**
 * 判断某段落在本轮是否需要处理。
 *
 * [TranslationSegmentStatus.TRANSLATED] 在任何模式下都返回 false——这是整个可恢复语义的
 * 核心不变量：已成功的段落绝不重新请求，否则「恢复」就变成了重复付费。
 */
fun TranslationSegment.isEligibleFor(
    mode: TranslationProcessingMode,
    now: Long
): Boolean = when (status) {
    TranslationSegmentStatus.TRANSLATED -> false
    TranslationSegmentStatus.UNTRANSLATED -> true
    TranslationSegmentStatus.TRANSLATING -> isLeaseExpired(now)
    TranslationSegmentStatus.FAILED ->
        mode == TranslationProcessingMode.RETRY_FAILED &&
            failureReason?.category?.isRetryable == true
}

/** 面向 UI 的安全进度快照，只含计数，不含正文。 */
data class WholeTranslationProgress(
    val total: Int,
    val translated: Int,
    val translating: Int,
    val failed: Int,
    val untranslated: Int
) {
    init {
        require(total >= 0) { "total must not be negative" }
    }

    /** 全部段落均已成功——最终事务性写入的前置条件。 */
    val isFullyTranslated: Boolean get() = total > 0 && translated == total

    /** 存在可重试的失败项时，UI 显示「重试失败项」。 */
    val hasFailures: Boolean get() = failed > 0

    companion object {
        fun from(segments: List<TranslationSegment>): WholeTranslationProgress =
            WholeTranslationProgress(
                total = segments.size,
                translated = segments.count { it.status == TranslationSegmentStatus.TRANSLATED },
                translating = segments.count { it.status == TranslationSegmentStatus.TRANSLATING },
                failed = segments.count { it.status == TranslationSegmentStatus.FAILED },
                untranslated = segments.count { it.status == TranslationSegmentStatus.UNTRANSLATED }
            )
    }
}
