package io.github.zoot.englishreader.data.ai

/**
 * AI 文本输出请求的语义类型。
 *
 * 每种输出语义都有稳定 token；新增语义时在此追加，并由 prompt policy 提供新的
 * `promptVersion`。不要把 Kotlin enum 名直接写入持久化身份。
 */
internal enum class ExplanationType {
    SENTENCE_EXPLANATION,
    SENTENCE_TRANSLATION,
    PARAGRAPH_TRANSLATION,
    ARTICLE_EXPLANATION;

    /**
     * @return 稳定的 cache token，用于构造缓存身份
     *
     * 不直接用 `name`：枚举名是 Kotlin 符号，可能因重构改变；cache token 是持久化
     * 身份的一部分，改变它会让所有现有缓存失效。
     */
    fun toStableToken(): String = when (this) {
        SENTENCE_EXPLANATION -> "sentence"
        SENTENCE_TRANSLATION -> "sentence-translation"
        PARAGRAPH_TRANSLATION -> "paragraph-translation"
        ARTICLE_EXPLANATION -> "article"
    }
}
