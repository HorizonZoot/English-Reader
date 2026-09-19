package io.github.zoot.englishreader.tts.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpikeMeasurementTest {
    @Test
    fun synthesisRtf_playbackBackpressure_excludesTimeWaitingForAudio() {
        val rtf = synthesisRtf(
            generationNanos = 1_300_000_000,
            callbackNanos = 900_000_000,
            samples = 22_050,
            sampleRate = 22_050
        )

        assertEquals(0.4, rtf, 0.000001)
    }

    @Test
    fun synthesisRtf_multiSecondAudio_measuresAgainstCompleteDuration() {
        val rtf = synthesisRtf(
            generationNanos = 600_000_000,
            callbackNanos = 200_000_000,
            samples = 44_100,
            sampleRate = 22_050
        )

        assertEquals(0.2, rtf, 0.000001)
    }
}
