package io.github.zoot.englishreader.ui.component

import io.github.zoot.englishreader.model.TtsReadingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingSpeechRateDraftTest {

    @Test
    fun drag_keepsContinuousValueAndRoundsOnlyAtSubmission() {
        val draft = ReadingSpeechRateDraft()
        val commits = mutableListOf<Float>()

        draft.drag(1.63f)
        assertEquals(1.63f, requireNotNull(draft.value), 0.0001f)
        assertNull(draft.requestId)
        draft.submit { rate -> commits += rate; 7L }

        assertEquals(listOf(1.6f), commits)
        assertEquals(1.6f, requireNotNull(draft.value), 0.0001f)
        assertEquals(7L, requireNotNull(draft.requestId))
        draft.acknowledge(6)
        assertEquals(1.6f, requireNotNull(draft.value), 0.0001f)
        draft.acknowledge(7)
        assertNull(draft.value)
        assertNull(draft.requestId)
    }

    @Test
    fun acknowledge_previousSubmissionDoesNotResetNewDragOrNewSubmission() {
        val draft = ReadingSpeechRateDraft()
        val commits = mutableListOf<Float>()
        draft.drag(1.64f)
        draft.submit { rate -> commits += rate; 1L }

        draft.drag(1.23f)
        draft.acknowledge(1)
        assertEquals(1.23f, requireNotNull(draft.value), 0.0001f)
        assertNull(draft.requestId)
        draft.submit { rate -> commits += rate; 2L }
        draft.acknowledge(1)
        assertEquals(1.2f, requireNotNull(draft.value), 0.0001f)
        assertEquals(2L, requireNotNull(draft.requestId))
        draft.acknowledge(2)

        assertEquals(listOf(1.6f, 1.2f), commits)
        assertNull(draft.value)
        assertNull(draft.requestId)
    }

    @Test
    fun acknowledge_newerResetReleasesSubmittedDraftToOwner() {
        val draft = ReadingSpeechRateDraft()
        draft.drag(1.8f)
        draft.submit { 3L }

        draft.acknowledge(4)

        assertNull(draft.value)
        assertNull(draft.requestId)
    }

    @Test
    fun submit_rejectedOwnerReleasesDraftWithoutWaitingForAcknowledgement() {
        val draft = ReadingSpeechRateDraft()
        draft.drag(1.4f)

        draft.submit { null }

        assertNull(draft.value)
        assertNull(draft.requestId)
    }

    @Test
    fun drag_outOfRangeOrNonFiniteValuesStayWithinReadingRateContract() {
        val cases = listOf(
            -1f to TtsReadingSettings.MIN_RATE,
            4f to TtsReadingSettings.MAX_RATE,
            Float.NaN to TtsReadingSettings.DEFAULT_RATE,
            Float.POSITIVE_INFINITY to TtsReadingSettings.DEFAULT_RATE
        )
        cases.forEach { (input, expected) ->
            val draft = ReadingSpeechRateDraft()
            draft.drag(input)
            assertEquals("input=$input", expected, requireNotNull(draft.value), 0.0001f)
        }
    }
}
