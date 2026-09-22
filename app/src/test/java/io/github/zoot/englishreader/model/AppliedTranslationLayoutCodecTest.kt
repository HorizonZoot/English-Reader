package io.github.zoot.englishreader.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 已发布布局编解码的回归。
 *
 * 这一层的失败模式全部是**静默的**：解出一份自相矛盾的布局，阅读页照样渲染，只是每块对到错误的
 * 译文上。因此这里对每一条拒绝理由都单独立一个用例，而不是只测往返。
 */
class AppliedTranslationLayoutCodecTest {

    private val codec = AppliedTranslationLayoutCodec(
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    )

    @Test
    fun decode_roundTrippedLayout_returnsSameCoordinates() {
        val layout = layoutOf(
            block(0, 0, 0, 4, 0, 3),
            block(1, 0, 4, 6, 3, 7),
            block(2, 1, 0, 3, 0, 5)
        )

        val decoded = codec.decode(codec.encode(layout), PARAGRAPHS)

        assertEquals(layout, decoded)
    }

    /** 块译文内部含换行时仍必须按持久区间还原，不能按分隔符反切。 */
    @Test
    fun decode_translationRangesSpanningNewlines_stayContiguous() {
        val translation = "第一块首行\n第一块次行第二块"
        val layout = layoutOf(
            block(0, 0, 0, 4, 0, 11),
            block(1, 0, 4, 6, 11, translation.length)
        )

        val decoded = requireNotNull(codec.decode(codec.encode(layout), listOf("abcdef")))

        val texts = decoded.blocks.map {
            translation.substring(it.translationStartOffset, it.translationEndOffset)
        }
        assertEquals(listOf("第一块首行\n第一块次行", "第二块"), texts)
        assertEquals(translation, texts.joinToString(""))
    }

    @Test
    fun decode_unknownLayoutVersion_returnsNull() {
        val json = codec.encode(layoutOf(block(0, 0, 0, 6, 0, 4), block(1, 1, 0, 3, 0, 2)))
            .replace(AppliedTranslationLayout.VERSION_V1, "layout-v99")

        assertNull(codec.decode(json, PARAGRAPHS))
    }

    @Test
    fun decode_unknownSegmentationMode_returnsNull() {
        val json = codec.encode(
            layoutOf(block(0, 0, 0, 6, 0, 4), block(1, 1, 0, 3, 0, 2))
        ).replace("\"preserve\"", "\"sentence\"")

        assertNull(codec.decode(json, PARAGRAPHS))
    }

    /** 源区间不铺满原段落说明正文已变，继续裁切只会错位。 */
    @Test
    fun decode_sourceRangesNotCoveringParagraph_returnsNull() {
        val json = codec.encode(layoutOf(block(0, 0, 0, 5, 0, 4), block(1, 1, 0, 3, 0, 2)))

        assertNull(codec.decode(json, PARAGRAPHS))
    }

    @Test
    fun decode_sourceRangesBeyondCurrentParagraph_returnsNull() {
        val json = codec.encode(layoutOf(block(0, 0, 0, 6, 0, 4), block(1, 1, 0, 3, 0, 2)))

        assertNull(codec.decode(json, listOf("abc", "def")))
    }

    /**
     * 同段第二块的中文 offset 仍从 0 起，是本方案最容易写错的一处。
     *
     * 渲染出来像是模型对每块都重复输出了整段开头，很难追回坐标，所以必须在解码时就拒绝。
     */
    @Test
    fun decode_secondBlockRestartingTranslationOffset_returnsNull() {
        val json = codec.encode(
            layoutOf(
                block(0, 0, 0, 4, 0, 3),
                block(1, 0, 4, 6, 0, 3),
                block(2, 1, 0, 3, 0, 5)
            )
        )

        assertNull(codec.decode(json, PARAGRAPHS))
    }

    @Test
    fun decode_translationGapWithinParagraph_returnsNull() {
        val json = codec.encode(
            layoutOf(
                block(0, 0, 0, 4, 0, 3),
                block(1, 0, 4, 6, 5, 9),
                block(2, 1, 0, 3, 0, 5)
            )
        )

        assertNull(codec.decode(json, PARAGRAPHS))
    }

    /** 每个原段落的中文都是独立字符串，因此下一段的第一块必须重新从 0 起。 */
    @Test
    fun decode_nextParagraphRestartingTranslationOffset_isAccepted() {
        val decoded = codec.decode(
            codec.encode(layoutOf(block(0, 0, 0, 6, 0, 4), block(1, 1, 0, 3, 0, 2))),
            PARAGRAPHS
        )

        assertEquals(listOf(0, 0), decoded?.blocks?.map { it.translationStartOffset })
    }

    @Test
    fun decode_malformedOrMissingJson_returnsNull() {
        assertNull(codec.decode(null, PARAGRAPHS))
        assertNull(codec.decode("", PARAGRAPHS))
        assertNull(codec.decode("{", PARAGRAPHS))
        assertNull(codec.decode("{\"layoutVersion\":\"layout-v1\"}", PARAGRAPHS))
    }

    /** 空块列表违反 init require；坏数据必须返回 null 而不是抛到阅读页。 */
    @Test
    fun decode_layoutViolatingInvariants_returnsNullInsteadOfThrowing() {
        val json = "{\"layoutVersion\":\"layout-v1\",\"segmentationMode\":\"auto\"," +
            "\"articleFingerprint\":\"fp\",\"blocks\":[]}"

        assertNull(codec.decode(json, PARAGRAPHS))
    }

    @Test
    fun blocksByParagraph_groupsBlocksKeepingOrder() {
        val layout = layoutOf(
            block(0, 0, 0, 4, 0, 3),
            block(1, 0, 4, 6, 3, 7),
            block(2, 1, 0, 3, 0, 5)
        )

        val grouped = layout.blocksByParagraph()

        assertEquals(listOf(0, 1), grouped.getValue(0).map { it.blockIndex })
        assertEquals(listOf(2), grouped.getValue(1).map { it.blockIndex })
    }

    // ---- 辅助 ----

    private fun layoutOf(vararg blocks: AppliedTranslationBlock) = AppliedTranslationLayout(
        layoutVersion = AppliedTranslationLayout.VERSION_V1,
        segmentationMode = TranslationSegmentationMode.PRESERVE.toStableToken(),
        articleFingerprint = "fp-article",
        blocks = blocks.toList()
    )

    private fun block(
        blockIndex: Int,
        sourceParagraphIndex: Int,
        sourceStart: Int,
        sourceEnd: Int,
        translationStart: Int,
        translationEnd: Int
    ) = AppliedTranslationBlock(
        blockIndex = blockIndex,
        sourceParagraphIndex = sourceParagraphIndex,
        sourceStartOffset = sourceStart,
        sourceEndOffset = sourceEnd,
        translationStartOffset = translationStart,
        translationEndOffset = translationEnd
    )

    private companion object {
        val PARAGRAPHS = listOf("abcdef", "ghi")
    }
}
