package io.github.zoot.englishreader.data.importer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * XHTML → 纯文本。
 *
 * ## br 不是段落
 *
 * 一个曾被写错的规则是「p/div/br → 空行」。`<br>` 是**段内换行**，把它转成空段会虚增
 * 段落数，并直接破坏 ParagraphAligner 的下标配对（英文段与译文段按 index 一一对应，
 * 段数一变全篇错位）。正确规则：
 *
 * - `p/div/section/article/h1-h6/li/blockquote/tr/dd/dt` → 段落边界（两个换行）
 * - `br` → 单换行
 * - `script/style/head/title` → 连同内部文本一起跳过（章节 `<head><title>` 的文本
 *   不会进入正文，否则每章都会多出一个由标题构成的伪段落）
 *
 * ## 安全
 *
 * 关闭 DOCTYPE 与外部实体处理（XXE / billion-laughs 防线）。开 namespace 并按
 * **local name** 匹配——EPUB 的 XHTML 常带 `xhtml:` 前缀，按限定名匹配会全部漏掉。
 */
object XhtmlTextExtractor {

    private val PARAGRAPH_TAGS = setOf(
        "p", "div", "section", "article", "blockquote", "li", "tr", "dd", "dt",
        "h1", "h2", "h3", "h4", "h5", "h6"
    )
    private val SKIP_CONTENT_TAGS = setOf("script", "style", "head", "title")

    private val THREE_OR_MORE_NEWLINES = Regex("\n{3,}")
    private val TRAILING_SPACES = Regex("[ \\t]+\n")

    /** 命名实体引用。数字引用（&#8212;）由 XML 解析器原生处理，不在此列。 */
    private val NAMED_ENTITY = Regex("&([A-Za-z][A-Za-z0-9]{1,31});")

    /** XML 自带的五个实体，解析器认识，必须原样留给它。 */
    private val XML_BUILTIN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    /**
     * 有明确闭合标记的不可解析区段：其中的 `&xxx;` 是字面文本而非实体引用。
     *
     * DOCTYPE 不在此表——它的结束标记是单个 `>`，但引号内的 `>` 不算，
     * 故由 [declarationEnd] 单独处理。
     */
    private val UNPARSED_SECTIONS = listOf(
        "<![CDATA[" to "]]>",
        "<!--" to "-->",
        "<?" to "?>"
    )

    /**
     * HTML 命名实体替换表。
     *
     * XML 解析器只认识 XML 的五个内建实体，遇到 `&nbsp;` 会抛「unresolved entity」，
     * 而 EPUB 正文里排版类实体极其常见——不预先替换，大量合法书会被误判为「结构损坏」。
     *
     * 只列真实 EPUB 正文里高频出现的排版符号。带变音符的拉丁字母不在此列：EPUB 用 UTF-8
     * 直接写 `café` 而不是 `caf&eacute;`，为它们维护上百条映射的收益远低于出错风险。
     * 表外的未知实体转成 `&amp;name;` 保留为字面文本（见 [resolveEntitiesInText]），
     * 既不让一个陌生实体否决整本书，也不会把 `caf&eacute;` 删成 `caf`。
     *
     * 全部用 \uXXXX 转义而非字面字符：本文件曾因非 ASCII 字面量被工具链改坏过。
     */
    private val HTML_ENTITIES = mapOf(
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "shy" to "", "zwj" to "", "zwnj" to "",
        "ndash" to "–", "mdash" to "—", "horbar" to "―", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
        "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
        "bull" to "•", "middot" to "·", "dagger" to "†", "Dagger" to "‡",
        "sect" to "§", "para" to "¶", "permil" to "‰",
        "prime" to "′", "Prime" to "″",
        "copy" to "©", "reg" to "®", "trade" to "™",
        "deg" to "°", "micro" to "µ",
        "plusmn" to "±", "times" to "×", "divide" to "÷", "minus" to "−",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "iexcl" to "¡", "iquest" to "¿", "acute" to "´", "uml" to "¨"
    )

