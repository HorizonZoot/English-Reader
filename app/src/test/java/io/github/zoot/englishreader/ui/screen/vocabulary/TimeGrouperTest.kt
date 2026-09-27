package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.data.entity.VocabularyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimeGrouperTest {

    private val zone = TimeZone.getTimeZone("America/New_York")

    @Test
    fun group_springForwardPreviousDay_usesInclusiveExclusiveCalendarBounds() {
        val todayStart = localMillis(2024, Calendar.MARCH, 11)
        val yesterdayStart = previousDayStart(todayStart)
        assertEquals(23 * 60 * 60 * 1000L, todayStart - yesterdayStart)

        assertPreviousDayBounds(
            now = localMillis(2024, Calendar.MARCH, 11, 12),
            yesterdayStart = yesterdayStart,
            todayStart = todayStart
        )
    }

    @Test
    fun group_fallBackPreviousDay_usesInclusiveExclusiveCalendarBounds() {
        val todayStart = localMillis(2024, Calendar.NOVEMBER, 4)
        val yesterdayStart = previousDayStart(todayStart)
        assertEquals(25 * 60 * 60 * 1000L, todayStart - yesterdayStart)

        assertPreviousDayBounds(
            now = localMillis(2024, Calendar.NOVEMBER, 4, 12),
            yesterdayStart = yesterdayStart,
            todayStart = todayStart
        )
    }

    @Test
    fun group_normalWeek_classifiesEveryBucketOnce() {
        val words = listOf(
            vocab(1, localMillis(2024, Calendar.JULY, 18, 9)),
            vocab(2, localMillis(2024, Calendar.JULY, 17, 9)),
            vocab(3, localMillis(2024, Calendar.JULY, 16, 9)),
            vocab(4, localMillis(2024, Calendar.JULY, 14, 9))
        )

        val groups = grouper(localMillis(2024, Calendar.JULY, 18, 12)).group(words, emptySet())

        assertEquals(listOf(1L), groups.wordsFor(VocabularyGroupId.Today))
        assertEquals(listOf(2L), groups.wordsFor(VocabularyGroupId.Yesterday))
        assertEquals(listOf(3L), groups.wordsFor(VocabularyGroupId.ThisWeek))
        assertEquals(
            listOf(4L),
            groups.wordsFor(VocabularyGroupId.OlderDate("2024-07-14"))
        )
    }

    @Test
    fun group_mondayBoundary_sundayAppearsOnlyInYesterdayAndNoRowsAreDuplicated() {
        val sundayId = 2L
        val words = listOf(
            vocab(1, localMillis(2024, Calendar.JULY, 15, 10)),
            vocab(sundayId, localMillis(2024, Calendar.JULY, 14, 10)),
            vocab(3, localMillis(2024, Calendar.JULY, 13, 10))
        )

        val groups = grouper(localMillis(2024, Calendar.JULY, 15, 12)).group(words, emptySet())
        val outputIds = groups.flatMap { it.words }.map { it.id }

        assertEquals(listOf(sundayId), groups.wordsFor(VocabularyGroupId.Yesterday))
        assertFalse(
            groups.filter { it.id != VocabularyGroupId.Yesterday }
                .flatMap { it.words }
                .any { it.id == sundayId }
        )
        assertEquals(words.map { it.id }.toSet(), outputIds.toSet())
        assertEquals(outputIds.size, outputIds.toSet().size)
    }

    @Test
    fun group_typedExpandedId_controlsExpansion() {
        val groups = grouper(localMillis(2024, Calendar.JULY, 18, 12)).group(
            listOf(vocab(1, localMillis(2024, Calendar.JULY, 18, 9))),
            setOf(VocabularyGroupId.Today)
        )

        assertTrue(groups.single().isExpanded)
    }

    @Test
    fun group_repeatedExpansion_reusesBucketsAtEveryVocabularySize() {
        val rebuiltCounts = listOf(100, 1_000, 10_000).map { size ->
            val grouper = grouper(localMillis(2024, Calendar.JULY, 18, 12))
            val words = List(size) { vocab(it.toLong(), localMillis(2024, Calendar.JULY, 10)) }
            val first = grouper.group(words, emptySet()).single()
            var rebuilt = 0
            repeat(20) { iteration ->
                val expanded = iteration % 2 == 0
                val next = grouper.group(words, if (expanded) setOf(first.id) else emptySet()).single()
                if (next.words !== first.words) rebuilt++
                assertEquals(expanded, next.isExpanded)
                assertEquals(words, next.words)
            }
            println("TIME-GROUP size=$size toggles=20 rebuiltBuckets=$rebuilt")
            rebuilt
        }
        assertEquals(listOf(0, 0, 0), rebuiltCounts)
    }

    @Test
    fun group_wordSnapshotChanges_invalidatesBucketsAndAcceptsEqualCopies() {
        val grouper = grouper(localMillis(2024, Calendar.JULY, 18, 12))
        val words = listOf(vocab(1, localMillis(2024, Calendar.JULY, 18, 9)))
        val first = grouper.group(words, emptySet()).single()
        assertSame(first.words, grouper.group(words.map { it.copy() }, emptySet()).single().words)

        val added = words + vocab(2, localMillis(2024, Calendar.JULY, 18, 10))
        val afterAdd = grouper.group(added, emptySet()).single()
        assertNotSame(first.words, afterAdd.words)
        assertEquals(listOf(1L, 2L), afterAdd.words.map { it.id })
        assertEquals(listOf(2L), grouper.group(added.drop(1), emptySet()).single().words.map { it.id })
        assertTrue(grouper.group(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun group_midnightAndDstChanges_reclassifiesTheSameWordSnapshot() {
        for ((month, day) in listOf(Calendar.MARCH to 10, Calendar.NOVEMBER to 3, Calendar.JULY to 18)) {
            var now = localMillis(2024, month, day, 12)
            val grouper = TimeGrouper(nowProvider = { now }, timeZoneProvider = { zone })
            val words = listOf(vocab(1, localMillis(2024, month, day, 9)))
            assertEquals(VocabularyGroupId.Today, grouper.group(words, emptySet()).single().id)
            now = localMillis(2024, month, day + 1, 12)
            assertEquals(VocabularyGroupId.Yesterday, grouper.group(words, emptySet()).single().id)
        }
    }

    @Test
    fun group_timeZoneChanges_reclassifiesTheSameWordSnapshot() {
        val utc = TimeZone.getTimeZone("UTC")
        var currentZone = utc
        val now = Calendar.getInstance(utc).apply { clear(); set(2024, Calendar.JULY, 18, 1, 0) }.timeInMillis
        val words = listOf(vocab(1, now - 2 * 60 * 60 * 1000L))
        val grouper = TimeGrouper(nowProvider = { now }, timeZoneProvider = { currentZone })
        assertEquals(VocabularyGroupId.Yesterday, grouper.group(words, emptySet()).single().id)
        currentZone = zone
        assertEquals(VocabularyGroupId.Today, grouper.group(words, emptySet()).single().id)
    }

    private fun assertPreviousDayBounds(now: Long, yesterdayStart: Long, todayStart: Long) {
        val words = listOf(
            vocab(1, yesterdayStart),
            vocab(2, todayStart - 1),
            vocab(3, todayStart),
            vocab(4, yesterdayStart - 1)
        )

        val groups = grouper(now).group(words, emptySet())

        assertEquals(listOf(1L, 2L), groups.wordsFor(VocabularyGroupId.Yesterday))
        assertEquals(listOf(3L), groups.wordsFor(VocabularyGroupId.Today))
        assertFalse(groups.wordsFor(VocabularyGroupId.Yesterday).contains(4L))
    }

    private fun grouper(now: Long) = TimeGrouper(
        nowProvider = { now },
        timeZoneProvider = { zone }
    )

    private fun vocab(id: Long, createdAt: Long) = VocabularyEntity(
        id = id,
        word = "word$id",
        createdAt = createdAt
    )

    private fun localMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month, day, hour, 0, 0)
    }.timeInMillis

    private fun previousDayStart(todayStart: Long): Long = Calendar.getInstance(zone).apply {
        timeInMillis = todayStart
        add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    private fun List<VocabularyGroup>.wordsFor(id: VocabularyGroupId): List<Long> =
        firstOrNull { it.id == id }?.words?.map { it.id }.orEmpty()
}
