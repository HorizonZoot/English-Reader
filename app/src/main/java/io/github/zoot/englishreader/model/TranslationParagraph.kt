package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.CacheKeyFactory
import io.github.zoot.englishreader.util.ParagraphAligner

/**
 * 一个待翻译段落的不可变快照。
 *
 * [paragraphIndex] 是**该 article 内**的段落序号，由
 * [ParagraphAligner.splitParagraphs] 产出，与阅读页渲染时的分段完全同源——两边各自
 * 分段是本模块最容易出的错：段落数一致而边界不同，checkpoint 就会把译文对到错误的段落上，
 * 且没有任何断言能在运行时发现。
 *
 * [sourceFingerprint] 覆盖段落自身文本。它回答的是「这段还是当初那段吗」，用于恢复时判断
 * 已完成的译文是否仍然可用。
 */
data class TranslationParagraph(
    val articleId: Long,
    val paragraphIndex: Int,
    val text: String,
    val sourceFingerprint: String
) {
    init {
        require(articleId > 0) { "articleId must be positive" }
        require(paragraphIndex >= 0) { "paragraphIndex must not be negative" }
        require(text.isNotBlank()) { "paragraph text must not be blank" }
        require(sourceFingerprint.isNotBlank()) { "sourceFingerprint must not be blank" }
    }

    /** 段落文本是用户正在读的内容，不进日志。 */
    override fun toString(): String =
        "TranslationParagraph(articleId=$articleId, paragraphIndex=$paragraphIndex, " +
            "text=[REDACTED], sourceFingerprint=$sourceFingerprint)"
}

/**
 * 源文本指纹。
 *
 * 与 AI 缓存身份 (`ExplanationCacheIdentity`) 是**两件不同的事**，不可合并：
 * - 缓存身份决定「这次请求能否复用别人的译文」，因此只含输出决定因素，跨文章共享；
 * - 指纹决定「这份 checkpoint 还属于当前文章吗」，是一次性的完整性校验。
 *
 * 把两者合并会立刻产生矛盾需求：缓存身份必须排除 article ID 才能跨文章复用，而指纹必须
 * 绑定具体文章才能挡住「旧任务覆盖新正文」。
 */
object TranslationFingerprint {

    /**
     * 整篇文章正文的指纹。
     *
     * 用**完整原始 content** 而非拼接后的段落：段落拆分会丢弃空行与首尾空白，若用户编辑
     * 只改动了这些位置，拼接指纹不变，而 `ParagraphAligner` 的实际分段可能已经变了。
     */
    fun forArticle(content: String): String = CacheKeyFactory.generate(ARTICLE_PREFIX, content)

    /** 单个段落文本的指纹。 */
    fun forParagraph(text: String): String = CacheKeyFactory.generate(PARAGRAPH_PREFIX, text)

    /**
     * 单个对照块文本的指纹。
     *
     * 前缀与 [forParagraph] 不同是有意的：同一段文本作为「整个原段落」和作为「恰好占满该段的
     * 一个块」时得到不同的值。两种 checkpoint 的 `paragraphIndex` 列含义不同（段落序号 vs
     * 块序号），若指纹相同，一份 legacy 行就能通过 block 路径的指纹校验并被当作块读取，而这种
     * 错位不会抛异常。分开前缀让版本分派出错时表现为「校验失败、拒绝发布」而不是静默错位。
     *
     * 这不影响 AI 缓存复用：缓存身份由 `AiExplanationRequestResolver` 按输出决定因素独立构造，
     * 与这里的完整性指纹无关，相同文本仍然命中同一条缓存。
     */
    fun forBlock(text: String): String = CacheKeyFactory.generate(BLOCK_PREFIX, text)

    /**
     * 已发布译文的指纹，绑定布局与它所描述的那一份确切译文。
     *
     * 只校验正文不够：布局里的中文 offset 只对发布当时写入的那个译文字符串成立，而
     * `articles.translation` 可能被别的路径覆盖（旧版全文翻译、将来的手工编辑）。正文没变而
     * 译文变了时，按旧 offset 裁切出来的中文会整体错位，且不会抛异常。
     */
    fun forTranslation(translation: String): String =
        CacheKeyFactory.generate(TRANSLATION_PREFIX, translation)

