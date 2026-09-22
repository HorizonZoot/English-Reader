package io.github.zoot.englishreader.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次全文翻译任务。
 *
 * 任务是**可恢复下载**语义的载体：它在创建时固定目标范围与源快照，此后进程被杀、应用重启
 * 或部分段落失败都只影响未完成的段落，已成功的段落不再重新请求。
 *
 * [scopeKey] 来自 `WholeTranslationScope.scopeKey`，用于识别「同源任务」——用户重新进入
 * 阅读页时据此找回未完成的任务，而不是又建一个并把请求量翻倍。
 */
@Entity(
    tableName = "whole_translation_tasks",
    indices = [Index(value = ["scopeKey"])]
)
data class WholeTranslationTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val taskId: Long = 0,

    /** 范围标识（`article:<id>` 或 `book:<id>`），不含正文。 */
    val scopeKey: String,

    /** 章节范围对应的书；单篇范围为 null。 */
    val bookId: Long? = null,

    /** 任务状态的稳定 token，见 `WholeTranslationTaskStatus.toStableToken`。 */
    val status: String,

    /**
     * 导致任务中止的失败原因 token；非中止状态为 null。
     *
     * 只存安全分类，不存 HTTP 状态码、异常文本或响应体。
     */
    val failureReason: String? = null,

    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 任务范围内的一篇目标 article 及其**创建时**的正文指纹。
 *
 * 单独成表而非把指纹冗余到每个段落上，有两个理由：
 *
 * 1. 章节范围的 article 顺序只存在于内存中的 `WholeTranslationScope.articleIds` 里。进程重启
 *    后必须能重建这个顺序，否则无法判断「预计请求数」与处理顺序，恢复出来的任务和用户当初
 *    看到的不是同一个。[ordinal] 就是这份顺序的持久化。
 * 2. 指纹是**每篇文章一个**的属性。冗余到 N 个段落行上，只要有一次写入漏了某几行，就会出现
 *    同一篇文章的段落对指纹各执一词，而这种分叉没有任何断言能发现。
 *
 * 刻意**不设**对 `articles` 的外键：article 被删除时，CASCADE 会让这些行连带消失，任务于是
 * 静默缩小一篇，用户看到进度莫名变化却没有任何提示。保留悬空行反而让「目标文章已不存在」
 * 成为 materialization 可以显式检测并拒绝的条件。
 */
@Entity(
    tableName = "translation_task_articles",
    primaryKeys = ["taskId", "articleId"],
    foreignKeys = [
        ForeignKey(
            entity = WholeTranslationTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId"])]
)
data class TranslationTaskArticleEntity(
    val taskId: Long,
    val articleId: Long,

    /** 该 article 在任务范围内的处理顺序，从 0 起。 */
    val ordinal: Int,

    /** 创建任务时 `ArticleEntity.content` 的指纹，见 `TranslationFingerprint.forArticle`。 */
    val articleFingerprint: String,

    /**
     * 本目标使用的分块方式 token，见 `TranslationSegmentationMode.toStableToken`。
     *
     * 存在**每个目标**上而不是任务上：整本书翻译是一个任务，但分块方式是按文章保存的偏好，
     * 一本书里各章完全可能不同。挂在任务上就只能取一个值，于是其余章节的块会按别人的方式
     * 被解释，译文整体错位。
     */
    @ColumnInfo(defaultValue = "'preserve'")
    val segmentationMode: String = "preserve",

    /**
     * 范围解释的版本 token，见 `TranslationPlannerVersion`。
     *
     * v7 之前的行没有 offset，只有空行段落序号，默认 `legacy-v1` 让它们继续按旧语义读完。
     * 未知版本必须在 profile、凭据与网络之前拒绝，不能猜一套坐标去发付费请求。
     */
    @ColumnInfo(defaultValue = "'legacy-v1'")
    val plannerVersion: String = "legacy-v1"
)

/**
 * 单个段落的持久化 checkpoint。
 *
 * 复合主键 `(taskId, articleId, paragraphIndex)` 直接表达身份，不额外加自增 id：段落身份本身
 * 就是这三元组，多一个代理键只会让「同一段落被登记两次」变成可能。
 *
 * [leaseExpiresAt] 是可恢复语义的另一半。进程在段落处理中途被杀时，该行会永久停在
 * `translating`；没有 lease 就再也没有任何流程会碰它，任务永远差最后几段无法完成。lease 过期
 * 后段落重新变为可领取，代价最多是重复一次那一段的请求。
 *
 * 同样不设对 `articles` 的外键，理由见 [TranslationTaskArticleEntity]。
 */
@Entity(
    tableName = "translation_segments",
    primaryKeys = ["taskId", "articleId", "paragraphIndex"],
    foreignKeys = [
        ForeignKey(
            entity = WholeTranslationTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId", "status"])]
)
data class TranslationSegmentEntity(
    val taskId: Long,
    val articleId: Long,

    /**
     * 该 article 内这一行的序号，含义由本目标的 `plannerVersion` 决定：
     *
     * - `legacy-v1`：`ParagraphAligner.splitParagraphs` 的空行段落序号。
     * - `block-v1`：对照块序号，原段落由 [sourceParagraphIndex] 单独给出。
     *
     * 列名保持 `paragraphIndex` 不变，因为它是主键的一部分：改名就得重建表并重新编号所有在途
     * 任务的行，而用户已经为那些段落付过费。语义分派必须按版本做，不能看这个名字。
     */
    val paragraphIndex: Int,

    /**
     * 创建任务时这一行源文本的指纹。
     *
     * `legacy-v1` 用 `TranslationFingerprint.forParagraph`，`block-v1` 用 `forBlock`。两者前缀
     * 不同，因此同一段文本在两种语义下的指纹不会相等——这正是要的：一份按块切出来的 checkpoint
     * 不应当被当作整段的 checkpoint 接受。
     */
    val sourceFingerprint: String,

    /** 段落状态的稳定 token，见 `TranslationSegmentStatus.toStableToken`。 */
    val status: String,

    /** 成功时的段落译文；其余状态为 null。 */
    val translatedText: String? = null,

    /** 失败原因的稳定 token，见 `TranslationFailureReason.toStableToken`。 */
    val failureReason: String? = null,

    val attemptCount: Int = 0,

    /** `translating` 状态下的 lease 到期时间；其余状态为 null。 */
    val leaseExpiresAt: Long? = null,

    val updatedAt: Long,

    /**
     * `block-v1` 下这一块所属的原空行段落序号；`legacy-v1` 的行为 null。
     *
     * 与 [paragraphIndex] 分开存是整个分块方案的关键：阅读定位、句子身份和朗读全都以原段落为
     * 坐标系，块只是叠在上面的展示与请求单位。让一个列同时充当两者，就必然有一方被错误解释。
     *
     * 三个 `source*` 列放在最后，与 `MIGRATION_6_7` 的 `ALTER TABLE ADD COLUMN` 追加位置一致：
     * SQLite 只能把新列加在末尾，实体顺序跟着它，升级得到的库与全新安装的库物理列序才相同。
     */
    val sourceParagraphIndex: Int? = null,

    /** `block-v1` 下这一块在原段落内的起始 UTF-16 下标；`legacy-v1` 为 null。 */
    val sourceStartOffset: Int? = null,

    /** `block-v1` 下这一块在原段落内的结束下标（半开区间）；`legacy-v1` 为 null。 */
    val sourceEndOffset: Int? = null
)
