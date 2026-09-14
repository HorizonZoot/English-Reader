package io.github.zoot.englishreader.data.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 短篇 EPUB 自解析导入。
 *
 * ## 为什么必须先复制到临时文件
 *
 * ZipFile 只接受 File，content:// URI 交不了。而 ZipInputStream 是顺序流，EPUB 内 entry
 * 顺序不保证与解析顺序一致——若 chapter1.xhtml 在前、container.xml/OPF 在后，读到
 * XHTML 时还不知道 spine，读完 OPF 又回不去已消费的流。故：
 *
 *   content URI → 有界复制到 cache 临时 .epub → ZipFile 随机访问 → finally 删除
 *
 * ## ZIP bomb 防线（五重）
 *
 * 1. 复制时硬性计数字节数，超 [ImportBudget.MAX_EPUB_ARCHIVE_BYTES] 立即停止
 * 2. entry 数量不超 [ImportBudget.MAX_ZIP_ENTRIES]
 * 3. 每个 XML/XHTML entry 解压时硬性计数，超 [ImportBudget.MAX_XML_ENTRY_BYTES] 立即停止
 *    （**不信 ZipEntry.getSize()**，该字段来自压缩包元数据，可被伪造）
 * 4. 整本书共享一个累计解压预算 [ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES]——
 *    单 entry 上限**不是**累计预算：500 个各自 4 MiB 的 entry 都合规，却能累计解压 2 GiB，
 *    而正文字符数仍可低于上限（内容多是注释、标签或被跳过的 script/style）
 * 5. spine 条目数不超 [ImportBudget.MAX_SPINE_ITEMS]（只计 linear != "no" 的条目）
 *
 * ## 路径安全
 *
 * 不解压到目录，传统 Zip Slip 不会直接发生，但仍须规范化 OCF 路径，且**必须使用消解后
 * 的路径**去查 entry 与比对加密清单——详见 [OcfPathNormalizer]。
 *
 * ## DRM 检测
 *
 * encryption.xml 也用于**字体混淆**，「存在即报错」会误判合法书。故须解析其中被加密的
 * 资源路径：spine 的 XHTML 被加密才报 [ImportFailure.EncryptedEpub]，仅字体被混淆则忽略。
 */
class EpubTextExtractor(private val context: Context) {

    /**
     * 单次解析的累计解压字节数。
     *
     * 每次 [extract] 开始时归零。[EpubTextExtractor] 由 ArticleImporter 每次导入新建实例，
     * 实例不跨导入复用，故无需并发保护。
     *
     * 注意：并发闸门是 ArticleListViewModel 的 importJob，不是 isImporting——后者只是
     * 给界面看的状态，本身存在「读 value → launch → 置 true」的窗口。
     */
    private var inflatedBytes = 0L

    /** 提取结果：正文 + OPF 里声明的书名（可空，供标题解析优先使用）。 */
    data class EpubContent(
        val text: String,
        val declaredTitle: String?
    )

    /**
     * 从 [uri] 提取 EPUB 正文与书名。
     *
     * @throws ImportException 见 [ImportFailure]
     */
    fun extract(uri: Uri): EpubContent {
        inflatedBytes = 0L
        val tmp = copyToTemp(uri)
        try {
            return extractFromFile(tmp)
        } finally {
            // 无论成功、失败还是协程取消，临时文件都必须删除，不留半成品
            deleteTempOrLog(tmp)
        }
    }

    // ---- 有界复制到临时文件 ----

    private fun copyToTemp(uri: Uri): File {
        val tmp = File.createTempFile("epub_import_", ".epub", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportException(ImportFailure.SourceUnreadable)
            input.use { copyBounded(it, tmp) }
        } catch (e: CancellationException) {
            // 取消不是失败：必须原样上抛，否则协程取消语义被吞成一个普通导入错误
            deleteTempOrLog(tmp)
            throw e
        } catch (e: ImportException) {
            deleteTempOrLog(tmp)
            throw e
        } catch (e: Exception) {
            deleteTempOrLog(tmp)
            throw ImportException(ImportFailure.SourceUnreadable)
        }
        return tmp
    }

    /**
     * 删除临时文件，失败时留痕。
     *
     * 这里的失败路径与 [extract] 的 finally 是**互斥**的：copyToTemp 抛异常时
     * extract 的 try 还没进入，那个 finally 不会执行，故本方法必须在每个 catch 里
     * 各自调用一次——静默 `tmp.delete()` 会让 EPUB 正文残留在 cacheDir 且查不到原因。
     */
    private fun deleteTempOrLog(tmp: File) {
        if (!tmp.delete() && tmp.exists()) {
            Log.w(TAG, "Failed to delete temp EPUB")
            // 进程退出时兜底清理，避免长期残留
            tmp.deleteOnExit()
        }
    }

