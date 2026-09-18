package io.github.zoot.englishreader.ui.screen

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.zoot.englishreader.BuildConfig
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.viewmodel.ManualCheckOutcome
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsUpdateTest {
    @get:Rule val compose = createComposeRule()
    private val checking = mutableStateOf(false)
    private val outcomes = Channel<ManualCheckOutcome>(Channel.BUFFERED)
    private var clicks = 0
    private val model = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.fontSizeOption } returns MutableStateFlow(FontSizeOption.DEFAULT)
        every { it.themeOption } returns MutableStateFlow(ThemeOption.DEFAULT)
        every { it.profiles } returns MutableStateFlow(emptyList())
        every { it.activeProfileId } returns MutableStateFlow(null)
        every { it.dictionaryPackState } returns MutableStateFlow(DictionaryPackState.NotInstalled)
        every { it.ttsCapability } returns MutableStateFlow(TtsCapability.Checking)
        every { it.allowNetworkTts } returns MutableStateFlow(false)
        every { it.savingTtsPreference } returns MutableStateFlow(false)
        every { it.ttsPreferenceErrors } returns emptyFlow()
        every { it.dictionaryPackRemovalEvents } returns emptyFlow()
    }

    private fun render() {
        val events = outcomes.receiveAsFlow()
        compose.setContent {
            EnglishReaderTheme {
                SettingsScreen(
                    onCheckForUpdate = { clicks++; checking.value = true },
                    checkingForUpdate = checking.value,
                    manualUpdateOutcomes = events,
                    viewModel = model
                )
            }
        }
    }

    @Test
    fun about_versionAndManualEntry_showsCurrentVersionAndBlocksWhileChecking() {
        render()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CHECK_UPDATE_TEST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CHECK_UPDATE_TEST_TAG).assertHasNoClickAction()
        compose.onNodeWithText("正在检查…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, clicks); checking.value = false }
        compose.onNodeWithTag(CHECK_UPDATE_TEST_TAG).assertHasClickAction()
    }

    @Test
    fun manualResult_upToDate_showsExistingSnackbar() {
        render()
        compose.runOnIdle { outcomes.trySend(ManualCheckOutcome.UP_TO_DATE) }
        compose.onNodeWithText("已是最新版本").assertIsDisplayed()
    }

    @Test
    fun manualResult_failure_showsExistingSnackbar() {
        render()
        compose.runOnIdle { outcomes.trySend(ManualCheckOutcome.FAILED) }
        compose.onNodeWithText("检查更新失败，请稍后再试").assertIsDisplayed()
    }
}
