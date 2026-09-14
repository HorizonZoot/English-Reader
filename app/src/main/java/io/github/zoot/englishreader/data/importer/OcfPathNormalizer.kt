package io.github.zoot.englishreader.data.importer

import java.io.ByteArrayOutputStream

/**
 * OCF（EPUB 容器）内路径的解码、归一化与逃逸校验。
 *
 * 从 [EpubTextExtractor] 提出来独立成对象，是因为它埋在需要 Android `Xml` 与真实 ZIP
 * 文件的类里时**无法纯 JVM 单测**——而这里恰好出过一个不做归一化就返回原串的缺陷：
 * `OEBPS/./content.opf` 校验通过但带着 `./` 去 `getEntry()` 查不到，合法书被误判损坏。
 *
 * ## 三步顺序不可调换：先解码 → 再归一化 → 最后校验逃逸
 *
 * OPF/container.xml 里的 href 是 **URI 引用**，空格等字符按 URI 规则 percent-encode。
 * ZIP entry 名却是解码后的字面值：清单写 `chapter%20one.xhtml`，entry 实际叫
 * `chapter one.xhtml`。不解码就查不到，合法书被拒。
 *
 * 但解码必须发生在逃逸校验**之前**：`%2e%2e%2f` 解码后才是 `../`，先校验后解码等于
 * 让攻击者用编码绕过整道防线。
 *
 * ## 为什么不是「禁止含 `..`」
 *
 * EPUB 的 manifest href 相对 OPF 目录，`../images/cover.xhtml` 这类先回父目录再进子目录
 * 的写法完全合法。一律拒绝 `..` 会挡掉正常的书。正确判据是**消解后是否逃出容器根**：
 * 用栈处理，`..` 弹栈，栈空时再弹即为逃逸。
 */
object OcfPathNormalizer {

    /**
     * 解码、归一化 ZIP 内路径并校验不逃逸容器根。
     *
     * - percent-decoding（`%20` → 空格），非法转义序列原样保留
     * - 反斜杠统一为正斜杠（部分打包工具会写 Windows 分隔符）
     * - 去掉前导 `/`（OCF 路径相对容器根，不是绝对路径）
     * - 消解 `.` 与空段
     * - `..` 弹栈；栈空时弹栈即判定逃逸
     * - 含 NUL 直接拒绝（会在部分文件系统 API 上截断路径）
     *
     * @return 可直接用于 `ZipFile.getEntry()` 的 entry 名
     * @throws ImportException [ImportFailure.InvalidEpub] 路径逃逸、含 NUL 或消解后为空
     */
    fun normalize(path: String): String {
        // 解码必须先于逃逸校验：%2e%2e%2f 解码后才是 ../
        return normalizeDecoded(percentDecode(path))
    }

    /**
     * 归一化**已解码**的路径。
     *
     * 与 [normalize] 分开是因为解码只能做一次：[resolve] 的 baseDir 来自上一次
     * normalize 的结果（已解码），再解一遍会把文件名里的字面 `%2e%2e` 解成 `..`，
     * 既可能读错章节，也让 DRM 路径比对错位。
     */
    private fun normalizeDecoded(decoded: String): String {
        if (decoded.contains(NUL)) throw ImportException(ImportFailure.InvalidEpub)

        val stack = mutableListOf<String>()
        for (segment in decoded.replace('\\', '/').trimStart('/').split('/')) {
            when (segment) {
                ".." -> {
                    // 栈空时还要弹 → 已经在容器根，再上一层就逃逸了
                    if (stack.isEmpty()) throw ImportException(ImportFailure.InvalidEpub)
                    stack.removeAt(stack.size - 1)
                }
                ".", "" -> Unit
                else -> stack += segment
            }
        }
        if (stack.isEmpty()) throw ImportException(ImportFailure.InvalidEpub)

        return stack.joinToString("/")
    }

    /**
     * 把相对 [baseDir] 的 [href] 解析为 ZIP 内路径。
     *
     * @param baseDir OPF 所在目录（已解码、已归一），容器根下时为空串
     */
    fun resolve(baseDir: String, href: String): String {
        // 片段标识（chapter.xhtml#section2）不是文件名的一部分。
        // 在**解码前**切分：编码为 %23 的 # 是文件名里的字面井号，不是片段分隔符。
        val cleanHref = href.substringBefore('#')
        // 只解码 href：baseDir 来自上一次 normalize，已经是解码后的字面路径。
        // 拼好再整体解码会对 baseDir 二次解码，把其中字面的 %2e%2e 解成 ..。
        val decodedHref = percentDecode(cleanHref)
        val combined = if (baseDir.isEmpty()) decodedHref else "$baseDir/$decodedHref"
        return normalizeDecoded(combined)
    }

    /**
     * URI percent-decoding（UTF-8）。
     *
     * 刻意手写而不用 `URLDecoder.decode`：后者会把 `+` 解成空格（那是
     * application/x-www-form-urlencoded 的规则，不是 URI 路径的规则），
     * 会破坏文件名里合法的加号。
     *
     * 非法转义序列（`%` 后不足两位十六进制）原样保留而不抛错——那更可能是文件名里
     * 的字面百分号，拒绝整本书代价过大。
     *
     * 只解码一次：重复解码会让 `%2525` 变成 `%`，给攻击者留出多轮编码绕过的空间。
     */
    private fun percentDecode(path: String): String {
        if (!path.contains('%')) return path

        // 按「未转义片段」整体编码，而不是逐 Char：单个 Char 可能只是 surrogate pair 的
        // 一半，`char.toString().toByteArray(UTF_8)` 会把它单独编成替换字符，
        // 于是 emoji 文件名被破坏、getEntry() 查不到，合法 EPUB 被判损坏。
        val bytes = ByteArrayOutputStream(path.length)
        var literalStart = 0
        var i = 0

        fun flushLiteral(until: Int) {
            if (until > literalStart) {
                bytes.write(path.substring(literalStart, until).toByteArray(Charsets.UTF_8))
            }
        }

        while (i < path.length) {
            // i + 3 <= length 才能取到完整两位十六进制；写成 i + 2 < length 会漏掉
            // 位于串尾的转义序列（如 "a%41"）
            if (path[i] == '%' && i + 3 <= path.length) {
                val value = parseHexPair(path[i + 1], path[i + 2])
                if (value != null) {
                    flushLiteral(i)
                    bytes.write(value)
                    i += 3
                    literalStart = i
                    continue
                }
            }
            i++
        }
        flushLiteral(path.length)

        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 解析两位十六进制，两位都必须是 **ASCII** `[0-9A-Fa-f]`。
     *
     * 既不能用 `"$hi$lo".toIntOrNull(16)`（接受符号前缀，`%+41` 会被解成 0x41、
     * `%-1b` 解成负数再 toByte() 成 0xFF），也不能用 `Character.digit`——
     * 后者认全角数字与字母：`Character.digit('Ａ', 16) == 10`，于是 `%ＡＦ` 被解成
     * 0xAF。两种情况都改写了文件名，让 getEntry() 查不到而误判合法书损坏。
     *
     * @return 0..255 的字节值；任一位不是 ASCII 十六进制数字则返回 null
     */
    private fun parseHexPair(hi: Char, lo: Char): Int? {
        val h = asciiHexValue(hi) ?: return null
        val l = asciiHexValue(lo) ?: return null
        return h shl 4 or l
    }

    private fun asciiHexValue(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }

    private const val NUL = '\u0000'
}
