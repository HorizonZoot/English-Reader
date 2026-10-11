package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsPlaybackResult
import io.github.zoot.englishreader.util.TtsPlayer
import io.github.zoot.englishreader.util.TtsVoiceMode
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertNull
import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyExcerptViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val repository = mockk<ExcerptRepository> {
        every { observeAll() } returns flowOf(emptyList())
    }
    private val settings = mockk<SettingsPreferences>(relaxed = true) {
        every { ttsReadingSettings } returns flowOf(TtsReadingSettings("reader-voice", 1.4f))
        every { allowNetworkTts } returns flowOf(false)
        every { favoritesAlphabetical } returns flowOf(false)
    }
    private val callbacks = mutableListOf<(TtsPlaybackResult) -> Unit>()
    private val spokenText = mutableListOf<String>()
    private val tts = mockk<TtsPlayer>(relaxed = true) {
        every { speakReading(any(), any(), any(), any()) } answers {
            spokenText += firstArg<String>()
            callbacks += arg<(TtsPlaybackResult) -> Unit>(3)
        }
    }
    private val store = ViewModelStore()
    @After fun clear() = store.clear()

    private fun create() = VocabularyViewModel(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), tts, mockk(relaxed = true), settings, repository
    ).also { store.put("vm", it) }
    private val excerpt = ExcerptEntity(1, 7, 7, "Source", "fp", 0, 0, 5, "Alpha", 10)

    @Test
    fun playback_deletedSource_usesSnapshotAndReadingPreferences() = runTest {
        val vm = create()
        vm.toggleExcerptPlayback(excerpt.copy(articleId = null))
        runCurrent()
        assertEquals(ExcerptPlaybackState(1, true), vm.excerptPlayback.value)
        verify { tts.speakReading("Alpha", TtsReadingSettings("reader-voice", 1.4f), false, any()) }
        callbacks.single()(TtsPlaybackResult.Started("u1", TtsVoiceMode.LOCAL))
        assertEquals(ExcerptPlaybackState(1, false), vm.excerptPlayback.value)
        callbacks.single()(TtsPlaybackResult.Finished("u1"))
        runCurrent()
        assertNull(vm.excerptPlayback.value)
    }

    @Test
    fun playback_longSnapshot_waitsForEachChunkAndDoesNotTruncate() = runTest {
        val vm = create()
        val longText = "x".repeat(8_100)
        vm.toggleExcerptPlayback(excerpt.copy(text = longText))
        runCurrent()
        assertEquals(1, callbacks.size)
        callbacks[0](TtsPlaybackResult.Finished("u1"))
        runCurrent()
        assertEquals(2, callbacks.size)
        callbacks[1](TtsPlaybackResult.Finished("u2"))
        runCurrent()
        assertEquals(3, callbacks.size)
        callbacks[2](TtsPlaybackResult.Finished("u3"))
        runCurrent()
        assertEquals(longText, spokenText.joinToString(""))
        assertNull(vm.excerptPlayback.value)
    }

    @Test
    fun playback_switchExcerptAndStop_ignoresLateCallbacks() = runTest {
        val vm = create()
        vm.toggleExcerptPlayback(excerpt)
        runCurrent()
        val oldCallback = callbacks.single()
        vm.toggleExcerptPlayback(excerpt.copy(id = 2, text = "Beta"))
        runCurrent()
        oldCallback(TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED))
        oldCallback(TtsPlaybackResult.Started("old", TtsVoiceMode.LOCAL))
        assertEquals(ExcerptPlaybackState(2, true), vm.excerptPlayback.value)
        vm.stopAudio()
        callbacks.last()(TtsPlaybackResult.Started("new", TtsVoiceMode.LOCAL))
        runCurrent()
        assertNull(vm.excerptPlayback.value)
        vm.uiEvent.test { expectNoEvents() }

        vm.selectTab(1)
        vm.toggleExcerptPlayback(excerpt)
        runCurrent()
        assertEquals(ExcerptPlaybackState(1, true), vm.excerptPlayback.value)
        vm.selectTab(0)
        callbacks.last()(TtsPlaybackResult.Started("after-tab-change", TtsVoiceMode.LOCAL))
        runCurrent()
        assertEquals(0, vm.selectedTab.value)
        assertNull(vm.excerptPlayback.value)
        vm.uiEvent.test { expectNoEvents() }
    }

    @Test
    fun playback_secondTapWhilePreferencesPending_neverStartsLateSpeech() = runTest {
        val gate = CompletableDeferred<Unit>()
        every { settings.ttsReadingSettings } returns flow {
            withContext(NonCancellable) { gate.await() }
            emit(TtsReadingSettings())
        }
        val vm = create()
        vm.toggleExcerptPlayback(excerpt)
        runCurrent()
        vm.toggleExcerptPlayback(excerpt)
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.excerptPlayback.value)
        verify(exactly = 0) { tts.speakReading(any(), any(), any(), any()) }
    }

    @Test
    fun playback_typedFailure_reportsReasonAndStops() = runTest {
        val vm = create()
        vm.uiEvent.test {
            vm.toggleExcerptPlayback(excerpt)
            runCurrent()
            callbacks.single()(TtsPlaybackResult.Failed(TtsFailureReason.NETWORK_VOICE_DISABLED))
            assertEquals(VocabularyUiEvent.ExcerptAudioFailed(TtsFailureReason.NETWORK_VOICE_DISABLED), awaitItem())
            runCurrent()
            assertNull(vm.excerptPlayback.value)
        }
    }

    @Test
    fun deleteExcerpt_whilePlaying_clearsPlaybackAndRejectsLateCompletion() = runTest {
        val vm = create()
        coEvery { repository.deleteForUndo(1) } returns excerpt
        vm.toggleExcerptPlayback(excerpt)
        runCurrent()
        vm.deleteExcerpt(excerpt)
        runCurrent()
        assertNull(vm.excerptPlayback.value)
        callbacks.single()(TtsPlaybackResult.Finished("old"))
        runCurrent()
        assertEquals(1, callbacks.size)
    }

    @Test
    fun openExcerpt_withQueuedSnackbar_navigatesIndependently() = runTest {
        val vm = create()
        coEvery { repository.deleteForUndo(1) } returns excerpt
        coEvery { repository.hasSource(excerpt) } returns true
        vm.excerptToOpen.test {
            vm.deleteExcerpt(excerpt)
            runCurrent()
            vm.openExcerpt(excerpt)
            assertEquals(excerpt, awaitItem())
            vm.uiEvent.test { assertEquals(VocabularyUiEvent.ExcerptDeleted(excerpt), awaitItem()) }
        }
    }

    @Test
    fun openExcerpt_newerRequest_dropsLateNonCooperativeOldResult() = runTest {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        val newer = excerpt.copy(id = 2)
        coEvery { repository.hasSource(excerpt) } coAnswers { withContext(NonCancellable) { gate.await(); true } }
        coEvery { repository.hasSource(newer) } returns true
        vm.excerptToOpen.test {
            vm.openExcerpt(excerpt)
            runCurrent()
            vm.openExcerpt(newer)
            assertEquals(newer, awaitItem())
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun cancelExcerptNavigation_lateResult_doesNotNavigateAfterLeavingTab() = runTest {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.hasSource(excerpt) } coAnswers { withContext(NonCancellable) { gate.await(); true } }
        vm.excerptToOpen.test {
            vm.openExcerpt(excerpt)
            runCurrent()
            vm.cancelExcerptNavigation()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun openExcerpt_missingSource_reportsUnavailableWithoutNavigation() = runTest {
        val vm = create()
        coEvery { repository.hasSource(excerpt) } returns false
        vm.excerptToOpen.test {
            vm.uiEvent.test {
                vm.openExcerpt(excerpt)
                assertEquals(VocabularyUiEvent.ExcerptSourceUnavailable, awaitItem())
            }
            expectNoEvents()
        }
    }

    @Test
    fun deleteExcerpt_repeatedTap_emitsOnlyAfterSingleSuccessfulWrite() = runTest {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.deleteForUndo(1) } coAnswers { gate.await(); excerpt }
        vm.uiEvent.test {
            vm.deleteExcerpt(excerpt)
            vm.deleteExcerpt(excerpt)
            runCurrent()
            expectNoEvents()
            gate.complete(Unit)
            assertEquals(VocabularyUiEvent.ExcerptDeleted(excerpt), awaitItem())
            coVerify(exactly = 1) { repository.deleteForUndo(1) }
        }
    }
}
