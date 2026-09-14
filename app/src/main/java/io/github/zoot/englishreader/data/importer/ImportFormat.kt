package io.github.zoot.englishreader.data.importer

/**
 * 导入格式。
 *
 * 只存在于导入流程，**不进 Room**：ArticleEntity 里没有对应列，将来若真需要按格式筛选，
 * 应新增类型明确的 importFormat 列并写 Migration，而不是复用语义已混杂的 source 字段。
 */
enum class ImportFormat {
    PLAIN_TEXT,
    MARKDOWN,
    EPUB
}

/**
 * 格式判定。
 *
 * MIME 只是**提示**不是边界：国产 ROM 的 DocumentsProvider 对 .md 可能上报
 * application/octet-stream，对 .epub 可能上报 application/zip。故选中之后必须集中重判，
 * 且判据的可靠性排序为：**内容签名 > 扩展名 > MIME**。
 *
 * 尤其 EPUB 不能只信扩展名——用户完全可能选中一个改名成 .epub 的普通 ZIP。
 */
object ImportFormatDetector {

    /** 文件选择器的 MIME 过滤数组。 */
    val PICKER_MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/epub+zip",
        "application/zip",
        // 兜底：provider 无法识别时的通用类型。缺了它，被上报为 octet-stream 的 .md
        // 在选择器里直接不可见——用户根本选不到，选中后的扩展名兜底也就无从谈起。
        "application/octet-stream"
    )

    private val MARKDOWN_EXTENSIONS = listOf(".md", ".markdown")
    private val MARKDOWN_MIME_TYPES = listOf("text/markdown", "text/x-markdown")

    /** ZIP 本地文件头签名（0x50 0x4B 0x03 0x04）。EPUB 是 ZIP 容器，必以此开头。 */
    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * 已知非文本格式的签名与 MIME——内容优先策略下的拒绝清单。
     *
     * B+ 规则：宽容接受可信文本（包括无扩展名、.ini、.log），但拒绝高置信度的二进制格式，
     * 避免 PDF 元数据或图片 EXIF 被当成"合法 UTF-8"塞进阅读列表。
     */
    private val PDF_SIGNATURE = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)  // %PDF-
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF_SIGNATURE = byteArrayOf(0x47, 0x49, 0x46, 0x38)  // GIF8

    private val REJECTED_MIME_TYPES = setOf(
        "application/pdf",
        "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )

    /**
     * 按内容签名与文件名判定格式。
     *
     * @param headBytes 文件头部若干字节（至少 4 字节才能判 ZIP 签名）
     * @param displayName 文件显示名，可空
     * @param mimeType provider 上报的 MIME，可空
     * @throws ImportException [ImportFailure.UnsupportedFormat] 检测到已知非文本格式
     */
    fun detect(headBytes: ByteArray, displayName: String?, mimeType: String?): ImportFormat {
        // B+ 规则第一关：ZIP 签名优先识别（EPUB），避免被错误 MIME 误伤。
        // 国产文件管理器可能把 .epub 错报为 application/pdf，若 MIME 检查在前，
        // 合法 EPUB 会被拦截。内容签名 > MIME 的顺序在此体现。
        if (headBytes.startsWith(ZIP_SIGNATURE)) return ImportFormat.EPUB

        // B+ 规则第二关：高置信度的非文本格式签名直接拒绝
        if (headBytes.startsWith(PDF_SIGNATURE)) throw ImportException(ImportFailure.UnsupportedFormat)
        if (headBytes.startsWith(PNG_SIGNATURE)) throw ImportException(ImportFailure.UnsupportedFormat)
        // JPEG：所有合法 JPEG 都以 FF D8 FF 开头，后跟任意 marker（E0=JFIF, E1=Exif,
        // E2=ICC, E3=JPS, ...）。只匹配前三字节，不枚举 marker，避免漏过合法变体。
        if (headBytes.size >= 3 &&
            headBytes[0] == 0xFF.toByte() &&
            headBytes[1] == 0xD8.toByte() &&
            headBytes[2] == 0xFF.toByte()
        ) {
            throw ImportException(ImportFailure.UnsupportedFormat)
        }
        if (headBytes.startsWith(GIF_SIGNATURE)) throw ImportException(ImportFailure.UnsupportedFormat)

        // B+ 规则第三关：明确的非文本 MIME（即使内容可能解码也拒绝）
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        // 拒绝整个 image/* 而非枚举，避免 image/svg+xml 等可解码图片作为文本导入
        if (mime?.startsWith("image/") == true) throw ImportException(ImportFailure.UnsupportedFormat)
        if (mime in REJECTED_MIME_TYPES) throw ImportException(ImportFailure.UnsupportedFormat)

        val name = displayName?.lowercase().orEmpty()
        if (MARKDOWN_EXTENSIONS.any { name.endsWith(it) }) return ImportFormat.MARKDOWN

        if (mime in MARKDOWN_MIME_TYPES) return ImportFormat.MARKDOWN

        // B+ 规则第四关：未知扩展名、application/octet-stream 都继续走编码检测，
        // 只要能解码成可信文本就按 PLAIN_TEXT 导入（.ini / .log / 无扩展名文本都通过）
        return ImportFormat.PLAIN_TEXT
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
