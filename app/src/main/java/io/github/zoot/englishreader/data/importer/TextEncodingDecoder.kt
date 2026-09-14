package io.github.zoot.englishreader.data.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码检测与严格解码。
 *
 * 改造前硬编码 Charsets.UTF_8：Windows 记事本存的 GBK 文本读进来整篇乱码，
 * 且不抛异常、静默入库。
 *
 * ## 判定顺序：BOM → NUL 密度 → 严格 UTF-8 → UTF-16 中文 → 严格 GBK
 *
 * 两处顺序是踩过坑定下来的，不要随意调换：
 *
 * - **NUL 密度判据必须在 UTF-8 之前**。无 BOM 的 UTF-16 *英文* 文本严格 UTF-8 解码
 *   会「成功」——"Hel" 存成 UTF-16LE 是 `48 00 65 00 6C 00`，每个字节都 < 0x80，
 *   全是合法的单字节 UTF-8 序列，产出夹大量 NUL 的垃圾且不抛异常。
 * - **UTF-16 中文判据必须在 UTF-8 之后**。它靠高字节区间，而 `0x4E..0x9F` 与 ASCII 的
 *   `'N'..'z'` 重叠，"ReaderText" 这类普通英文会整串落入区间。放在 UTF-8 前面等于
 *   拒收合法英文——合法 UTF-8 是确定性事实，不能被概率判据否决。
 *
 * 最后的 NUL/控制字符校验是兜底：任何走到这一步仍夹带大量控制字符的内容，
 * 要么是二进制文件、要么是判错了编码，一律拒绝，绝不入库。
 *
 * ## 支持范围
 *
 * 只承诺 **UTF-8、带 BOM 的 UTF-16LE/BE、GBK**。「Windows ANSI」不等于固定 GBK
 * （取决于系统代码页），其余编码一律报「编码不支持」而非猜测。
 *
 * ## 已知限制：部分无 BOM UTF-16 中文会被误判为 UTF-8
 *
 * 这是**编码本身的歧义，不是可修的缺陷**。短中文短语的 UTF-16 字节序列可能同时是
 * 合法 UTF-8：`"中午"` 的 UTF-16LE 是 `2D 4E 48 53`，四个字节都 < 0x80，UTF-8 严格
 * 解码直接得到 `-NHS` 并返回，后面的 UTF-16 中文判据根本执行不到。
 *
 * 无法靠调整顺序闭环——把判据搬回 UTF-8 之前就会重新拒收 "ReaderText" 那类英文。
 * 实测 16 个常见双字中文短语里有 8 个存在这种碰撞。
 *
 * 结论：**合法 UTF-8 永远优先**。要真正解决，需要产品侧提供「按指定编码重新导入」
 * 的入口，让用户在结果不对时手动指定 UTF-16/GBK。在那之前这是明确的已知限制。
 */
object TextEncodingDecoder {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** 判定「疑似无 BOM UTF-16」所需的最少样本字节数，太短的文本不足以统计。 */
    private const val UTF16_SNIFF_MIN_BYTES = 16

    /** 样本中某一奇偶位上 NUL 占比超过该比例即判为 UTF-16。 */
    private const val UTF16_NUL_RATIO = 0.3

    /** 用于统计的最大样本字节数（无需扫全文，取头部即可）。 */
    private const val UTF16_SNIFF_WINDOW = 4096

    /**
     * 「疑似 UTF-16 中文」判据所需的最少字节数（= 2 个字符）。
     *
     * 比 [UTF16_SNIFF_MIN_BYTES] 低：NUL 密度需要样本量才有统计意义，而「高字节全部
     * 落在有限几段区间」是逐字节的结构判据。门槛不能定高——`"中文"` 只有 4 字节，
     * 原先 8 字节的门槛让它漏网并被 GBK 解成乱码入库。
     */
    private const val UTF16_CHINESE_MIN_BYTES = 4

    /**
     * 中文文本在 UTF-16 下会用到的高字节集合。
     *
     * - `0x00` —— ASCII（U+0000..U+007F）
     * - `0x20..0x30` —— 通用标点与 CJK 符号（U+2000..U+30FF，含「，。…“”、」）
     * - `0x4E..0x9F` —— CJK 统一汉字（U+4E00..U+9FFF）
     * - `0xFF` —— 全角字符（U+FF00..U+FFEF，含「！？（）」）
     *
     * 标点段不可省：真实中文必然带标点，只认 CJK 段会让 `"中文测…试文章"` 这类文本
     * 漏判（省略号 U+2026 的高字节是 0x20）。
     */
    private val UTF16_CHINESE_HIGH_BYTES: Set<Int> =
        buildSet {
            add(0x00)
            addAll(0x20..0x30)
            addAll(0x4E..0x9F)
            add(0xFF)
        }

