package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.model.TranslationBlock
import io.github.zoot.englishreader.model.TranslationBlockPlan
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.model.TranslationPlannerVersion
import io.github.zoot.englishreader.model.TranslationSegmentationMode

/**
 * 把一篇文章切成对照块。
 *
 * 纯逻辑，分句经 [plan] 的 `sentenceSplitter` 注入（生产传 `SentenceSplitter::split`），因此
 * 全部规则都能在 JVM 上验证，不需要设备上的 ICU。
 *
 * 三条贯穿全文件的不变量，破坏任何一条都会让译文静默错位而不是报错：
 *
 * 1. **不改写正文。** 块只记录 `ParagraphAligner.splitParagraphs` 产出的原段落内的 UTF-16
 *    区间。原段落序号仍是阅读定位、句子身份和朗读的坐标系，块只是叠在上面的展示单位。
 * 2. **块不跨原段落。** 跨段落合并会让一个块同时属于两个段落序号，而 checkpoint 只能记一个。
 * 3. **同一段内的块首尾相接、恰好铺满 `[0, 段落长度)`。** 少一个字符就是丢正文，多一次覆盖
 *    就是重复付费翻译同一段文字。分隔用的换行归入它前面的块，不丢弃。
 */
object TranslationBlockPlanner {

    /**
     * 自动模式下一个块的目标长度。
     *
     * 目标而非上限：累积到这个长度后在**下一个完整句子之前**收束，因此实际块长普遍略超。
     * 取 300 是为了让一个对照块只有寥寥几句——原文与译文并排时，短块比整段更容易逐句对上眼，
     * 也不至于让一段普通篇幅的正文堆成一整屏英文后再跟一整屏中文。同时远端请求数仍远不及
     * [MAX_TRANSLATION_BLOCKS_PER_ARTICLE]。
     */
    const val AUTO_TARGET_CHARS: Int = 300

    /**
     * 自动模式下的软上限。
     *
     * 加入下一整句会超过它就提前收束。之所以是「软」：单个句子本身超过它时仍原样保留成一块，
     * 绝不在句中切断——按长度硬切会把主句和修饰成分分到两个请求里，模型看不到完整结构，
     * 译文质量下降却没有任何错误提示。
     */
    const val AUTO_MAX_CHARS: Int = 1_000

    /**
     * 单篇文章的块数上限。
     *
     * 与 `ImportBudget.MAX_IMPORT_PARAGRAPHS` 数值相同但**是独立常量**，不复用也不修改后者：
     * 那个上限约束的是阅读页要渲染多少段，这个上限约束的是一次任务最多发出多少个付费请求。
     * 两者恰好同值是巧合，混用会让调整其中一个意外改变另一个的语义。
     */
    const val MAX_TRANSLATION_BLOCKS_PER_ARTICLE: Int = 1_200

    sealed interface Result {
        data class Planned(val plan: TranslationBlockPlan) : Result

        /** 正文没有可翻译的段落。 */
        data object NoContent : Result

        /**
         * 块数超限。
         *
         * 单独成类而不是回落到 [TranslationSegmentationMode.PRESERVE]：静默换一种分块方式，
         * 用户会看到与自己所选不符的对照结果却没有任何说明。调用方应当提示改用保留原段落。
         */
        data class TooManyBlocks(val actualBlocks: Int, val maxBlocks: Int) : Result
    }

    /**
     * @param sentenceSplitter 段落局部分句；偏移相对传入的段落文本
     * @return 固定下来的计划；调用方必须持久化它，不要在恢复时重算
     */
    fun plan(
        articleId: Long,
        content: String,
        mode: TranslationSegmentationMode,
        sentenceSplitter: (String) -> List<SentenceRange>
    ): Result {
        val paragraphs = ParagraphAligner.splitParagraphs(content)
        if (paragraphs.isEmpty()) return Result.NoContent

        val blocks = mutableListOf<TranslationBlock>()
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            for (bounds in boundariesFor(paragraph, mode, sentenceSplitter)) {
                val text = paragraph.substring(bounds.start, bounds.end)
                blocks += TranslationBlock(
                    blockIndex = blocks.size,
                    sourceParagraphIndex = paragraphIndex,
                    startOffset = bounds.start,
                    endOffset = bounds.end,
                    text = text,
                    sourceFingerprint = TranslationFingerprint.forBlock(text)
                )
            }
        }
        if (blocks.isEmpty()) return Result.NoContent

        // 保留原段落不设新门槛：它产出的块数等于导入时已经通过 `MAX_IMPORT_PARAGRAPHS` 的段落数，
        // 在这里再拦一次只会让一篇本来能翻译的文章忽然翻不了。
        if (mode != TranslationSegmentationMode.PRESERVE &&
            blocks.size > MAX_TRANSLATION_BLOCKS_PER_ARTICLE
        ) {
            return Result.TooManyBlocks(blocks.size, MAX_TRANSLATION_BLOCKS_PER_ARTICLE)
        }

