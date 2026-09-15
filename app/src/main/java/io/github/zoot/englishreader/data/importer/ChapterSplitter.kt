package io.github.zoot.englishreader.data.importer

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.util.ParagraphAligner

/**
 * 把超出预算的章节正文切成若干可导入的部分。
 *
 * 存在理由：`EpubBookParser` 原先对超限章节直接抛 [ImportFailure.ChapterTooLong]，而那是
 * 从 `parse()` 抛出的——**一章超限，整本书被拒**。32 本公版语料实测只有 11 本（34%）能导入，
 * 19 本死在章节闸门、2 本死在段落闸门，没有一本是因为编码、DRM 或结构问题。最刺眼的是
 * `se-crime-and-punishment`：某一章 40,131 字符，超了 131 个，整本书进不来。
 *
 * ## 为什么是切分，而不是抬高上限
 *
 * ADR-013 禁止移动 [ImportBudget.MAX_CHAPTER_CHARS]，因为没有真机渲染基线。切分**绕开**了
 * 这道门禁：每个产物都仍在 40,000 以内，那是线上已经在跑的、验证过的形状。所以本类不改任何
 * 一个预算常量，也不需要那份从未跑过的基准数据。
 *
 * ADR-013 里「切分被语料反驳了」那段话反驳的是**按 TOC 锚点切**——Standard Ebooks 已经一章
 * 一文件，锚点无处可切。它没有反驳按**段落边界**切：`the-adventure-of-the-copper-beeches.xhtml`
 * 那 52,650 字符没有锚点，但有大把段落边界。
 *
 * ## 两级切分，内层优先
 *
 * 1. 段落自身超过 [maxParagraphChars] 时，先按句子边界切开（语料里共 16 段，占 86,621 段的
 *    0.02%，但足以拒掉 6 本书）。
 * 2. 再把整段贪心装箱到 [maxChapterChars] 以内，不重排顺序。
 *
 * 顺序不能反：一个本身就超过章节上限的单段，必须先在内层切开才可能装得进任何一个箱子。
 *
 * ## 两处刻意的「不切」
 *
 * 单个句子超过段落上限、或单个段落超过章节上限时，本类**原样输出**那个超限单元，让调用方的
 * 既有校验照常抛 [ImportFailure.ParagraphTooLong] / [ImportFailure.ChapterTooLong]。切到句子
 * 以下（按字符硬切）会在正文中间造出断句，那比拒绝更糟：用户读到的是坏文本，而且没有任何提示。
 */
object ChapterSplitter {

    /** 装箱时的段落分隔符，与 `ParagraphAligner` 的分段语义一致（一个空行）。 */
    private const val PARAGRAPH_SEPARATOR = "\n\n"

    /**
     * 切分章节正文。
     *
     * @param content 已提取并规范换行的章节正文
     * @param sentenceSplitter 分句实现。生产传 `SentenceSplitter::split`；它依赖
     *   `android.icu.text.BreakIterator`，注入才能让本类保持纯 JVM 可测（与
     *   [ParagraphAligner.align] 同一约定）。
     * @return 至少一个部分，顺序即阅读顺序。未超限时返回**原始字符串本身**。
     */
    fun split(
        content: String,
        maxChapterChars: Int = ImportBudget.MAX_CHAPTER_CHARS,
        maxParagraphChars: Int = ImportBudget.MAX_PARAGRAPH_CHARS,
        sentenceSplitter: (String) -> List<SentenceRange>
    ): List<String> {
        val paragraphs = ParagraphAligner.splitParagraphs(content)

        // 恒等短路，且必须返回 content 本身而不是 paragraphs 的重新拼接：
        // splitParagraphs 会 trim 每段、丢弃空段、把连续空行吞成一个分隔符，重新拼接得到的
        // 字节与原文不同。而 BookMetadata.contentFingerprint 摘要的正是正文字节——今天能导入的
        // 那 11 本书若因此换了指纹，用户重新导入时会被判成「不是同一本书」，重复检测失效。
        if (content.length <= maxChapterChars &&
            paragraphs.all { it.length <= maxParagraphChars }
        ) {
            return listOf(content)
        }

        // 全空白正文：`splitParagraphs` 丢弃空段后什么都不剩，装箱会返回空列表，违反本函数
        // 「至少一个部分」的契约。生产路径上 `EpubBookParser` 先按 `content.isBlank()` 过滤过，
        // 所以这里不可达；但契约必须自洽，否则将来第二个调用方会拿到空列表并静默丢掉这一章。
        // 原样返回，由调用方的 `ImportBudgetValidator` 按 EmptyContent 拒绝。
        if (paragraphs.isEmpty()) return listOf(content)

        val units = paragraphs.flatMap { paragraph ->
            subdivideParagraph(paragraph, maxParagraphChars, sentenceSplitter)
        }
        return packIntoParts(units, maxChapterChars)
    }

