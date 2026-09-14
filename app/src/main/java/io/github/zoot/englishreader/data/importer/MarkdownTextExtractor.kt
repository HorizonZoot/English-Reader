package io.github.zoot.englishreader.data.importer

/**
 * Markdown 阅读子集提取器。
 *
 * ## 范围刻意收窄
 *
 * **不承诺完整 Markdown 剥离**，只实现「阅读子集」：未识别的语法一律**原样保留**。
 * 这不是偷懒，而是因为「尽量剥干净」的方向会静默删除正文。两个具体反例：
 *
 * - `Use ` + 反引号 + `List<T>` + 反引号 + ` and call **map**.`
 *   若先做「去 HTML 标签」，行内代码里的 `<T>` 会被当成标签删掉。
 * - `This is \*not emphasis\*.`
 *   朴素的强调正则会把用户明确转义的星号删掉。
 *
 * 所以行内代码要先被**保护**起来，转义符要被尊重，拿不准的一律留着——
 * 读者看到一个多余的 `~~` 只是碍眼，看到句子少了半截是数据损坏。
 *
 * ## 空行是硬约束
 *
 * 段落分隔必须保留为空行。util/ParagraphAligner.kt 按空行正则分段，
 * 压掉空行会让全文变成一段，译文对齐与句子偏移全部失效。
 */
object MarkdownTextExtractor {

    /**
     * ATX 标题的行首井号序列。
     *
     * 尾部 `(?:[ \t]+|$)`：CommonMark 允许开头井号后直接结束行，`###` 是一个**空标题**。
     * 只认 `\s+` 会让它留在正文里——整篇只有 `###` 时本该判为 EmptyContent，
     * 却会导入一篇正文是 `###` 的文章。
     */
    private val ATX_HEADING = Regex("^ {0,3}#{1,6}(?:[ \\t]+|$)")

    /**
     * ATX 标题的尾部闭合井号。
     *
     * 前置的 `\s` 不可省：CommonMark 要求闭合序列前必须有空白，否则 `# Learning C#`
     * 里紧贴字母的 `#` 是词的一部分，会被误删成 `Learning C`。
     */
    private val ATX_CLOSING_HASHES = Regex("\\s+#+\\s*$")
    private val SETEXT_UNDERLINE = Regex("^ {0,3}(=+|-{2,})\\s*$")
    private val UNORDERED_LIST = Regex("^ {0,3}[*+-]\\s+")
    private val ORDERED_LIST = Regex("^ {0,3}\\d{1,9}[.)]\\s+")
    private val BLOCKQUOTE = Regex("^ {0,3}(> ?)+")
    private val THEMATIC_BREAK = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")

    /** 围栏起始：三个以上反引号或波浪号。 */
    private val FENCE_OPEN = Regex("^ {0,3}(`{3,}|~{3,})")

    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")

    private val THREE_OR_MORE_NEWLINES = Regex("\n{3,}")

    /** 围栏（开启与关闭）允许的最大前导空格数。更深的缩进属于代码块内容。 */
    private const val MAX_FENCE_INDENT = 3

    /** 制表符的等效空格数（CommonMark 规定 tab stop 为 4）。 */
    private const val TAB_WIDTH = 4

    /**
     * 强调标记 run 的最大识别长度（`***x***` = 加粗 + 斜体）。
     *
     * 再长的 run（`****x****`）超出阅读子集的承诺范围，按 1-2 个退化处理即可——
     * 多留一个符号只是碍眼，删错正文才是损坏。
     */
    private const val MAX_EMPHASIS_RUN = 3

    /**
     * 把 Markdown 源文本转为阅读用纯文本。
     *
     * @throws ImportException [ImportFailure.EmptyContent] 剥离后为空
     *   （例如整篇只有代码块）
     */
    fun extract(markdown: String): String {
        // 顺序不可调换：必须先删围栏、再保护行内代码。
        // 反过来的话，protectInlineCode 会把围栏的三个反引号当成行内代码分隔符吃掉，
        // 等 removeFencedBlocks 拿到文本时围栏标记已成占位符，整段代码块会漏进正文。
        val withoutFences = removeFencedBlocks(normalizeNewlines(markdown))
        // 哨兵按输入动态选取：固定哨兵遇到正文本就含该字符时会张冠李戴，
        // 把用户原有的私有区字符当成占位符替换成代码内容。
        val sentinel = pickSentinel(withoutFences)
        val (protectedText, codeSpans) = protectInlineCode(withoutFences, sentinel)
        val lines = protectedText.split('\n').map { stripBlockMarkers(it) }
        val inline = lines.joinToString("\n") { stripInlineMarkers(it) }
        val restored = restoreInlineCode(inline, codeSpans, sentinel)

        val result = THREE_OR_MORE_NEWLINES.replace(restored, "\n\n").trim()
        if (result.isEmpty()) {
            throw ImportException(ImportFailure.EmptyContent)
        }
        return result
    }