    /** 解码结果中控制字符占比超过该比例即判为二进制/编码判错。 */
    private const val MAX_CONTROL_CHAR_RATIO = 0.02

    /**
     * 将 [bytes] 解码为文本，并统一换行为 LF。
     *
     * @throws ImportException [ImportFailure.UnsupportedEncoding] 无法用支持的编码解码，
     *   或解码结果疑似二进制内容
     */
    fun decode(bytes: ByteArray): String {
        val raw = decodeToRawText(bytes)
        // 兜底去掉残留的 U+FEFF：BOM 字节已按编码跳过，但部分文件正文里还会再带一个。
        val text = normalizeNewlines(raw).removePrefix("\uFEFF")

        if (looksBinary(text)) {
            throw ImportException(ImportFailure.UnsupportedEncoding)
        }
        return text
    }

    private fun decodeToRawText(bytes: ByteArray): String {
        // ① BOM 优先：BOM 是明确声明，不需要任何猜测
        bomCharset(bytes)?.let { (charset, bomLength) ->
            return strictDecode(bytes, bomLength, charset)
                ?: throw ImportException(ImportFailure.UnsupportedEncoding)
        }

        // ② 无 BOM 时先按 NUL 密度排除 UTF-16。这条判据只在含 ASCII 的文本上成立
        //    （ASCII 字符的高字节是 NUL），对纯 CJK 文本完全失效——见 ④。
        if (looksLikeBomlessUtf16(bytes)) {
            throw ImportException(ImportFailure.UnsupportedEncoding)
        }

        // ③ 严格 UTF-8。必须排在任何启发式判据**之前**：合法 UTF-8 是确定性事实，
        //    不该被概率判据否决。曾把 ④ 放在这一步前面，结果 "ReaderText" 这类普通英文
        //    （字节全落在 0x4E..0x9F，与 CJK 高字节区间重叠）被直接拒收。
        strictDecode(bytes, 0, Charsets.UTF_8)?.let { return it }

        // ④ UTF-8 已失败。此时若直接试 GBK，无 BOM 的 UTF-16 中文文档往往能被 GBK
        //    "成功"解码成不含控制字符的乱码，绕过所有防线静默入库。故在回退 GBK 前
        //    先拦一道：UTF-16 编码的中文，其高字节只会落在少数几个区间。
        if (looksLikeUtf16Chinese(bytes)) {
            throw ImportException(ImportFailure.UnsupportedEncoding)
        }

        // ⑤ 严格 GBK（Windows 简体中文记事本的默认编码）
        gbkCharset()?.let { gbk ->
            strictDecode(bytes, 0, gbk)?.let { return it }
        }

        throw ImportException(ImportFailure.UnsupportedEncoding)
    }

