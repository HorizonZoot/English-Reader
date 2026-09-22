package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.ParagraphAligner

/** 投影块的文本种类。与 [ReadingTextKind] 的区别是这里只有正文与译文两种。 */
enum class ReadingProjectedKind { ORIGINAL, TRANSLATION }

/**
 * 阅读页实际渲染的一个块。
 *
 * [text] 是**已经切好**的片段，渲染层直接用它构造 `AnnotatedString`，不再自己 `substring`。
 * 这一点是刻意的：源坐标与布局局部坐标混用是本方案最容易出错的地方，把切片收在投影里，渲染层
 * 就只需要处理「块内偏移」一种坐标。
 *
 * [sourceStartOffset] 是这个块在**原空行段落**内的起点。它是两套坐标之间唯一的桥：
 * 点击、朗读、句子身份全都要先加上它才能回到原段落，而阅读位置锚点存的一直是原段落坐标。
 */
data class ReadingProjectedBlock(
    val blockIndex: Int,
    val sourceParagraphIndex: Int,
    val kind: ReadingProjectedKind,
    val text: String,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
    /** 起点位于本 kind 的完整原段文本中；中文不能使用英文起点。 */
    val textStartOffset: Int = if (kind == ReadingProjectedKind.ORIGINAL) sourceStartOffset else 0
) {
    init {
        require(blockIndex >= 0) { "blockIndex must not be negative" }
        require(sourceParagraphIndex >= 0) { "sourceParagraphIndex must not be negative" }
        require(sourceStartOffset >= 0) { "sourceStartOffset must not be negative" }
        require(sourceEndOffset >= sourceStartOffset) { "source range must not be inverted" }
    }

    /**
     * 稳定身份，供 LazyColumn 的 item key 与布局缓存使用。
     *
     * 不用 `paragraphIndex + 1` 那种位置推算：分块之后一个原段落会对应多个 item，位置推算会把
     * 第二块的布局结果记到第一块名下，表现为高亮和弹层落在错误的行上。
     */
    val identity: String get() = "$kind:$blockIndex"

    /** 块内偏移 → 原段落偏移。ORIGINAL 才有意义；TRANSLATION 的文本坐标系是译文。 */
    fun toSourceOffset(localOffset: Int): Int = sourceStartOffset + localOffset

    /** 原段落偏移 → 块内偏移。落在本块之外时返回 null。 */
    fun toLocalOffset(sourceOffset: Int): Int? =
        if (sourceOffset in sourceStartOffset until maxOf(sourceEndOffset, sourceStartOffset + 1)) {
            sourceOffset - sourceStartOffset
        } else {
            null
        }

    /** 片段文本是用户正在读的内容，不进日志。 */
    override fun toString(): String =
        "ReadingProjectedBlock(blockIndex=$blockIndex, sourceParagraphIndex=$sourceParagraphIndex, " +
            "kind=$kind, text=[REDACTED], sourceStartOffset=$sourceStartOffset, " +
            "sourceEndOffset=$sourceEndOffset)"
}

/**
 * 把「正文 + 译文 + 已发布布局」投影成阅读页要渲染的块序列。
 *
 * 这一层存在的理由是把**唯一一次**坐标换算集中起来。没有它，滚动、分页、命中测试、朗读和进度
 * 各自按 `paragraphIndex` 推算一遍，任何一处与布局不一致都表现为「对照错位」，而错位不抛异常。
 *
 * 没有布局（[AppliedTranslationLayout] 为 null、版本未知或与当前正文不符）时退回**整段投影**，
 * 与分块功能上线前的行为逐字相同。这是本方案的兼容底座：旧译文、旧任务、正文刚被编辑过的文章
 * 都走这条路，不需要任何迁移。
 */
object ReadingTranslationProjection {

    /**
     * @param paragraphs 当前正文按 `ParagraphAligner.splitParagraphs` 的分段结果
     * @param translation `ArticleEntity.translation`，为 null 时只投影正文
     * @param layout 已发布布局；null 表示按整段投影
     * @param showTranslation 用户是否显示译文；false 时只投影正文块
     */
    fun project(
        paragraphs: List<String>,
        translation: String?,
        layout: AppliedTranslationLayout?,
        showTranslation: Boolean
    ): List<ReadingProjectedBlock> =
        if (layout == null || !showTranslation) {
            projectWholeParagraphs(paragraphs, translation, showTranslation)
        } else {
            projectBlocks(paragraphs, translation, layout, showTranslation)
        }

    /** 兼容路径：一个原段落一个块，译文取该段完整中文。 */
    private fun projectWholeParagraphs(
        paragraphs: List<String>,
        translation: String?,
        showTranslation: Boolean
    ): List<ReadingProjectedBlock> {
        val translations = translation?.let { ParagraphAligner.splitParagraphs(it) } ?: emptyList()
        return buildList {
            paragraphs.forEachIndexed { index, paragraph ->
                add(
                    ReadingProjectedBlock(
                        blockIndex = index,
                        sourceParagraphIndex = index,
                        kind = ReadingProjectedKind.ORIGINAL,
                        text = paragraph,
                        sourceStartOffset = 0,
                        sourceEndOffset = paragraph.length
                    )
                )
                val chinese = translations.getOrNull(index)?.takeIf { it.isNotBlank() }
                if (showTranslation && chinese != null) {
                    add(
                        ReadingProjectedBlock(
                            blockIndex = index,
                            sourceParagraphIndex = index,
                            kind = ReadingProjectedKind.TRANSLATION,
                            text = chinese,
                            sourceStartOffset = 0,
                            sourceEndOffset = paragraph.length
                        )
                    )
                }
            }
        }
    }

