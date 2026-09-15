package io.github.zoot.englishreader.data.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.zoot.englishreader.util.SentenceSplitter
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * 把 EPUB 解析成 [ImportedBook]。
 *
 * Readium 负责容器、OPF、spine、NAV/NCX 与资源读取；本类负责把它的类型投影成本项目的
 * 领域模型，并在投影处夹住 Phase 0 实测出来的三条约束：
 *
 * 1. **预算必须前置。** Readium 不执行本项目的 ZIP entry / 解压预算，
 *    [BookArchivePreflight] 必须在 publication 打开之前跑完。
 * 2. **加密正文必须前置拒绝。** Readium 会打开并返回声明了不支持加密算法的 spine 资源的
 *    明文字节，所以 DRM preflight 由现有 [EpubTextExtractor.detectEncryptedPayload] 承担。
 * 3. **纯图片章节要过滤。** 真实出版物的第一个 reading-order item 常是只含 `<img>` 的封面页，
 *    提取后正文为空。它不是错误，但不能作为可读章节，过滤后必须重新编号。
 */
@Singleton
class EpubBookParser @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 解析整本书。
     *
     * @param file 已由调用方完成有界复制的本地临时文件。
     * @throws ImportException 结构、预算、加密或无可读章节。
     * @throws CancellationException 原样上抛，不转换为 [ImportFailure]。
     */
    suspend fun parse(file: File): ImportedBook = withContext(Dispatchers.IO) {
        // 顺序不可调整：预算与加密都必须在 Readium 打开 publication 之前完成。
        val preflight = BookArchivePreflight.inspect(file)
        // 每次新建：EpubTextExtractor 持有可变的 inflatedBytes 计数器，且只有 extract() 会归零。
        // 在 @Singleton 上复用它会让计数跳导入累加，数百本后永久报 InvalidEpub。
        EpubTextExtractor(context).requireNotEncrypted(file)

        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(
            contentResolver = context.contentResolver,
            httpClient = httpClient
        )
        val publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null
            )
        )

        val asset = assetRetriever.retrieve(file).getOrNull() ?: invalidEpub()
        val publication = publicationOpener
            .open(asset = asset, allowUserInteraction = false)
            .getOrNull()
            ?: invalidEpub()

        try {
            publication.toImportedBook(preflight.packageVersion)
        } finally {
            publication.close()
        }
    }

    private suspend fun Publication.toImportedBook(packageVersion: String?): ImportedBook {
        val navigationTitles = buildNavigationTitleIndex(tableOfContents)

        val chapters = mutableListOf<ImportedChapter>()
        var totalChars = 0
        // 源资源序号，与 chapters.size 分开：一个资源可能切出多章，回退标题要按资源编号。
        var sourceOrdinal = 0

        readingOrder.forEach { link ->
            coroutineContext.ensureActive()

            // Readium 3.0.3 的 Resource 不是 Closeable，生命周期随 publication（见 Phase 0 Spike）。
            val bytes = get(link)?.read()?.getOrNull() ?: return@forEach

            val xhtml = try {
                XmlBytesDecoder.decode(bytes)
            } catch (_: ImportException) {
                // 单个资源解码失败不应废掉整本书：跳过并让「无可读章节」兜底。
                return@forEach
            }

            val content = try {
                XhtmlTextExtractor.extract(xhtml)
            } catch (_: ImportException) {
                return@forEach
            }

            // 封面页等纯图片资源提取后为空白。它们合法但不可读，过滤掉。
            if (content.isBlank()) return@forEach

            val path = link.decodedPath()
            val navigationTitle = path?.let { navigationTitles[it] }
            // 标题在预算校验**之前**解析：ChapterTooLong 要带上章节标题，
            // 否则用户只知道「有一章太长」却不知道是哪一章。
            //
            // 回退标题按**源资源**序号而非已产出章节数编号：切分后两者不再相等，用产出数会让
            // 「第 3 个资源」在前面有资源被切开时显示成 Chapter 5。无一章被切时两者恒等，
            // 故今天能导入的书标题逐字不变。
            val title = navigationTitle?.takeIf { it.isNotBlank() }
                ?: link.title?.takeIf { it.isNotBlank() }
                ?: fallbackChapterTitle(sourceOrdinal)
            sourceOrdinal++

            // 超限章节按段落边界切开，而不是把整本书拒掉。原先这里直接从 parse() 抛
            // ChapterTooLong，于是一章超限整本进不来——32 本公版语料只有 11 本（34%）能导入。
            // 每个产物仍在 MAX_CHAPTER_CHARS 以内，所以这不需要 ADR-013 要求的真机渲染基线。
            val parts = ChapterSplitter.split(
                content = content,
                sentenceSplitter = SentenceSplitter::split
            )

            parts.forEachIndexed { partIndex, part ->
                // 切分不到句子以下，所以单个句子超过段落上限时这一部分仍可能超限。
                // 那种正文只能拒绝：硬切会在句子中间断开，用户读到坏文本且无任何提示。
                if (part.length > ImportBudget.MAX_CHAPTER_CHARS) {
                    throw ImportException(
                        ImportFailure.ChapterTooLong(
                            chapterTitle = title,
                            actualChars = part.length,
                            limitChars = ImportBudget.MAX_CHAPTER_CHARS
                        )
                    )
                }

                // 章节就是一条 ArticleEntity，由同一个 ReadingScreen 渲染，故段落数与单段长度
                // 这两条**渲染**上限对它与对单篇文章同等适用。字符上限单独在上面按
                // MAX_CHAPTER_CHARS 施加，不能整体复用 ImportBudgetValidator.validate——
                // 那会把章节换成由单篇的 MAX_IMPORT_CHARS 管辖。
                ImportBudgetValidator.validateParagraphStructure(part)

                totalChars += part.length
                if (totalChars > ImportBudget.MAX_BOOK_TEXT_CHARS) {
                    throw ImportException(
                        ImportFailure.BookTooLong(
                            actualChars = totalChars,
                            limitChars = ImportBudget.MAX_BOOK_TEXT_CHARS
                        )
                    )
                }

                val split = parts.size > 1
                chapters += ImportedChapter(
                    // 过滤与切分后重新编号，保证 chapterIndex 连续。
                    chapterIndex = chapters.size,
                    // 只有真被切开时才加后缀：未切分的章节标题保持逐字不变。
                    title = if (split) partTitle(title, partIndex + 1, parts.size) else title,
                    sourceHref = link.href.toString(),
                    // navigationTitle 也要带后缀，不能只给 title 加。
                    // `BookTocScreen` 显示的是 navigationTitle（见该文件的 headlineContent），
                    // 只给 title 加后缀会让一章切出的若干部分在目录里显示成完全相同的几行。
                    // null 时不补：那种书 TOC 本来就回退到「第 N 章 / 共 M 章」，序号已能区分。
                    navigationTitle = navigationTitle?.let {
                        if (split) partTitle(it, partIndex + 1, parts.size) else it
                    },
                    content = part
                )
            }
        }

        if (chapters.isEmpty()) throw ImportException(ImportFailure.NoReadableChapters)
        if (chapters.size > ImportBudget.MAX_BOOK_CHAPTERS) {
            throw ImportException(
                ImportFailure.BookTooManyChapters(
                    actualChapters = chapters.size,
                    limitChapters = ImportBudget.MAX_BOOK_CHAPTERS
                )
            )
        }

        return ImportedBook(
            metadata = BookMetadata(
                title = metadata.title?.takeIf { it.isNotBlank() } ?: DEFAULT_BOOK_TITLE,
                author = metadata.authors.firstOrNull()?.name?.takeIf { it.isNotBlank() },
                language = metadata.languages.firstOrNull(),
                identifier = metadata.identifier?.takeIf { it.isNotBlank() },
                contentFingerprint = fingerprint(chapters),
                // 取自 OPF package/@version。**不能用「有没有 NAV 目录」判版本**：
                // Phase 0 实测 EPUB 2 + NCX 的 tableOfContents 同样非空，那样判会把
                // 真实 EPUB 2 记成 epub3。Readium 3.0.3 不暴露 version，故由 preflight 顺手读出。
                sourceFormat = packageVersion.toBookFormat()
            ),
            chapters = chapters,
            toc = tableOfContents.map { it.toTocNode() }
        )
    }

    /**
     * 递归建立「资源路径 → 目录标题」索引。
     *
     * 同一路径被多个目录项指向时保留最先出现的：depth-first 顺序下它是层级最浅的那个，
     * 更接近用户认知的章节名。
     */
    private fun buildNavigationTitleIndex(nodes: List<Link>): Map<String, String> {
        val index = mutableMapOf<String, String>()
        fun visit(link: Link) {
            val path = link.decodedPath()
            val title = link.title
            if (path != null && title != null && title.isNotBlank()) {
                index.putIfAbsent(path, title)
            }
            link.children.forEach(::visit)
        }
        nodes.forEach(::visit)
        return index
    }

    private fun Link.toTocNode(): BookTocNode {
        val url = Url(href.toString())
        return BookTocNode(
            title = title?.takeIf { it.isNotBlank() } ?: UNTITLED_TOC_ENTRY,
            href = href.toString(),
            fragment = url?.fragment,
            children = children.map { it.toTocNode() }
        )
    }

    private fun Link.decodedPath(): String? = Url(href.toString())?.path

    /**
     * 正文内容指纹。
     *
     * 只摘要正文，不含元数据：同一本书换个标题重新导入仍应被识别为重复。
     * `dc:identifier` 缺失时这是唯一的重复判据。
     *
     * 每章先写入字节长度再写正文，否则无分隔的拼接会让
     * `["AB", "C"]` 与 `["A", "BC"]` 得到同一摘要——章节边界不同的两个编排版
     * 会被误判为同一本书而拒绝导入。写入顺序本身绑定章节顺序。
     */
    private fun fingerprint(chapters: List<ImportedChapter>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        chapters.forEach { chapter ->
            val bytes = chapter.content.toByteArray()
            digest.update(bytes.size.toLongBytes())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 定长大端编码，避免变长十进制文本引入新的边界模糊。 */
    private fun Int.toLongBytes(): ByteArray =
        ByteArray(4) { i -> ((this shr ((3 - i) * 8)) and 0xFF).toByte() }

    private fun fallbackChapterTitle(index: Int): String = "Chapter ${index + 1}"

    /**
     * 被切开的章节各部分的标题。
     *
     * 用 `navigationTitle` 这个自由字符串承载「第几部分」，而不是给 Room 加层级结构：
     * ADR-012 明确目录层级只解析、不持久化，加一层会把那个决定推翻。后缀只在 [total] > 1
     * 时出现，所以未被切开的章节标题一个字符都不变。
     */
    private fun partTitle(base: String, part: Int, total: Int): String = "$base ($part/$total)"

    /**
     * OPF version → [BookFormat]。
     *
     * 只看主版本号：`"3.0"` / `"3.1"` 都是 EPUB 3。缺失或无法识别时按 EPUB 2 记——
     * `version` 在 EPUB 2 里就常被省略，而 EPUB 3 强制要求它。
     */
    private fun String?.toBookFormat(): BookFormat =
        if (this?.trim()?.startsWith("3") == true) BookFormat.EPUB3 else BookFormat.EPUB2

    private fun invalidEpub(): Nothing = throw ImportException(ImportFailure.InvalidEpub)

    private companion object {
        const val DEFAULT_BOOK_TITLE = "Untitled Book"
        const val UNTITLED_TOC_ENTRY = "Untitled"
    }
}