    /** @return charset 与 BOM 字节数；无 BOM 时返回 null */
    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.startsWith(UTF8_BOM) -> Charsets.UTF_8 to UTF8_BOM.size
        // UTF-16LE 的 BOM (FF FE) 必须在 UTF-16BE (FE FF) 之前判，两者互为逆序不会混淆，
        // 但 FF FE 也是 UTF-32LE BOM 的前缀——UTF-32 不在支持范围内，按 UTF-16LE 处理后
        // 会在解码或 NUL 校验阶段被拒绝。
        bytes.startsWith(UTF16LE_BOM) -> Charsets.UTF_16LE to UTF16LE_BOM.size
        bytes.startsWith(UTF16BE_BOM) -> Charsets.UTF_16BE to UTF16BE_BOM.size
        else -> null
    }

    /**
     * 严格解码：遇到非法字节序列抛异常，而不是替换成 U+FFFD。
     *
     * 捕获范围是 [CharacterCodingException] 而非只 MalformedInputException——
     * UnmappableCharacterException 同属其子类，只捕前者会漏。
     *
     * @return 解码后的文本；解码失败返回 null（供调用方尝试下一种编码）
     */
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

    /**
     * 识别无 BOM 的 UTF-16：ASCII 文本存成 UTF-16 后，每个字符占两字节且其中一字节为 NUL，
     * 故 NUL 会规律地落在全部奇数位或全部偶数位上。
     *
     * 纯 UTF-8/GBK 文本正常不含 NUL，故只要某一奇偶位上 NUL 密集出现即可判定。
     */
    private fun looksLikeBomlessUtf16(bytes: ByteArray): Boolean {
        if (bytes.size < UTF16_SNIFF_MIN_BYTES) return false

        val window = minOf(bytes.size, UTF16_SNIFF_WINDOW)
        var evenNuls = 0
        var oddNuls = 0

        for (i in 0 until window) {
            if (bytes[i].toInt() == 0) {
                if (i % 2 == 0) evenNuls++ else oddNuls++
            }
        }

        val perPosition = window / 2
        if (perPosition == 0) return false

        // UTF-16BE 的 NUL 在偶数位（高位字节在前），UTF-16LE 在奇数位
        return evenNuls.toDouble() / perPosition >= UTF16_NUL_RATIO ||
            oddNuls.toDouble() / perPosition >= UTF16_NUL_RATIO
    }

    /**
     * 在回退 GBK **之前**拦住「无 BOM 的 UTF-16 中文文本」。
     *
     * ## 为什么需要这道额外的闸
     *
     * [looksLikeBomlessUtf16] 靠 NUL 密度，而汉字的两个字节都非零（「中」LE = 2D 4E），
     * 纯中文的 UTF-16 文本一个 NUL 都没有，那条判据完全失效。随后严格 UTF-8 解码失败、
     * **GBK 却往往能解码成功**，产出的乱码又不含控制字符，于是所有防线放行，一篇中文
     * 文档被静默解成乱码入库——用户根本不会发现。
     *
     * ## 为什么必须排在严格 UTF-8 **之后**
     *
     * 高字节区间与 ASCII 有重叠（`0x4E..0x9F` 覆盖 `'N'..'z'`），故 `"ReaderText"`
     * 这类普通英文的每个字节都落在区间内。若把本判据放在 UTF-8 之前，它们会在解码前
     * 就被拒收。合法 UTF-8 是确定性事实，不能被概率判据否决；本判据只负责决定
     * 「UTF-8 已失败时，是否还允许回退 GBK」。
     *
     * ## 判据本身
     *
     * 中文文本在 UTF-16 下用到的高字节只有几段：ASCII 0x00、标点 0x20..0x30、
     * CJK 0x4E..0x9F、全角 0xFF（见 [UTF16_CHINESE_HIGH_BYTES]）。若全部奇数位（LE）
     * 或全部偶数位（BE）都落在这些段里，即判为 UTF-16。
     *
     * 区间必须包含标点：真实中文必然带「，。……」，只认 CJK 段会让含标点的文本漏判
     * （曾如此，`"中文测…试文章"` 因省略号高字节是 0x20 而漏网）。
     *
     * ⚠️ **概率判据而非充要条件**。实测：合法 GBK 中文（含标点、2–20 字、UTF-8 解码
     * 失败因而真会走到这一步的样本）零误判，真 UTF-16 中文识别率约 97.6%。剩余漏判是
     * 高字节落在区间外的生僻字符；反方向的对抗性反例是「只由 GBK 扩展区生僻字组成」的
     * 文件会被误拒——那不是现实的中文文章，且失败方向是明确报错而非静默乱码入库。
     * 无 BOM 时编码判定本就无解，此处刻意选择前者。
     */
    private fun looksLikeUtf16Chinese(bytes: ByteArray): Boolean {
        // 一个汉字占两字节，两个汉字（4 字节）已足以判断；再短的输入没有判据可依
        if (bytes.size < UTF16_CHINESE_MIN_BYTES) return false
        // UTF-16 以双字节为单位，奇数长度不可能是完整的 UTF-16 文本
        if (bytes.size % 2 != 0) return false

        val window = minOf(bytes.size, UTF16_SNIFF_WINDOW).let { it - it % 2 }

        var allOddInRange = true
        var allEvenInRange = true
        for (i in 0 until window) {
            val value = bytes[i].toInt() and 0xFF
            val inRange = value in UTF16_CHINESE_HIGH_BYTES
            if (i % 2 == 0) {
                if (!inRange) allEvenInRange = false
            } else {
                if (!inRange) allOddInRange = false
            }
            if (!allOddInRange && !allEvenInRange) return false
        }
        // LE 的高字节在奇数位，BE 在偶数位
        return allOddInRange || allEvenInRange
    }

    /**
     * 兜底判定：解码「成功」但内容夹带大量控制字符，说明是二进制文件或编码判错。
     *
     * 制表符/换行/回车是正常文本内容，不计入。
     */
    private fun looksBinary(text: String): Boolean {
        if (text.isEmpty()) return false

        var controlChars = 0
        for (char in text) {
            if (char == '\t' || char == '\n' || char == '\r') continue
            if (char.code < 0x20 || char.code == 0x7F) controlChars++
        }
        return controlChars.toDouble() / text.length > MAX_CONTROL_CHAR_RATIO
    }

    /** CRLF / 单独 CR 统一为 LF，保证段落分隔的空行判定在各平台一致。 */
    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * GBK 在标准 JVM 上可能不存在（非中文环境的精简 JRE），故运行时查找而非静态引用。
     * Android 上始终可用。
     */
    private fun gbkCharset(): Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
