package io.github.zoot.englishreader.model

/**
 * 对照分块方式。
 *
 * 这是**用户可纠正的展示决定**，不是文本事实：同一篇正文按哪种方式切，取决于它的换行到底是
 * 段落分隔还是复制产生的硬折行，而这一点程序只能猜。因此三个取值都要能长期存在，不能在
 * 「自动足够好」之后删掉另外两个——猜错时用户需要一个能立刻改回去的出口。
 */
enum class TranslationSegmentationMode {

    /** 沿用空行段落，并把落在完整句边界的独立换行也当作块边界。 */
    AUTO,

    /** 严格按单换行切块，即使换行落在句子中间。 */
    LINE,

    /** 一个空行段落就是一块，与旧版行为一致。 */
    PRESERVE;

    fun toStableToken(): String = when (this) {
        AUTO -> "auto"
        LINE -> "line"
        PRESERVE -> "preserve"
    }

    companion object {
        /**
         * 未知 token 返回 null 而**不是**回落到某个默认值。
         *
         * 分块方式决定 `translation_segments` 里那些 offset 该如何解释。猜一个默认值会让
         * 一份按 LINE 切出来的 checkpoint 被当成 PRESERVE 读，于是块与译文整体错位，而错位
         * 不抛异常、只表现为「翻译质量变差」。调用方必须显式处理 null。
         */
        fun fromStableToken(token: String?): TranslationSegmentationMode? =
            token?.let { value -> entries.firstOrNull { it.toStableToken() == value } }
    }
}

/**
 * 规划器版本，范围解释的分派键。
 *
 * 版本随 offset 语义走，不随分块方式走：[LEGACY] 的行里 `paragraphIndex` 是空行段落序号且
 * 没有 offset；[BLOCK_V1] 的行里它是块序号，并带原段内的 `[start, end)`。旧任务必须继续按
 * 旧语义读完，不能在升级后被新代码重新分块——那会让已经付费成功的段落全部重来。
 *
 * 未知版本一律拒绝执行。宁可让用户重建任务，也不要拿猜出来的坐标去发付费请求。
 */
object TranslationPlannerVersion {

    /** v7 之前创建的任务：按 `ParagraphAligner.splitParagraphs` 的段落序号。 */
    const val LEGACY: String = "legacy-v1"

    /** 首个对照分块版本：块序号 + 原段落内字符区间。 */
    const val BLOCK_V1: String = "block-v1"

    fun isBlock(version: String?): Boolean = version == BLOCK_V1

    fun isLegacy(version: String?): Boolean = version == LEGACY

    fun isSupported(version: String?): Boolean = isBlock(version) || isLegacy(version)
}

/**
 * 一个对照块在原文中的坐标，也就是持久化到 `translation_segments` 的那三个值。
 *
 * offset 是**原空行段落内**的 UTF-16 下标，不是全文下标：阅读定位、句子选中和朗读都以原段落
 * 为坐标系，块若换一套全文坐标，两边就要各自换算，而换算点越多越容易只改一处。
 */
data class TranslationBlockRange(
    val blockIndex: Int,
    val sourceParagraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int
) {
    init {
        require(blockIndex >= 0) { "blockIndex must not be negative" }
        require(sourceParagraphIndex >= 0) { "sourceParagraphIndex must not be negative" }
        require(startOffset >= 0) { "startOffset must not be negative" }
        require(endOffset > startOffset) { "block range must not be empty" }
    }

    val length: Int get() = endOffset - startOffset
}

/**
 * 一个对照块：坐标 + 从原段落切出的文本 + 该文本的指纹。
 *
 * [text] 必须真的是 `paragraph.substring(startOffset, endOffset)`，长度校验在这里就做掉：
 * 翻译请求发的是这段文本，而 checkpoint 校验的是坐标，两者一旦不同源，用户会为一段文本付费
 * 却把译文记在另一段坐标上。
 */
