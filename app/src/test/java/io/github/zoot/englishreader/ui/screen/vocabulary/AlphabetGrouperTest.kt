package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.data.entity.VocabularyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetGrouperTest {
    @Test
    fun group_repeatedExpansion_reusesBucketsAtEveryVocabularySize() {
        val rebuiltCounts = listOf(100, 1_000, 10_000).map { size ->
            val words = List(size) { VocabularyEntity(id = it.toLong(), word = "word${size - it}") }
            val grouper = AlphabetGrouper()
            val first = grouper.group(words, emptySet()).single()
            var rebuilt = 0
            repeat(20) { iteration ->
                val expanded = iteration % 2 == 0
                val next = grouper.group(words, if (expanded) setOf(first.id) else emptySet()).single()
                if (next.words !== first.words) rebuilt++
                assertEquals(expanded, next.isExpanded)
                assertEquals(first.words, next.words)
            }
            println("ALPHABET-GROUP size=$size toggles=20 rebuiltBuckets=$rebuilt")
            rebuilt
        }
        assertEquals(listOf(0, 0, 0), rebuiltCounts)
    }

    @Test
    fun group_sortKeys_areComputedOncePerWordAndNotOnExpansion() {
        val words = List(1_000) { VocabularyEntity(id = it.toLong(), word = "word${(it * 373) % 1_000}") }
        var legacyCalls = 0
        val expected = words.sortedBy { legacyCalls++; it.word.lowercase() }
        var calls = 0
        val grouper = AlphabetGrouper(sortKey = { calls++; it.lowercase() })
        val first = grouper.group(words, emptySet()).single()
        assertEquals(expected, first.words)
        assertEquals(words.size, calls)
        repeat(20) { grouper.group(words, setOf(first.id)) }
        assertEquals(words.size, calls)
        assertTrue(legacyCalls > calls)
        println("ALPHABET-KEYS words=${words.size} legacy=$legacyCalls optimized=$calls after20Toggles=$calls")
    }

    @Test
    fun group_unicodeAndEqualSortKeys_preservesExistingGroupingAndStableOrder() {
        val words = listOf("apple", "Apple", "APPLE", "banana", "Banana", "", "2word", "Éclair", "İtem", "item")
            .mapIndexed { index, word -> VocabularyEntity(id = index.toLong(), word = word) }
        val groups = AlphabetGrouper().group(words, emptySet())
        assertEquals(listOf("#", "A", "B", "I"), groups.map { (it.id as VocabularyGroupId.Alphabet).value })
        assertEquals(listOf(0L, 1L, 2L), groups.first { it.id == VocabularyGroupId.Alphabet("A") }.words.map { it.id })
        groups.forEach { group ->
            val input = words.filter { word ->
                val first = word.word.firstOrNull()?.uppercaseChar()
                val letter = if (first != null && first in 'A'..'Z') first.toString() else "#"
                group.id == VocabularyGroupId.Alphabet(letter)
            }
            assertEquals(input.sortedBy { it.word.lowercase() }, group.words)
        }
    }

    @Test
    fun group_wordSnapshotChanges_invalidatesBucketsAndAcceptsEqualCopies() {
        val grouper = AlphabetGrouper()
        val words = listOf(VocabularyEntity(id = 1, word = "apple"))
        val first = grouper.group(words, emptySet()).single()
        assertSame(first.words, grouper.group(words.map { it.copy() }, emptySet()).single().words)
        val added = words + VocabularyEntity(id = 2, word = "avocado")
        assertEquals(added, grouper.group(added, emptySet()).single().words)
        assertEquals(added.drop(1), grouper.group(added.drop(1), emptySet()).single().words)
        assertTrue(grouper.group(emptyList(), emptySet()).isEmpty())
    }
}
