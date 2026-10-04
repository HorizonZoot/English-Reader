package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.util.SavedVocabularyMeaning

sealed interface StudyWord {
    val key: String
    val word: String
    val meaning: String
    val articleId: Long?
    val sourceTitle: String?
    val createdAt: Long
    val dueDay: Long
    val lastReviewedAt: Long?
    val pendingStep: Int
    val answerRevision: Long

    data class Context(val card: ContextCardEntity) : StudyWord {
        override val key get() = "context:${card.id}"
        override val word get() = card.word
        override val meaning get() = card.meaning
        override val articleId get() = card.articleId
        override val sourceTitle get() = card.sourceTitle
        override val createdAt get() = card.createdAt
        override val dueDay get() = card.dueDay
        override val lastReviewedAt get() = card.lastReviewedAt
        override val pendingStep get() = card.pendingStep
        override val answerRevision get() = card.answerRevision
    }

    data class Legacy(val vocabulary: VocabularyEntity, override val sourceTitle: String? = null) : StudyWord {
        override val key get() = "vocabulary:${vocabulary.id}"
        override val word get() = vocabulary.word
        override val meaning get() = vocabulary.studyMeaning?.takeIf(String::isNotBlank)
            ?: SavedVocabularyMeaning.parse(vocabulary.definitions).orEmpty()
        override val articleId get() = vocabulary.articleId
        override val createdAt get() = vocabulary.createdAt
        override val dueDay get() = vocabulary.dueDay
        override val lastReviewedAt get() = vocabulary.lastReviewedAt
        override val pendingStep get() = vocabulary.pendingStep
        override val answerRevision get() = vocabulary.answerRevision
    }
}

data class PreparedStudyMeaning(
    val key: String,
    val word: String,
    val answerRevision: Long,
    val meaning: String
)
