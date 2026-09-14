package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.data.entity.VocabularyEntity

/**
 * 按字母分组器
 *
 * 分组规则：
 * - A-Z：按首字母分组
 * - #：特殊字符和数字
 */
class AlphabetGrouper {

    fun group(
        words: List<VocabularyEntity>,
        expandedGroups: Set<VocabularyGroupId>
    ): List<VocabularyGroup> {
        if (words.isEmpty()) return emptyList()

        return words.groupBy { getFirstLetter(it.word) }
            .toSortedMap()
            .map { (letter, letterWords) ->
                val groupId = VocabularyGroupId.Alphabet(letter)
                VocabularyGroup(
                    id = groupId,
                    words = letterWords.sortedBy { it.word.lowercase() },
                    isExpanded = groupId in expandedGroups
                )
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
