package io.github.zoot.englishreader.model

import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException

/**
 * 已发布对照布局中的一个块。
 *
 * 两套坐标同时存在，且都必不可少：
 * - [sourceParagraphIndex] / [sourceStartOffset] / [sourceEndOffset] 指向**原文**，供阅读页裁出
 *   这一块要显示的英文，并把点击位置换算回原段落。
 * - [translationStartOffset] / [translationEndOffset] 指向**该原段落聚合后的完整中文串**，供裁出
 *   对应的译文。
 *
 * 中文坐标必须持久化，不能在读取时按换行反切。块译文内部本来就可能含换行（分点、引文），按换行
 * 反切会把一块拆成两块，此后同段落内所有块的中文整体错位一格——而错位不抛异常，用户只会以为
 * 翻译质量差。这正是 `TranslationOutputAssembler` 已经为段落级踩过的那个坑。
 */
@JsonClass(generateAdapter = true)
data class AppliedTranslationBlock(
    val blockIndex: Int,
    val sourceParagraphIndex: Int,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
    val translationStartOffset: Int,
    val translationEndOffset: Int
) {
    init {
        require(blockIndex >= 0) { "blockIndex must not be negative" }
        require(sourceParagraphIndex >= 0) { "sourceParagraphIndex must not be negative" }
        require(sourceStartOffset >= 0) { "sourceStartOffset must not be negative" }
        require(sourceEndOffset > sourceStartOffset) { "source range must not be empty" }
        require(translationStartOffset >= 0) { "translationStartOffset must not be negative" }
        require(translationEndOffset > translationStartOffset) { "translation range must not be empty" }
    }

    val sourceRange: TranslationBlockRange
        get() = TranslationBlockRange(blockIndex, sourceParagraphIndex, sourceStartOffset, sourceEndOffset)
}

/**
 * 一篇文章当前**已发布**的对照布局。
 *
 * 与 [TranslationBlockPlan] 分开是有意的：计划描述「准备怎么翻」，布局描述「已经翻好的译文该
 * 怎么对照显示」。任务可以被删除、重建或取消，而已发布的对照必须继续可读，所以布局自带正文与
 * 译文指纹，不依赖任何任务行存在。
 *
 * [layoutVersion] 是解释这些 offset 的版本键。未知版本一律回落到整段展示，不猜坐标。
 */
@JsonClass(generateAdapter = true)
data class AppliedTranslationLayout(
    val layoutVersion: String,
    val segmentationMode: String,
    val articleFingerprint: String,
    val blocks: List<AppliedTranslationBlock>
) {
    init {
        require(articleFingerprint.isNotBlank()) { "articleFingerprint must not be blank" }
        require(blocks.isNotEmpty()) { "layout must contain at least one block" }
        blocks.forEachIndexed { position, block ->
            require(block.blockIndex == position) {
                "blockIndex must be sequential from 0: expected $position, got ${block.blockIndex}"
            }
        }
    }

    val mode: TranslationSegmentationMode?
        get() = TranslationSegmentationMode.fromStableToken(segmentationMode)

    /** 按原段落分组，保持块序。阅读层按原段落渲染，所以这是它要的形状。 */
    fun blocksByParagraph(): Map<Int, List<AppliedTranslationBlock>> =
        blocks.groupBy { it.sourceParagraphIndex }

    /** 验证中文覆盖与分隔符，而不把模型输出按单换行重新猜块。 */
    fun matchesText(paragraphs: List<String>, translations: List<String>): Boolean {
        if (layoutVersion != VERSION_V1 || mode == null || paragraphs.size != translations.size ||
            !TranslationBlockCoverage.isComplete(paragraphs, blocks.map { it.sourceRange })
        ) return false
        return blocksByParagraph().all { (index, group) ->
            val text = translations[index]
            var end = 0
            group.all { block ->
                val valid = block.translationStartOffset == end && block.translationEndOffset <= text.length &&
                    text.substring(block.translationStartOffset, block.translationEndOffset).isNotBlank() &&
                    (block == group.last() || text[block.translationEndOffset - 1] == '\n')
                end = block.translationEndOffset
                valid
            } && end == text.length
        }
    }

    companion object {
        /** 首个布局版本：原段落内字符区间 + 段落译文内字符区间。 */
        const val VERSION_V1: String = "layout-v1"
    }
}

/**
 * 布局的 JSON 编解码。
 *
 * 存成结构化文本而不是再开三张表：布局是「跟着这一版译文一起发布、整体替换」的单一值，拆成行
 * 之后每次发布都要先删后插，中途失败就会留下半份布局；而半份布局与「没有布局」不同，它会被当成
 * 有效的对照去裁切，结果是部分段落错位。存成一个值让发布天然是原子替换。
 *
 * 解析失败、版本未知或范围不自洽时返回 null，调用方据此回落到整段展示。这里**不抛异常**：
 * 一份坏掉的布局不应该让用户连文章都打不开。
 */
class AppliedTranslationLayoutCodec(moshi: Moshi) {

    private val adapter = moshi.adapter(AppliedTranslationLayout::class.java)

    fun encode(layout: AppliedTranslationLayout): String = adapter.toJson(layout)

    /**
     * @param paragraphs 当前正文的分段结果，用于校验源坐标仍然落在正文内
     * @return 自洽且版本已知的布局；否则 null
     */
    fun decode(json: String?, paragraphs: List<String>): AppliedTranslationLayout? {
        val layout = try {
            json?.let { adapter.fromJson(it) }
        } catch (_: JsonDataException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // data class 的 init require 失败也走这里：坏数据不该让阅读页崩溃。
            null
        } ?: return null

        if (layout.layoutVersion != AppliedTranslationLayout.VERSION_V1) return null
        if (layout.mode == null) return null
        if (!TranslationBlockCoverage.isComplete(paragraphs, layout.blocks.map { it.sourceRange })) return null
        if (!translationRangesContiguous(layout)) return null
        return layout
    }

    /**
     * 同一原段落内的中文区间必须从 0 起首尾相接。
     *
     * 段落译文是把该段各块的译文按顺序拼出来的，所以第二块的中文不可能还从 0 开始。这条检查专门
     * 挡「同段多个块的中文 offset 都写成 0」这类回归——它在渲染时表现为每块都显示整段译文的开头，
     * 看起来像模型重复输出，很难追到坐标上。
     */
    private fun translationRangesContiguous(layout: AppliedTranslationLayout): Boolean =
        layout.blocksByParagraph().values.all { blocks ->
            var expectedStart = 0
            blocks.sortedBy { it.blockIndex }.all { block ->
                (block.translationStartOffset == expectedStart).also {
                    expectedStart = block.translationEndOffset
                }
            }
        }
}
