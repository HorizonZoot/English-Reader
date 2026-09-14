package io.github.zoot.englishreader.data.importer

/**
 * 整本书导入的领域模型。
 *
 * 这一层的存在理由是隔离：解析框架（当前为 Readium）的 `Publication` / `Link` / `Url` /
 * `MediaType` 一律不得越过它进入 Repository、ViewModel 或 UI。换解析器时受影响的只有
 * 适配层，不是整条阅读链路。
 */
data class ImportedBook(
    val metadata: BookMetadata,
    val chapters: List<ImportedChapter>,
    /** NAV/NCX 目录。可能为空（有 spine 但无目录的书是合法的）。 */
    val toc: List<BookTocNode>
) {
    init {
        require(chapters.isNotEmpty()) { "ImportedBook must contain at least one chapter" }
        // chapterIndex 必须是从 0 开始的连续序列：阅读位置恢复和上一章/下一章都按序号寻址，
        // 空洞会让「下一章」需要额外查表。空正文项在解析阶段过滤后必须重新编号。
        chapters.forEachIndexed { position, chapter ->
            require(chapter.chapterIndex == position) {
                "chapterIndex must be dense and zero-based"
            }
        }
    }

    /** 全书正文总字符数。用于全书预算校验与书架展示。 */
    val totalChars: Int get() = chapters.sumOf { it.content.length }
}

/**
 * 书籍元数据。
 *
 * [identifier] 是 EPUB 的 `dc:identifier`，可能缺失或为空——不少书只有标题。
 * 因此重复检测不能只依赖它，[contentFingerprint] 是必需的兜底。
 */
data class BookMetadata(
    val title: String,
    val author: String?,
    val language: String?,
    val identifier: String?,
    /** 正文内容指纹，identifier 缺失时作为重复导入的判据。 */
    val contentFingerprint: String,
    val sourceFormat: BookFormat
)

/** 解析出的可读章节。一个 `linear=yes` 的 reading-order item 对应一章。 */
data class ImportedChapter(
    /** 从 0 开始的连续序号，非原始 spine 下标。 */
    val chapterIndex: Int,
    val title: String,
    /** 在 EPUB 内的资源路径，用于诊断与将来的目录合并。 */
    val sourceHref: String,
    /** NAV/NCX 里为该资源标注的标题，可能缺失。 */
    val navigationTitle: String?,
    /** 已提取的纯文本正文，保证非空白。 */
    val content: String
) {
    init {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative" }
        require(content.isNotBlank()) { "chapter content must not be blank" }
    }
}

/**
 * 目录节点。
 *
 * 保留层级而不是提前展平：EPUB 3 NAV 的嵌套是用户可见的书籍结构，展平后无法还原。
 * 阅读链路需要线性序列时自行展平，这是消费侧的选择，不是模型的损失。
 */
data class BookTocNode(
    val title: String,
    /** 目标资源路径。目录项可以指向非线性资源，此时不对应任何章节。 */
    val href: String,
    /** href 的 fragment（`#` 之后的部分），首版仅保留不消费。 */
    val fragment: String?,
    val children: List<BookTocNode> = emptyList()
)

/** 书籍来源格式。首版仅 EPUB，但显式区分 2/3 便于诊断解析差异。 */
enum class BookFormat {
    EPUB2,
    EPUB3
}
