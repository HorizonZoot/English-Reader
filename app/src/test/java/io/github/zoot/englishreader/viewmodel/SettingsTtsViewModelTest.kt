package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.data.ai.AiClient
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsPlayer
import io.github.zoot.englishreader.util.TtsVoiceMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTtsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val allowed = MutableStateFlow(false)
    private val preferences = mockk<SettingsPreferences> {
        every { fontSizeOption } returns MutableStateFlow(FontSizeOption.DEFAULT)
        every { themeOption } returns MutableStateFlow(ThemeOption.DEFAULT)
        every { allowNetworkTts } returns allowed
        coEvery { setAllowNetworkTts(any()) } coAnswers { allowed.value = firstArg() }
    }
    private val player = mockk<TtsPlayer>(relaxed = true)
    private val callbacks = mutableListOf<(TtsCapability) -> Unit>()

    private fun viewModel(): SettingsViewModel {
        every { player.refresh(any(), capture(callbacks)) } returns Unit
        return SettingsViewModel(
            preferences,
            mockk<AiProfileRepository>(relaxed = true) {
                every { profiles } returns MutableStateFlow(emptyList())
                every { activeProfileId } returns MutableStateFlow(null)
            },
            mockk(relaxed = true),
            mockk<AiClient>(relaxed = true) {
                every { inFlightProfileIds } returns MutableStateFlow(emptySet())
            },
            mockk(relaxed = true), mockk(relaxed = true), player
        )
    }

    @Test
    fun setAllowNetworkTts_persistedChoice_refreshesUsingNewConsent() = runTest {
        val vm = viewModel()
        vm.allowNetworkTts.test {
            assertFalse(awaitItem())
            vm.refreshTtsCapability()
            runCurrent()
            verify { player.refresh(false, any()) }
            vm.setAllowNetworkTts(true)
            runCurrent()
            assertTrue(awaitItem())
            verify { player.refresh(true, any()) }
            assertFalse(vm.savingTtsPreference.value)
        }
    }

    @Test
    fun refreshTtsCapability_oldResultAndRelease_cannotOverwriteNewCheck() = runTest {
        val vm = viewModel()
        vm.refreshTtsCapability()
        runCurrent()
        vm.refreshTtsCapability()
        runCurrent()
        callbacks[0](TtsCapability.LanguageDataMissing)
        assertEquals(TtsCapability.Checking, vm.ttsCapability.value)
        callbacks[1](TtsCapability.Ready(TtsVoiceMode.LOCAL))
        assertEquals(TtsCapability.Ready(TtsVoiceMode.LOCAL), vm.ttsCapability.value)

        vm.releaseTts()
        callbacks[1](TtsCapability.InitializationFailed)
        assertEquals(TtsCapability.Ready(TtsVoiceMode.LOCAL), vm.ttsCapability.value)
        verify { player.shutdown() }
    }

    @Test
    fun setAllowNetworkTts_writeFailure_keepsConsentOffAndEmitsError() = runTest {
        coEvery { preferences.setAllowNetworkTts(true) } throws IOException()
        val vm = viewModel()
        vm.setAllowNetworkTts(true)
        runCurrent()

        assertFalse(allowed.value)
        assertFalse(vm.savingTtsPreference.value)
        assertEquals(Unit, vm.ttsPreferenceErrors.first())
    }

    @Test
    fun openTtsSystemAction_requestedActions_emitToHost() = runTest {
        val vm = viewModel()
        vm.ttsSystemActions.test {
            vm.openTtsSystemAction(TtsSystemAction.OPEN_SETTINGS)
            assertEquals(TtsSystemAction.OPEN_SETTINGS, awaitItem())
            vm.openTtsSystemAction(TtsSystemAction.INSTALL_DATA)
            assertEquals(TtsSystemAction.INSTALL_DATA, awaitItem())
        }
    }
}
