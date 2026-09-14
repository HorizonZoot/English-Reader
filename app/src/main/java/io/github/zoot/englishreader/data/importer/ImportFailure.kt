package io.github.zoot.englishreader.data.importer

/**
 * 导入失败原因（typed failure）。
 *
 * 改造前所有失败都走 require() 抛 IllegalArgumentException，被 ViewModel 统一映射为
 * 「文件内容为空」——加了多格式后，超长文件、DRM EPUB、乱码文件都会显示同一句错话。
 * 故失败原因必须是有类型的、可被界面区分映射的值。
 */
sealed interface ImportFailure {

    /** 解码/提取后正文为空或全空白（含「纯代码 Markdown 剥离后为空」）。 */
    data object EmptyContent : ImportFailure

    /**
     * 源文件字节数超过安全上限，读取已中止。
     *
     * 此时并不知道正文总字符数（为显示精确字数继续扫完整个大文件不可接受），
     * 故提示只能给出安全上限本身。
     */
    data class SourceTooLarge(val limitBytes: Int) : ImportFailure

    /** 成功解码/提取后正文字符数超过产品上限。此时字数已知，可精确提示。 */
    data class ContentTooLong(val actualChars: Int, val limitChars: Int) : ImportFailure

    /**
     * 整本书里某个章节的字符数超过单章上限。
     *
     * 与 [ContentTooLong] 分开，因为**用户能做的事不同**。单篇导入超限时文件是用户自己选的，
     * 换一个更短的文件是可行动作；而章节边界由出版方决定 —— 一个章节就是一个 linear spine
     * item，用户对此无从干预，读到「正文过长」只会去找一篇更短的文章，那不是这里的问题。
     *
     * 实测（32 本公版书，见 ADR-013）：这条上限拒掉 59% 的书，且 Standard Ebooks 这类
     * 一章一文件的精细切分源同样有 5/10 被拒 —— 说明触发原因不是打包粗糙，而是真实文学
     * 章节本来就比 [ImportBudget.MAX_CHAPTER_CHARS] 长。
     *
     * @param chapterTitle 触发上限的章节标题，供提示定位到具体章节
     */
    data class ChapterTooLong(
        val chapterTitle: String,
        val actualChars: Int,
        val limitChars: Int
    ) : ImportFailure

    /** 段落数超过渲染上限（同字数下段落结构差异会让渲染成本差一个数量级）。 */
    data class TooManyParagraphs(val actualParagraphs: Int, val limitParagraphs: Int) : ImportFailure

    /** 单个段落过长。 */
    data class ParagraphTooLong(val actualChars: Int, val limitChars: Int) : ImportFailure

    /**
     * 无法用支持的编码解码。
     *
     * 仅承诺 UTF-8、带 BOM 的 UTF-16LE/BE、GBK；其余（含二进制文件、无 BOM 的
     * UTF-16、非 GBK 的其他 ANSI 代码页）一律归此类。
     */
    data object UnsupportedEncoding : ImportFailure

    /** 选中的文件不是支持的格式。 */
    data object UnsupportedFormat : ImportFailure

    /** EPUB 结构损坏（缺 container.xml、无合法 rootfile、spine 为空等）。 */
    data object InvalidEpub : ImportFailure

    /** EPUB 的正文资源被加密（DRM）。仅字体混淆不算，会被忽略并继续导入。 */
    data object EncryptedEpub : ImportFailure

    /** 读取源文件时发生 IO 错误。 */
    data object SourceUnreadable : ImportFailure

    /**
     * 正文已成功解析，但写入数据库失败（磁盘满、Room 约束冲突等）。
     *
     * 必须与 [SourceUnreadable] 区分：源文件读得好好的，提示「源文件无法读取」会把用户
     * 引向检查文件本身，而真正该做的是清理存储空间。粘贴导入更明显——那里根本没有源文件。
     */
    data object StorageFailed : ImportFailure

    // ---- 整本书导入 ----

    /** 一本书的章节数超过上限。 */
    data class BookTooManyChapters(val actualChapters: Int, val limitChapters: Int) : ImportFailure

    /**
     * 一本书的正文总字符数超过上限。
     *
     * 与 [ContentTooLong] 分开：后者是单页渲染上限（对书而言即单章），
     * 两者的数字、触发时机和给用户的建议都不同。
     */
    data class BookTooLong(val actualChars: Int, val limitChars: Int) : ImportFailure

    /**
     * EPUB 正文类资源解压量超出上限。
     *
     * 与 [BookTooLong] 分开：那个按**字符数**判定，需要先解析出全部章节正文；
     * 本项在 preflight 阶段按**解压字节**判定，此时还没解析任何章节。
     * 一本 400 万字符的合法长篇，其 XHTML 标记解压后很容易超过
     * [ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES]，会先撞这一项——
     * 若沿用 [InvalidEpub]，用户会收到「文件损坏」而去重新下载一本没问题的书。
     *
     * 只带上限：有界读取在上限处停止，无法知道实际体量。
     */
    data class BookArchiveTooLarge(val limitBytes: Int) : ImportFailure

    /**
     * ZIP entry 数超过 [ImportBudget.MAX_ZIP_ENTRIES]。
     *
     * 与 [InvalidEpub] 分开的理由和 [BookArchiveTooLarge] 一样：一本插图密集的合法长篇
     * 很容易超过两千个 entry，报「文件已损坏」会让用户去重新下载一本本来没问题的书。
     * 这是结构规模超限，不是结构错误。
     *
     * 只带上限：预检在超限处立刻停止，不继续枚举，因此不知道实际 entry 数。
     */
    data class BookArchiveTooManyEntries(val limitEntries: Int) : ImportFailure

    /**
     * 该书已存在。
     *
     * 第一版不自动覆盖也不自动保留两份，只报告冲突并带上已有书的身份，
     * 供界面提示。合并/替换策略需要书架 UI 才能让用户做出知情选择。
     */
    data class DuplicateBook(val existingBookId: Long, val existingTitle: String) : ImportFailure

    /**
     * EPUB 结构合法，但没有任何 reading-order item 产出可阅读正文。
     *
     * 与 [InvalidEpub] 分开：纯图片书（扫描版、漫画）的 OPF/spine 完全合法，
     * 说它「结构损坏」会把用户引向错误的排查方向。第一版不支持此类书。
     */
    data object NoReadableChapters : ImportFailure
}

/**
 * 导入失败异常。
 *
 * 用异常而非 Result 返回：失败点分散在字节读取、解码、Markdown 剥离、ZIP/XML 解析
 * 等多层深处，逐层包装 Result 会让每层都要解包再重新包装。
 */
class ImportException(val failure: ImportFailure) :
    Exception(failure::class.simpleName)