    /**
     * 分块路径：按持久坐标切原文，按持久中文坐标切译文。
     *
     * 译文**必须**按 offset 切，不能按换行反切：块译文内部本来就可能含换行，按分隔符切会让同段
     * 之后的块整体错位一格。切出来的片段要 `trimEnd`，因为聚合时块间分隔符算在前一块的区间里。
     *
     * 任何一处坐标越界就整体退回整段投影，而不是跳过这一块。缺块会让对照静默少一段，比退回整段
     * 更难被发现。
     */
    private fun projectBlocks(
        paragraphs: List<String>,
        translation: String?,
        layout: AppliedTranslationLayout,
        showTranslation: Boolean
    ): List<ReadingProjectedBlock> {
        val translations = translation?.let(ParagraphAligner::splitParagraphs).orEmpty()
        if (!layout.matchesText(paragraphs, translations)) {
            return projectWholeParagraphs(paragraphs, translation, showTranslation)
        }
        val grouped = layout.blocksByParagraph()
        val blocks = mutableListOf<ReadingProjectedBlock>()

        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            val group = grouped[paragraphIndex]
                ?: return projectWholeParagraphs(paragraphs, translation, showTranslation)
            val chinese = translations.getOrNull(paragraphIndex)

            for (block in group.sortedBy { it.blockIndex }) {
                if (block.sourceEndOffset > paragraph.length) {
                    return projectWholeParagraphs(paragraphs, translation, showTranslation)
                }
                blocks += ReadingProjectedBlock(
                    blockIndex = block.blockIndex,
                    sourceParagraphIndex = paragraphIndex,
                    kind = ReadingProjectedKind.ORIGINAL,
                    text = paragraph.substring(block.sourceStartOffset, block.sourceEndOffset),
                    sourceStartOffset = block.sourceStartOffset,
                    sourceEndOffset = block.sourceEndOffset
                )
                if (!showTranslation || chinese == null) continue
                if (block.translationEndOffset > chinese.length) {
                    return projectWholeParagraphs(paragraphs, translation, showTranslation)
                }
                val slice = chinese
                    .substring(block.translationStartOffset, block.translationEndOffset)
                    .trimEnd()
                if (slice.isEmpty()) continue
                blocks += ReadingProjectedBlock(
                    blockIndex = block.blockIndex,
                    sourceParagraphIndex = paragraphIndex,
                    kind = ReadingProjectedKind.TRANSLATION,
                    text = slice,
                    sourceStartOffset = block.sourceStartOffset,
                    sourceEndOffset = block.sourceEndOffset,
                    textStartOffset = block.translationStartOffset
                )
            }
        }
        return blocks
    }

    /**
     * 找出包含某个原段落偏移的正文块。
     *
     * 命中测试、朗读定位和阅读位置恢复都要走这一步：它们持有的一直是原段落坐标，而渲染单位是块。
     * 偏移落在段末之后（例如恢复一个指向段尾的锚点）时返回该段最后一个块，避免恢复失败退回段首。
     */
    fun blockContaining(
        blocks: List<ReadingProjectedBlock>,
        paragraphIndex: Int,
        sourceOffset: Int
    ): ReadingProjectedBlock? {
        val candidates = blocks.filter {
            it.kind == ReadingProjectedKind.ORIGINAL && it.sourceParagraphIndex == paragraphIndex
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { sourceOffset < it.sourceEndOffset } ?: candidates.last()
    }

    /**
     * 新布局发布后转换一个持久阅读锚点。
     *
     * 只有 TRANSLATION 锚点需要转换，且转换目标是**对应块的原文起点**，而不是新译文里的某个中文
     * 偏移。理由：旧偏移是针对旧译文字符串算的，新译文是另一次模型输出，长度与断句都不同，任何
     * 「按比例缩放」或「直接沿用」都会落在无关的字符上。退回原文起点则一定是用户读过的位置附近。
     *
     * ORIGINAL、TITLE、SOURCE 锚点原样返回：它们的坐标系是正文，而发布不改正文。
     *
     * @param previousLayout 发布前的布局；null 表示此前是整段对照，中文锚点回到原段起点
     */
    fun convertAnchorForNewPublication(
        anchor: ReadingAnchor,
        previousLayout: AppliedTranslationLayout?
    ): ReadingAnchor {
        if (anchor.textKind != ReadingTextKind.TRANSLATION) return anchor
        val sourceOffset = previousLayout
            ?.blocksByParagraph()
            ?.get(anchor.paragraphIndex)
            ?.sortedBy { it.blockIndex }
            ?.firstOrNull { anchor.characterOffset < it.translationEndOffset }
            ?.sourceStartOffset
            ?: 0
        return ReadingAnchor(
            paragraphIndex = anchor.paragraphIndex,
            textKind = ReadingTextKind.ORIGINAL,
            characterOffset = sourceOffset
        )
    }
}
