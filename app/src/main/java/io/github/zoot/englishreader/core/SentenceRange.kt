package io.github.zoot.englishreader.core

/**
 * 句子范围数据类
 *
 * 保存分句器返回的句子原文与段落内精确位置，供 `InteractiveText` 直接使用。
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
        // 这里只校验区间长度；AlignedParagraph 另行校验原文与偏移的对应。
        // InteractiveText 直接 append 完整段落，分句忽略的空白仍保留，不能用句子拼接重建坐标。
        require(text.length == endOffset - startOffset) {
            "text length mismatch: expected ${endOffset - startOffset}, got ${text.length}"
        }
    }
}
