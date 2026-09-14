package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.model.ReadingVoiceSettingsState
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsVoiceMode
import io.github.zoot.englishreader.util.TtsVoiceOption
import io.github.zoot.englishreader.util.TtsVoiceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingVoiceSettingsSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun content_networkVoiceRequiresConsentThenCanBeSelectedAndPreviewed() {
        val state = mutableStateOf(readyState())
        val selectedVoices = mutableListOf<String?>()
        val networkChanges = mutableListOf<Boolean>()
        var previewCount = 0
        var stopCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(640.dp).fillMaxWidth()) {
                    ReadingVoiceSettingsContent(
                        state = state.value,
                    onDismiss = {},
                    onVoiceChange = { voiceId ->
                        selectedVoices += voiceId
                        state.value = state.value.copy(
                            settings = state.value.settings.copy(voiceId = voiceId)
                        )
                    },
                    onRateChange = {},
                    onNetworkAllowedChange = { allowed ->
                        networkChanges += allowed
                        state.value = state.value.copy(allowNetwork = allowed)
                    },
                    onPreview = {
                        previewCount++
                        state.value = state.value.copy(previewing = true)
                    },
                    onStopPreview = {
                        stopCount++
                        state.value = state.value.copy(previewing = false)
                    },
                    onReset = {},
                    onRecheck = {},
                    onSystemAction = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("reading-voice-auto").assertIsSelected()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-option-1"))
        composeRule.onNodeWithTag("reading-voice-option-1")
            .assertIsNotEnabled()
        composeRule.onNodeWithText("网络 · 请先允许使用网络语音").assertIsDisplayed()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-network"))
        composeRule.onNodeWithTag("reading-voice-network").performClick()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-option-1"))
        composeRule.onNodeWithText("网络 · 质量 300").assertIsDisplayed()
        composeRule.onNodeWithTag("reading-voice-option-1")
            .assertIsEnabled().performClick().assertIsSelected()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-preview"))
        composeRule.onNodeWithTag("reading-voice-preview").performClick()
        composeRule.onNodeWithText("停止试听").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(true), networkChanges)
            assertEquals(listOf("engine::network"), selectedVoices)
            assertEquals(1, previewCount)
            assertEquals(1, stopCount)
        }
    }

    @Test
    fun content_rateAndRecoveryCommandsDispatchExpectedValues() {
        val state = mutableStateOf(
            readyState().copy(settings = TtsReadingSettings(speechRate = 1.0f))
        )
        val rates = mutableListOf<Float>()
        val systemActions = mutableListOf<TtsSystemAction>()
        var resets = 0
        var rechecks = 0

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(640.dp).fillMaxWidth()) {
                    ReadingVoiceSettingsContent(
                        state = state.value,
                    onDismiss = {},
                    onVoiceChange = {},
                    onRateChange = { rate ->
                        rates += rate
                        state.value = state.value.copy(
                            settings = state.value.settings.copy(speechRate = rate)
                        )
                    },
                    onNetworkAllowedChange = {},
                    onPreview = {},
                    onStopPreview = {},
                    onReset = { resets++ },
                    onRecheck = { rechecks++ },
                    onSystemAction = systemActions::add
                    )
                }
            }
        }

        val rateNode = composeRule.onNodeWithTag("reading-voice-rate")
        rateNode.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(1.6f)
        }
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-reset"))
        composeRule.onNodeWithTag("reading-voice-reset").performClick()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-recheck"))
        composeRule.onNodeWithTag("reading-voice-recheck").performClick()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-system-settings"))
        composeRule.onNodeWithTag("reading-voice-system-settings").performClick()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-install-data"))
        composeRule.onNodeWithTag("reading-voice-install-data").performClick()

        composeRule.runOnIdle {
            assertEquals(1, rates.size)
            assertEquals(1.6f, rates.single(), 0.01f)
            assertEquals(1, resets)
            assertEquals(1, rechecks)
            assertEquals(
                listOf(TtsSystemAction.OPEN_SETTINGS, TtsSystemAction.INSTALL_DATA),
                systemActions
            )
        }
    }

    @Test
    fun content_unavailableVoiceDisablesPreviewAndShowsFailure() {
        val state = ReadingVoiceSettingsState(
            isOpen = true,
            snapshot = TtsVoiceSnapshot(
                capability = TtsCapability.LanguageDataMissing,
                catalogLoaded = true
            ),
            previewFailure = TtsFailureReason.LANGUAGE_DATA_MISSING
        )

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(640.dp).fillMaxWidth()) {
                    ReadingVoiceSettingsContent(
                        state = state,
                    onDismiss = {},
                    onVoiceChange = {},
                    onRateChange = {},
                    onNetworkAllowedChange = {},
                    onPreview = {},
                    onStopPreview = {},
                    onReset = {},
                    onRecheck = {},
                    onSystemAction = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("reading-voice-preview").assertIsNotEnabled()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-preview-failure"))
        composeRule.onNodeWithTag("reading-voice-preview-failure").assertIsDisplayed()
    }

    private fun readyState() = ReadingVoiceSettingsState(
        isOpen = true,
        snapshot = TtsVoiceSnapshot(
            voices = listOf(
                TtsVoiceOption("engine::local", "en-US", TtsVoiceMode.LOCAL, 300),
                TtsVoiceOption("engine::network", "en-US", TtsVoiceMode.NETWORK, 300)
            ),
            capability = TtsCapability.Ready(TtsVoiceMode.LOCAL),
            catalogLoaded = true
        )
    )
}
