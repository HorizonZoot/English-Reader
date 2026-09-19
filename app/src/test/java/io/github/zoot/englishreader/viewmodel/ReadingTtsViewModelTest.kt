package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingTtsPhase
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.ParagraphAligner.AlignedParagraph
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsPlaybackResult
import io.github.zoot.englishreader.util.TtsVoiceMode
import io.github.zoot.englishreader.util.TtsVoiceOption
import io.github.zoot.englishreader.util.TtsVoiceSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTtsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    /**
     * 本类自己持有并**在用例中改写**同意开关（见 `consent.value = ...`），因此这条偏好流
     * 必须是热流。共享装置的默认值是 `flowOf`（发射后即完成），无法承载后续变更，
     * 所以这一条显式传入本类自己的 [MutableStateFlow]。
     */
    private val consent = MutableStateFlow(false)
    private val fixture = ReadingViewModelFixture(allowNetworkTts = consent)
    private val preferences = fixture.settingsPreferences
    private val player = fixture.ttsPlayer
    private val audioPlayer = fixture.audioPlayer
    private val calls = mutableListOf<SpeechCall>()
    private val first = SentenceRange(0, "First. ", 0, 7)
    private val second = SentenceRange(1, "Second.", 7, 14)
    private val paragraphs = listOf(
        AlignedParagraph("First. Second.", null, 0, listOf(first, second)),
        AlignedParagraph("Third.", null, 2, listOf(SentenceRange(0, "Third.", 0, 6)))
    )

    private class SpeechCall(
        val text: String,
        val allowed: Boolean,
        val id: String,
        val callback: (TtsPlaybackResult) -> Unit
    ) {
        fun finish() = callback(TtsPlaybackResult.Finished(id))
        fun fail(reason: TtsFailureReason) = callback(TtsPlaybackResult.Failed(reason))
    }

    private fun TestScope.loadedViewModel(): ReadingViewModel {
        every { player.currentVoiceSnapshot() } returns TtsVoiceSnapshot()
        every { player.speakReading(any(), any(), any(), any()) } answers {
            val call = SpeechCall(firstArg(), arg<Boolean>(2), "utterance-" + calls.size, arg<(TtsPlaybackResult) -> Unit>(3))
            calls += call
            call.callback(TtsPlaybackResult.Started(call.id, TtsVoiceMode.LOCAL))
        }
        every { player.refreshVoices(any(), any(), any()) } answers {
            thirdArg<(TtsVoiceSnapshot) -> Unit>()(TtsVoiceSnapshot(capability = TtsCapability.Ready(TtsVoiceMode.LOCAL)))
        }
        every { player.refresh(any(), any()) } answers {
            secondArg<(TtsCapability) -> Unit>()(TtsCapability.Ready(TtsVoiceMode.LOCAL))
        }
        // 连续朗读需要真实的多段正文：段落推进与跨段落切换都依赖它，
        // 因此这条打桩是本类特有的，不能退到共享装置的默认空正文。
        coEvery { fixture.articleRepository.getArticleById(any()) } coAnswers {
            ArticleEntity(firstArg(), "Title", "First. Second.\n\nThird.")
        }
        val vm = fixture.create()
        vm.loadArticle(1)
        runCurrent()
        vm.consumePositionTarget(requireNotNull(vm.pendingPositionTarget.value))
        return vm
    }

    private fun TestScope.positionAndPlay(vm: ReadingViewModel) {
        vm.consumeTtsPositionTarget(requireNotNull(vm.ttsPositionTarget.value))
        runCurrent()
    }

    @Test
    fun continuousReading_eachSentenceWaitsForPositionThenAdvancesAcrossParagraphs() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor(textKind = ReadingTextKind.TITLE))
        assertTrue(calls.isEmpty())
        val firstTarget = requireNotNull(vm.ttsPositionTarget.value)
        positionAndPlay(vm)
        assertEquals("First. ", calls.single().text)

        calls[0].finish()
        assertEquals(ReadingTtsPhase.PREPARING, vm.readingTtsState.value.phase)
        assertEquals(1, calls.size)
        vm.consumeTtsPositionTarget(firstTarget)
        runCurrent()
        assertEquals(1, calls.size)
        positionAndPlay(vm)
        assertEquals("Second.", calls[1].text)

        calls[1].finish()
        assertEquals(ReadingAnchor(1, ReadingTextKind.ORIGINAL, 0), vm.ttsPositionTarget.value?.position?.anchor)
        positionAndPlay(vm)
        calls[2].finish()
        assertEquals(listOf("First. ", "Second.", "Third."), calls.map { it.text })
        assertEquals(ReadingTtsPhase.COMPLETED, vm.readingTtsState.value.phase)
        assertNull(vm.ttsPositionTarget.value)
    }

    @Test
    fun continuousReading_selectedSentenceWinsOtherwiseUsesCharacterAnchor() = runTest {
        val vm = loadedViewModel()
        vm.selectSentence(1, 1, second)
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        assertEquals("Second.", calls.last().text)
        assertNull(vm.selectedSentence.value)

        vm.stopAudio()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor(0, ReadingTextKind.ORIGINAL, 9))
        positionAndPlay(vm)
        assertEquals("Second.", calls.last().text)
    }

    @Test
    fun pauseAndResume_stopsAndReplaysSameWholeSentenceIgnoringOldCompletion() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        val old = calls.single()
        vm.pauseReadingTts()
        old.finish()
        assertEquals(ReadingTtsPhase.PAUSED, vm.readingTtsState.value.phase)
        assertEquals(1, calls.size)

        vm.resumeReadingTts()
        positionAndPlay(vm)
        assertEquals(old.text, calls.last().text)
        old.finish()
        assertEquals(ReadingTtsPhase.PLAYING, vm.readingTtsState.value.phase)
        assertEquals(0, vm.readingTtsState.value.sentenceIndex)
    }

    @Test
    fun pause_beforePositionCompletes_discardsOldPositionAcknowledgment() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        val old = requireNotNull(vm.ttsPositionTarget.value)
        vm.pauseReadingTts()
        vm.consumeTtsPositionTarget(old)
        runCurrent()
        assertTrue(calls.isEmpty())
        vm.resumeReadingTts()
        assertNotEquals(old.requestId, vm.ttsPositionTarget.value?.requestId)
        positionAndPlay(vm)
        assertEquals("First. ", calls.single().text)
    }

    @Test
    fun skipSentence_oldAndWrongUtteranceCallbacks_cannotAdvanceNewRequest() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        val old = calls.single()
        vm.nextTtsSentence()
        old.finish()
        assertEquals(1, vm.readingTtsState.value.sentenceIndex)
        positionAndPlay(vm)
        calls.last().callback(TtsPlaybackResult.Finished("wrong-token"))
        assertEquals(ReadingTtsPhase.PLAYING, vm.readingTtsState.value.phase)
        vm.previousTtsSentence()
        positionAndPlay(vm)
        assertEquals("First. ", calls.last().text)
    }

    @Test
    fun singleSentence_selectionChangesDuringPreferenceRead_neverSpeaksOldText() = runTest {
        val vm = loadedViewModel()
        val gate = CompletableDeferred<Unit>()
        every { preferences.allowNetworkTts } returns flow {
            withContext(NonCancellable) { gate.await() }
            emit(false)
        }
        vm.selectSentence(1, 0, first)
        vm.playSelectedSentence()
        runCurrent()
        vm.selectSentence(1, 1, second)
        gate.complete(Unit)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(ReadingTtsPhase.IDLE, vm.readingTtsState.value.phase)
    }

    @Test
    fun retry_oneSentenceNetworkConsent_doesNotPersistOrContinueToNextSentence() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        assertFalse(calls.single().allowed)
        vm.ttsFailures.test {
            calls[0].fail(TtsFailureReason.NETWORK_VOICE_DISABLED)
            val failure = awaitItem()
            vm.retryReadingTts(failure.requestId, allowNetworkOnce = true)
            runCurrent()
            assertTrue(calls.last().allowed)
            assertEquals("First. ", calls.last().text)
            calls.last().finish()
            assertFalse(vm.readingTtsState.value.continuous)
            assertEquals(ReadingTtsPhase.COMPLETED, vm.readingTtsState.value.phase)
            assertEquals(2, calls.size)
            assertFalse(consent.value)
            coVerify(exactly = 0) { preferences.setAllowNetworkTts(any()) }
        }
    }

    @Test
    fun failedSentence_retryAndSystemActions_requireCurrentFailure() = runTest {
        val vm = loadedViewModel()
        vm.selectSentence(1, 0, first)
        vm.playSelectedSentence()
        runCurrent()
        calls.single().fail(TtsFailureReason.LANGUAGE_DATA_MISSING)
        val request = vm.readingTtsState.value.requestId
        vm.ttsSystemActions.test {
            vm.openTtsSystemAction(request, TtsSystemAction.INSTALL_DATA)
            assertEquals(TtsSystemAction.INSTALL_DATA, awaitItem())
            vm.retryReadingTts(request)
            runCurrent()
            assertEquals(2, calls.size)
            vm.openTtsSystemAction(request, TtsSystemAction.OPEN_SETTINGS)
            vm.retryReadingTts(request, true)
            expectNoEvents()
            assertEquals(2, calls.size)
        }
    }

    @Test
    fun stopArticleChangeAndExit_discardOldCallbacksAndNeverAutoResume() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        val old = calls.single()
        vm.ttsFailures.test {
            vm.loadArticle(2)
            old.fail(TtsFailureReason.SYNTHESIS_FAILED)
            old.finish()
            runCurrent()
            assertEquals(ReadingTtsPhase.IDLE, vm.readingTtsState.value.phase)
            expectNoEvents()
            vm.releaseTts()
            vm.refreshTtsCapability()
            runCurrent()
            assertEquals(1, calls.size)
            verify { player.shutdown() }
        }
    }

    @Test
    fun persistedNetworkConsent_isReadForEachSentence() = runTest {
        val vm = loadedViewModel()
        consent.value = true
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        assertTrue(calls.single().allowed)
        consent.value = false
        calls[0].finish()
        positionAndPlay(vm)
        assertFalse(calls.last().allowed)
    }

    @Test
    fun continuousReading_oldWordAudioFailure_cannotStartWordFallback() = runTest {
        val vm = loadedViewModel()
        lateinit var oldError: (Exception) -> Unit
        coEvery { audioPlayer.play(any(), any(), any()) } coAnswers { oldError = thirdArg() }
        vm.playWordAudio("old", "https://example.com/word.mp3")
        runCurrent()

        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        oldError(IllegalStateException())
        runCurrent()

        assertEquals("First. ", calls.single().text)
        assertEquals(ReadingTtsPhase.PLAYING, vm.readingTtsState.value.phase)
        verify(exactly = 0) { player.speakWord("old", any(), any(), any()) }
    }

    @Test
    fun openVoiceSettings_pausesContinuousReading_andCloseNeverAutoResumes() = runTest {
        val vm = loadedViewModel()
        vm.startContinuousReading(1, paragraphs, ReadingAnchor())
        positionAndPlay(vm)
        assertEquals(ReadingTtsPhase.PLAYING, vm.readingTtsState.value.phase)
        val spoken = calls.size

        vm.openVoiceSettings()
        runCurrent()
        assertTrue(vm.voiceSettings.value.isOpen)
        assertEquals(ReadingTtsPhase.PAUSED, vm.readingTtsState.value.phase)

        vm.closeVoiceSettings()
        runCurrent()
        assertFalse(vm.voiceSettings.value.isOpen)
        assertEquals(ReadingTtsPhase.PAUSED, vm.readingTtsState.value.phase)
        assertEquals(spoken, calls.size)

        // 只有显式继续才会重新朗读，且仍是暂停时那一句。
        vm.resumeReadingTts()
        runCurrent()
        assertEquals("First. ", calls.last().text)
    }

    @Test
    fun previewReadingVoice_doesNotAdvanceIndexChangeTargetOrSelection() = runTest {
        val vm = loadedViewModel()
        vm.selectSentence(1, 0, first)
        vm.openVoiceSettings()
        runCurrent()
        val spoken = calls.size
        val target = vm.ttsPositionTarget.value

        vm.previewReadingVoice("Sample text.")
        runCurrent()

        assertEquals("Sample text.", calls.last().text)
        assertEquals(spoken + 1, calls.size)
        assertEquals(0, vm.readingTtsState.value.sentenceIndex)
        assertEquals(target, vm.ttsPositionTarget.value)
        assertEquals(0, vm.selectedSentence.value?.sentenceIndex)
    }

    @Test
    fun recheckVoiceSettings_clearsMissingVoiceOnlyAfterCatalogLoads() = runTest {
        val vm = loadedViewModel()
        var snapshot = TtsVoiceSnapshot()
        every { player.refreshVoices(any(), any(), any()) } answers {
            thirdArg<(TtsVoiceSnapshot) -> Unit>()(snapshot)
        }
        every { preferences.ttsReadingSettings } returns MutableStateFlow(TtsReadingSettings(voiceId = "gone"))

        vm.openVoiceSettings()
        runCurrent()
        // 枚举失败（catalogLoaded = false）时不能误删用户选择。
        coVerify(exactly = 0) { preferences.clearTtsVoiceIf(any()) }

        snapshot = TtsVoiceSnapshot(
            voices = listOf(TtsVoiceOption("other", "en-US", TtsVoiceMode.LOCAL, 300)),
            capability = TtsCapability.Ready(TtsVoiceMode.LOCAL),
            catalogLoaded = true
        )
        vm.recheckVoiceSettings()
        runCurrent()
        coVerify(exactly = 1) { preferences.clearTtsVoiceIf("gone") }
    }

    @Test
    fun voiceSettings_modelUnavailable_keepsExplicitPreferenceForRepair() = runTest {
        val vm = loadedViewModel()
        val voiceId = "model/kokoro-int8-en-v0_19/1"
        every { preferences.ttsReadingSettings } returns MutableStateFlow(TtsReadingSettings(voiceId = voiceId))
        every { player.refreshVoices(any(), any(), any()) } answers {
            thirdArg<(TtsVoiceSnapshot) -> Unit>()(TtsVoiceSnapshot(
                capability = TtsCapability.ModelUnavailable,
                catalogLoaded = true
            ))
        }
        vm.openVoiceSettings()
        runCurrent()
        assertEquals(voiceId, vm.voiceSettings.value.settings.voiceId)
        assertEquals(TtsCapability.ModelUnavailable, vm.voiceSettings.value.snapshot.capability)
        coVerify(exactly = 0) { preferences.clearTtsVoiceIf(any()) }
    }

    /**
     * 查词兜底也要用用户挑的语音。
     *
     * 改动前 `speakWord` 收到的是 `TtsReadingSettings()`（`voiceId = null`），于是设置页挑的
     * 语音只对整句朗读生效。叠加自动选择的「离线优先」后就是用户听得见的割裂：好语音基本都是
     * 网络语音，已授权并挑了网络神经语音的人，整句是神经音、长按查词却掉回本地拼接音。
     *
     * 断言用 `eq("engine/neural")` 而非 `any()`：本文件既有的那条
     * `continuousReading_oldWordAudioFailure_cannotStartWordFallback` 用的是 `any()`，
     * 在「voiceId 被丢弃」的实现下照样绿。
     */
    @Test
    fun wordFallback_usesTheVoiceChosenForReading() = runTest {
        every { preferences.ttsReadingSettings } returns
            MutableStateFlow(TtsReadingSettings(voiceId = "engine/neural"))
        val vm = loadedViewModel()
        // audioUrl 为空 → 这条结果没有真人音可播，直接走 TTS 兜底。
        vm.playWordAudio("lives", audioUrl = null)
        runCurrent()

        verify(exactly = 1) { player.speakWord("lives", eq("engine/neural"), any(), any()) }
    }
}