    /**
     * 把超长段落按句子边界切成若干段。
     *
     * 用 [SentenceRange] 的偏移做 `substring` 而不是把句子文本拼起来：句子之间的空白不属于
     * 任何一个 [SentenceRange]（`SentenceSplitter` 跳过纯空白句段），拼接会把它们吃掉，
     * 于是切出来的文本比原文短——正文被静默改写。
     */
    private fun subdivideParagraph(
        paragraph: String,
        maxParagraphChars: Int,
        sentenceSplitter: (String) -> List<SentenceRange>
    ): List<String> {
        if (paragraph.length <= maxParagraphChars) return listOf(paragraph)

        val sentences = sentenceSplitter(paragraph)
        // 守的是**注入契约**，不是真实 ICU 行为。这里的 paragraph 来自
        // `ParagraphAligner.splitParagraphs`（已 trim、已丢空段），而 `SentenceSplitter.split`
        // 只在文本为空时返回空列表，所以生产路径上这条分支不会命中——语料实测那几段无标点的
        // 独白，ICU 报的是 `sentences=1`（整段一句），不是零句。
        //
        // 但 [sentenceSplitter] 是本函数的公开参数，调用方可以传任何实现。没有这条判断，
        // 一个返回空列表的 splitter 会让 start 停在 -1，本函数直接返回空 chunks，
        // **整段正文被静默丢弃**。原样返回让调用方的 ParagraphTooLong 照常拒绝。
        if (sentences.isEmpty()) return listOf(paragraph)

        val chunks = mutableListOf<String>()
        var start = -1
        var end = -1
        sentences.forEach { sentence ->
            when {
                start < 0 -> {
                    start = sentence.startOffset
                    end = sentence.endOffset
                }
                // 加上这一句就超限：先收下已积累的部分。单句本身超限时，这里收下的就是那一句，
                // 它会原样带着超限长度流向调用方的校验——刻意如此，见类 KDoc。
                sentence.endOffset - start > maxParagraphChars -> {
                    chunks += paragraph.substring(start, end)
                    start = sentence.startOffset
                    end = sentence.endOffset
                }
                else -> end = sentence.endOffset
            }
        }
        if (start >= 0) chunks += paragraph.substring(start, end)

        return chunks.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 把段落贪心装箱，每箱不超过 [maxChapterChars]。
     *
     * 不重排、不合并跨段文本：段落边界是作者的，装箱只决定「哪几段同属一个 article」。
     * 单段超过上限时它独占一箱并原样超限，由调用方按 ChapterTooLong 拒绝。
     */
    private fun packIntoParts(units: List<String>, maxChapterChars: Int): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()

        units.forEach { unit ->
            val addition =
                if (current.isEmpty()) unit.length else unit.length + PARAGRAPH_SEPARATOR.length
            if (current.isNotEmpty() && current.length + addition > maxChapterChars) {
                parts += current.toString()
                current.setLength(0)
                current.append(unit)
            } else {
                if (current.isNotEmpty()) current.append(PARAGRAPH_SEPARATOR)
                current.append(unit)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()

        return parts
    }
}
