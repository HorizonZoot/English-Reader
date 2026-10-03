package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import java.time.ZonedDateTime

fun interface ContextReviewClock {
    fun now(): ZonedDateTime
}

data class ContextWordSelection(
    val article: ArticleEntity,
    val paragraphIndex: Int,
    val sentence: SentenceRange,
    val wordStartOffset: Int,
    val wordEndOffset: Int
)

data class ContextMeaningDraft(
    val id: String,
    val selection: ContextWordSelection,
    val dictionaryWord: String,
    val choices: List<String>,
    val meaning: String = "",
    val saving: Boolean = false
)

enum class ContextCardSaveResult { SAVED, ALREADY_EXISTS, SOURCE_CHANGED, INVALID_MEANING }
