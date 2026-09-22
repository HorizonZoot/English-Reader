package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.ParagraphAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读投影的坐标映射回归。
 *
 * 这一层的每个缺陷都是**静默**的：块照样渲染，只是英文对到别人的中文，或者点击落在错误的句子上。
 * 所以这里的断言基本都在比对「切出来的文本」和「换算回去的偏移」，而不只是块数。
 *
 * 两条最关键的不变量：
 * - 正文块拼回去必须与原段落逐字相同（不丢字、不重复）。
 * - 中文必须按持久 offset 切，不能按换行反切——块译文内部可以含换行。
 */
class ReadingTranslationProjectionTest {

    // ---- 兼容路径：没有布局 ----

    @Test
    fun project_withoutLayout_keepsWholeParagraphBehaviour() {
        val paragraphs = ParagraphAligner.splitParagraphs(CONTENT)

        val blocks = ReadingTranslationProjection.project(
            paragraphs = paragraphs,
            translation = "第一段译文\n\n第二段译文",
            layout = null,
            showTranslation = true
        )

        assertEquals(
            listOf(
                ReadingProjectedKind.ORIGINAL to "First one. Second one.",
                ReadingProjectedKind.TRANSLATION to "第一段译文",
                ReadingProjectedKind.ORIGINAL to "Third one.",
                ReadingProjectedKind.TRANSLATION to "第二段译文"
            ),
            blocks.map { it.kind to it.text }
        )
    }

