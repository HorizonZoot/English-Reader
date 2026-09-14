package io.github.zoot.englishreader.model

/**
 * 不可变的阅读选中快照，用于 UI 关联和请求入参。
 *
 * `startOffset` 与 `endOffset` 是发出这次选中的那个 `InteractiveText` 实例内的段落局部
 * 半开区间，不是文章全局偏移；跨段落的身份标识是 `sentenceIndex`，偏移永远不进入 AI
 * 缓存身份。
 */
data class SelectedSentence(
    override val articleId: Long,
    override val sentenceIndex: Int,
    override val rawText: String,
    override val normalizedText: String,
    override val startOffset: Int,
    override val endOffset: Int
) : SelectedSentenceSnapshot {
    init {
        require(articleId >= 0) { "articleId must not be negative" }
        require(sentenceIndex >= 0) { "sentenceIndex must not be negative" }
        require(startOffset >= 0) { "startOffset must not be negative" }
        require(endOffset >= startOffset) { "endOffset must not precede startOffset" }
        require(rawText.length == endOffset - startOffset) {
            "rawText length must match the half-open offset range"
        }
        require(normalizedText.isNotBlank()) { "normalizedText must not be blank" }
        require(normalizedText == AiExplanationTextNormalizer.normalize(rawText)) {
            "normalizedText must match the normalized rawText"
        }
    }

    override fun toString(): String =
        "SelectedSentence(" +
            "articleId=$articleId, " +
            "sentenceIndex=$sentenceIndex, " +
            "rawText=[REDACTED], " +
            "normalizedText=[REDACTED], " +
            "startOffset=$startOffset, " +
            "endOffset=$endOffset)"
}
