package io.github.zoot.englishreader.data.importer

import android.util.Xml
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * 整本书导入的安全前置校验。
 *
 * 这一步不可省略。Phase 0 的 Readium 兼容性 Spike 已实测确认：Readium 的
 * `Resource.read()` 会一次性materialize 整个资源，且**不执行本项目的任何 ZIP 预算**——
 * entry 数量、单 entry 解压上限、累计解压上限、spine 数量它都不管。声明了不支持加密算法的
 * spine 资源，它照样打开并返回明文字节。所以 ZIP bomb 与加密正文的防线必须在
 * publication 被打开**之前**建立，而不是之后。
 *
 * 校验范围限于 XML/HTML 出版物资源（container、OPF、NCX、NAV、XHTML）。图片与字体
 * entry 不计入累计解压量，其边界仅由 [ImportBudget.MAX_EPUB_ARCHIVE_BYTES] 提供——
 * 与现有 [EpubTextExtractor] 的行为一致，因为两者都只读文本。若将来章节适配器开始通过
 * `Resource.read()` 读封面图，这道闸不提供保护，需要另行扩展。
 */
internal object BookArchivePreflight {

    /** 校验通过后的观测结果，供上层决定章节容量与诊断。 */
    data class Report(
        val archiveEntries: Int,
        val inspectedTextEntries: Int,
        val inflatedTextBytes: Long,
        /** `linear != "no"` 的 spine item 数量，即可读章节的上界。 */
        val linearSpineItems: Int,
        /**
         * OPF `package/@version` 原文（如 `"2.0"` / `"3.0"`），缺失时为 null。
         *
         * Readium 3.0.3 不暴露这个值，而「有没有 NAV 目录」不能用来判 EPUB 版本——
         * Phase 0 实测 EPUB 2 + NCX 的 `tableOfContents` 同样非空。要正确记录
         * `books.sourceFormat`，只能在这里顺手读 OPF。
         */
        val packageVersion: String?
    )

