package io.github.zoot.englishreader.data.entity

import androidx.room.Embedded

/** Shelf projection; no chapter bodies are loaded into the list. */
data class BookSummary(
    @Embedded val book: BookEntity,
    val readingProgress: Float = 0f
)