data class TranslationBlock(
    val blockIndex: Int,
    val sourceParagraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val sourceFingerprint: String
) {
    init {
        require(text.length == endOffset - startOffset) {
            "text length mismatch: expected ${endOffset - startOffset}, got ${text.length}"
        }
        require(text.isNotBlank()) { "block text must not be blank" }
        require(sourceFingerprint.isNotBlank()) { "sourceFingerprint must not be blank" }
    }

    val range: TranslationBlockRange
        get() = TranslationBlockRange(blockIndex, sourceParagraphIndex, startOffset, endOffset)

    /** 块文本是用户正在读的内容，不进日志。 */
    override fun toString(): String =
        "TranslationBlock(blockIndex=$blockIndex, sourceParagraphIndex=$sourceParagraphIndex, " +
            "startOffset=$startOffset, endOffset=$endOffset, text=[REDACTED], " +
            "sourceFingerprint=$sourceFingerprint)"
}

/**
 * 一篇文章的完整分块结果。
 *
 * 规划一次、固定下来，之后的预览、创建任务、逐块请求和最终发布都读同一份：分块依据里有 ICU
 * 分句，而 ICU 的边界随系统版本变化，读时重算会让恢复出来的块与已付费的译文对不上。
 */
data class TranslationBlockPlan(
    val articleId: Long,
    val mode: TranslationSegmentationMode,
    val plannerVersion: String,
    val articleFingerprint: String,
    val blocks: List<TranslationBlock>
) {
    init {
        require(articleId > 0) { "articleId must be positive" }
        require(blocks.isNotEmpty()) { "plan must contain at least one block" }
        require(articleFingerprint.isNotBlank()) { "articleFingerprint must not be blank" }
        blocks.forEachIndexed { position, block ->
            require(block.blockIndex == position) {
                "blockIndex must be sequential from 0: expected $position, got ${block.blockIndex}"
            }
        }
    }

    val ranges: List<TranslationBlockRange> get() = blocks.map { it.range }
}

/**
 * 范围完整性校验。
 *
 * 独立于规划器存在，因为最终发布时校验的是**持久化的**范围加**当前的**正文，此时已经没有
 * 规划器的中间结果可信：范围来自数据库，正文可能已被编辑。逐段要求「从 0 起、首尾相接、
 * 到段末结束」，任何洞、重叠或越界都说明这份 checkpoint 与正文已经不是一回事，应当拒绝发布
 * 而不是把译文错位地写进去。
 */
object TranslationBlockCoverage {

    /** @param paragraphs 当前正文按 `ParagraphAligner.splitParagraphs` 的分段结果 */
    fun isComplete(paragraphs: List<String>, ranges: List<TranslationBlockRange>): Boolean {
        if (paragraphs.isEmpty() || ranges.isEmpty()) return false
        val ordered = ranges.sortedBy { it.blockIndex }
        if (ordered.mapIndexed { index, range -> range.blockIndex == index }.any { !it }) return false

        var paragraphIndex = 0
        var expectedStart = 0
        for (range in ordered) {
            // 块序号递增时原段落序号只能持平或前进一格：跳段意味着中间那段没有任何块，
            // 回退意味着同一段被切成两批不连续的块。
            if (range.sourceParagraphIndex == paragraphIndex + 1 && expectedStart == currentLength(paragraphs, paragraphIndex)) {
                paragraphIndex++
                expectedStart = 0
            }
            if (range.sourceParagraphIndex != paragraphIndex) return false
            if (range.startOffset != expectedStart) return false
            if (range.endOffset > currentLength(paragraphs, paragraphIndex)) return false
            expectedStart = range.endOffset
        }
        return paragraphIndex == paragraphs.lastIndex &&
            expectedStart == currentLength(paragraphs, paragraphIndex)
    }

    private fun currentLength(paragraphs: List<String>, index: Int): Int =
        paragraphs.getOrNull(index)?.length ?: -1
}
