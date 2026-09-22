package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingVoicePreferencesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())
    private val settings = MutableStateFlow(TtsReadingSettings())
    private val fixture = ReadingViewModelFixture(ttsReadingSettings = settings)
    private val preferences = fixture.settingsPreferences

    @Test
    fun speechRate_olderSuccessCannotReplaceNewPendingRateAndPreviewWaitsForLatestWrite() = runTest {
        val firstWrite = CompletableDeferred<Unit>()
        val secondWrite = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        coEvery { preferences.setTtsSpeechRate(1.4f) } coAnswers {
            firstWrite.await()
            settings.value = settings.value.copy(speechRate = 1.4f)
        }
        coEvery { preferences.setTtsSpeechRate(1.8f) } coAnswers {
            secondStarted.complete(Unit)
            secondWrite.await()
            settings.value = settings.value.copy(speechRate = 1.8f)
        }
        val vm = fixture.create()
        vm.openVoiceSettings()
        runCurrent()

        val firstId = requireNotNull(vm.setReadingSpeechRate(1.36f))
        assertEquals(1.4f, requireNotNull(vm.voiceSettings.value.pendingSpeechRate), 0.001f)
        runCurrent()
        val secondId = requireNotNull(vm.setReadingSpeechRate(1.76f))
        assertTrue(secondId > firstId)
        vm.previewReadingVoice("Sample.")
        runCurrent()
        assertFalse(secondStarted.isCompleted)
        verify(exactly = 0) { fixture.ttsPlayer.speakReading(any(), any(), any(), any()) }

        firstWrite.complete(Unit)
        runCurrent()
        assertTrue(secondStarted.isCompleted)
        assertFalse(secondWrite.isCompleted)
        assertEquals(1.4f, vm.voiceSettings.value.settings.speechRate, 0.001f)
        assertEquals(1.8f, requireNotNull(vm.voiceSettings.value.pendingSpeechRate), 0.001f)
        verify(exactly = 0) { fixture.ttsPlayer.speakReading(any(), any(), any(), any()) }

        secondWrite.complete(Unit)
        runCurrent()
        assertNull(vm.voiceSettings.value.pendingSpeechRate)
        assertEquals(1.8f, vm.voiceSettings.value.settings.speechRate, 0.001f)
        verify(exactly = 1) {
            fixture.ttsPlayer.speakReading("Sample.", TtsReadingSettings(speechRate = 1.8f), false, any())
        }
    }

    @Test
    fun speechRate_olderFailureCannotClearOrReportOverNewPendingRequest() = runTest {
        val firstWrite = CompletableDeferred<Unit>()
        val secondWrite = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        coEvery { preferences.setTtsSpeechRate(1.4f) } coAnswers {
            firstWrite.await()
            throw IOException()
        }
        coEvery { preferences.setTtsSpeechRate(1.8f) } coAnswers {
            secondStarted.complete(Unit)
            secondWrite.await()
            settings.value = settings.value.copy(speechRate = 1.8f)
        }
        val vm = fixture.create()
        vm.openVoiceSettings()
        runCurrent()
        vm.readingErrors.test {
            vm.setReadingSpeechRate(1.4f)
            runCurrent()
            vm.setReadingSpeechRate(1.8f)
            runCurrent()
            firstWrite.complete(Unit)
            runCurrent()

            assertTrue(secondStarted.isCompleted)
            assertFalse(secondWrite.isCompleted)
            assertEquals(1.8f, requireNotNull(vm.voiceSettings.value.pendingSpeechRate), 0.001f)
            expectNoEvents()
            secondWrite.complete(Unit)
            runCurrent()
            assertNull(vm.voiceSettings.value.pendingSpeechRate)
            assertEquals(1.8f, vm.voiceSettings.value.settings.speechRate, 0.001f)
            expectNoEvents()
        }
    }

    @Test
    fun speechRate_latestFailureRestoresPersistedRateAndReportsError() = runTest {
        settings.value = TtsReadingSettings(speechRate = 1.2f)
        val write = CompletableDeferred<Unit>()
        coEvery { preferences.setTtsSpeechRate(1.8f) } coAnswers {
            write.await()
            throw IOException()
        }
        val vm = fixture.create()
        vm.openVoiceSettings()
        runCurrent()
        vm.readingErrors.test {
            vm.setReadingSpeechRate(1.8f)
            runCurrent()
            assertEquals(1.8f, requireNotNull(vm.voiceSettings.value.pendingSpeechRate), 0.001f)
            expectNoEvents()
            write.complete(Unit)
            runCurrent()

            assertEquals(ReadingError.SAVE_PREFERENCE, awaitItem())
            assertNull(vm.voiceSettings.value.pendingSpeechRate)
            assertEquals(1.2f, vm.voiceSettings.value.settings.speechRate, 0.001f)
            expectNoEvents()
        }
    }

    @Test
    fun reset_queuedBehindRateWrite_keepsDefaultPendingUntilResetCompletes() = runTest {
        val write = CompletableDeferred<Unit>()
        val reset = CompletableDeferred<Unit>()
        val resetStarted = CompletableDeferred<Unit>()
        coEvery { preferences.setTtsSpeechRate(1.8f) } coAnswers {
            write.await()
            settings.value = settings.value.copy(speechRate = 1.8f)
        }
        coEvery { preferences.resetReadingVoiceSettings() } coAnswers {
            resetStarted.complete(Unit)
            reset.await()
            settings.value = TtsReadingSettings()
        }
        val vm = fixture.create()
        vm.openVoiceSettings()
        runCurrent()
        val oldId = requireNotNull(vm.setReadingSpeechRate(1.8f))
        runCurrent()
        vm.resetReadingVoiceSettings()
        assertTrue(vm.voiceSettings.value.rateChangeId > oldId)
        runCurrent()
        write.complete(Unit)
        runCurrent()

        assertTrue(resetStarted.isCompleted)
        assertFalse(reset.isCompleted)
        assertEquals(TtsReadingSettings.DEFAULT_RATE, requireNotNull(vm.voiceSettings.value.pendingSpeechRate), 0.001f)
        reset.complete(Unit)
        runCurrent()
        assertNull(vm.voiceSettings.value.pendingSpeechRate)
        assertEquals(TtsReadingSettings(), vm.voiceSettings.value.settings)
        coVerify(exactly = 1) { preferences.resetReadingVoiceSettings() }
    }

    @Test
    fun speechRate_closeAndReopenDuringWrite_preservesPendingValueUntilAcknowledged() = runTest {
        val write = CompletableDeferred<Unit>()
        coEvery { preferences.setTtsSpeechRate(1.8f) } coAnswers {
            write.await()
            settings.value = settings.value.copy(speechRate = 1.8f)
        }
        val vm = fixture.create()
        assertNull(vm.setReadingSpeechRate(1.4f))
        coVerify(exactly = 0) { preferences.setTtsSpeechRate(any()) }
        vm.openVoiceSettings()
        runCurrent()
        val requestId = vm.setReadingSpeechRate(1.8f)
        runCurrent()
        vm.closeVoiceSettings()
        vm.openVoiceSettings()
        runCurrent()

        assertEquals(requestId, vm.voiceSettings.value.rateChangeId)
        assertEquals(1.8f, requireNotNull(vm.voiceSettings.value.pendingSpeechRate), 0.001f)
        write.complete(Unit)
        runCurrent()
        assertNull(vm.voiceSettings.value.pendingSpeechRate)
        assertEquals(1.8f, vm.voiceSettings.value.settings.speechRate, 0.001f)
    }
}
