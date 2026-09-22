package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.categoryName

/** 预览和显式启动共用的源快照；不按 UI 展示计数重新规划。 */
data class WholeTranslationPreview(
    val scope: WholeTranslationScope,
    val plans: List<TranslationBlockPlan>,
    val hasTranslation: Boolean
) {
    val option: ScopeOption get() = ScopeOption(
        paragraphCount = plans.sumOf { plan -> plan.blocks.map { it.sourceParagraphIndex }.distinct().size },
        articleCount = plans.size,
        blockCount = plans.sumOf { it.blocks.size }
    )

    override fun toString(): String = "WholeTranslationPreview(articleCount=${plans.size}, content=[REDACTED])"
}

sealed interface WholeTranslationPreviewResult {
    data class Ready(val preview: WholeTranslationPreview) : WholeTranslationPreviewResult
    data class TooManyBlocks(val articleId: Long, val actualBlocks: Int, val maxBlocks: Int) : WholeTranslationPreviewResult
    data class Rejected(val error: AiError) : WholeTranslationPreviewResult {
        override fun toString(): String = "Rejected(error=${error.categoryName})"
    }
}
