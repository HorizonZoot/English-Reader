package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.model.StudyWord
import java.time.Instant
import java.util.Locale

data class StudyWordGroup(
    val id: VocabularyGroupId,
    val words: List<StudyWord>,
    val isExpanded: Boolean
)

fun groupStudyWords(
    words: List<StudyWord>,
    type: GroupType,
    expanded: Set<VocabularyGroupId>,
    time: VocabularyGroupingTime
): List<StudyWordGroup> {
    val today = Instant.ofEpochMilli(time.nowMillis).atZone(time.zoneId).toLocalDate()
    val buckets = words.groupBy { word ->
        when (type) {
            GroupType.ByAlphabet -> VocabularyGroupId.Alphabet(
                word.word.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
            )
            else -> {
                val date = Instant.ofEpochMilli(word.createdAt).atZone(time.zoneId).toLocalDate()
                when {
                    date >= today -> VocabularyGroupId.Today
                    date == today.minusDays(1) -> VocabularyGroupId.Yesterday
                    else -> VocabularyGroupId.OlderDate(date.toString())
                }
            }
        }
    }
    val ids = if (type == GroupType.ByAlphabet) {
        buckets.keys.filterIsInstance<VocabularyGroupId.Alphabet>().sortedBy { it.value }
    } else {
        listOf(VocabularyGroupId.Today, VocabularyGroupId.Yesterday, VocabularyGroupId.ThisWeek)
            .filter { it in buckets } + buckets.keys.filterIsInstance<VocabularyGroupId.OlderDate>()
            .sortedByDescending { it.value }
    }
    return ids.map { id ->
        val grouped = buckets.getValue(id)
        StudyWordGroup(
            id = id,
            words = if (type == GroupType.ByAlphabet) grouped.sortedWith(compareBy<StudyWord> { it.word.lowercase(Locale.ROOT) }.thenBy { it.key })
                else grouped.sortedWith(compareByDescending<StudyWord> { it.createdAt }.thenBy { it.key }),
            isExpanded = id in expanded
        )
    }
}