    /**
     * 提取 [xhtml] 中的可读文本。
     *
     * @throws ImportException [ImportFailure.InvalidEpub] XML 结构无法解析
     */
    fun extract(xhtml: String): String {
        val parser = XmlParsers.forXml(resolveEntities(xhtml))

        val out = StringBuilder()
        // 用计数而非布尔：<style> 内可能再嵌 <style>（畸形文档），布尔会提前恢复输出
        var skipDepth = 0

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name?.lowercase()
                        when {
                            tag in SKIP_CONTENT_TAGS -> skipDepth++
                            skipDepth > 0 -> Unit
                            tag == "br" -> out.append('\n')
                            tag in PARAGRAPH_TAGS -> out.appendParagraphBreak()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name?.lowercase()
                        when {
                            tag in SKIP_CONTENT_TAGS -> if (skipDepth > 0) skipDepth--
                            skipDepth > 0 -> Unit
                            tag in PARAGRAPH_TAGS -> out.appendParagraphBreak()
                        }
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (skipDepth == 0) {
                            // XHTML 里的换行/缩进是排版空白，不是内容换行
                            val text = parser.text?.replace('\n', ' ')?.replace('\r', ' ')
                            if (!text.isNullOrEmpty()) out.append(text)
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw ImportException(ImportFailure.InvalidEpub)
        }

        return normalize(out.toString())
    }

    /**
     * 预处理命名实体，使 XML 解析器不会因 `&nbsp;` 之类抛「unresolved entity」。
     *
     * - XML 五个内建实体原样留给解析器（自己替换 `&amp;` 会导致二次解析出错）
     * - 表内实体替换为对应字符
     * - 表外未知实体转成**转义后的字面文本**（`&eacute;` → `&amp;eacute;`）
     *
     * 最后一条曾是「替换成空串」，那会让 `caf&eacute;` 变成 `caf`——正文被悄悄删字。
     * 留个可见的 `&eacute;` 只是碍眼，删字是数据损坏。转成 `&amp;` 形式是为了让解析器
     * 把它当普通文本而非未知实体，否则仍会抛异常否决整本书。
     *
     * ## 必须跳过不可解析区段
     *
     * CDATA、注释、处理指令、DOCTYPE 里的 `&xxx;` 是**字面文本**，不是实体引用。
     * 无上下文地全局替换会篡改它们：`<![CDATA[caf&eacute;]]>` 被改成
     * `<![CDATA[caf&amp;eacute;]]>`，而 CDATA 不解析 `&amp;`，最终读者看到的是
     * 字面 `caf&amp;eacute;`。
     */
    private fun resolveEntities(xhtml: String): String {
        val out = StringBuilder(xhtml.length)
        var textStart = 0
        var i = 0

        // 单次游标扫描：只在遇到 '<' 时才判断是否为不可解析区段的起点。
        // 曾对每一段分别 indexOf 四种起始标记，不存在的 CDATA/PI 会被反复扫完整个剩余
        // 字符串——实测输入翻倍耗时翻四倍，360 KB 就要 3.5 秒，而 entry 上限是 4 MiB。
        // 字节上限挡不住计算量攻击，故必须是真正的线性扫描。
        while (i < xhtml.length) {
            if (xhtml[i] != '<') {
                i++
                continue
            }
            val sectionEnd = unparsedSectionEnd(xhtml, i)
            if (sectionEnd <= i) {
                i++
                continue
            }
            // 结算此前累积的普通文本，再把区段原样搬运
            out.append(resolveEntitiesInText(xhtml.substring(textStart, i)))
            out.append(xhtml, i, sectionEnd)
            i = sectionEnd
            textStart = i
        }
        out.append(resolveEntitiesInText(xhtml.substring(textStart)))
        return out.toString()
    }

    /** 在普通文本片段内做实体替换。 */
    private fun resolveEntitiesInText(text: String): String =
        NAMED_ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name in XML_BUILTIN_ENTITIES -> match.value
                else -> HTML_ENTITIES[name] ?: "&amp;$name;"
            }
        }

    /**
     * 若 [index] 处正是某个不可解析区段的起点，返回该区段的结束下标（不含），否则返回
     * [index] 本身。
     *
     * 未闭合时返回文本末尾——畸形文档宁可整段原样保留，也不能丢内容。
     */
    private fun unparsedSectionEnd(xhtml: String, index: Int): Int {
        for ((open, close) in UNPARSED_SECTIONS) {
            if (!xhtml.startsWith(open, index)) continue
            val closeAt = xhtml.indexOf(close, index + open.length)
            return if (closeAt < 0) xhtml.length else closeAt + close.length
        }
        // DOCTYPE 等 markup declaration：不能简单找第一个 '>'。XML 的 SystemLiteral
        // 只排除与之匹配的引号，**不排除 '>'**，故 <!DOCTYPE html SYSTEM "a>b.dtd">
        // 会在引号内的 '>' 处被截断，声明区后半段被当成正文、其中的实体遭改写。
        if (xhtml.startsWith("<!", index)) {
            return declarationEnd(xhtml, index)
        }
        return index
    }

    /** 扫到与 `<!` 配对的 `>`，跳过引号内的内容。未闭合时返回文本末尾。 */
    private fun declarationEnd(xhtml: String, start: Int): Int {
        var i = start + 2
        var quote = ' '
        while (i < xhtml.length) {
            val c = xhtml[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i + 1
            }
            i++
        }
        return xhtml.length
    }

    /** 追加段落边界，且不重复堆叠——嵌套 div 会连续触发多次。 */
    private fun StringBuilder.appendParagraphBreak() {
        if (isEmpty()) return
        var existing = 0
        var i = length - 1
        while (i >= 0 && (this[i] == '\n' || this[i] == ' ' || this[i] == '\t')) {
            if (this[i] == '\n') existing++
            i--
        }
        repeat((2 - existing).coerceAtLeast(0)) { append('\n') }
    }

    private fun normalize(text: String): String {
        var result = text.replace('\u00A0', ' ')  // nbsp 视作普通空格
        result = TRAILING_SPACES.replace(result, "\n")
        result = THREE_OR_MORE_NEWLINES.replace(result, "\n\n")
        return result.trim()
    }
}
