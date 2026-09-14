package io.github.zoot.englishreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import io.github.zoot.englishreader.model.TtsSystemAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsSystemActionTest {
    @Test
    fun openTtsSystemAction_supportedAndMissingHandlers_returnExplicitResult() {
        val intent = slot<Intent>()
        val context = mockk<Context> {
            every { startActivity(capture(intent)) } returns Unit
        }
        assertTrue(openTtsSystemAction(context, TtsSystemAction.OPEN_SETTINGS))
        assertEquals("com.android.settings.TTS_SETTINGS", intent.captured.action)
        assertTrue(openTtsSystemAction(context, TtsSystemAction.INSTALL_DATA))
        assertEquals(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, intent.captured.action)

        every { context.startActivity(any()) } throws ActivityNotFoundException()
        assertFalse(openTtsSystemAction(context, TtsSystemAction.INSTALL_DATA))
        every { context.startActivity(any()) } throws SecurityException()
        assertFalse(openTtsSystemAction(context, TtsSystemAction.OPEN_SETTINGS))
    }
}
