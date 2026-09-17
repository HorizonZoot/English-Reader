package io.github.zoot.englishreader.util

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TtsPlayerTest {
    private val networkChecker = mockk<NetworkChecker>()
    private val engines = ArrayDeque<TextToSpeech>()
    private val initializations = mutableListOf<TextToSpeech.OnInitListener>()
    private lateinit var player: TtsPlayer
    private val localVoice = voice("local", network = false)
    private val networkVoice = voice("network", network = true)

    @Before
    fun setUp() {
        every { networkChecker.isOnline() } returns true
        player = TtsPlayer(networkChecker) { listener ->
            initializations += listener
            engines.removeFirst()
        }
    }

    @After
    fun tearDown() {
        player.shutdown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun refresh_missingLanguageDataWithNetworkVoice_reportsNetworkReady() {
        engine(TextToSpeech.LANG_MISSING_DATA, setOf(networkVoice))
        val capabilities = mutableListOf<TtsCapability>()

        player.refresh(allowNetwork = true, capabilities::add)
        initialize()

        assertEquals(listOf(TtsCapability.Ready(TtsVoiceMode.NETWORK)), capabilities)
    }

    @Test
    fun speak_unsupportedUsLocaleWithNetworkVoice_usesFullTextAndNetworkVoice() {
        val engine = engine(TextToSpeech.LANG_NOT_SUPPORTED, setOf(networkVoice))
        val results = mutableListOf<TtsPlaybackResult>()
        val text = "  Full sentence with its original whitespace.  "

        player.speak(text, allowNetwork = true, results::add)
        initialize()

        assertTrue(results.single() is TtsPlaybackResult.Started)
        assertEquals(TtsVoiceMode.NETWORK, (results.single() as TtsPlaybackResult.Started).mode)
        verify { engine.setVoice(networkVoice) }
        verify { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, any()) }
    }

    @Test
    fun refresh_localVoice_reportsReadyWithoutFirstSpeaking() {
        engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        every { networkChecker.isOnline() } returns false
        val capabilities = mutableListOf<TtsCapability>()

        player.refresh(allowNetwork = false, capabilities::add)
        initialize()

        assertEquals(listOf(TtsCapability.Ready(TtsVoiceMode.LOCAL)), capabilities)
    }

    @Test
    fun speak_networkOnlyWithoutConsent_doesNotSubmitTextAndCanRetryWithConsent() {
        val engine = engine(TextToSpeech.LANG_MISSING_DATA, setOf(networkVoice))
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", allowNetwork = false, results::add)
        initialize()

        assertEquals(
            listOf(TtsPlaybackResult.Failed(TtsFailureReason.NETWORK_VOICE_DISABLED)),
            results
        )
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }

        player.speak("Sentence.", allowNetwork = true, results::add)
        assertTrue(results.last() is TtsPlaybackResult.Started)
    }

    @Test
    fun refresh_noEnglishVoices_distinguishesMissingDataAndUnsupportedLanguage() {
        engine(TextToSpeech.LANG_MISSING_DATA, emptySet())
        engine(TextToSpeech.LANG_NOT_SUPPORTED, emptySet())
        val capabilities = mutableListOf<TtsCapability>()

        player.refresh(allowNetwork = true, capabilities::add)
        initialize(0)
        player.refresh(allowNetwork = true, capabilities::add)
        initialize(1)

        assertEquals(
            listOf(TtsCapability.LanguageDataMissing, TtsCapability.LanguageNotSupported),
            capabilities
        )
    }

    @Test
    fun refresh_voiceInstalledAfterFailure_releasesOldEngineAndRecovers() {
        val oldEngine = engine(TextToSpeech.LANG_MISSING_DATA, emptySet())
        engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        val capabilities = mutableListOf<TtsCapability>()

        player.refresh(allowNetwork = false, capabilities::add)
        initialize(0)
        player.refresh(allowNetwork = false, capabilities::add)
        initialize(1)

        assertEquals(TtsCapability.LanguageDataMissing, capabilities.first())
        assertEquals(TtsCapability.Ready(TtsVoiceMode.LOCAL), capabilities.last())
        verify(exactly = 1) { oldEngine.shutdown() }
    }

    @Test
    fun refresh_oldInitializationCompletesDuringNewCheck_ignoresOldResult() {
        engine(TextToSpeech.LANG_MISSING_DATA, emptySet())
        engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        val capabilities = mutableListOf<TtsCapability>()

        player.refresh(allowNetwork = false) { }
        player.refresh(allowNetwork = false, capabilities::add)
        initialize(0)
        assertTrue(capabilities.isEmpty())
        initialize(1)

        assertEquals(listOf(TtsCapability.Ready(TtsVoiceMode.LOCAL)), capabilities)
    }

    @Test
    fun speak_voiceSelectionRejected_doesNotSpeakWithDefaultVoice() {
        val engine = engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        every { engine.setVoice(localVoice) } returns TextToSpeech.ERROR
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", allowNetwork = false, results::add)
        initialize()

        assertEquals(listOf(TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED)), results)
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }
    }

    @Test
    fun speak_replacedUtteranceCompletes_keepsOnlyCurrentCallback() {
        val engine = engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        val progress = slot<UtteranceProgressListener>()
        every { engine.setOnUtteranceProgressListener(capture(progress)) } returns TextToSpeech.SUCCESS
        val first = mutableListOf<TtsPlaybackResult>()
        val second = mutableListOf<TtsPlaybackResult>()

        player.speak("First.", false, first::add)
        initialize()
        val firstId = (first.single() as TtsPlaybackResult.Started).utteranceId
        player.speak("Second.", false, second::add)
        val secondId = (second.single() as TtsPlaybackResult.Started).utteranceId
        progress.captured.onDone(firstId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, first.size)
        assertEquals(1, second.size)

        progress.captured.onDone(secondId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TtsPlaybackResult.Finished(secondId), second.last())
    }

    @Test
    fun refresh_failedInitialization_distinguishesAbsentEngineFromInitializationFailure() {
        val absent = engine(TextToSpeech.LANG_MISSING_DATA, emptySet())
        every { absent.engines } returns emptyList()
        engine(TextToSpeech.LANG_MISSING_DATA, emptySet())
        val capabilities = mutableListOf<TtsCapability>()

        player.refresh(false, capabilities::add)
        initialize(0, TextToSpeech.ERROR)
        player.refresh(false, capabilities::add)
        initialize(1, TextToSpeech.ERROR)

        assertEquals(
            listOf(TtsCapability.EngineUnavailable, TtsCapability.InitializationFailed),
            capabilities
        )
    }

    @Test
    fun speak_synchronousInitialization_waitsUntilEngineIsAssigned() {
        val engine = engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        player = TtsPlayer(networkChecker) { listener ->
            listener.onInit(TextToSpeech.SUCCESS)
            engine
        }
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", false, results::add)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(results.single() is TtsPlaybackResult.Started)
    }

    @Test
    fun speak_connectivityChangesAfterInspection_rechecksBeforeSubmittingText() {
        val engine = engine(TextToSpeech.LANG_MISSING_DATA, setOf(networkVoice))
        player.refresh(true) { }
        initialize()
        every { networkChecker.isOnline() } returns false
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", true, results::add)

        assertEquals(listOf(TtsPlaybackResult.Failed(TtsFailureReason.NETWORK_UNAVAILABLE)), results)
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }
    }

    @Test
    fun speak_uninstalledOfflineVoice_usesNetworkOnlyWithConsent() {
        val missing = Voice(
            "not-installed", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL,
            false, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        )
        val engine = engine(TextToSpeech.LANG_MISSING_DATA, setOf(missing, networkVoice))
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", false, results::add)
        initialize()
        assertEquals(TtsPlaybackResult.Failed(TtsFailureReason.NETWORK_VOICE_DISABLED), results.single())
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }

        player.speak("Sentence.", true, results::add)
        assertEquals(TtsVoiceMode.NETWORK, (results.last() as TtsPlaybackResult.Started).mode)
    }

    @Test
    fun onError_backgroundCallback_reportsTypedFailureOnMainOnce() {
        val engine = engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        val progress = slot<UtteranceProgressListener>()
        every { engine.setOnUtteranceProgressListener(capture(progress)) } returns TextToSpeech.SUCCESS
        val results = mutableListOf<TtsPlaybackResult>()
        player.speak("Sentence.", false) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            results += it
        }
        initialize()
        val id = (results.single() as TtsPlaybackResult.Started).utteranceId

        Thread { progress.captured.onError(id, TextToSpeech.ERROR_NETWORK) }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        progress.captured.onDone(id)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, results.size)
        assertEquals(TtsPlaybackResult.Failed(TtsFailureReason.NETWORK_UNAVAILABLE), results.last())
    }

    @Test
    fun shutdown_initializationPending_discardsSpeechAndRefreshCallbacks() {
        val engine = engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        val capabilities = mutableListOf<TtsCapability>()
        val results = mutableListOf<TtsPlaybackResult>()
        player.refresh(false, capabilities::add)
        player.speak("Sentence.", false, results::add)

        player.shutdown()
        initialize()

        assertTrue(capabilities.isEmpty())
        assertTrue(results.isEmpty())
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }
    }

    @Test
    fun speak_submissionThrows_doesNotDeliverLaterCompletion() {
        val engine = engine(TextToSpeech.LANG_COUNTRY_AVAILABLE, setOf(localVoice))
        val progress = slot<UtteranceProgressListener>()
        val id = slot<String>()
        every { engine.setOnUtteranceProgressListener(capture(progress)) } returns TextToSpeech.SUCCESS
        every { engine.speak(any(), any(), any(), capture(id)) } throws IllegalStateException()
        val results = mutableListOf<TtsPlaybackResult>()

        player.speak("Sentence.", false, results::add)
        initialize()
        progress.captured.onDone(id.captured)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED)), results)
    }

    @Test
    fun speakReading_explicitNetworkWithLocalAvailable_checksConsentConnectivityAndVoice() {
        val engine = engine(TextToSpeech.LANG_AVAILABLE, setOf(localVoice, networkVoice))
        val settings = TtsReadingSettings("test.engine/network", 1.7f)
        val results = mutableListOf<TtsPlaybackResult>()
        player.speakReading("Reading.", settings, false, results::add)
        initialize()
        assertEquals(TtsFailureReason.NETWORK_VOICE_DISABLED, (results.single() as TtsPlaybackResult.Failed).reason)
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }

        every { networkChecker.isOnline() } returns false
        player.speakReading("Reading.", settings, true, results::add)
        assertEquals(TtsFailureReason.NETWORK_UNAVAILABLE, (results.last() as TtsPlaybackResult.Failed).reason)

        every { networkChecker.isOnline() } returns true
        player.speakReading("Reading.", settings, true, results::add)
        assertEquals(TtsVoiceMode.NETWORK, (results.last() as TtsPlaybackResult.Started).mode)
        verify { engine.setVoice(networkVoice) }
        verify { engine.setSpeechRate(1.7f) }
    }

    @Test
    fun speakReading_thenWord_resetsVoiceAndRateEveryTime() {
        val engine = engine(TextToSpeech.LANG_AVAILABLE, setOf(localVoice, networkVoice))
        player.speakReading("Reading.", TtsReadingSettings("test.engine/network", 2f), true) {}
        initialize()
        player.speakWord("word", voiceId = null, allowNetwork = false)

        io.mockk.verifyOrder {
            engine.setVoice(networkVoice)
            engine.setSpeechRate(2f)
            engine.speak("Reading.", any(), any(), any())
            engine.setVoice(localVoice)
            engine.setSpeechRate(1f)
            engine.speak("word", any(), any(), any())
        }
    }

    @Test
    fun refreshVoices_filtersUnavailableEnglishAndScopesIdentityToEngine() {
        val pending = Voice("pending", Locale.US, 300, 300, false, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val chinese = Voice("chinese", Locale.CHINA, 300, 300, false, emptySet())
        engine(TextToSpeech.LANG_AVAILABLE, setOf(pending, chinese, localVoice, networkVoice))
        var snapshot = TtsVoiceSnapshot()
        player.refreshVoices(TtsReadingSettings("old.engine/local"), false) { snapshot = it }
        initialize()
        assertTrue(snapshot.catalogLoaded)
        assertEquals(listOf("test.engine/local", "test.engine/network"), snapshot.voices.map { it.id })
        assertEquals(TtsCapability.Ready(TtsVoiceMode.LOCAL), snapshot.capability)
    }

    @Test
    fun speakReading_rateFailure_reportsFailureWithoutSpeaking() {
        val engine = engine(TextToSpeech.LANG_AVAILABLE, setOf(localVoice))
        every { engine.setSpeechRate(any()) } returns TextToSpeech.ERROR
        val results = mutableListOf<TtsPlaybackResult>()
        player.speakReading("Reading.", TtsReadingSettings(), false, results::add)
        initialize()
        assertEquals(TtsFailureReason.SYNTHESIS_FAILED, (results.single() as TtsPlaybackResult.Failed).reason)
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }
    }

    @Test
    fun speak_wordRateFailure_stillSpeaksWithDefaultRate() {
        val engine = engine(TextToSpeech.LANG_AVAILABLE, setOf(localVoice))
        every { engine.setSpeechRate(any()) } returns TextToSpeech.ERROR
        val unavailable = mutableListOf<Boolean>()

        player.speakWord("word", voiceId = null, allowNetwork = false) { unavailable += true }
        initialize()

        assertTrue(unavailable.isEmpty())
        verify { engine.speak("word", TextToSpeech.QUEUE_FLUSH, null, any()) }
    }
    /** 单词发音使用传入的 voiceId，而不是自动选择的那个。 */
    @Test
    fun speakWord_chosenVoice_isUsedInsteadOfAutomaticSelection() {
        val engine = engine(TextToSpeech.LANG_AVAILABLE, setOf(localVoice, networkVoice))

        player.speakWord("word", voiceId = "test.engine/network", allowNetwork = true)
        initialize()

        verify { engine.setVoice(networkVoice) }
        verify { engine.speak("word", TextToSpeech.QUEUE_FLUSH, null, any()) }
    }

    /**
     * 选中的网络语音因未授权用不了时，单词发音退回本地语音**并且照样出声**。
     *
     * 对照 `speakReading_explicitNetworkWithLocalAvailable_checksConsentConnectivityAndVoice`：
     * 同样的输入，整句朗读报 `NETWORK_VOICE_DISABLED` 且不提交文本。两者的差别是刻意的——
     * 阅读页有恢复对话框可以让用户选择，单词发音只有一条 snackbar，而它本身就是兜底路径。
     */
    @Test
    fun speakWord_chosenNetworkVoiceWithoutConsent_fallsBackToLocalAndStillSpeaks() {
        val engine = engine(TextToSpeech.LANG_AVAILABLE, setOf(localVoice, networkVoice))
        val unavailable = mutableListOf<Boolean>()

        player.speakWord("word", voiceId = "test.engine/network", allowNetwork = false) {
            unavailable += true
        }
        initialize()

        assertTrue(unavailable.isEmpty())
        verify { engine.setVoice(localVoice) }
        verify { engine.speak("word", TextToSpeech.QUEUE_FLUSH, null, any()) }
        verify(exactly = 0) { engine.setVoice(networkVoice) }
    }

    /**
     * **退回不得放宽同意。** 选中网络语音、未授权、且机器上只有网络语音时，
     * 必须报不可用而不是「反正要退回，就用网络语音吧」。
     *
     * 上一条用例在「退回实现成直接挑 network.first()」的写法下照样绿，只有这条会红。
     */
    @Test
    fun speakWord_fallbackWithNoLocalVoice_neverSpeaksOverNetworkWithoutConsent() {
        val engine = engine(TextToSpeech.LANG_MISSING_DATA, setOf(networkVoice))
        val unavailable = mutableListOf<Boolean>()

        player.speakWord("word", voiceId = "test.engine/network", allowNetwork = false) {
            unavailable += true
        }
        initialize()

        assertEquals(listOf(true), unavailable)
        verify(exactly = 0) { engine.speak(any(), any(), any(), any()) }
    }

    private fun engine(languageResult: Int, voices: Set<Voice>): TextToSpeech {
        val engine = mockk<TextToSpeech>(relaxed = true)
        every { engine.setLanguage(Locale.US) } returns languageResult
        every { engine.voices } returns voices
        every { engine.engines } returns listOf(TextToSpeech.EngineInfo())
        every { engine.defaultEngine } returns "test.engine"
        engines += engine
        return engine
    }

    private fun initialize(index: Int = 0, status: Int = TextToSpeech.SUCCESS) {
        initializations[index].onInit(status)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun voice(name: String, network: Boolean) = Voice(
        name, Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network, emptySet()
    )
}
