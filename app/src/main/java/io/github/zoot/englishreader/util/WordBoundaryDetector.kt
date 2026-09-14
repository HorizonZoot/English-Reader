package io.github.zoot.englishreader.util

/**
 * 单词边界检测工具
 *
 * 从给定文本和偏移位置提取单词，去除首尾标点符号
 * 纯 Kotlin 实现，不依赖 Android Framework，支持 JVM 单元测试
 */
object WordBoundaryDetector {

    /**
     * 从文本中提取指定位置的单词
     *
     * @param text 完整文本
     * @param offset 点击/长按位置的字符偏移
     * @return 提取的单词，如果位置不在单词内则返回 null
     */
    fun getWordAtOffset(text: String, offset: Int): String? {
        // 边界检查
        if (offset < 0 || offset > text.length) return null

        // 处理末尾位置：光标在文本最后
        if (offset == text.length) {
            if (text.isEmpty()) return null
            return getWordAtOffset(text, text.length - 1)
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
        var end = offset
        while (end < text.length - 1 && isWordChar(text[end + 1])) {
            end++
        }

        // 提取单词并去除首尾标点
        val word = text.substring(start, end + 1)

        // 先去除所有首尾标点（包括 hyphen/apostrophe）
        val trimmed = word.trim { !it.isLetterOrDigit() }

        // 过滤纯标点：必须包含至少一个字母或数字
        return trimmed.takeIf { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }
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
