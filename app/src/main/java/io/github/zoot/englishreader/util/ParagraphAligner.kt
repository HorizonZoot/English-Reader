package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.core.SentenceRange

/**
 * 段落对齐器
 *
 * 将文章正文与译文按空行分段后一一配对，为每段计算其首句在全文中的全局句子索引偏移
 * （供 InteractiveText 的 sentenceIndexOffset 使用，保证分段渲染后句子高亮索引仍全局唯一），
 * 并**保留该段的分句结果**供渲染层复用。
 *
 * 纯逻辑，不依赖 Android API：分句通过 [sentenceSplitter] 注入，生产环境传
 * `SentenceSplitter::split`，测试可传假实现。
 */
object ParagraphAligner {

    /**
     * @param english 英文段落原文
     * @param chinese 对应中文译文段（译文段数不足时为 null）
     * @param sentenceOffset 该段第一句在全文中的全局句子索引
     * @param sentences 该段的分句结果，offset 是**段落局部**的（相对 [english]）
     *
     * [sentences] 与 [english] 是同一次对齐产出的不可分割配对：渲染层直接复用它，不再重复分句。
     * 构造处只校验两条：索引从 0 起连续，且每句文本确实落在它声称的偏移上（可捕获
     * 「句子来自另一段文本」与「偏移整体平移」）。
     *
     * **没有**校验区间重叠与覆盖空洞，而「连续覆盖」才是 InteractiveText 逐句 append 拼出
     * annotatedText 时真正依赖的不变量（详见 [SentenceRange] 的说明）。SentenceSplitter 会
     * 跳过纯空白句段，因此空洞在理论上可以出现（如相邻的 U+2028/U+000B 分隔符）。
     * 这里有意不把连续覆盖写成 require：那会把一个既有的高亮错位问题升级成阅读页崩溃。
     */
    data class AlignedParagraph(
        val english: String,
        val chinese: String?,
        val sentenceOffset: Int,
        val sentences: List<SentenceRange>
    ) {
        init {
            sentences.forEachIndexed { position, sentence ->
                // 索引必须是从 0 起的连续序列：InteractiveText 用 index + sentenceOffset 作为
                // 跨段落的全局句子身份，索引跳号会让不同段落的全局索引撞车或空缺。
                require(sentence.index == position) {
                    "sentence index must be sequential from 0: expected $position, got ${sentence.index}"
                }
                // 句子文本必须真的落在它声称的偏移上。捕获「句子来自另一段文本」这类调用方错误。
                require(english.startsWith(sentence.text, sentence.startOffset)) {
                    "sentence $position does not occur at offset ${sentence.startOffset} of its paragraph"
                }
            }
        }
    }

    /**
     * 以英文段落为准配对译文。
     *
     * - 英文段数决定渲染段数；译文段数不足的段落 chinese 为 null，多余的译文段被忽略。
     * - 每段只分句一次：结果既用于累加 sentenceOffset，也随段落返回给渲染层复用。
     *   偏移与句子列表因此出自同一次分句，不存在计数与渲染各分一次而结果分叉的可能。
     */
    fun align(
        content: String,
        translation: String?,
        sentenceSplitter: (String) -> List<SentenceRange>
    ): List<AlignedParagraph> {
        val englishParagraphs = splitParagraphs(content)
        val chineseParagraphs = translation?.let { splitParagraphs(it) } ?: emptyList()

        var offset = 0
        return englishParagraphs.mapIndexed { index, english ->
            val sentences = sentenceSplitter(english)
            val paragraph = AlignedParagraph(
                english = english,
                chinese = chineseParagraphs.getOrNull(index),
                sentenceOffset = offset,
                sentences = sentences
            )
            offset += sentences.size
            paragraph
        }
    }

    /**
     * 按一个或多个空行分段，trim 后丢弃空段。
     *
     * 公开而非私有：全文翻译的段落快照必须与渲染看到的段落**逐一对应**。若快照自己再实现
     * 一套分段，两边对「trim」「空段」「连续空行」的处理只要差一处，段落索引就整体错位，
     * 于是 checkpoint 把第 N 段的译文写到第 N+1 段上——这种错位不会抛异常，只会让用户读到
     * 对不上的译文。因此分段规则只允许有这一个实现。
     */
    fun splitParagraphs(text: String): List<String> =
        text.split(PARAGRAPH_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 空行作为段落分隔。`\s` 本身匹配 `\n`，故连续多个空行会被贪婪吞成**一个**分隔符，
     * 不会产出空段。注意段内的单个 `\n`（硬折行）不是分隔符，会原样留在段落文本里。
     */
    private val PARAGRAPH_DELIMITER = Regex("\\n\\s*\\n")
}
