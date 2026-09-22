package io.github.zoot.englishreader.util

/** 只用于用户明确选择的整理预览，不改变阅读器和翻译任务的空行分段协议。 */
object ArticleParagraphFormatting {
    fun hasUnseparatedLines(content: String): Boolean {
        val normalized = normalizeLineEndings(content)
        if (ParagraphAligner.splitParagraphs(normalized).size != 1) return false
        return normalized.lineSequence().zipWithNext().any { (previous, next) ->
            previous.isNotBlank() && next.isNotBlank()
        }
    }

    /** 保留每行文字、缩进和已有空行，只在相邻非空行之间补一个换行。 */
    fun separateLines(content: String): String {
        val lines = normalizeLineEndings(content).split('\n')
        return buildString {
            lines.forEachIndexed { index, line ->
                if (index > 0) {
                    append('\n')
                    if (lines[index - 1].isNotBlank() && line.isNotBlank()) append('\n')
                }
                append(line)
            }
        }
    }

    private fun normalizeLineEndings(content: String): String =
        content.replace("\r\n", "\n").replace('\r', '\n')
}
