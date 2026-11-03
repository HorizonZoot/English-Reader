package io.github.zoot.englishreader.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePopupPolicyTest {
    @Test
    fun actionPopup_doesNotOwnFocusOrOutsideDismissal() {
        val properties = sentencePopupProperties(SentencePopupMode.ACTIONS)

        assertEquals(false, properties.focusable)
        assertEquals(false, properties.dismissOnClickOutside)
    }

    @Test
    fun resultPopupModes_keepFocusAndOutsideDismissal() {
        listOf(
            SentencePopupMode.TRANSLATION,
            SentencePopupMode.EXPLANATION
        ).forEach { mode ->
            val properties = sentencePopupProperties(mode)

            assertEquals(mode.name, true, properties.focusable)
            assertEquals(mode.name, true, properties.dismissOnClickOutside)
        }
    }

    @Test
    fun popupFadeDurations_matchExpectedMilliseconds() {
        assertEquals(100, SENTENCE_POPUP_FADE_IN_DURATION_MS)
        assertEquals(90, SENTENCE_POPUP_FADE_OUT_DURATION_MS)
        assertEquals(100, SENTENCE_POPUP_CONTENT_CROSSFADE_DURATION_MS)
    }

    @Test
    fun popupDismissGate_allowsOnlyOneTerminalCallback() {
        val gate = SentencePopupDismissGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isAcquired)
        assertTrue(!gate.tryAcquire())
    }
}
