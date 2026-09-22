package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.ParagraphAligner

/** 一个已成功翻译的块：它的源坐标与模型返回的译文。 */
data class TranslatedBlock(
    val range: TranslationBlockRange,
    val translatedText: String
) {
    /** 译文是用户内容，不进日志。 */
    override fun toString(): String = "TranslatedBlock(range=$range, translatedText=[REDACTED])"
}

/**
 * 把逐块译文聚合成 `ArticleEntity.translation`，并同时产出每块的中文坐标。
 *
 * 聚合与产出坐标必须是**同一次**计算。分成「先拼字符串、再另算 offset」两步，两边对规范化、
 * 分隔符和空白的处理只要差一个字符，整段对照就会错位一格，而错位不抛异常——用户只会以为翻译
 * 质量变差。这正是 [TranslationOutputAssembler] 已经为段落级踩过的坑，块级把它放大了一层。
 *
 * 两条分隔符约定：
 * - **块间单换行**，且这个换行算作**前一块**区间的最后一个字符。与源侧一致（`lineCuts` 也把
 *   换行留在前一块），于是两侧的区间都恰好铺满，[AppliedTranslationLayoutCodec] 的连续性检查
 *   才能保持严格——它挡的是「同段第二块的中文 offset 还从 0 起」这类静默回归。渲染时需要
 *   `trimEnd` 掉这个分隔符。
 * - **原段间空行**，与 `ParagraphAligner` 的段落分隔符定义一致。因此即使布局丢失或版本未知，
 *   `articles.translation` 仍能按空行切回与英文段落一一对应的段数，回落到整段对照仍然正确。
 *   块间之所以只能用单换行而不能用空行，原因就在这里：空行会让一个原段落被切成多段。
 */
object TranslationBlockAggregator {

    /** 块间分隔符。单换行不是 `ParagraphAligner` 的段落分隔符，故不会破坏原段落边界。 */
    private const val BLOCK_SEPARATOR = "\n"

    /** 原段间分隔符，与 `TranslationOutputAssembler` 保持同一形式。 */
    private const val PARAGRAPH_SEPARATOR = "\n\n"

    sealed interface Result {
        data class Aggregated(
            val translation: String,
            val blocks: List<AppliedTranslationBlock>
        ) : Result {
            /** 译文是用户内容，不进日志。 */
            override fun toString(): String =
                "Aggregated(translation=[REDACTED], blockCount=${blocks.size})"
        }

        /**
         * 块与当前正文不再自洽，或某块译文规范化后为空。
         *
         * 返回类型而非抛异常：调用方是 materialization 事务，它要把这件事映射成一个明确的
         * 「不写入、保留旧译文」结果，而不是靠捕获异常来推断。
         */
        data object Invalid : Result
    }

    /**
     * @param paragraphs 当前正文按 `ParagraphAligner.splitParagraphs` 的分段结果
     * @param blocks 全部已成功的块，顺序不限；内部按块序号排序
     */
    fun aggregate(paragraphs: List<String>, blocks: List<TranslatedBlock>): Result {
        if (paragraphs.isEmpty() || blocks.isEmpty()) return Result.Invalid

        val ordered = blocks.sortedBy { it.range.blockIndex }
        // 先校验覆盖，再拼字符串：覆盖不完整时任何拼接结果都是错的，而错的译文一旦写进
        // articles.translation 就覆盖了用户原有的那一份。
        if (!TranslationBlockCoverage.isComplete(paragraphs, ordered.map { it.range })) {
            return Result.Invalid
        }

        val grouped = ordered.groupBy { it.range.sourceParagraphIndex }
        val applied = mutableListOf<AppliedTranslationBlock>()
        val paragraphTranslations = mutableListOf<String>()

        for (paragraphIndex in paragraphs.indices) {
            // 覆盖校验已保证每个原段落都有块，这里仍然判空是因为返回 Invalid 比 !! 崩溃便宜。
            val group = grouped[paragraphIndex] ?: return Result.Invalid
            val builder = StringBuilder()
            group.forEachIndexed { position, block ->
                val normalized = TranslationOutputAssembler.normalizeParagraph(block.translatedText)
                // 空译文不能占位：拼进去会让这一块的中文区间长度为 0，而
                // AppliedTranslationBlock 要求区间非空，等于把问题推迟到构造时才炸。
                if (normalized.isEmpty()) return Result.Invalid
                val start = builder.length
                builder.append(normalized)
                if (position < group.lastIndex) builder.append(BLOCK_SEPARATOR)
                applied += AppliedTranslationBlock(
                    blockIndex = block.range.blockIndex,
                    sourceParagraphIndex = paragraphIndex,
                    sourceStartOffset = block.range.startOffset,
                    sourceEndOffset = block.range.endOffset,
                    translationStartOffset = start,
                    translationEndOffset = builder.length
                )
            }
            paragraphTranslations += builder.toString()
        }

        return Result.Aggregated(
            translation = paragraphTranslations.joinToString(PARAGRAPH_SEPARATOR),
            blocks = applied
        )
    }

    /**
     * 从聚合后的整篇译文里取出某个原段落的中文串。
     *
     * 布局里的中文 offset 是**原段落内**的，而 `articles.translation` 是整篇。阅读层必须先按空行
     * 取到本段的中文，再用 offset 裁块；直接拿整篇去裁会整体偏移。这个换算只允许有一个实现。
     */
    fun paragraphTranslation(translation: String?, paragraphIndex: Int): String? =
        translation?.let { ParagraphAligner.splitParagraphs(it).getOrNull(paragraphIndex) }
}
