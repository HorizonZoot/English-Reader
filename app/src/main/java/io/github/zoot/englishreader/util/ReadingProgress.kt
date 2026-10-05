package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.model.ReadingTextKind

/** Progress follows the saved source anchor, not time spent or the furthest point ever reached. */
fun readingProgress(content: String, paragraphIndex: Int, textKind: String, characterOffset: Int): Float {
    if (textKind == ReadingTextKind.TITLE.name) return 0f
    val paragraphs = ParagraphAligner.splitParagraphs(content)
    val total = paragraphs.sumOf { it.length }
    if (total == 0) return 0f
    if (paragraphIndex >= paragraphs.size) return 1f
    val index = paragraphIndex.coerceAtLeast(0)
    val paragraph = paragraphs[index]
    // A translation anchor follows its source paragraph in the reading order.
    val offset = if (textKind == ReadingTextKind.TRANSLATION.name) paragraph.length
        else characterOffset.coerceIn(0, paragraph.length)
    return (paragraphs.take(index).sumOf { it.length } + offset).toFloat() / total
}
