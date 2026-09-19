package io.github.zoot.englishreader.util

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalTtsPlayerTest {
    private val local = FakeLocal()
    private val network = mockk<NetworkChecker>()
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private var initializations = 0
    private var onInit: TextToSpeech.OnInitListener? = null
    private val player = TtsPlayer(network, local) { onInit = it; initializations++; engine }

    @After fun close() { player.shutdown(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun speak_default_usesJenWithOriginalTextWithoutSystemEngineOrNetwork() {
        val results = mutableListOf<TtsPlaybackResult>()
        player.speak("  It is a sentence.  ", false, results::add)
        assertEquals(0, initializations)
        assertEquals("model/libritts_r-medium-int8/100", local.voice)
        assertEquals("  It is a sentence.  ", local.text)
        local.callbacks.single()(TtsPlaybackResult.Started("one", TtsVoiceMode.LOCAL_MODEL))
        assertTrue(results.single() is TtsPlaybackResult.Started)
        verify(exactly = 0) { network.isOnline() }
    }

    @Test fun speak_explicitSystemPreference_keepsSystemVoice() {
        val voice = Voice("chosen", Locale.US, 300, 100, false, emptySet())
        every { engine.voices } returns setOf(voice)
        every { engine.defaultEngine } returns "system"
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        player.speakReading("Sentence.", TtsReadingSettings("system/chosen", 1.4f), false) {}
        onInit!!.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(local.callbacks.isEmpty())
        verify { engine.setVoice(voice) }
        verify { engine.setSpeechRate(1.4f) }
    }

    @Test fun speak_replacedAndStopped_ignoresOldLocalCallbacks() {
        val first = mutableListOf<TtsPlaybackResult>()
        val second = mutableListOf<TtsPlaybackResult>()
        player.speak("First.", false, first::add)
        player.speak("Second.", false, second::add)
        local.callbacks[0](TtsPlaybackResult.Finished("old"))
        local.callbacks[1](TtsPlaybackResult.Started("new", TtsVoiceMode.LOCAL_MODEL))
        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        player.stop()
        local.callbacks[1](TtsPlaybackResult.Finished("new"))
        assertEquals(1, second.size)
    }

    @Test fun speakWord_selectedModel_usesFixedRateAndFallsBackWhenUnavailable() {
        player.speakWord("word", TtsModelCatalog.kokoro.voiceId(1), false)
        assertEquals(1f, local.rate)
        local.callbacks.single()(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
        assertEquals(1, initializations)
    }

    @Test fun speakReading_modelFailure_reportsFailureWithoutSilentlyChangingVoice() {
        val results = mutableListOf<TtsPlaybackResult>()
        player.speakReading("Sentence.", TtsReadingSettings(TtsModelCatalog.kokoro.voiceId(2), 1.7f), false, results::add)
        assertEquals(1.7f, local.rate)
        local.callbacks.single()(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
        assertEquals(listOf(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE)), results)
        assertEquals(0, initializations)
    }

    @Test fun speakReading_automaticModelFailsBeforeAudio_usesSystemVoiceWithOriginalTextAndRate() {
        val voice = Voice("offline", Locale.US, 300, 100, false, emptySet())
        every { engine.voices } returns setOf(voice)
        every { engine.defaultEngine } returns "system"
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        val results = mutableListOf<TtsPlaybackResult>()
        val text = "  It is a complete sentence.  "

        player.speakReading(text, TtsReadingSettings(speechRate = 1.6f), false, results::add)
        assertEquals(0, initializations)
        local.callbacks.single()(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
        assertEquals(1, initializations)
        onInit!!.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(TtsVoiceMode.LOCAL, (results.single() as TtsPlaybackResult.Started).mode)
        verify { engine.setVoice(voice) }
        verify { engine.setSpeechRate(1.6f) }
        verify { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, any()) }
        verify(exactly = 0) { network.isOnline() }
    }

    @Test fun speakReading_modelFailsWithOnlyNetworkVoice_doesNotBypassConsent() {
        val voice = Voice("network", Locale.US, 500, 100, true, emptySet())
        every { engine.voices } returns setOf(voice)
        every { engine.defaultEngine } returns "system"
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", false, results::add)
        local.callbacks.single()(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))
        onInit!!.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(TtsPlaybackResult.Failed(TtsFailureReason.NETWORK_VOICE_DISABLED)), results)
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }
        verify(exactly = 0) { network.isOnline() }
    }

    @Test fun speakReading_localAudioAlreadyStarted_doesNotRestartSentenceOnSystemEngine() {
        val results = mutableListOf<TtsPlaybackResult>()
        player.speak("Sentence.", false, results::add)
        val callback = local.callbacks.single()
        callback(TtsPlaybackResult.Started("local", TtsVoiceMode.LOCAL_MODEL))
        callback(TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED))

        assertEquals(0, initializations)
        assertEquals(2, results.size)
        assertEquals(TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED), results.last())
    }

    @Test fun speakReading_stoppedBeforeLocalFailure_doesNotStartFallback() {
        val results = mutableListOf<TtsPlaybackResult>()
        player.speak("Sentence.", false, results::add)
        player.stop()
        local.callbacks.single()(TtsPlaybackResult.Failed(TtsFailureReason.MODEL_UNAVAILABLE))

        assertTrue(results.isEmpty())
        assertEquals(0, initializations)
    }

    @Test fun refresh_systemNeverInitializes_stillOffersLocalVoiceAndKeepsIncompleteCatalog() {
        val snapshots = mutableListOf<TtsVoiceSnapshot>()
        player.refreshVoices(TtsReadingSettings(), false, snapshots::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(TtsCapability.Ready(TtsVoiceMode.LOCAL_MODEL), snapshots.single().capability)
        assertEquals(TtsModelCatalog.defaultVoiceId, snapshots.single().voices.first().id)
        assertFalse(snapshots.single().catalogLoaded)
    }

    private class FakeLocal : LocalTtsBackend {
        val callbacks = mutableListOf<(TtsPlaybackResult) -> Unit>()
        var voice = ""
        var text = ""
        var rate = 0f
        override fun voices() = listOf(TtsVoiceOption(TtsModelCatalog.defaultVoiceId, "en-US", TtsVoiceMode.LOCAL_MODEL, 500))
        override fun refresh(onComplete: () -> Unit) = onComplete()
        override fun speak(text: String, voiceId: String, rate: Float, callback: (TtsPlaybackResult) -> Unit) {
            this.text = text; voice = voiceId; this.rate = rate; callbacks += callback
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
