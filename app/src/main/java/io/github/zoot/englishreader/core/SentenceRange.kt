package io.github.zoot.englishreader.core

/**
 * 句子范围数据类
 *
 * 保存句子的原文和由当前段落 `InteractiveText` 计算出的精确位置。
 * 从 PoC 项目迁移，用于 InteractiveText 的句子高亮功能。
 *
 * @property index 句子索引（从 0 开始）
 * @property text 句子文本（包含标点符号）
 * @property startOffset 句子在传给当前 `InteractiveText` 的段落文本中的起始位置
 * @property endOffset 句子在该段落文本中的结束位置（半开区间，不包含）
 *
 * 偏移是段落局部的，不是文章全局的。`ReadingViewModel` 用 `index`（经过段落偏移换算后）
 * 作为跨段落的句子身份标识；这些偏移不属于语义解释缓存身份的组成部分。
 */
data class SentenceRange(
    val index: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int
) {
    init {
        // 只校验单个区间自身的长度自洽。注意它**捕获不到**：offset 整体平移、区间重叠、
        // 以及相邻区间之间的覆盖空洞——而「连续覆盖」恰恰是 InteractiveText 逐句 append
        // 拼出 annotatedText 时依赖的不变量（一旦有空洞，annotatedText 与原文长度不等，
        // 布局 offset 与 startOffset 的坐标系就分叉了）。
        // 分段渲染路径上，`ParagraphAligner.AlignedParagraph` 会在构造时校验每句确实落在
        // 其声称的偏移上（可捕获整体平移），但重叠与空洞仍无人校验。
        require(text.length == endOffset - startOffset) {
            "text length mismatch: expected ${endOffset - startOffset}, got ${text.length}"
        }
    }
}
