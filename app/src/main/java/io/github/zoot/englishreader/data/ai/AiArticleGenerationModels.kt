package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.remote.ai.AiChatMessage

internal enum class AiArticleDifficulty {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED
}

internal enum class AiArticleLength(val targetWords: Int) {
    SHORT(200),
    MEDIUM(500),
    LONG(800);

    fun isOutsideWordTarget(wordCount: Int): Boolean =
        wordCount < targetWords * 70 / 100 || wordCount > targetWords * 130 / 100
}

internal data class AiArticleGenerationParameters(
    val difficulty: AiArticleDifficulty = AiArticleDifficulty.BEGINNER,
    val length: AiArticleLength = AiArticleLength.MEDIUM,
    val topic: String = ""
) {
    override fun toString(): String =
        "AiArticleGenerationParameters(difficulty=$difficulty, length=$length, topic=[REDACTED])"
}

internal data class PreparedAiArticlePrompt(
    val parameters: AiArticleGenerationParameters,
    val messages: List<AiChatMessage>
) {
    override fun toString(): String =
        "PreparedAiArticlePrompt(parameters=$parameters, messageCount=${messages.size})"
}

internal sealed interface AiArticlePromptPreparationResult {
    data class Ready(val prompt: PreparedAiArticlePrompt) : AiArticlePromptPreparationResult
    data class Rejected(val error: AiError) : AiArticlePromptPreparationResult
}

internal data class AiGeneratedArticle(
    val title: String,
    val content: String,
    val parameters: AiArticleGenerationParameters,
    val wordCount: Int
) {
    val isOutsideWordTarget: Boolean
        get() = parameters.length.isOutsideWordTarget(wordCount)

    override fun toString(): String =
        "AiGeneratedArticle(title=[REDACTED], content=[REDACTED], " +
            "parameters=$parameters, wordCount=$wordCount)"
}

internal sealed interface AiArticleGenerationResult {
    data class Success(val article: AiGeneratedArticle) : AiArticleGenerationResult
    data class Failure(val failure: AiArticleGenerationFailure) : AiArticleGenerationResult
}

internal sealed interface AiArticleGenerationFailure {
    data class Request(val error: AiError) : AiArticleGenerationFailure
    data class Output(val reason: AiGeneratedArticleRejection) : AiArticleGenerationFailure
}

internal sealed interface AiGeneratedArticleParseResult {
    data class Ready(val article: AiGeneratedArticle) : AiGeneratedArticleParseResult
    data class Rejected(val reason: AiGeneratedArticleRejection) : AiGeneratedArticleParseResult
}

internal enum class AiGeneratedArticleRejection {
    NO_CONTENT,
    MALFORMED_RESPONSE,
    TRUNCATED_RESPONSE,
    INVALID_TITLE,
    TITLE_TOO_LONG,
    CONTENT_TOO_LONG,
    TOO_MANY_PARAGRAPHS,
    PARAGRAPH_TOO_LONG
}
