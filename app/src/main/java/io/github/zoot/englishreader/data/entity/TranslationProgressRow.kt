package io.github.zoot.englishreader.data.entity

import io.github.zoot.englishreader.model.TranslationSegmentStatus

/** UI 进度投影；译文只在 SQLite 内检查，不跨 Cursor 搬运。 */
data class TranslationProgressRow(
    val articleId: Long,
    val paragraphIndex: Int,
    val attemptCount: Int,
    val status: String,
    val failureReason: String?,
    val leaseExpiresAt: Long?,
    val hasNonBlankTranslation: Boolean
) {
    internal fun validatedStatus(): TranslationSegmentStatus {
        if (articleId <= 0 || paragraphIndex < 0 || attemptCount < 0) {
            throw TranslationProgressIntegrityException()
        }
        val parsed = TranslationSegmentStatus.entries.firstOrNull { it.toStableToken() == status }
            ?: throw TranslationProgressIntegrityException()
        if (parsed == TranslationSegmentStatus.TRANSLATED && !hasNonBlankTranslation) {
            throw TranslationProgressIntegrityException()
        }
        return parsed
    }

    companion object {
        /** 与 Kotlin isBlank 同源；SQLite 默认 trim 只识别 ASCII space。 */
        val WHITESPACE_CHARS: String = buildString {
            for (char in Char.MIN_VALUE..Char.MAX_VALUE) {
                if (char.isWhitespace()) append(char)
            }
        }
    }
}

internal class TranslationProgressIntegrityException : IllegalStateException("Invalid translation progress row")
