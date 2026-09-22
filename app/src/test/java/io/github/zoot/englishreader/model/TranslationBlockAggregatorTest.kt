package io.github.zoot.englishreader.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.zoot.englishreader.util.ParagraphAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块译文聚合的回归。
 *
 * 这一层的正确性只有一个判据：**聚合出的字符串与产出的中文坐标必须互相印证**。因此每个用例都
 * 用返回的坐标把译文切回来，再与期望的分块逐一比对——只断言拼接结果会漏掉坐标算错的情形，而
 * 坐标算错正是会让用户读到错位对照的那一半。
 */
class TranslationBlockAggregatorTest {

    @Test
    fun aggregate_multipleBlocksInOneParagraph_joinsWithSingleNewline() {
        val result = aggregated(
            listOf("abcdef"),
            translated(0, 0, 0, 4, "第一块"),
            translated(1, 0, 4, 6, "第二块")
        )

        assertEquals("第一块\n第二块", result.translation)
        assertEquals(listOf("第一块\n", "第二块"), slices(result, 0))
    }

    /** 原段之间必须是空行，否则按空行反切会把一个原段当成多段。 */
    @Test
    fun aggregate_blocksAcrossParagraphs_separatesParagraphsWithBlankLine() {
        val result = aggregated(
            listOf("abcdef", "ghi"),
            translated(0, 0, 0, 4, "甲一"),
            translated(1, 0, 4, 6, "甲二"),
            translated(2, 1, 0, 3, "乙一")
        )

        assertEquals("甲一\n甲二\n\n乙一", result.translation)
        assertEquals(
            listOf("甲一", "甲二", "乙一"),
            ParagraphAligner.splitParagraphs(result.translation)
                .flatMap { it.split("\n") }
        )
    }

    /**
     * 聚合结果必须仍能按空行切回与英文段落等数的段。
     *
     * 这是布局丢失或版本未知时回落到整段对照的前提；块间若用空行分隔，这条就会破。
     */
    @Test
    fun aggregate_result_splitsBackIntoOneParagraphPerSourceParagraph() {
        val paragraphs = listOf("abcdef", "ghi", "jk")
        val result = aggregated(
            paragraphs,
            translated(0, 0, 0, 3, "甲一"),
            translated(1, 0, 3, 6, "甲二"),
            translated(2, 1, 0, 3, "乙"),
            translated(3, 2, 0, 2, "丙")
        )

        assertEquals(
            paragraphs.size,
            ParagraphAligner.splitParagraphs(result.translation).size
        )
    }

    /** 同段第二块的中文 offset 必须接在前一块之后，不能从 0 重新开始。 */
    @Test
    fun aggregate_secondBlockInParagraph_continuesTranslationOffset() {
        val result = aggregated(
            listOf("abcdef"),
            translated(0, 0, 0, 4, "第一块"),
            translated(1, 0, 4, 6, "第二块")
        )

        assertEquals(listOf(0, 4), result.blocks.map { it.translationStartOffset })
    }

    /** 每个原段的中文是独立字符串，故下一段的首块从 0 起。 */
    @Test
    fun aggregate_firstBlockOfNextParagraph_restartsTranslationOffset() {
        val result = aggregated(
            listOf("abcdef", "ghi"),
            translated(0, 0, 0, 6, "甲"),
            translated(1, 1, 0, 3, "乙")
        )

        assertEquals(listOf(0, 0), result.blocks.map { it.translationStartOffset })
    }

    /** 块内换行必须原样保留，且坐标仍要覆盖它。 */
    @Test
    fun aggregate_blockTranslationContainingNewline_keepsItInsideThatBlock() {
        val result = aggregated(
            listOf("abcdef"),
            translated(0, 0, 0, 4, "首行\n次行"),
            translated(1, 0, 4, 6, "第二块")
        )

        assertEquals(listOf("首行\n次行\n", "第二块"), slices(result, 0))
    }

    /** 模型返回的空行会破坏段落分隔，必须折叠成单换行后再聚合。 */
    @Test
    fun aggregate_blockTranslationContainingBlankLine_collapsesIt() {
        val result = aggregated(
            listOf("abcdef"),
            translated(0, 0, 0, 4, "首行\n\n次行"),
            translated(1, 0, 4, 6, "第二块")
        )

        assertEquals("首行\n次行\n第二块", result.translation)
        assertEquals(
            1,
            ParagraphAligner.splitParagraphs(result.translation).size
        )
    }

    @Test
    fun aggregate_blockTranslationWithSurroundingWhitespace_isTrimmed() {
        val result = aggregated(
            listOf("abcdef"),
            translated(0, 0, 0, 6, "  甲  ")
        )

        assertEquals("甲", result.translation)
    }

    /** 坐标必须真的能把译文逐字切回来，且首尾相接不丢字。 */
    @Test
    fun aggregate_anyLayout_slicesReconstructTheParagraphTranslation() {
        val paragraphs = listOf("abcdefgh", "ijk")
        val result = aggregated(
            paragraphs,
            translated(0, 0, 0, 3, "甲一"),
            translated(1, 0, 3, 5, "甲二\n带换行"),
            translated(2, 0, 5, 8, "甲三"),
            translated(3, 1, 0, 3, "乙一")
        )

        paragraphs.indices.forEach { index ->
            val paragraphTranslation =
                TranslationBlockAggregator.paragraphTranslation(result.translation, index)
            assertEquals(paragraphTranslation, slices(result, index).joinToString(""))
        }
    }

