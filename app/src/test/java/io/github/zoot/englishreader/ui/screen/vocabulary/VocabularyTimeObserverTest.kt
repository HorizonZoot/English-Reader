package io.github.zoot.englishreader.ui.screen.vocabulary

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyTimeObserverTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun nextMidnight_dstTransitions_usesCalendarDayRatherThan24Hours() {
        val zone = ZoneId.of("America/New_York")
        listOf("2024-03-10T05:00:00Z" to 23L, "2024-11-03T04:00:00Z" to 25L).forEach { (now, hours) ->
            assertEquals(hours * HOUR, time(now, zone).millisUntilNextMidnight())
        }
        assertEquals(24 * HOUR, time("2024-07-18T00:00:00Z").millisUntilNextMidnight())
    }

    @Test
    fun observe_midnight_emitsAtDeadlineAndSchedulesTheNextDay() = runTest {
        val start = time("2024-07-18T23:59:59Z").nowMillis
        val signals = MutableSharedFlow<Unit>()
        val seen = mutableListOf<VocabularyGroupingTime>()
        backgroundScope.launch {
            observeVocabularyTime(signals.onStart { emit(Unit) }) {
                VocabularyGroupingTime(start + testScheduler.currentTime, utc)
            }.collect(seen::add)
        }
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(time("2024-07-19T00:00:00Z"), seen.last())
        assertEquals(2, seen.size)
        advanceTimeBy(24 * HOUR)
        runCurrent()
        assertEquals(time("2024-07-20T00:00:00Z"), seen.last())
        assertEquals(3, seen.size)
    }

    @Test
    fun observe_clockMovedBackwardWithinSameDate_cancelsOldDeadline() = runTest {
        var base = time("2024-07-18T23:00:00Z").nowMillis
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val seen = mutableListOf<VocabularyGroupingTime>()
        backgroundScope.launch {
            observeVocabularyTime(signals.onStart { emit(Unit) }) {
                VocabularyGroupingTime(base + testScheduler.currentTime, utc)
            }.collect(seen::add)
        }
        runCurrent()
        base -= 2 * HOUR
        signals.emit(Unit)
        runCurrent()
        assertEquals(2, seen.size)
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(2, seen.size)
        advanceTimeBy(2 * HOUR)
        runCurrent()
        assertEquals(3, seen.size)
        assertEquals(time("2024-07-19T00:00:00Z"), seen.last())
    }

    @Test
    fun observe_clockMovedForwardWithinSameDate_bringsDeadlineForward() = runTest {
        var base = time("2024-07-18T23:00:00Z").nowMillis
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val seen = mutableListOf<VocabularyGroupingTime>()
        backgroundScope.launch {
            observeVocabularyTime(signals.onStart { emit(Unit) }) {
                VocabularyGroupingTime(base + testScheduler.currentTime, utc)
            }.collect(seen::add)
        }
        runCurrent()
        base += HOUR - 1_000
        signals.emit(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, seen.size)
        assertEquals(time("2024-07-19T00:00:00Z"), seen.last())
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(3, seen.size)
    }

    @Test
    fun observe_zoneChanged_resamplesAndReplacesDeadline() = runTest {
        val base = time("2024-07-18T23:00:00Z").nowMillis
        var zone = utc
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val seen = mutableListOf<VocabularyGroupingTime>()
        backgroundScope.launch {
            observeVocabularyTime(signals.onStart { emit(Unit) }) {
                VocabularyGroupingTime(base + testScheduler.currentTime, zone)
            }.collect(seen::add)
        }
        runCurrent()
        zone = ZoneId.of("America/New_York")
        signals.emit(Unit)
        runCurrent()
        assertEquals(zone, seen.last().zoneId)
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(2, seen.size)
        advanceTimeBy(4 * HOUR)
        runCurrent()
        assertEquals(3, seen.size)
        assertEquals(time("2024-07-19T04:00:00Z", zone), seen.last())
    }

    @Test
    fun observe_stoppedAndRestarted_doesNotReadClockInBackgroundOrReplayMissedDays() = runTest {
        val base = time("2024-07-18T23:00:00Z").nowMillis
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var reads = 0
        val times = observeVocabularyTime(signals.onStart { emit(Unit) }) {
            reads++
            VocabularyGroupingTime(base + testScheduler.currentTime, utc)
        }
        val seen = mutableListOf<VocabularyGroupingTime>()
        val job = backgroundScope.launch { times.collect(seen::add) }
        runCurrent()
        job.cancel()
        runCurrent()
        advanceTimeBy(72 * HOUR)
        signals.emit(Unit)
        runCurrent()
        assertEquals(1, reads)
        backgroundScope.launch { times.collect(seen::add) }
        runCurrent()
        assertEquals(2, reads)
        assertEquals(time("2024-07-21T23:00:00Z"), seen.last())
    }

    private fun time(value: String, zone: ZoneId = utc) =
        VocabularyGroupingTime(Instant.parse(value).toEpochMilli(), zone)

    private companion object {
        const val HOUR = 60 * 60 * 1_000L
    }
}
