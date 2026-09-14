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
        val existing: ExistingTaskSummary?
    ) : WholeTranslationSheetState

    /** 任务已存在（进行中、暂停或失败），展示进度与可用操作。 */
    data class Tracking(
        val taskId: Long,
        val scopeKey: String,
        val status: WholeTranslationTaskStatus,
        val progress: WholeTranslationProgress,
        val failureReason: TranslationFailureReason?
    ) : WholeTranslationSheetState {
        /** 主按钮语义由状态推导，UI 不自己判断。 */
        val primaryAction: WholeTranslationPrimaryAction
            get() = when {
                status == WholeTranslationTaskStatus.RUNNING -> WholeTranslationPrimaryAction.CONTINUE_IN_BACKGROUND
                status == WholeTranslationTaskStatus.COMPLETED -> WholeTranslationPrimaryAction.DONE
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
}

enum class WholeTranslationScopeChoice { CURRENT_ARTICLE, CHAPTER }

/** 一个范围选项的展示数据：段落数就是预计请求数（每段一个请求）。 */
data class ScopeOption(
    val paragraphCount: Int,
    val articleCount: Int
)

data class ExistingTaskSummary(
    val taskId: Long,
    val progress: WholeTranslationProgress
)

enum class WholeTranslationPrimaryAction {
    RESUME,
    RETRY_FAILED,
    CONTINUE_IN_BACKGROUND,
    DONE
}