    /**
     * 复制时硬性计数。
     *
     * OpenableColumns.SIZE 可做快速预检，但 provider 可能返回 null 或错值，
     * 故不依赖它——只信实际读到的字节数。
     */
    private fun copyBounded(input: InputStream, dest: File) {
        val limit = ImportBudget.MAX_EPUB_ARCHIVE_BYTES
        dest.outputStream().use { out ->
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                total += read
                if (total > limit) throw ImportException(ImportFailure.SourceTooLarge(limit))
                out.write(buf, 0, read)
            }
        }
    }

    // ---- 主解析流程 ----

    /**
     * 只做 DRM 前置检查，不提取正文。
     *
     * 供整本书导入路径（[EpubBookParser]）在交给 Readium 之前调用。Phase 0 Spike 实测：
     * Readium 3.0.3 会打开并返回声明了不支持加密算法的 spine 资源的**明文字节**，
     * 不产生任何 typed error，所以加密判定不能依赖 Readium，必须在它之前跑。
     *
     * 与 [extract] 共用同一套 `readEncryptedPaths` / spine 解析逻辑，避免两条路径
     * 对「什么算加密正文」产生分歧：字体混淆忽略，spine 正文被加密才拒绝。
     *
     * @throws ImportException [ImportFailure.EncryptedEpub] 或 [ImportFailure.InvalidEpub]
     */
    fun requireNotEncrypted(file: File) {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw ImportException(ImportFailure.InvalidEpub)
        }
        zip.use { z ->
            val opfPath = readRootfilePath(z)
            val opf = readOpf(z, opfPath)
            val spinePaths = opf.spine.map { idref ->
                val href = opf.manifest[idref] ?: throw ImportException(ImportFailure.InvalidEpub)
                resolvePath(opf.opfDir, href)
            }
            if (spinePaths.isEmpty()) throw ImportException(ImportFailure.InvalidEpub)
            val encrypted = readEncryptedPaths(z)
            if (spinePaths.any { it in encrypted }) {
                throw ImportException(ImportFailure.EncryptedEpub)
            }
        }
    }

    private fun extractFromFile(file: File): EpubContent {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw ImportException(ImportFailure.InvalidEpub)
        }

        zip.use { z ->
            if (z.size() > ImportBudget.MAX_ZIP_ENTRIES) {
                throw ImportException(ImportFailure.InvalidEpub)
            }

            val opfPath = readRootfilePath(z)
            val opf = readOpf(z, opfPath)

            // manifest href 相对 OPF 所在目录，须并入路径后才能在 ZIP 里查到。
            // 任一 spine 条目解析不出 manifest document 就是结构损坏——静默丢弃它会
            // 导入一篇残缺文章并报告成功，用户不知道正文已被截断，比直接拒绝更糟。
            val spinePaths = opf.spine.map { idref ->
                val href = opf.manifest[idref] ?: throw ImportException(ImportFailure.InvalidEpub)
                resolvePath(opf.opfDir, href)
            }
            if (spinePaths.isEmpty()) throw ImportException(ImportFailure.InvalidEpub)

            // DRM：只有 spine 正文被加密才拒绝；字体混淆不影响阅读，忽略。
            // 两侧路径都经 OcfPathNormalizer 归一，否则 ./ 之类差异会让比对漏判。
            val encrypted = readEncryptedPaths(z)
            if (spinePaths.any { it in encrypted }) {
                throw ImportException(ImportFailure.EncryptedEpub)
            }

            val text = concatChapters(z, spinePaths)
            if (text.isEmpty()) throw ImportException(ImportFailure.EmptyContent)

            return EpubContent(text, opf.declaredTitle)
        }
    }

    /**
     * 逐章提取并拼接。
     *
     * 章节间插一个段落边界。章节标题作为独立段落即可，**不得**为迎合 SentenceSplitter
     * 给它补句号——那是篡改正文。
     *
     * 提取过程中累计字符数，超预算立即停止，不先构造超大 String 再判断。
     */
    private fun concatChapters(zip: ZipFile, paths: List<String>): String {
        val sb = StringBuilder()
        for (path in paths) {
            // entry 缺失同样是结构损坏，不能跳过：静默少一章会让用户读到残缺全文
            // 却看到「导入成功」
            val entry = zip.getEntry(path) ?: throw ImportException(ImportFailure.InvalidEpub)
            val chapter = XhtmlTextExtractor.extract(readEntryXml(zip, entry))
            // 章节本身可能只有图片或空 body，那是合法的，跳过不拼即可
            if (chapter.isEmpty()) continue

            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(chapter)

            // 边提取边判，避免为一本超大书先拼出完整 String
            if (sb.length > ImportBudget.MAX_IMPORT_CHARS) {
                throw ImportException(
                    ImportFailure.ContentTooLong(sb.length, ImportBudget.MAX_IMPORT_CHARS)
                )
            }
        }
        return sb.toString().trim()
    }

    // ---- container.xml ----

    /**
     * 读 META-INF/container.xml 取 OPF 路径。
     *
     * rootfile 须按 `media-type="application/oebps-package+xml"` 选择，
     * 而不是取第一个 .opf——多 rootfile 的书里第一个可能是别的渲染版本。
     */
    private fun readRootfilePath(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml")
            ?: throw ImportException(ImportFailure.InvalidEpub)
        val parser = newParser(readEntryXml(zip, entry))

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val mediaType = parser.getAttributeValue(null, "media-type")
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (mediaType == OPF_MEDIA_TYPE && fullPath != null) {
                        return validateZipPath(fullPath)
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw ImportException(ImportFailure.InvalidEpub)
        }
        throw ImportException(ImportFailure.InvalidEpub)
    }

    // ---- OPF ----

    private data class Opf(
        val opfDir: String,
        /** id → href（相对 opfDir），只含 XHTML/HTML 条目 */
        val manifest: Map<String, String>,
        /** 有序 idref 列表 */
        val spine: List<String>,
        val declaredTitle: String?
    )

    private fun readOpf(zip: ZipFile, opfPath: String): Opf {
        val entry = zip.getEntry(opfPath) ?: throw ImportException(ImportFailure.InvalidEpub)
        val xml = readEntryXml(zip, entry)
        // OPF 所在目录：manifest href 都相对它
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var title: String? = null
        // dc:title 只在 <metadata> 内有效。不限定作用域会把 <reference title="..."> 之类
        // 同名元素误取为书名。
        var inMetadata = false
        val parser = newParser(xml)

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "metadata" -> inMetadata = true

                        "item" -> readManifestItem(parser)?.let { (id, href) ->
                            manifest[id] = href
                        }

                        "itemref" -> readSpineItemRef(parser)?.let { idref ->
                            if (spine.size >= ImportBudget.MAX_SPINE_ITEMS) {
                                throw ImportException(ImportFailure.InvalidEpub)
                            }
                            spine += idref
                        }

                        // dc:title：namespace 已开启，按 local name 匹配。
                        // nextText() 会把 parser 推进到对应的 END_TAG，外层 next() 仍安全。
                        "title" -> if (inMetadata && title == null) {
                            title = runCatching { parser.nextText() }
                                .getOrNull()
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "metadata") {
                    inMetadata = false
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw ImportException(ImportFailure.InvalidEpub)
        }

        return Opf(opfDir, manifest, spine, title)
    }

    /** @return id to href，非 XHTML/HTML 条目返回 null（图片/CSS/字体不是正文） */
    private fun readManifestItem(parser: XmlPullParser): Pair<String, String>? {
        val id = parser.getAttributeValue(null, "id") ?: return null
        val href = parser.getAttributeValue(null, "href") ?: return null
        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
        val isDocument = mediaType.contains("xhtml") || mediaType.contains("html")
        return if (isDocument) id to href else null
    }

    /**
     * @return idref；`linear="no"` 返回 null（合法的主动忽略：封面/版权页等非线性内容）
     * @throws ImportException [ImportFailure.InvalidEpub] itemref 缺 idref——那是结构损坏，
     *   与 linear="no" 性质完全不同，混为一谈会静默跳过损坏条目、导入残缺文章却报成功
     */
    private fun readSpineItemRef(parser: XmlPullParser): String? {
        if (parser.getAttributeValue(null, "linear") == "no") return null
        return parser.getAttributeValue(null, "idref")
            ?: throw ImportException(ImportFailure.InvalidEpub)
    }

    // ---- encryption.xml ----

    /**
     * @return 被加密的资源路径集合（相对 ZIP 根，已规范化）。无 encryption.xml 时为空集。
     *
     * **只有 entry 不存在才返回空集**。entry 存在却读不出来（解压超单项上限、CRC 损坏、
     * 其他 IO 错误）必须拒绝导入——把读取失败解释成「没有任何加密资源」是 fail-open，
     * 与本方法在 XML 解析失败时 fail-closed 的策略自相矛盾，且正好是攻击者想要的结果。
     */
    private fun readEncryptedPaths(zip: ZipFile): Set<String> {
        val entry = zip.getEntry("META-INF/encryption.xml") ?: return emptySet()

        val xml = try {
            readEntryXml(zip, entry)
        } catch (e: ImportException) {
            // 累计预算/单项上限被击穿时保留原始原因，其余按「无法判定加密范围」拒绝
            throw if (e.failure == ImportFailure.InvalidEpub) e
            else ImportException(ImportFailure.EncryptedEpub)
        } catch (e: Exception) {
            throw ImportException(ImportFailure.EncryptedEpub)
        }

        val encrypted = mutableSetOf<String>()
        val parser = newParser(xml)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "CipherReference") {
                    // 缺 URI 或路径非法都意味着「有资源被加密，但无法确定是哪个」。
                    // 跳过等于把它当成未加密——若那正是 spine 正文，DRM 检测直接失效，
                    // 程序会去解析密文并产出乱码。故 fail-closed。
                    val uri = parser.getAttributeValue(null, "URI")
                        ?: throw ImportException(ImportFailure.EncryptedEpub)
                    encrypted += try {
                        validateZipPath(uri)
                    } catch (e: ImportException) {
                        throw ImportException(ImportFailure.EncryptedEpub)
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // 声明损坏：无法判定加密范围，按「不支持」处理而不是放行
            throw ImportException(ImportFailure.EncryptedEpub)
        }
        return encrypted
    }

    // ---- 工具 ----

    /**
     * 有界读取单个 entry，返回原始字节。
     *
     * 两处关键：
     *
     * 1. **不信 [ZipEntry.getSize]**——它来自压缩包元数据，可被伪造成很小的值。
     *    只信实际解压出的字节数。
     * 2. 本次读取上限取 `min(单项上限, 累计剩余预算) + 1`。若固定用单项上限，
     *    在累计已用 23 MiB 时仍会先解压完整的 4 MiB entry 才发现超预算，
     *    真实解压量可达 `总预算 + 单项上限`——声明的 24 MiB 就不是硬上限了。
     *
     * 返回字节而非 String：编码由 [decodeXml] 按 XML 规则判定，不能在这里预设 UTF-8。
     */
    private fun readEntryBytes(zip: ZipFile, entry: ZipEntry): ByteArray {
        val remaining = ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES - inflatedBytes
        if (remaining <= 0) throw ImportException(ImportFailure.InvalidEpub)

        val limit = minOf(ImportBudget.MAX_XML_ENTRY_BYTES.toLong(), remaining).toInt()
        val buf = ByteArray(limit + 1)
        var filled = 0

        zip.getInputStream(entry).use { input ->
            while (filled < buf.size) {
                val read = input.read(buf, filled, buf.size - filled)
                if (read < 0) break
                filled += read
            }
        }
        // 读满 limit + 1：要么击穿单项上限，要么击穿剩余累计预算，两者都拒绝
        if (filled > limit) throw ImportException(ImportFailure.InvalidEpub)

        inflatedBytes += filled
        return buf.copyOf(filled)
    }

    /** 读 entry 并按 XML 规则解码为文本。 */
    private fun readEntryXml(zip: ZipFile, entry: ZipEntry): String =
        XmlBytesDecoder.decode(readEntryBytes(zip, entry))

    private fun newParser(xml: String): XmlPullParser = XmlParsers.forXml(xml)

    /**
     * 规范化并验证 ZIP 内路径，确保不逃逸容器根。
     *
     * 委托 [OcfPathNormalizer]。该逻辑原先内联在本类，而本类需要 Android Xml 与真实 ZIP
     * 文件、无法纯 JVM 单测——正因如此漏过一个缺陷：校验通过却返回**未消解**的原串，
     * `OEBPS/./content.opf` 带着 `./` 去 getEntry() 查不到，合法书被误判为结构损坏；
     * DRM 检测同理，两侧路径未归一到同一形式就会漏判被加密的正文。
     */
    private fun validateZipPath(raw: String): String = OcfPathNormalizer.normalize(raw)

    /** manifest href 相对 OPF 目录，合并为 ZIP 内路径。 */
    private fun resolvePath(opfDir: String, href: String): String =
        OcfPathNormalizer.resolve(opfDir, href)

    private companion object {
        const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
        const val TAG = "EpubTextExtractor"
    }
}