    /**
     * 校验 EPUB 归档。
     *
     * @throws ImportException [ImportFailure.InvalidEpub] 表示结构损坏（缺 container.xml、
     *   OPF 不可解析、spine 指向不存在的资源等）；
     *   [ImportFailure.BookArchiveTooManyEntries] 表示 ZIP entry 数超限；
     *   [ImportFailure.BookArchiveTooLarge] 表示单项或累计解压字节超限；
     *   [ImportFailure.BookTooManyChapters] 表示 spine 数量超出全书章节上限。
     *
     *   体量超限不报 [ImportFailure.InvalidEpub]：一本合法的插图密集长篇不是「已损坏」，
     *   报错方向会让用户去重新下载一本没问题的书。
     */
    fun inspect(file: File): Report {
        val zip = try {
            ZipFile(file)
        } catch (_: Exception) {
            throw ImportException(ImportFailure.InvalidEpub)
        }

        zip.use { archive ->
            if (archive.size() > ImportBudget.MAX_ZIP_ENTRIES) {
                throw ImportException(
                    ImportFailure.BookArchiveTooManyEntries(ImportBudget.MAX_ZIP_ENTRIES)
                )
            }

            val entries = linkedMapOf<String, ZipEntry>()
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                val normalized = try {
                    normalizeEntryName(entry.name)
                } catch (_: ImportException) {
                    invalidEpub()
                }
                if (entries.put(normalized, entry) != null) invalidEpub()
            }

            val budget = InflatedBudget(archive)
            val container = entries[CONTAINER_PATH] ?: invalidEpub()
            val opfPath = parseRootfilePath(budget.read(container))
            val opfEntry = entries[opfPath] ?: invalidEpub()
            val opf = parseOpf(budget.read(opfEntry), opfPath)

            entries.forEach { (path, entry) ->
                if (path.isPublicationTextResource(opf.declaredDocumentPaths)) budget.read(entry)
            }

            return Report(
                archiveEntries = archive.size(),
                inspectedTextEntries = budget.inspectedEntries,
                inflatedTextBytes = budget.inflatedBytes,
                linearSpineItems = opf.linearSpineItems,
                packageVersion = opf.packageVersion
            )
        }
    }

    /**
     * 累计解压预算。
     *
     * 每个 entry 只读一次（[inspected] 去重），读取上限取「单项上限」与「剩余累计额度」的
     * 较小值，并多读一字节以判定是否超限——这与 [EpubTextExtractor] 的手法一致，避免
     * 「正好等于上限」被误判为超限。
     */
    private class InflatedBudget(private val zip: ZipFile) {
        private val inspected = mutableSetOf<String>()
        var inflatedBytes: Long = 0
            private set
        val inspectedEntries: Int
            get() = inspected.size

        fun read(entry: ZipEntry): ByteArray {
            if (!inspected.add(entry.name)) return ByteArray(0)

            val remaining = ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES - inflatedBytes
            if (remaining <= 0) archiveTooLarge(ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES)
            val perEntry = ImportBudget.MAX_XML_ENTRY_BYTES.toLong()
            // 记住是哪道闸真正约束了本次读取——超限时要报准确的那个限额，
            // 否则用户看到的数字与实际触发原因不符。
            val boundByCumulative = remaining < perEntry
            val limit = minOf(perEntry, remaining).toInt()
            val output = ByteArray(limit + 1)
            var filled = 0

            try {
                zip.getInputStream(entry).use { input ->
                    while (filled < output.size) {
                        val read = input.read(output, filled, output.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                }
            } catch (e: ImportException) {
                throw e
            } catch (_: Exception) {
                invalidEpub()
            }

            if (filled > limit) {
                archiveTooLarge(
                    if (boundByCumulative) {
                        ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES
                    } else {
                        ImportBudget.MAX_XML_ENTRY_BYTES
                    }
                )
            }
            inflatedBytes += filled
            return output.copyOf(filled)
        }
    }

    private fun parseRootfilePath(bytes: ByteArray): String {
        val parser = parser(bytes)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val mediaType = parser.getAttributeValue(null, "media-type")
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (mediaType == OPF_MEDIA_TYPE && fullPath != null) {
                        return try {
                            OcfPathNormalizer.normalize(fullPath)
                        } catch (_: ImportException) {
                            invalidEpub()
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            invalidEpub()
        }
        invalidEpub()
    }

    /**
     * 统计 `linear != "no"` 的 spine item。
     *
     * 超出 [ImportBudget.MAX_BOOK_CHAPTERS] 报 [ImportFailure.BookTooManyChapters] 而不是
     * `InvalidEpub`：这本书结构合法，只是超出本应用当前能处理的规模，用户看到的提示应当
     * 区分「文件坏了」与「书太大」。
     */
    /** OPF 单趟解析结果：spine 容量、正文类资源集合、package 版本。 */
    private class OpfInfo(
        val linearSpineItems: Int,
        val declaredDocumentPaths: Set<String>,
        val packageVersion: String?
    )

    /**
     * 单趟解析 OPF：manifest 的正文类资源、spine 线性项数、package 版本。
     *
     * 正文类资源按 **media-type** 判定，与生产 [EpubTextExtractor] 的 manifest 解析口径一致。
     * 不能只按扩展名：OPF 完全可以声明 `href="ch1.bin" media-type="application/xhtml+xml"`
     * 并放进 spine，Readium 会照读，而扩展名白名单会让它绕过单项与累计解压上限。
     */
    private fun parseOpf(bytes: ByteArray, opfPath: String): OpfInfo {
        val parser = parser(bytes)
        val baseDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val documents = mutableSetOf<String>()
        var version: String? = null
        var inSpine = false
        var count = 0
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "package" ->
                        version = parser.getAttributeValue(null, "version")?.trim()

                    event == XmlPullParser.START_TAG && parser.name == "item" -> {
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null && mediaType.isDocumentMediaType()) {
                            // href 是 URI reference，必须 percent-decode 后再解析
                            val resolved = runCatching {
                                OcfPathNormalizer.resolve(baseDir, href)
                            }.getOrNull()
                            if (resolved != null) documents.add(resolved)
                        }
                    }

                    event == XmlPullParser.START_TAG && parser.name == "spine" -> inSpine = true
                    event == XmlPullParser.END_TAG && parser.name == "spine" -> inSpine = false
                    event == XmlPullParser.START_TAG && inSpine && parser.name == "itemref" -> {
                        if (parser.getAttributeValue(null, "idref") == null) invalidEpub()
                        if (parser.getAttributeValue(null, "linear") != "no") {
                            count++
                            if (count > ImportBudget.MAX_BOOK_CHAPTERS) {
                                // 只报「超出」不报精确总数：一旦击穿就停止解析，继续数下去
                                // 对一个已经确定要拒绝的文件毫无价值，还得付完整 spine 的解析代价。
                                throw ImportException(
                                    ImportFailure.BookTooManyChapters(
                                        actualChapters = count,
                                        limitChapters = ImportBudget.MAX_BOOK_CHAPTERS
                                    )
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            invalidEpub()
        }
        if (count == 0) invalidEpub()
        return OpfInfo(count, documents, version)
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(bytes.inputStream(), null)
        }

    /**
     * 归一化 ZIP entry 名并复用生产的逃逸判据，但**不做 percent-decoding**。
     *
     * [OcfPathNormalizer.normalize] 的输入契约是 **URI 引用**（container.xml / OPF 里的
     * href），所以它第一步就 percent-decode。ZIP entry 名不是 URI，而是解码后的字面值：
     * 字面叫 `a%20b.xhtml` 的 entry，清单里会写成 `a%2520b.xhtml`。直接把 entry 名交给
     * `normalize` 等于多解一次码——map key 变成 `a b.xhtml`，而 opfPath 查的是
     * `a%20b.xhtml`，合法书会被误判损坏；`a b.xhtml` 与 `a%20b.xhtml` 共存时还会撞进
     * 重复键分支。
     *
     * 这里先把 `%` 转义成 `%25`，让 `normalize` 内部的解码步骤成为恒等变换，从而只借用它的
     * 反斜杠统一、`.` 消解、`..` 弹栈与 NUL 拒绝——逃逸校验语义与生产完全一致。
     */
    private fun normalizeEntryName(name: String): String =
        OcfPathNormalizer.normalize(name.replace("%", "%25"))

    /**
     * 正文类资源判定：manifest 声明的文档类型 **或** 已知的结构文件扩展名。
     *
     * 取并集而非只用 manifest：container.xml / encryption.xml 不在 manifest 里，
     * 而 NCX 在某些书里 media-type 写得不规范。两边都算，宁可多扣预算也不漏。
     */
    private fun String.isPublicationTextResource(declaredDocuments: Set<String>): Boolean {
        if (this in declaredDocuments) return true
        val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in TEXT_EXTENSIONS || this == CONTAINER_PATH || this == ENCRYPTION_PATH
    }

    /**
     * 与生产 [EpubTextExtractor] 同口径，另加 SVG——EPUB 3 允许 SVG content document
     * 进入 spine，Readium 会把它当资源读出来。
     */
    private fun String.isDocumentMediaType(): Boolean =
        contains("xhtml") || contains("html") || contains("svg")

    private fun invalidEpub(): Nothing = throw ImportException(ImportFailure.InvalidEpub)

    /**
     * 解压量超限。
     *
     * 刻意**不报** [ImportFailure.InvalidEpub]：文件本身是合法的，只是正文体量超出
     * 本机处理能力。报「文件损坏」会让用户去重新下载一本没有问题的书，
     * 而正确的动作是换一本更小的。
     *
     * 无法报告实际字节数——有界读取在上限处就停了，读到 limit+1 只能证明「超了」，
     * 不知道超多少。故 typed failure 只带上限。
     */
    private fun archiveTooLarge(limitBytes: Int): Nothing =
        throw ImportException(ImportFailure.BookArchiveTooLarge(limitBytes))

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
    private val TEXT_EXTENSIONS = setOf("xml", "opf", "ncx", "xhtml", "html", "htm")
}
