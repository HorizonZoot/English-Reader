package io.github.zoot.englishreader.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.zoot.englishreader.data.importer.BoundedSourceReader
import io.github.zoot.englishreader.data.importer.EpubTextExtractor
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportBudgetValidator
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.ImportFormat
import io.github.zoot.englishreader.data.importer.ImportFormatDetector
import io.github.zoot.englishreader.data.importer.MarkdownTextExtractor
import io.github.zoot.englishreader.data.importer.TextEncodingDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文章导入器。
 *
 * 是唯一的 IO façade：所有 ContentResolver / 文件访问都集中在此，ViewModel 不直接读文件。
 * 但**具体解析职责已拆出**到 data/importer 下的各个单一职责组件——否则一个
 * importFromUri() 要同时处理字节上限、字符集嗅探、ZIP、XML、Markdown 与标题解析，
 * 既无法单测也无法安全修改。
 *
 * 读取在 Dispatchers.IO 执行，避免阻塞主线程导致 ANR。
 */
@Singleton
class ArticleImporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 导入结果：标题 + 正文（已 trim 且过预算校验，保证非空） */
    data class ImportedArticle(
        val title: String,
        val content: String
    )

    /**
     * 从 SAF URI 导入文章，自动识别 TXT / Markdown / EPUB。
     *
     * @throws ImportException 所有失败情形都带 [ImportFailure]，供界面区分提示
     */
    suspend fun importFromUri(uri: Uri): ImportedArticle = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()

        // 先读一小段头部判格式：EPUB 走 ZipFile 随机访问（需要临时文件），
        // 文本类走一次性有界读取，两条路径读流的方式不同，故必须先判再读。
        val head = readHead(uri)
        val format = ImportFormatDetector.detect(head, displayName, mimeType)

        val (content, declaredTitle) = when (format) {
            ImportFormat.EPUB -> {
                val epub = EpubTextExtractor(context).extract(uri)
                epub.text to epub.declaredTitle
            }

            ImportFormat.MARKDOWN -> {
                val raw = TextEncodingDecoder.decode(readAllBounded(uri))
                MarkdownTextExtractor.extract(raw) to null
            }

            ImportFormat.PLAIN_TEXT -> {
                TextEncodingDecoder.decode(readAllBounded(uri)) to null
            }
        }

        // 四条入口共用同一套预算校验，避免某条路径漏网
        ImportBudgetValidator.validate(content)

        ImportedArticle(
            title = resolveTitle(declaredTitle, displayName, uri),
            content = content.trim()
        )
    }

    // ---- 读取 ----

    /** 读头部若干字节用于格式签名判定。读不到内容不算错——空文件由预算校验统一报 EmptyContent。 */
    private fun readHead(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException(ImportFailure.SourceUnreadable)
        return input.use {
            val buf = ByteArray(HEAD_PROBE_BYTES)
            var filled = 0
            while (filled < buf.size) {
                val read = it.read(buf, filled, buf.size - filled)
                if (read < 0) break
                filled += read
            }
            buf.copyOf(filled)
        }
    }

    /**
     * 一次开流读完整个文本源（有界）。
     *
     * 这里刻意重开一次流而非复用 [readHead] 的流：格式判定只需头部，但文本解码必须在
     * **完整**字节数组上做——前 8KB 全 ASCII、第 50000 字节才出现 GBK 中文的文件，
     * 靠采样会判错编码。重开的代价换来的是正确性。
     */
    private fun readAllBounded(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException(ImportFailure.SourceUnreadable)
        return input.use {
            BoundedSourceReader.readAtMost(it, ImportBudget.MAX_TEXT_SOURCE_BYTES)
        }
    }

    // ---- 标题 ----

    /**
     * 标题优先级：EPUB 的 dc:title → 文件显示名（去扩展名）→ [DEFAULT_TITLE]。
     *
     * dc:title 为空白时回退文件名。长度上限由
     * [ImportBudgetValidator.normalizeTitle] 统一施加——它与粘贴导入是同一入口，
     * 否则某条路径必然漏掉截断。
     */
    private fun resolveTitle(declaredTitle: String?, displayName: String?, uri: Uri): String {
        val fromMetadata = declaredTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (fromMetadata != null) {
            return ImportBudgetValidator.normalizeTitle(fromMetadata, DEFAULT_TITLE)
        }

        val rawName = displayName
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')   // 去掉可能的路径前缀
                ?.substringAfterLast(':')   // 去掉 SAF document id 的 scheme 前缀（如 primary:）

        val withoutExt = rawName?.let { stripKnownExtension(it) }

        return ImportBudgetValidator.normalizeTitle(withoutExt, DEFAULT_TITLE)
    }

    /** 去掉已支持格式的扩展名。未知扩展名保留——它可能是标题的一部分。 */
    private fun stripKnownExtension(name: String): String {
        for (ext in KNOWN_EXTENSIONS) {
            if (name.endsWith(ext, ignoreCase = true)) {
                return name.dropLast(ext.length)
            }
        }
        return name
    }

    /**
     * 查询文件显示名。
     *
     * 优先 [OpenableColumns.DISPLAY_NAME]（SAF 提供的真实显示名），
     * 查询失败返回 null 交由调用方 fallback。
     */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()

    companion object {
        /** 无法解析文件名时的默认标题 */
        const val DEFAULT_TITLE = "Untitled"

        /** 判 ZIP 签名只需 4 字节，取 64 字节留余量。 */
        private const val HEAD_PROBE_BYTES = 64

        private val KNOWN_EXTENSIONS = listOf(".txt", ".markdown", ".md", ".epub")
    }
}
