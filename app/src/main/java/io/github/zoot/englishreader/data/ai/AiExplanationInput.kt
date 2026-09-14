package io.github.zoot.englishreader.data.ai

/**
 * 阅读体验所支持的有界 AI 文本操作上下文。
 *
 * UI 身份（article ID、句子偏移、选择世代）不进入本类型。只有原文文本会越过 AI facade 边界。
 */
sealed interface AiExplanationInput {
    val text: String

    data class Sentence(override val text: String) : AiExplanationInput {
        override fun toString(): String = "Sentence(text=[REDACTED])"
    }

    /** 句子翻译与句子解释是两种不同的输出语义。 */
    data class SentenceTranslation(override val text: String) : AiExplanationInput {
        override fun toString(): String = "SentenceTranslation(text=[REDACTED])"
    }

    /**
     * 全文翻译中的单个段落。
     *
     * 与 [SentenceTranslation] 必须是**不同类型**，即使 prompt 措辞相近：两者共用
     * `ExplanationType` 会让「翻译这一句」和「翻译这一段」在段落恰好只有一句时算出同一个
     * 缓存键，于是句子翻译的结果会被当成段落译文写进文章，反之亦然。这一点在 context.md
     * 里已经以 `AiExplanationInput` 子类型的形式记过一次（解释与翻译撞键），此处是同一个
     * 陷阱的第三种形态。
     */
    data class ParagraphTranslation(override val text: String) : AiExplanationInput {
        override fun toString(): String = "ParagraphTranslation(text=[REDACTED])"
    }

    data class Article(override val text: String) : AiExplanationInput {
        override fun toString(): String = "Article(text=[REDACTED])"
    }
}
