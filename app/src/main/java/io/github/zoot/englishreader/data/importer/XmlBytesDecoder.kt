package io.github.zoot.englishreader.data.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 按 XML 自身规则判定编码并解码 XML/XHTML 字节。
 *
 * ## 为什么不能直接 String(bytes, UTF_8)
 *
 * EPUB 的 container.xml / OPF / XHTML 允许用 UTF-16（带 BOM 且/或在 XML 声明里写
 * `encoding="UTF-16"`）。强行按 UTF-8 转 String 会产出夹 NUL 的垃圾，XML 声明此时已经
 * 救不回来——`XmlPullParser` 随后解析失败，**合法的书被判为结构损坏**。
 *
 * 这与 [TextEncodingDecoder] 的处境不同：那里面对的是无任何元数据的裸文本，只能靠嗅探；
 * 这里 XML 自己声明了编码，照着读就行。
 *
 * ## 判定顺序
 *
 * 1. BOM——最可靠的声明，优先
 * 2. 无 BOM 时扫过前导空白，看首个非空白字符是否为 `<` 及其所在的奇偶位（此时还没
 *    解码，只能看字节模式）
 * 3. 其余按 UTF-8
 *
 * XML 声明里的 `encoding` 属性刻意**不解析**：BOM 与字节模式已覆盖本项目承诺支持的
 * UTF-8 / UTF-16LE / UTF-16BE 三种；解析声明还得先猜编码才能读到声明，是循环依赖。
 * 声明与实际字节冲突时以字节为准——那才是真实内容。
 */
object XmlBytesDecoder {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * 解码 [bytes] 为 XML 文本。
     *
     * @throws ImportException [ImportFailure.InvalidEpub] 无法用支持的编码严格解码
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        bomCharset(bytes)?.let { (charset, bomLength) ->
            return strictDecode(bytes, bomLength, charset)
                ?: throw ImportException(ImportFailure.InvalidEpub)
        }

        // 无 BOM 的 UTF-16：跳过前导空白后，首个非空白字符必为 '<'，
        // 其所在的奇偶位决定字节序（详见 bomlessUtf16Charset）
        bomlessUtf16Charset(bytes)?.let { charset ->
            return strictDecode(bytes, 0, charset)
                ?: throw ImportException(ImportFailure.InvalidEpub)
        }

        return strictDecode(bytes, 0, Charsets.UTF_8)
            ?: throw ImportException(ImportFailure.InvalidEpub)
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.startsWith(UTF8_BOM) -> Charsets.UTF_8 to UTF8_BOM.size
        bytes.startsWith(UTF16LE_BOM) -> Charsets.UTF_16LE to UTF16LE_BOM.size
        bytes.startsWith(UTF16BE_BOM) -> Charsets.UTF_16BE to UTF16BE_BOM.size
        else -> null
    }

    /**
     * 无 BOM 时识别 UTF-16。
     *
     * XML 文档的首个非空白字符必须是 `<`，而 UTF-16 把每个 ASCII 字符编成「字节 + NUL」
     * （LE）或「NUL + 字节」（BE）。故只需在头部找到第一个非空白的 ASCII 字符，看它落在
     * 偶数位还是奇数位即可——比通用文本的概率型嗅探可靠得多。
     *
     * 不能只看首字节是否为 `<`：XML 声明前允许有空白，UTF-16LE 下 `"\n  <?xml"` 的首字节
     * 对是 `0A 00` 而非 `3C 00`，只认 `<` 会漏判、整份文档随后按 UTF-8 解成夹 NUL 的垃圾。
     *
     * 也不设固定嗅探窗口：任何窗口都能被「前导空白比窗口更长」的合法文档绕过。
     * entry 本身已有 [ImportBudget.MAX_XML_ENTRY_BYTES] 硬上限，扫到首个非空白字符
     * 或数据末尾即止，代价有界。
     */
    private fun bomlessUtf16Charset(bytes: ByteArray): Charset? {
        if (bytes.size < 4) return null

        // 跳过 UTF-16 编码的空白，找第一个可判定的字符
        var i = 0
        while (i + 1 < bytes.size) {
            val first = bytes[i]
            val second = bytes[i + 1]

            // LE：ASCII 字节在前、NUL 在后
            if (second == NUL_BYTE && first != NUL_BYTE) {
                if (isXmlWhitespace(first)) {
                    i += 2
                    continue
                }
                return if (first == LT) Charsets.UTF_16LE else null
            }
            // BE：NUL 在前、ASCII 字节在后
            if (first == NUL_BYTE && second != NUL_BYTE) {
                if (isXmlWhitespace(second)) {
                    i += 2
                    continue
                }
                return if (second == LT) Charsets.UTF_16BE else null
            }
            // 两字节都非 NUL：不是 ASCII 范围的 UTF-16，按非 UTF-16 处理
            return null
        }
        return null
    }

    private fun isXmlWhitespace(byte: Byte): Boolean =
        byte == 0x20.toByte() || byte == 0x09.toByte() ||
            byte == 0x0A.toByte() || byte == 0x0D.toByte()

    /** 严格解码：非法序列抛异常而非替换成 U+FFFD——静默产出替换字符等于篡改正文。 */
    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private const val LT: Byte = 0x3C
    private const val NUL_BYTE: Byte = 0x00
}
