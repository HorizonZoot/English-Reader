package io.github.zoot.englishreader.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePopupPolicyTest {
    @Test
    fun popupFadeDurations_areShortAndAlphaOnly() {
        assertEquals(120, SENTENCE_POPUP_FADE_IN_DURATION_MS)
        assertEquals(90, SENTENCE_POPUP_FADE_OUT_DURATION_MS)
        assertEquals(120, SENTENCE_POPUP_CONTENT_CROSSFADE_DURATION_MS)
    }

    @Test
    fun popupDismissGate_allowsOnlyOneTerminalCallback() {
        val gate = SentencePopupDismissGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isAcquired)
        assertTrue(!gate.tryAcquire())
    }
}