    /**
     * 选一个 [text] 中不存在的字符作占位符哨兵。
     *
     * 私有区（U+E000..U+F8FF）不承载语义，正文里出现的概率极低——但**并非不可能**，
     * 而无转义的哨兵协议一旦碰撞就会篡改正文，故扫描输入逐个排除。
     */
    private fun pickSentinel(text: String): Char {
        for (code in 0xE000..0xF8FF) {
            val candidate = code.toChar()
            if (!text.contains(candidate)) return candidate
        }
        // 全部 6400 个码位都被占用：不能兜底返回其中任何一个（那等于明知冲突还用它）。
        // 正文上限 40000 字符，构造这种输入是可行的，故此路径真实可达——直接拒绝。
        throw ImportException(ImportFailure.UnsupportedEncoding)
    }

    /** 源文件可能是 CRLF；围栏与空行判定都依赖 LF。 */
    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * 把行内代码内容换成占位符，避免后续任何规则动到它内部。
     *
     * 反引号数量必须**成对匹配**：`` ``a`b`` `` 里的分隔符是两个反引号，内部的单个反引号
     * 是正文。故记录开启时的反引号个数，只被同样长度的序列闭合。
     */
    private fun protectInlineCode(text: String, sentinel: Char): Pair<String, List<String>> {
        val spans = mutableListOf<String>()
        val out = StringBuilder(text.length)
        var i = 0

        while (i < text.length) {
            val char = text[i]

            // 转义的反引号不是代码分隔符，连同反斜杠一起原样保留
            if (char == '\\' && i + 1 < text.length) {
                out.append(char).append(text[i + 1])
                i += 2
                continue
            }

            if (char != '`') {
                out.append(char)
                i++
                continue
            }

            var tickCount = 0
            while (i + tickCount < text.length && text[i + tickCount] == '`') tickCount++
            val contentStart = i + tickCount
            // 闭合序列必须与开启序列**等长**：indexOf("``") 会命中 "```" 的前两个反引号，
            // 把长序列错当成闭合符，留下一个游离反引号并破坏后续解析。
            val closeIndex = findClosingTickRun(text, contentStart, tickCount)

            // 未闭合的反引号不是代码，是正文里的普通字符，原样保留
            if (closeIndex < 0) {
                repeat(tickCount) { out.append('`') }
                i = contentStart
                continue
            }

            spans += text.substring(contentStart, closeIndex)
            out.append(sentinel).append(spans.size - 1).append(sentinel)
            i = closeIndex + tickCount
        }

        return out.toString() to spans
    }

    /**
     * 找到与开启序列**等长**的反引号闭合序列。
     *
     * 必须扫描完整的 run 再比长度：`` `` `` 开启后遇到 ``` ``` ``` 时，朴素的
     * `indexOf("``")` 会命中前两个反引号而把第三个留在正文里。CommonMark 的规则是
     * 只有长度完全相同的序列才能闭合。
     *
     * @return 闭合序列的起始下标；找不到返回 -1
     */
    private fun findClosingTickRun(text: String, from: Int, tickCount: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] != '`') {
                i++
                continue
            }
            var run = 0
            while (i + run < text.length && text[i + run] == '`') run++
            if (run == tickCount) return i
            i += run   // 长度不符：整个 run 都不是闭合符，跳过它继续找
        }
        return -1
    }

    private fun restoreInlineCode(text: String, spans: List<String>, sentinel: Char): String {
        if (spans.isEmpty()) return text

        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] != sentinel) {
                out.append(text[i])
                i++
                continue
            }
            val end = text.indexOf(sentinel, i + 1)
            val index = if (end > i + 1) text.substring(i + 1, end).toIntOrNull() else null
            if (index == null || index !in spans.indices) {
                // 占位符结构被破坏（理论上不会发生），原样输出该字符而不是丢内容
                out.append(text[i])
                i++
                continue
            }
            out.append(spans[index])
            i = end + 1
        }
        return out.toString()
    }

    /**
     * 用状态机整块丢弃三反引号/三波浪号围栏。
     *
     * 关闭符的判据比开启符更严：必须**同种字符**、长度不短于开启序列，且该行除围栏符外
     * 只能有空白。三个条件缺一不可：
     *
     * - 不查字符种类：` ``` ` 会被 `~~~` 闭合，后面的正文被连带吞掉
     * - 不查尾随内容：` ```not-a-close ` 会被误判为关闭符，代码块内容泄漏进正文，
     *   而真正的关闭符又会开启一个新围栏，把后续正文整段吃掉
     *
     * 删除处留一个段落边界，防止围栏前后两段粘连成一段。
     */
    private fun removeFencedBlocks(text: String): String {
        val out = StringBuilder(text.length)
        var fence: String? = null

        for (line in text.split('\n')) {
            if (fence == null) {
                val open = openingFence(line)
                if (open != null) {
                    fence = open
                    out.append('\n').append('\n')   // 段落边界，防前后段粘连
                } else {
                    out.append(line).append('\n')
                }
                continue
            }

            if (closesFence(line, fence)) fence = null
        }

        return out.toString()
    }

    /**
     * 判断该行是否开启一个围栏，返回围栏序列本身。
     *
     * 开启符允许尾随 info string（` ```kotlin `），但**反引号围栏的 info string 不得
     * 含反引号**——否则无法与行内代码区分。CommonMark 明确规定这一点，故
     * ` ```foo`bar ` 是普通文本而非 opener；放行会让后续正文被当作代码整体丢弃。
     * 波浪号围栏没有这条限制。
     */
    private fun openingFence(line: String): String? {
        val match = FENCE_OPEN.find(line) ?: return null
        val open = match.groupValues[1]
        // info string 从围栏序列**结束处**开始，而不是从 open.length——带前导空格的
        // "   ```kotlin" 里围栏起点是 3 而非 0，用 open.length 当起点会把围栏自身的
        // 最后一个反引号当成 info string 里的反引号，合法 opener 被误判为普通文本，
        // 于是整段代码泄漏进正文。
        val infoStart = match.range.last + 1
        if (open[0] == '`' && line.indexOf('`', infoStart) >= 0) return null
        return open
    }

    /**
     * 关闭符须同种字符、长度不短于开启序列、最多 3 个前导空格，且行内除围栏符外仅有空白。
     *
     * 缩进上限不能省：任意缩进的 ` ``` ` 在 CommonMark 里是代码块内容而非关闭符，
     * 放行会让围栏提前闭合——内容泄漏进正文，真正的关闭符又开启新围栏吞掉后续段落。
     */
    private fun closesFence(line: String, fence: String): Boolean {
        // ① 前导缩进：只承认 ASCII space/tab。制表符按 CommonMark 的 tab stop 展开为
        //    4 空格——只数空格会把 "\t```" 当成 0 缩进而放行。
        var i = 0
        var indent = 0
        while (i < line.length) {
            when (line[i]) {
                ' ' -> indent++
                '\t' -> indent += TAB_WIDTH
                else -> break
            }
            if (indent > MAX_FENCE_INDENT) return false
            i++
        }
        if (i >= line.length) return false   // 整行皆空白，不是关闭符

        // ② 围栏符：必须同种字符、长度不短于开启序列
        val fenceStart = i
        while (i < line.length && line[i] == fence[0]) i++
        if (i - fenceStart < fence.length) return false

        // ③ 尾部：同样只承认 ASCII space/tab。
        //    不能用 line.trim()——它是 Unicode 级别的，会把 U+2003 EM SPACE、NBSP、
        //    form feed 一并删掉，于是含这些字符的行被误判为关闭符：内容泄漏进正文，
        //    真正的关闭符再开启新围栏吞掉后续段落。
        while (i < line.length) {
            if (line[i] != ' ' && line[i] != '\t') return false
            i++
        }
        return true
    }

    /** 只移除**行首确定性**的块级标记，行内内容不动。 */
    private fun stripBlockMarkers(line: String): String {
        if (THEMATIC_BREAK.matches(line)) return ""
        // Setext 下划线本身不是正文；上一行已作为普通段落保留
        if (SETEXT_UNDERLINE.matches(line)) return ""

        var result = BLOCKQUOTE.replace(line, "")

        val isHeading = ATX_HEADING.containsMatchIn(result)
        result = ATX_HEADING.replace(result, "")      // 标题只去 #，文本保留

        // 列表项去掉标记后成为独立行，与其他行一同参与后续空行分段
        result = UNORDERED_LIST.replace(result, "")
        result = ORDERED_LIST.replace(result, "")

        // 尾部 ATX 闭合井号（### Title ###）。除了「只对标题行生效」，还须要求闭合序列
        // **前面有空白**——CommonMark 如此规定，而 `# Learning C#` 里的 `#` 紧贴字母，
        // 是词的一部分。朴素的 trimEnd('#') 会把它吃成 `Learning C`。
        if (isHeading) {
            result = ATX_CLOSING_HASHES.replace(result.trimEnd(), "")
        }

        return result.trimEnd()
    }

    private fun stripInlineMarkers(line: String): String {
        // 图片保留 alt；无 alt 时整项删除（读出一串文件名对读者无意义）。
        // 被转义的起始标记要原样留下——`\[not a link](url)` 是用户明确要显示的字面文本，
        // 剥掉方括号会连 URL 一起丢失。转义判定必须在这里做：stripEmphasis 的转义处理
        // 排在其后，救不回已经被正则吃掉的内容。
        var result = IMAGE.replace(line) { match ->
            if (isEscapedAt(line, match.range.first)) match.value else match.groupValues[1]
        }
        result = LINK.replace(result) { match ->
            if (isEscapedAt(result, match.range.first)) match.value else match.groupValues[1]
        }
        result = stripEmphasis(result)
        return result
    }

    /**
     * 判断 [index] 处的字符是否被反斜杠转义。
     *
     * 数前导反斜杠的**奇偶**：`\[` 是转义的方括号，`\\[` 是转义的反斜杠加真链接。
     */
    private fun isEscapedAt(text: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    /**
     * 只处理**配对明确**的强调符：`**x**` / `__x__` / `*x*` / `_x_` / `~~x~~`。
     *
     * 未配对的、被转义的一律原样保留。刻意不用正则——正则很难同时正确处理
     * 转义符与嵌套，而这里出错的代价是删掉正文。
     */
    private fun stripEmphasis(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0

        while (i < line.length) {
            val char = line[i]

            // 尊重转义：`\*` 表示「显示一个星号」，故丢掉反斜杠、只保留被转义的字符，
            // 且该字符绝不参与后续的强调配对。
            // 只有标点才可被转义——Markdown 规范如此，且字母前的反斜杠是字面反斜杠
            // （如 Windows 路径 C:\notes 里的 \n 不该被吃掉）。
            if (char == '\\' && i + 1 < line.length) {
                val next = line[i + 1]
                if (next.isEscapablePunctuation()) {
                    out.append(next)
                } else {
                    out.append(char).append(next)
                }
                i += 2
                continue
            }

            if (char != '*' && char != '_' && char != '~') {
                out.append(char)
                i++
                continue
            }

            var runLength = 0
            while (i + runLength < line.length && line[i + runLength] == char) runLength++

            // 开符必须能「左侧定界」（CommonMark left-flanking）。不判这条会让
            // `* foo*`、`_snake_case` 之类被当成强调，把标识符悄悄改名或删掉正文符号。
            if (!canOpen(line, i, runLength, char)) {
                repeat(runLength) { out.append(char) }
                i += runLength
                continue
            }

            // 先试完整 run，失败再退回 1-2 个：`***x***` 的开合各是 3 个星号，只认 2 个
            // 会把第三个当孤立符号留在正文里。`~` 只认 2 个（单个 `~` 是普通字符）。
            val candidates = if (char == '~') {
                if (runLength >= 2) listOf(2) else emptyList()
            } else {
                listOf(
                    runLength.coerceAtMost(MAX_EMPHASIS_RUN),
                    if (runLength >= 2) 2 else 1
                ).distinct()
            }

            var markerLength = 0
            var closeIndex = -1
            for (candidate in candidates) {
                // 搜索起点跳过**整个**开启 run，否则长 run 会匹配到自己内部的字符
                val found = findClosingRun(line, i + runLength, char, candidate)
                if (found >= 0) {
                    markerLength = candidate
                    closeIndex = found
                    break
                }
            }

            // 未配对：原样保留整串标记符
            if (closeIndex < 0) {
                repeat(runLength) { out.append(char) }
                i += runLength
                continue
            }

            val contentStart = i + markerLength

            // 强调内容要递归处理：直接原样 append 会漏掉其中的转义符与嵌套强调，
            // `**x\\**` 会输出两个反斜杠而不是一个。内容严格短于 line（至少少了两倍
            // 标记长度），递归必然终止。
            out.append(stripEmphasis(line.substring(contentStart, closeIndex)))
            i = closeIndex + markerLength
        }

        return out.toString()
    }

    /**
     * 查找长度**恰为** [length] 的关闭 run 起点。
     *
     * 要求整段 run 等长，而不是「包含这么多个」：`*x**` 里的两个星号是一个长度 2 的 run，
     * 不能当作长度 1 的关闭符，否则会剩下一个孤立星号。
     *
     * 转义判定用奇偶而非「前一个字符是反斜杠」：`**x\\**` 里末尾的 `**` 前面是转义后的
     * **字面反斜杠**，它本身没被转义，仍是有效关闭标记。
     *
     * @return 关闭 run 的起始下标；找不到返回 -1
     */
    private fun findClosingRun(line: String, from: Int, char: Char, length: Int): Int {
        var i = from
        while (i < line.length) {
            if (line[i] != char || isEscapedAt(line, i)) {
                i++
                continue
            }
            var run = 0
            while (i + run < line.length && line[i + run] == char) run++
            // 长度相符还不够：闭符同样要能「右侧定界」。只查 opener 会让 `_foo_bar` 的
            // 第二个下划线（夹在 o 与 b 之间的词内下划线）充当闭符，输出 foobar。
            if (run == length && canClose(line, i, run, char)) return i
            i += run   // 不合格：整段 run 都不是关闭符，跳过它继续找
        }
        return -1
    }

    /**
     * 开符能否「左侧定界」（CommonMark left-flanking delimiter run）。
     *
     * 两条：
     * - 后面不能紧跟空白——`* foo*` 里的 `*` 是普通字符（列表项符号或乘号）
     * - 下划线额外要求不在词内——`snake_case` 是一个标识符，不是「snake」强调「case」；
     *   星号没有这条限制，`a*b*c` 是合法强调
     */
    private fun canOpen(line: String, start: Int, runLength: Int, char: Char): Boolean {
        val after = line.getOrNull(start + runLength) ?: return false
        if (after.isWhitespace()) return false
        // CommonMark left-flanking 的第二个条件：后随标点时，前面必须是空白或标点。
        // 少了这条，`a**!**b` 里的 `**` 会被当成强调标记删掉，输出 a!b——正文符号丢失。
        if (isPunctuation(after)) {
            val before = line.getOrNull(start - 1)
            if (before != null && !before.isWhitespace() && !isPunctuation(before)) return false
        }
        if (char == '_') {
            val before = line.getOrNull(start - 1)
            if (before != null && before.isLetterOrDigit()) return false
        }
        return true
    }

    /**
     * 闭符能否「右侧定界」（CommonMark right-flanking delimiter run）。
     *
     * 与 [canOpen] 镜像：
     * - 前面不能紧跟空白——`*foo *` 里的第二个 `*` 不是关闭符
     * - 下划线额外要求不在词内——`_foo_bar` 里第二个下划线夹在字母之间，不能关闭
     */
    private fun canClose(line: String, start: Int, runLength: Int, char: Char): Boolean {
        val before = line.getOrNull(start - 1) ?: return false
        if (before.isWhitespace()) return false
        // 与 canOpen 镜像：前导标点时，后面必须是空白或标点
        if (isPunctuation(before)) {
            val after = line.getOrNull(start + runLength)
            if (after != null && !after.isWhitespace() && !isPunctuation(after)) return false
        }
        if (char == '_') {
            val after = line.getOrNull(start + runLength)
            if (after != null && after.isLetterOrDigit()) return false
        }
        return true
    }

    /**
     * CommonMark 的 punctuation：ASCII 标点 + Unicode 标点类。
     *
     * flanking 规则要同时看空白与标点，只判空白会让 `a**!**b` 这类输入丢掉正文符号。
     */
    private fun isPunctuation(char: Char): Boolean {
        if (char in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~") return true
        return when (Character.getType(char).toByte()) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    /**
     * Markdown 中可被反斜杠转义的字符集（CommonMark：ASCII 标点）。
     *
     * 字母/数字不在内——`C:\notes` 里的 `\n` 是字面反斜杠加字母，不是转义序列，
     * 吞掉反斜杠会改坏用户的正文。
     */
    private fun Char.isEscapablePunctuation(): Boolean =
        this in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
