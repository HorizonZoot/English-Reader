package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.ContextReviewEntry
import io.github.zoot.englishreader.data.entity.ContextReviewItemEntity
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.entity.ContextReviewSessionEntity

fun contextCard(id: Long = 1, dueDay: Long = 20_000) = ContextCardEntity(
    id = id, articleId = 7, originArticleId = 7, sourceTitle = "A story",
    contentFingerprint = "source-fingerprint", paragraphIndex = 0,
    sentenceStartOffset = 0, sentenceEndOffset = 18, sentence = "The bank is quiet.",
    targetStartOffset = 4, targetEndOffset = 8, word = "bank", dictionaryWord = "bank",
    meaning = "n. 河岸", createdAt = id, updatedAt = id, dueDay = dueDay
)

fun contextEntry(
    id: Long = 1,
    position: Int = id.toInt() - 1,
    revealed: Boolean = false,
    sessionToken: String = "round"
): ContextReviewEntry {
    val card = contextCard(id)
    return ContextReviewEntry(
        ContextReviewItemEntity(
            itemToken = "item-$id", sessionToken = sessionToken, cardId = id,
            answerRevision = card.answerRevision, meaningSnapshot = card.meaning,
            position = position, revealed = revealed
        ),
        card
    )
}

fun contextRound(
    entries: List<ContextReviewEntry> = listOf(contextEntry()),
    day: Long = 20_000,
    token: String = "round"
) = ContextReviewRound(
    ContextReviewSessionEntity(
        sessionToken = token, localDay = day, createdAt = 1,
        initialCount = entries.count { !it.item.isRetry },
        answerCount = entries.count { it.item.grade != null }
    ),
    entries
)
