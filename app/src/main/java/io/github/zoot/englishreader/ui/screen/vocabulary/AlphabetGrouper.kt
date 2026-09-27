package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.data.entity.VocabularyEntity

/**
 * 按字母分组器
 *
 * 分组规则：
 * - A-Z：按首字母分组
 * - #：特殊字符和数字
 */
class AlphabetGrouper(
    private val sortKey: (String) -> String = { it.lowercase() }
) {
    private data class Snapshot(val words: List<VocabularyEntity>, val groups: List<VocabularyGroup>)

    @Volatile private var snapshot: Snapshot? = null

    fun group(
        words: List<VocabularyEntity>,
        expandedGroups: Set<VocabularyGroupId>
    ): List<VocabularyGroup> {
        if (words.isEmpty()) {
            snapshot = null
            return emptyList()
        }
        val cached = snapshot
        val groups = if (cached != null && (cached.words === words || cached.words == words)) {
            cached.groups
        } else {
            words.groupBy { getFirstLetter(it.word) }
                .toSortedMap()
                .map { (letter, letterWords) ->
                    VocabularyGroup(
                        id = VocabularyGroupId.Alphabet(letter),
                        words = letterWords.map { it to sortKey(it.word) }
                            .sortedBy { it.second }.map { it.first }
                    )
                }.also { snapshot = Snapshot(words, it) }
        }
        return groups.map { group ->
            if (group.id in expandedGroups) group.copy(isExpanded = true) else group
        }
    }

    private fun getFirstLetter(word: String): String {
        if (word.isEmpty()) return "#"
        val firstChar = word.first().uppercaseChar()
        return if (firstChar in 'A'..'Z') {
            firstChar.toString()
        } else {
            "#"
        }
    }
}