    private const val ARTICLE_PREFIX = "article-v1"
    private const val PARAGRAPH_PREFIX = "paragraph-v1"
    private const val BLOCK_PREFIX = "block-v1"
    private const val TRANSLATION_PREFIX = "translation-v1"
}

/**
 * 把逐段译文拼回 `ArticleEntity.translation` 的单一实现。
 *
 * 存在的理由是一个不对称：`ParagraphAligner.align` 按**空行**切分译文，再与英文段落
 * **按索引配对**。于是任何一段译文内部只要含一个空行，它就会被切成两段，此后所有段落的
 * 中文全部错位一格——第 5 段配到第 4 段的译文，且越往后越离谱。模型完全可能在译文里
 * 输出空行（分点、引文换行），所以这不是理论风险。
 *
 * 因此拼接前必须把每段内部的空行折叠掉。折叠而非拒绝：一段译文里多一个换行不值得让整个
 * 任务失败，而错位是静默的、用户只会以为翻译质量差。
 */
object TranslationOutputAssembler {

    /**
     * 规范化单段译文：去首尾空白，并把段内空行折叠为单个换行。
     *
     * 保留段内的单个换行——它不是段落分隔符（`PARAGRAPH_DELIMITER` 要求空行），
     * 硬折行原样保留不会影响配对。
     */
    fun normalizeParagraph(text: String): String =
        text.trim().replace(BLANK_LINE_RUN, "\n")

    /**
     * 按段落顺序拼接为文章译文。
     *
     * @param translations 顺序必须与 `ParagraphAligner.splitParagraphs(content)` 的英文段落
     *   顺序一一对应，且每段规范化后非空。
     *
     * 空段**不可**用空字符串占位：`join(["A", "", "C"])` 得到 `"A\n\n\n\nC"`，而分隔符正则
     * `\n\s*\n` 是贪婪的，会把四个换行吞成一个分隔符，切回来只有 2 段——恰好是本类要防的
     * 那种错位。既然没有能存活的占位形式，就在这里把它变成契约违约：
     * [TranslationSegment] 的 init 已保证 `TRANSLATED` 段必带非空译文，因此正常路径不会
     * 触发；真触发时抛出会中止 materialization 事务，保住文章原有译文，这比静默写入一份
     * 整体错位的译文好。
     */
    fun join(translations: List<String>): String =
        translations.joinToString(PARAGRAPH_SEPARATOR) { text ->
            normalizeParagraph(text).also {
                require(it.isNotEmpty()) {
                    "translated paragraph must not normalize to empty; " +
                        "an empty placeholder would collapse and misalign every later paragraph"
                }
            }
        }

    private const val PARAGRAPH_SEPARATOR = "\n\n"

    /** 一个换行 + 若干空白 + 再一个换行，与 `ParagraphAligner` 的分隔符定义对齐。 */
    private val BLANK_LINE_RUN = Regex("\\n\\s*\\n")
}

/**
 * 按 [WholeTranslationScope] 的 article 顺序展开段落快照。
 *
 * @param articleContents article ID → 正文。缺失的 article 会被跳过而不是抛错：范围快照
 *   与正文读取之间存在时间窗，文章可能已被删除；这时应当让任务少一篇，而不是让用户面对
 *   一个无法开始的崩溃。
 */
fun WholeTranslationScope.toParagraphSnapshot(
    articleContents: Map<Long, String>
): List<TranslationParagraph> = articleIds.flatMap { articleId ->
    val content = articleContents[articleId] ?: return@flatMap emptyList()
    ParagraphAligner.splitParagraphs(content).mapIndexed { index, text ->
        TranslationParagraph(
            articleId = articleId,
            paragraphIndex = index,
            text = text,
            sourceFingerprint = TranslationFingerprint.forParagraph(text)
        )
    }
}