    /** 旧译文段数少于正文段数时，缺的那几段只显示英文，不把后面的中文顶上来。 */
    @Test
    fun project_withoutLayoutAndFewerTranslationParagraphs_leavesLaterParagraphsUntranslated() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = ParagraphAligner.splitParagraphs(CONTENT),
            translation = "只有第一段",
            layout = null,
            showTranslation = true
        )

        assertEquals(
            listOf(
                ReadingProjectedKind.ORIGINAL to "First one. Second one.",
                ReadingProjectedKind.TRANSLATION to "只有第一段",
                ReadingProjectedKind.ORIGINAL to "Third one."
            ),
            blocks.map { it.kind to it.text }
        )
    }

    @Test
    fun project_withoutTranslation_projectsOriginalOnly() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = ParagraphAligner.splitParagraphs(CONTENT),
            translation = null,
            layout = null,
            showTranslation = true
        )

        assertTrue(blocks.all { it.kind == ReadingProjectedKind.ORIGINAL })
        assertEquals(2, blocks.size)
    }

    /** 隐藏译文时恢复连续英文显示，且正文块身份与源坐标不变——朗读和句子身份不受影响。 */
    @Test
    fun project_translationHidden_keepsOriginalBlocksAndTheirSourceCoordinates() {
        val paragraphs = listOf(WRAPPED)
        val layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length))

        val shown = ReadingTranslationProjection.project(paragraphs, TRANSLATION, layout, true)
        val hidden = ReadingTranslationProjection.project(paragraphs, TRANSLATION, layout, false)

        assertEquals(1, hidden.size)
        assertEquals(WRAPPED, hidden.single().text)
        assertEquals(0, hidden.single().sourceStartOffset)
        assertEquals(WRAPPED.length, hidden.single().sourceEndOffset)
        assertEquals(2, shown.count { it.kind == ReadingProjectedKind.ORIGINAL })
    }

    // ---- 分块路径 ----

    @Test
    fun project_withLayout_alternatesBlockAndItsOwnTranslation() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        assertEquals(
            listOf(
                ReadingProjectedKind.ORIGINAL to "First line\n",
                ReadingProjectedKind.TRANSLATION to "第一行",
                ReadingProjectedKind.ORIGINAL to "second line.",
                ReadingProjectedKind.TRANSLATION to "第二行"
            ),
            blocks.map { it.kind to it.text }
        )
    }

    /** 正文块必须逐字铺满原段落：丢字或重复都只会表现为「译文对不上」。 */
    @Test
    fun project_withLayout_originalBlocksRebuildTheSourceParagraphExactly() {
        val paragraphs = listOf(WRAPPED)

        val blocks = ReadingTranslationProjection.project(
            paragraphs = paragraphs,
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        val rebuilt = blocks.filter { it.kind == ReadingProjectedKind.ORIGINAL }.joinToString("") { it.text }
        assertEquals(WRAPPED, rebuilt)
    }

    /**
     * 块译文内部含换行时必须按持久 offset 切。
     *
     * 按换行反切会把第一块的中文切成两段，此后同段落所有块的中文整体错位一格。
     */
    @Test
    fun project_translationContainingNewline_slicesByOffsetNotByNewline() {
        val translation = "第一行上\n第一行下\n第二行"

        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = translation,
            layout = layoutOf(
                block(0, 0, 0, 11, 0, 10),
                block(1, 0, 11, WRAPPED.length, 10, translation.length)
            ),
            showTranslation = true
        )

        assertEquals(
            listOf("第一行上\n第一行下", "第二行"),
            blocks.filter { it.kind == ReadingProjectedKind.TRANSLATION }.map { it.text }
        )
    }

    /** 同段第二块的中文起点不是 0；这是最容易写错、且渲染起来最像「模型重复输出」的一处。 */
    @Test
    fun project_secondBlockInParagraph_doesNotRestartTranslationFromZero() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        val chinese = blocks.filter { it.kind == ReadingProjectedKind.TRANSLATION }
        assertEquals(listOf("第一行", "第二行"), chinese.map { it.text })
        // 块间分隔符被 trimEnd 掉了（渲染不该显示它），所以要按分隔符拼回，而不是直接相连。
        assertEquals(TRANSLATION, chinese.joinToString("\n") { it.text })
    }

    /** 每个块的 item key 必须互不相同，否则 LazyColumn 会把两块的布局结果混在一起。 */
    @Test
    fun project_withLayout_givesEveryBlockADistinctIdentity() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        assertEquals(blocks.size, blocks.map { it.identity }.distinct().size)
    }

    // ---- 布局与正文不符时退回 ----

    @Test
    fun project_layoutRangeBeyondCurrentParagraph_fallsBackToWholeParagraphs() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf("Short."),
            translation = "译文",
            layout = layoutOf(block(0, 0, 0, 99, 0, 2)),
            showTranslation = true
        )

        assertEquals(listOf("Short.", "译文"), blocks.map { it.text })
    }

    @Test
    fun project_layoutMissingAParagraph_fallsBackToWholeParagraphs() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = ParagraphAligner.splitParagraphs(CONTENT),
            translation = "第一段译文\n\n第二段译文",
            layout = layoutOf(block(0, 0, 0, 21, 0, 5)),
            showTranslation = true
        )

        assertEquals(4, blocks.size)
        assertEquals("First one. Second one.", blocks.first().text)
    }

    @Test
    fun project_translationShorterThanLayoutClaims_fallsBackToWholeParagraphs() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = "短",
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        assertEquals(listOf(WRAPPED, "短"), blocks.map { it.text })
    }

    // ---- 坐标换算 ----

    @Test
    fun toSourceOffset_secondBlock_addsItsStartOffset() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        val second = blocks.filter { it.kind == ReadingProjectedKind.ORIGINAL }[1]
        assertEquals(11, second.toSourceOffset(0))
        assertEquals(14, second.toSourceOffset(3))
    }

    @Test
    fun toLocalOffset_offsetOutsideBlock_returnsNull() {
        val block = ReadingProjectedBlock(1, 0, ReadingProjectedKind.ORIGINAL, "second", 11, 17)

        assertEquals(0, block.toLocalOffset(11))
        assertEquals(5, block.toLocalOffset(16))
        assertNull(block.toLocalOffset(10))
        assertNull(block.toLocalOffset(17))
    }

    @Test
    fun blockContaining_offsetInSecondBlock_returnsThatBlock() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        assertEquals(0, ReadingTranslationProjection.blockContaining(blocks, 0, 0)?.blockIndex)
        assertEquals(0, ReadingTranslationProjection.blockContaining(blocks, 0, 10)?.blockIndex)
        assertEquals(1, ReadingTranslationProjection.blockContaining(blocks, 0, 11)?.blockIndex)
    }

    /** 恢复一个指向段尾之后的锚点时取最后一块，而不是失败退回段首。 */
    @Test
    fun blockContaining_offsetPastParagraphEnd_returnsLastBlock() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = TRANSLATION,
            layout = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length)),
            showTranslation = true
        )

        assertEquals(1, ReadingTranslationProjection.blockContaining(blocks, 0, 9_999)?.blockIndex)
    }

    @Test
    fun blockContaining_unknownParagraph_returnsNull() {
        val blocks = ReadingTranslationProjection.project(
            paragraphs = listOf(WRAPPED),
            translation = null,
            layout = null,
            showTranslation = false
        )

        assertNull(ReadingTranslationProjection.blockContaining(blocks, 7, 0))
    }

    // ---- 发布后的锚点转换 ----

    /** 中文锚点必须落到对应块的**原文**起点：新译文是另一次输出，旧中文偏移指向无关字符。 */
    @Test
    fun convertAnchorForNewPublication_translationAnchor_movesToItsBlockSourceStart() {
        val previous = layoutOf(block(0, 0, 0, 11, 0, 4), block(1, 0, 11, WRAPPED.length, 4, TRANSLATION.length))

        val converted = ReadingTranslationProjection.convertAnchorForNewPublication(
            ReadingAnchor(0, ReadingTextKind.TRANSLATION, 5),
            previous
        )

        assertEquals(ReadingAnchor(0, ReadingTextKind.ORIGINAL, 11), converted)
    }

    @Test
    fun convertAnchorForNewPublication_translationAnchorWithoutPreviousLayout_movesToParagraphStart() {
        val converted = ReadingTranslationProjection.convertAnchorForNewPublication(
            ReadingAnchor(2, ReadingTextKind.TRANSLATION, 40),
            null
        )

        assertEquals(ReadingAnchor(2, ReadingTextKind.ORIGINAL, 0), converted)
    }

    /** 正文坐标系不受发布影响，这几种锚点必须逐字保留。 */
    @Test
    fun convertAnchorForNewPublication_nonTranslationAnchors_areUnchanged() {
        val previous = layoutOf(block(0, 0, 0, 11, 0, 4))

        for (kind in listOf(ReadingTextKind.ORIGINAL, ReadingTextKind.TITLE, ReadingTextKind.SOURCE)) {
            val anchor = ReadingAnchor(0, kind, 7)
            assertEquals(anchor, ReadingTranslationProjection.convertAnchorForNewPublication(anchor, previous))
        }
    }

    // ---- 辅助 ----

    private fun layoutOf(vararg blocks: AppliedTranslationBlock) = AppliedTranslationLayout(
        layoutVersion = AppliedTranslationLayout.VERSION_V1,
        segmentationMode = TranslationSegmentationMode.LINE.toStableToken(),
        articleFingerprint = "fp",
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
        const val CONTENT = "First one. Second one.\n\nThird one."

        /** 用户报告的形态：一个原段落内含单换行。 */
        const val WRAPPED = "First line\nsecond line."

        const val TRANSLATION = "第一行\n第二行"
    }
}
