package io.github.zoot.englishreader.data.importer

/**
 * 正文预算校验。
 *
 * 五条导入入口（TXT / Markdown / EPUB 单篇 / 粘贴 / EPUB 整本的每一章）在拿到最终正文后
 * 都必须过这里，否则总有一条是漏网的——改造前粘贴导入完全无上限，整本书路径则漏掉了
 * 下面两条段落规则。
 *
 * ## 为什么拆成 [validate] 与 [validateParagraphStructure]
 *
 * 字符上限按入口取不同常量：单篇/粘贴用 [ImportBudget.MAX_IMPORT_CHARS]，章节用
 * [ImportBudget.MAX_CHAPTER_CHARS]。两者当前同值，但它们是可以分别校准的独立产品决定，
 * 若整本路径直接复用 [validate]，一旦将来解耦，章节就会被静默换成由单篇上限管辖。
 *
 * 段落规则相反，**必须**两条路径完全一致，故独立成 [validateParagraphStructure] 共享。
 *
 * ## 段落规则必须与 ParagraphAligner 一致
 *
 * 阅读页按 `ParagraphAligner`（util/ParagraphAligner.kt）的空行正则分段并逐段渲染。
 * 若此处用不同规则计数，校验通过的段数与实际渲染段数就会不一致，渲染上限形同虚设。
 * 故这里刻意复制它的分段语义：按「一个空行（含其间空白）」切分、trim、丢弃空段。
 */
object ImportBudgetValidator {

    /** 与 ParagraphAligner.PARAGRAPH_DELIMITER 保持一致。 */
    private val PARAGRAPH_DELIMITER = Regex("\\n\\s*\\n")

    /**
     * 校验正文是否在预算内。
     *
     * @param content 已解码/提取并规范换行后的正文
     * @throws ImportException 空内容、超字符上限、超段落上限、单段过长
     */
    fun validate(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            throw ImportException(ImportFailure.EmptyContent)
        }

        if (trimmed.length > ImportBudget.MAX_IMPORT_CHARS) {
            throw ImportException(
                ImportFailure.ContentTooLong(trimmed.length, ImportBudget.MAX_IMPORT_CHARS)
            )
        }

        validateParagraphStructure(trimmed)
    }

    /**
     * 只校验段落结构：段落数与单段长度。
     *
     * 供整本书导入逐章调用——章节就是一条 `ArticleEntity`，由同一个 `ReadingScreen`
     * 渲染，故这两条渲染上限对它与对单篇文章同等适用。字符上限由调用方按自己的常量施加。
     *
     * @param content 已 trim 的非空正文
     * @throws ImportException 全空白、超段落上限、单段过长
     */
    fun validateParagraphStructure(content: String) {
        val paragraphs = splitParagraphs(content)
        if (paragraphs.isEmpty()) {
            // 非空但全是空白/换行：阅读页会渲染出空态，不该入库
            throw ImportException(ImportFailure.EmptyContent)
        }

        if (paragraphs.size > ImportBudget.MAX_IMPORT_PARAGRAPHS) {
            throw ImportException(
                ImportFailure.TooManyParagraphs(paragraphs.size, ImportBudget.MAX_IMPORT_PARAGRAPHS)
            )
        }

        val longest = paragraphs.maxOf { it.length }
        if (longest > ImportBudget.MAX_PARAGRAPH_CHARS) {
            throw ImportException(
                ImportFailure.ParagraphTooLong(longest, ImportBudget.MAX_PARAGRAPH_CHARS)
            )
        }
    }

    /** 正文是否超过全文 AI 解释上限。**不拦截导入**，仅供成功提示附带说明。 */
    fun exceedsFullExplanationLimit(content: String): Boolean =
        content.trim().length > ImportBudget.MAX_FULL_EXPLANATION_CHARS

    /**
     * 规范化标题：trim、空白回退默认值、截到 [ImportBudget.MAX_TITLE_CHARS]。
     *
     * 所有导入入口共用。上限原先只存在于 ArticleImporter 的私有方法里，
     * 粘贴导入因此完全绕过它——`singleLine = true` 只控制显示，不限制输入长度，
     * 用户在标题框粘贴几万字符就会原样写进 Room。
     *
     * 截断上限按 UTF-16 单元计，但保证不切断 surrogate pair：`String.take` 会把 emoji
     * 之类的配对切成半个，留下孤立的高代理项，列表页显示成乱码方块且数据本身已损坏。
     *
     * 注意上限不是码点数——纯 emoji 标题实际只保留 [ImportBudget.MAX_TITLE_CHARS] 的一半个码点。
     * 这是有意的取舍：上限的目的是护住 Room 写入量，不是保证可见字符数。
     */
    fun normalizeTitle(raw: String?, fallback: String): String {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        return trimmed.takeCharsWithoutSplittingSurrogatePair(ImportBudget.MAX_TITLE_CHARS)
    }

    /**
     * 取前 [maxChars] 个 UTF-16 单元，但不切断 surrogate pair。
     *
     * 落在配对中间时退一格，宁可少一个字符也不产出半个码点。
     */
    private fun String.takeCharsWithoutSplittingSurrogatePair(maxChars: Int): String {
        if (length <= maxChars) return this
        val end = if (Character.isHighSurrogate(this[maxChars - 1])) maxChars - 1 else maxChars
        return substring(0, end)
    }

    private fun splitParagraphs(text: String): List<String> =
        text.split(PARAGRAPH_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
