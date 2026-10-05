package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.viewmodel.LibraryItem
import java.text.Collator
import java.util.Locale

enum class LibrarySortField { LAST_READ, ADDED, TITLE, PROGRESS }
enum class LibrarySortDirection { DESCENDING, ASCENDING }

data class LibrarySort(
    val field: LibrarySortField = LibrarySortField.LAST_READ,
    val direction: LibrarySortDirection = LibrarySortDirection.DESCENDING
)

/** Filter before sorting; stable identities resolve ties without mixing book and article ids. */
fun filterAndSortLibrary(
    items: List<LibraryItem>,
    query: String,
    sort: LibrarySort,
    locale: Locale = Locale.getDefault()
): List<LibraryItem> {
    val keyword = query.trim()
    val collator = Collator.getInstance(locale)
    val comparator = Comparator<LibraryItem> { left, right ->
        when (sort.field) {
            LibrarySortField.LAST_READ -> (left.lastReadAt ?: 0L).compareTo(right.lastReadAt ?: 0L)
            LibrarySortField.ADDED -> left.createdAt.compareTo(right.createdAt)
            LibrarySortField.TITLE -> collator.compare(left.title, right.title)
            LibrarySortField.PROGRESS -> left.progress.compareTo(right.progress)
        }
    }
    val directed = if (sort.direction == LibrarySortDirection.DESCENDING) comparator.reversed() else comparator
    return items.filter {
        it.title.contains(keyword, ignoreCase = true) ||
            (it is LibraryItem.Book && it.book.author?.contains(keyword, ignoreCase = true) == true)
    }.sortedWith(directed.thenBy { it.listKey })
}