        return Result.Planned(
            TranslationBlockPlan(
                articleId = articleId,
                mode = mode,
                plannerVersion = TranslationPlannerVersion.BLOCK_V1,
                articleFingerprint = TranslationFingerprint.forArticle(content),
                blocks = blocks
            )
        )
    }

    /**
     * 一个块在原段落内的半开区间。
     *
     * 不用 `IntRange`：那是闭区间，而这里全程按 `[start, end)` 计算，两种约定混在一个文件里
     * 早晚会在某次 `substring` 上差一个字符。
     */
    private data class Bounds(val start: Int, val end: Int)

    private fun boundariesFor(
        paragraph: String,
        mode: TranslationSegmentationMode,
        sentenceSplitter: (String) -> List<SentenceRange>
    ): List<Bounds> = when (mode) {
        TranslationSegmentationMode.PRESERVE -> wholeParagraph(paragraph)

        TranslationSegmentationMode.LINE -> nonBlank(paragraph, lineCuts(paragraph))

        // 自动模式对**每个段落各自**跑一遍分块。作者的空行分段始终是硬边界——plan() 逐段循环，
        // 这里只在单段内部给出切点，永远不会把两段并进一块，也永远不会跨空行切开。在这个前提下再
        // 把过长的段按完整句子细分：短段在 groupSentences 里产生不了切点，自然整段成块；只有超过
        // AUTO_TARGET_CHARS 的长段才会被切，避免一个很长的自然段原样堆成一整屏的对照块。
        TranslationSegmentationMode.AUTO ->
            nonBlank(paragraph, autoCuts(paragraph, sentenceSplitter))
    }

    private fun wholeParagraph(paragraph: String): List<Bounds> =
        listOf(Bounds(0, paragraph.length))

    /**
     * 按单换行切。
     *
     * 换行本身留在它前面的块里，因此这些块拼回去与原段落逐字相同。刻意**不**在这里跳过句中
     * 折行：用户选择按换行就是在纠正「这些换行是段落分隔」，代码再去猜一次等于无视纠正。跨块
     * 的同一个句子由阅读层负责映射回完整原句，而不是在分块时回避。
     */
    private fun lineCuts(paragraph: String): List<Int> {
        val cuts = mutableListOf<Int>()
        var position = 0
        while (position < paragraph.length) {
            if (paragraph[position] in MANDATORY_LINE_BREAKS) {
                // 连续的换行字符算**一个**切点，切在整段换行之后。CRLF 是两个字符，逐个切会让
                // 下一块以 `\n` 开头；` ` 这类分隔符同理。切点取在整段之后，换行全部归入
                // 前一块，拼回去仍与原段落逐字相同。
                var end = position
                while (end < paragraph.length && paragraph[end] in MANDATORY_LINE_BREAKS) end++
                cuts += end
                position = end
            } else {
                position++
            }
        }
        return cuts
    }

    /**
     * 自动模式在单一段落内的切点。
     *
     * 「落在完整句边界上的独立换行」是作者的分段意图（区别于 PDF 复制、固定宽度排版产生的句中
     * 折行），它们是**必切点**：无论长度都在这里收束，绝不把作者分开的两段并进一块。但仅有必切点
     * 不够——用户直接粘贴、段间只有单换行的长文会被 `splitParagraphs` 当成一个大段，此时必切点
     * 就是那几处段落换行，两处之间仍可能是几百字的整段。因此在必切点之上再按完整句子把超过
     * [AUTO_TARGET_CHARS] 的连续正文细分，否则一个长段原样成块，对照时原文占去大半屏。
     */
    private fun autoCuts(
        paragraph: String,
        sentenceSplitter: (String) -> List<SentenceRange>
    ): List<Int> {
        val sentences = sentenceSplitter(withoutLineBreaks(paragraph))
        if (sentences.isEmpty()) return emptyList()

        val sentenceEnds = sentences.mapTo(HashSet()) { it.startOffset + it.text.trimEnd().length }
        val mandatoryCuts = lineCuts(paragraph)
            .filter { cut -> sentenceEnds.contains(lastNonWhitespaceEnd(paragraph, cut - 1)) }
            .toHashSet()

        return groupSentences(paragraph, sentences, mandatoryCuts)
    }

    /**
     * 把强制换行符替换成空格，长度与每个下标都保持不变。
     *
     * 这一步是「句末换行」这个信号能否成立的前提。ICU 按 UAX#29 把换行本身就当作句子分隔符，
     * 因此直接对原段落分句时，**每个**换行都恰好落在某一句的终点上——包括 PDF 复制产生的句中
     * 折行。那样判断出的「句末」永远为真，硬折行会被逐行切开，一句话分到多个付费请求里。
     *
     * 换成空格后，分句只依据标点与后文大小写，于是「这个换行是不是句子真的结束了」才重新成为
     * 一个有区分度的问题。替换是一对一的，所以返回的 offset 可以直接用在原段落上。
     */
    private fun withoutLineBreaks(paragraph: String): String =
        paragraph.map { if (it in MANDATORY_LINE_BREAKS) ' ' else it }.joinToString("")

    /** 从 [index] 起向前跳过空白，返回最后一个非空白字符之后的位置。 */
    private fun lastNonWhitespaceEnd(paragraph: String, index: Int): Int {
        var position = index
        while (position > 0 && paragraph[position - 1].isWhitespace()) position--
        return position
    }

    /**
     * 按完整句子累积成块，并强制在 [mandatoryCuts] 处收束。
     *
     * 切点取**下一句的起点**而非本句的终点，这样句间空白自然归入前一块，覆盖仍然连续。
     * [mandatoryCuts] 是作者的段落换行（落在句尾的独立换行），无论长度都要在那里切；在两个必切点
     * 之间，累积长度达到 [AUTO_TARGET_CHARS] 后在下一句前收束，若加入下一整句会超过 [AUTO_MAX_CHARS]
     * 则提前收束。长度条件只在当前块已有内容时生效，因此超长单句会独占一块而不被切断。
     */
    private fun groupSentences(
        paragraph: String,
        sentences: List<SentenceRange>,
        mandatoryCuts: Set<Int>
    ): List<Int> {
        // 首块从 0 起、末块到段末止：ICU 会跳过纯空白句段，直接用句子区间当切点会在段首/段尾
        // 留下没有任何块覆盖的字符。必切点也并入候选边界：它落在换行之后，而句子起点在换行后的
        // 空白之后，二者相差那段空白；不并进来，`". \n X"` 这种换行后带空格的段落分隔就对不上句子
        // 起点、被静默漏切。
        val tiles = buildList {
            add(0)
            sentences.drop(1).forEach { add(it.startOffset) }
            addAll(mandatoryCuts)
            add(paragraph.length)
        }.distinct().sorted()

        val cuts = mutableListOf<Int>()
        var start = 0
        var pending = 0
        for (position in 1 until tiles.size) {
            val end = tiles[position]
            // 必切点优先：作者已在此分段，无论长度都收束，绝不跨过它把两段并进一块。
            val mustCut = pending > start && mandatoryCuts.contains(pending)
            val longEnough = pending > start &&
                (pending - start >= AUTO_TARGET_CHARS || end - start > AUTO_MAX_CHARS)
            if (mustCut || longEnough) {
                cuts += pending
                start = pending
            }
            pending = end
        }
        return cuts
    }

    /**
     * 把切点变成区间，并合并掉纯空白的片段。
     *
     * 段落内不可能出现空行（那已经是段落分隔符），所以理论上每个片段都含非空白字符。仍然做这
     * 一步是因为代价极低，而 [TranslationBlock] 对空白文本是 `require` 失败：真出现边界情况时，
     * 宁可让这一段少切一次，也不要在用户点开翻译面板时抛异常。
     */
    private fun nonBlank(paragraph: String, cuts: List<Int>): List<Bounds> {
        if (paragraph.isEmpty()) return emptyList()
        val ranges = mutableListOf<Bounds>()
        var start = 0
        for (cut in cuts.filter { it in 1 until paragraph.length }.distinct().sorted()) {
            // 空白片段与它后面的内容合并：丢掉这个切点，start 不动。
            if (paragraph.substring(start, cut).isBlank()) continue
            ranges += Bounds(start, cut)
            start = cut
        }
        // 末尾若只剩空白，并回前一块，避免产出空白块。
        if (paragraph.substring(start).isBlank() && ranges.isNotEmpty()) {
            val last = ranges.removeAt(ranges.lastIndex)
            ranges += Bounds(last.start, paragraph.length)
        } else {
            ranges += Bounds(start, paragraph.length)
        }
        return ranges
    }

    /**
     * UAX#14 的强制换行字符。
     *
     * 包含 `\r` 与 `\n` 之外的三个分隔符（NEL、行分隔符、段分隔符）：ICU 同样把它们当作句子
     * 终止符，遗漏任何一个都会让含该字符的正文重新回到「每个换行都算句末」的错误判断。
     */
    private val MANDATORY_LINE_BREAKS = charArrayOf('\r', '\n', '', ' ', ' ')
}
