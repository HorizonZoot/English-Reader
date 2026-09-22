package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.categoryName

/**
 * 全文翻译 sheet 的**唯一**聚合状态。
 *
 * 与 `AiSheetState` 同一设计取向：不拆成平行的 `isOpen` / `progress` / `error` 字段，代际
 * 一旦散到多个 flow 就无法保证一致。本状态只含计数与安全分类，不含正文、译文或凭据。
 */
sealed interface WholeTranslationSheetState {

    data object Hidden : WholeTranslationSheetState

    data class Preparing(val articleId: Long) : WholeTranslationSheetState

    /**
     * 范围选择。
     *
     * [chapterOption] 为 null 表示当前文章不属于任何书，只提供「当前页面」。
     * [existing] 非 null 表示该范围已有同源可继续任务；主按钮应显示「继续翻译」而非「开始」。
     */
    data class ChoosingScope(
        val articleId: Long,
        val selected: WholeTranslationScopeChoice,
        val currentArticleOption: ScopeOption,
        val chapterOption: ScopeOption?,
        val existing: ExistingTaskSummary?,
        val isStarting: Boolean = false,
        val segmentationMode: TranslationSegmentationMode = TranslationSegmentationMode.AUTO,
        val hasTranslation: Boolean = false,
        val isPreviewing: Boolean = false,
        val previewChanged: Boolean = false
    ) : WholeTranslationSheetState

    /** 任务已存在（进行中、暂停或失败），展示进度与可用操作。 */
    data class Tracking(
        val taskId: Long,
        val scopeKey: String,
        val status: WholeTranslationTaskStatus,
        val progress: WholeTranslationProgress,
        val failureReason: TranslationFailureReason?,
        val segmentationModes: Set<TranslationSegmentationMode> = emptySet()
    ) : WholeTranslationSheetState {
        /**
         * 主按钮语义由状态推导，UI 不自己判断。
         *
         * [WholeTranslationTaskStatus.CANCELLED] 必须排在失败计数之前：它是终态，
         * [io.github.zoot.englishreader.data.dao.WholeTranslationDao.beginTask] 会拒绝它，
         * 于是「重试失败项」与「继续翻译」点下去都不会发出任何请求、也不改变任何东西——
         * 一个没有反馈的死按钮。取消后要重来只有新建任务一条路，所以这里只给关闭。
         */
        val primaryAction: WholeTranslationPrimaryAction
            get() = when {
                status == WholeTranslationTaskStatus.RUNNING -> WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND
                status == WholeTranslationTaskStatus.COMPLETED -> WholeTranslationPrimaryAction.DONE
                status == WholeTranslationTaskStatus.CANCELLED -> WholeTranslationPrimaryAction.CLOSE
                progress.hasFailures -> WholeTranslationPrimaryAction.RETRY_FAILED
                else -> WholeTranslationPrimaryAction.RESUME
            }
    }

    /** 开始前就被拒绝（无 profile、无内容等），不曾创建任务。 */
    data class Rejected(
        val articleId: Long,
        val error: AiError
    ) : WholeTranslationSheetState {
        override fun toString(): String = "Rejected(articleId=$articleId, error=${error.categoryName})"
    }

    /**
     * 当前分块方式会把这篇文章切成过多块，未创建任务。
     *
     * 与 [Rejected] 分开而不是复用某个 `AiError`：这不是 AI 侧的失败，一次请求都还没发出，
     * 而且用户有一个明确的自救动作——改用「保留原段落」。把它塞进通用错误会让文案只能说
     * 「翻译失败」，用户无从知道换个分段方式就能继续。
     */
    data class TooManyBlocks(
        val articleId: Long,
        val actualBlocks: Int,
        val maxBlocks: Int,
        val canPreserve: Boolean = true
    ) : WholeTranslationSheetState

    /**
     * 另一个范围的任务已经覆盖了这篇文章，未创建新任务。
     *
     * 典型情形：用户先对整本书起了任务，回到某一章又点「当前页面」。两个任务都会跑到发布阶段，
     * 后提交的那个用自己的分块覆盖译文与布局，而另一个已发布的布局还指向旧译文的坐标——用户看到
     * 一半新一半旧的对照，且两边的请求都已计费。
     *
     * 因此这里只给两条出路：去看既有任务，或显式取消它。**不提供**「忽略并新建」，也不由代码替
     * 用户取消——那等于让他为已经翻好的那些块白付一次钱。
     */
    data class Conflict(
        val articleId: Long,
        val existingTaskId: Long,
        val existingScopeKey: String
    ) : WholeTranslationSheetState {
        /** 冲突任务是否是整本书范围；文案据此说明「整本书翻译」还是「另一篇文章」。 */
        val existingIsBook: Boolean get() = existingScopeKey.startsWith("book:")
    }
}

enum class WholeTranslationScopeChoice { CURRENT_ARTICLE, CHAPTER }

/** 原段数、对照块数与远端请求次数上界分别展示，缓存命中不作承诺。 */
data class ScopeOption(
    val paragraphCount: Int,
    val articleCount: Int,
    val blockCount: Int = paragraphCount
)

data class ExistingTaskSummary(
    val taskId: Long,
    val progress: WholeTranslationProgress
)

enum class WholeTranslationPrimaryAction {
    RESUME,
    RETRY_FAILED,
    CONTINUE_IN_BACKGROUND,

    /** 任务成功完成，收起面板。 */
    DONE,

    /**
     * 任务已被用户取消，收起面板。
     *
     * 与 [DONE] 分开只为文案：两者动作相同（收起面板），但取消后显示「完成」会让用户以为
     * 译文已经可用。
     */
    CLOSE
}
