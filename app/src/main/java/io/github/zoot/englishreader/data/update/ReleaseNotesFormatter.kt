package io.github.zoot.englishreader.data.update

object ReleaseNotesFormatter {
    fun format(raw: String?): String {
        val source = raw?.takeIf { it.isNotBlank() } ?: return ""
        val lines = mutableListOf<String>()
        var fenceCharacter: Char? = null
        var fenceLength = 0

        for (line in source.replace("\r\n", "\n").replace('\r', '\n').lineSequence()) {
            val indentation = line.takeWhile { it == ' ' }.length
            val content = line.drop(indentation)
            val marker = content.firstOrNull()
            val markerCount = if (indentation <= 3 && (marker == '`' || marker == '~')) {
                content.takeWhile { it == marker }.length
            } else {
                0
            }

            if (fenceCharacter != null) {
                lines += line
                if (marker == fenceCharacter && markerCount >= fenceLength &&
                    content.drop(markerCount).isBlank()
                ) {
                    fenceCharacter = null
                }
                continue
            }
            if (markerCount >= 3 && (marker != '`' || '`' !in content.drop(markerCount))) {
                fenceCharacter = marker
                fenceLength = markerCount
                lines += line
                continue
            }

            val formatted = formatLine(line)
            if (formatted.isNotEmpty() || lines.lastOrNull()?.isNotEmpty() != false) {
                lines += formatted
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun formatLine(line: String): String {
        val trimmedEnd = line.trimEnd()
        if (trimmedEnd.isBlank()) return ""
        val indent = trimmedEnd.takeWhile { it == ' ' || it == '\t' }
        val content = trimmedEnd.substring(indent.length)
        if (isRule(content)) return ""

        HEADING.matchEntire(content)?.let { match ->
            val heading = match.groupValues[1].trim()
            val withoutHashes = heading.trimEnd('#')
            val text = if (withoutHashes.isEmpty() || withoutHashes.last().isWhitespace()) {
                withoutHashes.trimEnd()
            } else {
                heading
            }
            return stripInline(text)
        }
        BULLET.matchEntire(content)?.let { match ->
            return indent + "• " + stripInline(match.groupValues[1].trim())
        }
        return indent + stripInline(content)
    }

    private fun isRule(content: String): Boolean {
        val marker = content.firstOrNull()
        if (marker != '*' && marker != '-' && marker != '_') return false
        var count = 0
        for (character in content) {
            if (character == marker) {
                count++
            } else if (!character.isWhitespace()) {
                return false
            }
        }
        return count >= 3
    }

    private fun stripInline(text: String): String {
        val delimiters = BACKTICKS.findAll(text).toList()
        if (delimiters.isEmpty()) return stripEmphasis(text)
        val nextByLength = mutableMapOf<Int, Int>()
        val closingIndices = IntArray(delimiters.size) { -1 }
        for (index in delimiters.indices.reversed()) {
            closingIndices[index] = nextByLength.put(delimiters[index].value.length, index) ?: -1
        }

        val masked = text.toCharArray()
        val removed = BooleanArray(text.length)
        var hasCode = false
        var index = 0
        while (index < delimiters.size) {
            val closingIndex = closingIndices[index]
            if (closingIndex < 0) {
                index++
                continue
            }
            val opening = delimiters[index].range
            val closing = delimiters[closingIndex].range
            // 屏蔽代码内容但保留原坐标，输出时只删除原文中的定界符。
            for (position in opening.first..closing.last) masked[position] = 0.toChar()
            for (position in opening) removed[position] = true
            for (position in closing) removed[position] = true
            hasCode = true
            index = closingIndex + 1
        }
        if (!hasCode) return stripEmphasis(text)

        val positions = IntArray(text.length) { it }
        var length = positions.size
        for (pattern in listOf(BOLD, DOUBLE_UNDERSCORE, ITALIC_UNDERSCORE, ITALIC_STAR)) {
            val protected = String(CharArray(length) { masked[positions[it]] })
            for (match in pattern.findAll(protected)) {
                val content = match.groups[1]!!.range
                for (position in match.range.first until content.first) removed[positions[position]] = true
                for (position in content.last + 1..match.range.last) removed[positions[position]] = true
            }
            var retained = 0
            for (position in 0 until length) {
                val original = positions[position]
                if (!removed[original]) positions[retained++] = original
            }
            length = retained
        }
        return buildString {
            for (position in 0 until length) append(text[positions[position]])
        }
    }

    private fun stripEmphasis(text: String): String = text
        .replace(BOLD, "$1")
        .replace(DOUBLE_UNDERSCORE, "$1")
        .replace(ITALIC_UNDERSCORE, "$1")
        .replace(ITALIC_STAR, "$1")

    private val HEADING = Regex("""#{1,6}(?:\s+(.*))?""")
    private val BULLET = Regex("""[-*+]\s+(.*)""")
    private val BOLD = Regex("""\*\*([^*]+)\*\*""")
    private val DOUBLE_UNDERSCORE = Regex("""__([^_]+)__""")
    private val ITALIC_UNDERSCORE = Regex("""(?<![\p{L}\p{N}_])_([^_\n]+)_(?![\p{L}\p{N}_])""")
    private val ITALIC_STAR = Regex("""\*([^*\n]+)\*""")
    private val BACKTICKS = Regex("`+")
}
