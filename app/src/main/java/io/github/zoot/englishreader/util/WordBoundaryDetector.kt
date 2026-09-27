package io.github.zoot.englishreader.util

/**
 * 单词边界检测工具
 *
 * 从给定文本和偏移位置提取单词，去除首尾标点符号
 * 纯 Kotlin 实现，不依赖 Android Framework，支持 JVM 单元测试
 */
object WordBoundaryDetector {

    data class WordRange(val word: String, val startOffset: Int, val endOffset: Int)

    /**
     * 从文本中提取指定位置的单词
     *
     * @param text 完整文本
     * @param offset 点击/长按位置的字符偏移
     * @return 提取的单词，如果位置不在单词内则返回 null
     */
    fun getWordAtOffset(text: String, offset: Int): String? =
        getWordRangeAtOffset(text, offset)?.word

    /** 词文本与半开范围来自同一段原文；首尾引号、连字符不属于词。 */
    fun getWordRangeAtOffset(text: String, offset: Int): WordRange? {
        // 边界检查
        if (offset < 0 || offset > text.length) return null

        // 处理末尾位置：光标在文本最后
        if (offset == text.length) {
            if (text.isEmpty()) return null
            return getWordRangeAtOffset(text, text.length - 1)
        }

        val char = text[offset]
        // 检查是否是单词字符
        if (!isWordChar(char)) return null

        // 向前查找单词起始位置
        var start = offset
        while (start > 0 && isWordChar(text[start - 1])) {
            start--
        }

        // 向后查找单词结束位置
        var end = offset + 1
        while (end < text.length && isWordChar(text[end])) {
            end++
        }

        while (start < end && !text[start].isLetterOrDigit()) start++
        while (end > start && !text[end - 1].isLetterOrDigit()) end--
        if (offset !in start until end) return null
        return WordRange(text.substring(start, end), start, end)
    }

    /**
     * 判断字符是否是单词字符
     * 字母、数字、内部的 apostrophe (') 和 hyphen (-) 都算单词字符
     */
    private fun isWordChar(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '\'' || char == '-'
    }

    /**
     * 判断字符是否是单词字符（公开版本，供 InteractiveText 使用）
     */
    fun isWordCharacter(char: Char): Boolean = isWordChar(char)
}