    /** 聚合产出的坐标必须能通过解码器的连续性检查，否则发布出去的布局读不回来。 */
    @Test
    fun aggregate_producedLayout_passesCodecValidation() {
        val paragraphs = listOf("abcdef", "ghi")
        val result = aggregated(
            paragraphs,
            translated(0, 0, 0, 4, "甲一"),
            translated(1, 0, 4, 6, "甲二"),
            translated(2, 1, 0, 3, "乙一")
        )
        val codec = AppliedTranslationLayoutCodec(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        )
        val layout = AppliedTranslationLayout(
            layoutVersion = AppliedTranslationLayout.VERSION_V1,
            segmentationMode = TranslationSegmentationMode.AUTO.toStableToken(),
            articleFingerprint = "fp-article",
            blocks = result.blocks
        )

        assertEquals(layout, codec.decode(codec.encode(layout), paragraphs))
    }

    @Test
    fun aggregate_blocksInAnyOrder_areSortedByBlockIndex() {
        val result = aggregated(
            listOf("abcdef"),
            translated(1, 0, 4, 6, "第二块"),
            translated(0, 0, 0, 4, "第一块")
        )

        assertEquals("第一块\n第二块", result.translation)
        assertEquals(listOf(0, 1), result.blocks.map { it.blockIndex })
    }

    // ---- 拒绝路径 ----

    /** 覆盖有洞说明块与正文已不是一回事，写入会覆盖用户旧译文。 */
    @Test
    fun aggregate_rangesWithGap_isInvalid() {
        assertInvalid(
            listOf("abcdef"),
            translated(0, 0, 0, 3, "甲"),
            translated(1, 0, 4, 6, "乙")
        )
    }

    @Test
    fun aggregate_rangesOverlapping_isInvalid() {
        assertInvalid(
            listOf("abcdef"),
            translated(0, 0, 0, 4, "甲"),
            translated(1, 0, 3, 6, "乙")
        )
    }

    @Test
    fun aggregate_paragraphWithoutAnyBlock_isInvalid() {
        assertInvalid(
            listOf("abcdef", "ghi"),
            translated(0, 0, 0, 6, "甲")
        )
    }

    @Test
    fun aggregate_rangeBeyondEditedParagraph_isInvalid() {
        assertInvalid(
            listOf("abc"),
            translated(0, 0, 0, 6, "甲")
        )
    }

    /** 规范化后为空的译文不能占位，否则会产出长度为 0 的中文区间。 */
    @Test
    fun aggregate_blockTranslationNormalizingToEmpty_isInvalid() {
        assertInvalid(
            listOf("abcdef"),
            translated(0, 0, 0, 4, "   \n  "),
            translated(1, 0, 4, 6, "乙")
        )
    }

    @Test
    fun aggregate_emptyInputs_areInvalid() {
        assertTrue(
            TranslationBlockAggregator.aggregate(emptyList(), listOf(translated(0, 0, 0, 1, "甲")))
                is TranslationBlockAggregator.Result.Invalid
        )
        assertTrue(
            TranslationBlockAggregator.aggregate(listOf("abc"), emptyList())
                is TranslationBlockAggregator.Result.Invalid
        )
    }

    // ---- paragraphTranslation ----

    @Test
    fun paragraphTranslation_blankLineSeparatedTranslation_returnsRequestedParagraph() {
        val translation = "甲一\n甲二\n\n乙一"

        assertEquals("甲一\n甲二", TranslationBlockAggregator.paragraphTranslation(translation, 0))
        assertEquals("乙一", TranslationBlockAggregator.paragraphTranslation(translation, 1))
    }

    @Test
    fun paragraphTranslation_missingOrOutOfRange_returnsNull() {
        assertEquals(null, TranslationBlockAggregator.paragraphTranslation(null, 0))
        assertEquals(null, TranslationBlockAggregator.paragraphTranslation("甲", 1))
    }

    // ---- 辅助 ----

    private fun aggregated(
        paragraphs: List<String>,
        vararg blocks: TranslatedBlock
    ): TranslationBlockAggregator.Result.Aggregated {
        val result = TranslationBlockAggregator.aggregate(paragraphs, blocks.toList())
        return result as TranslationBlockAggregator.Result.Aggregated
    }

    private fun assertInvalid(paragraphs: List<String>, vararg blocks: TranslatedBlock) {
        assertTrue(
            TranslationBlockAggregator.aggregate(paragraphs, blocks.toList())
                is TranslationBlockAggregator.Result.Invalid
        )
    }

    /** 用返回的坐标把某原段落的译文切回块，验证坐标与字符串互相印证。 */
    private fun slices(
        result: TranslationBlockAggregator.Result.Aggregated,
        paragraphIndex: Int
    ): List<String> {
        val paragraphTranslation = requireNotNull(
            TranslationBlockAggregator.paragraphTranslation(result.translation, paragraphIndex)
        )
        return result.blocks
            .filter { it.sourceParagraphIndex == paragraphIndex }
            .sortedBy { it.blockIndex }
            .map { paragraphTranslation.substring(it.translationStartOffset, it.translationEndOffset) }
    }

    private fun translated(
        blockIndex: Int,
        sourceParagraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        translatedText: String
    ) = TranslatedBlock(
        range = TranslationBlockRange(blockIndex, sourceParagraphIndex, startOffset, endOffset),
        translatedText = translatedText
    )
}
