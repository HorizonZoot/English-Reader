package io.github.zoot.englishreader.ui.component

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
@Config(sdk = [34], application = Application::class)
class ReadingVoiceSettingsSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun content_networkVoiceRequiresConsentThenCanBeSelectedAndPreviewed() {
        val state = mutableStateOf(readyState())
        val selectedVoices = mutableListOf<String?>()
        val networkChanges = mutableListOf<Boolean>()
        var previewCount = 0
        var stopCount = 0

        renderContent(
            state = { state.value },
            onVoiceChange = { voiceId ->
                selectedVoices += voiceId
                state.value = state.value.copy(
                    settings = state.value.settings.copy(voiceId = voiceId)
                )
            },
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
            }
        )

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

        renderContent(
            state = { state.value },
            onRateChange = { rate ->
                rates += rate
                val requestId = state.value.rateChangeId + 1
                state.value = state.value.copy(
                    settings = state.value.settings.copy(speechRate = rate),
                    rateChangeId = requestId
                )
                requestId
            },
            onReset = { resets++ },
            onRecheck = { rechecks++ },
            onSystemAction = systemActions::add
        )

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

        renderContent(state = { state })

        composeRule.onNodeWithTag("reading-voice-preview").assertIsNotEnabled()
        composeRule.onNodeWithTag("reading-voice-sheet")
            .performScrollToNode(hasTestTag("reading-voice-preview-failure"))
        composeRule.onNodeWithTag("reading-voice-preview-failure").assertIsDisplayed()
    }

    @Test
    fun content_rateCommitKeepsDraftUntilAcknowledgedAndIgnoresOlderPreferenceEmission() {
        val state = mutableStateOf(readyState())
        val rates = mutableListOf<Float>()
        renderContent(
            state = { state.value },
            onRateChange = { rate ->
                rates += rate
                rates.size.toLong()
            }
        )

        val range = composeRule.onNodeWithTag("reading-voice-rate")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0, range.steps)
        changeRate(1.63f)
        assertDisplayedRate(1.6f)
        composeRule.runOnIdle {
            assertEquals(1.0f, state.value.settings.speechRate, 0.001f)
            state.value = state.value.copy(allowNetwork = true)
        }
        assertDisplayedRate(1.6f)
        composeRule.runOnIdle {
            state.value = state.value.copy(rateChangeId = 1, pendingSpeechRate = 1.6f)
        }
        assertDisplayedRate(1.6f)

        changeRate(1.27f)
        composeRule.runOnIdle {
            state.value = state.value.copy(
                settings = state.value.settings.copy(speechRate = 1.6f),
                pendingSpeechRate = null
            )
        }
        assertDisplayedRate(1.3f)
        composeRule.runOnIdle {
            state.value = state.value.copy(rateChangeId = 2, pendingSpeechRate = 1.3f)
        }
        assertDisplayedRate(1.3f)
        composeRule.runOnIdle {
            state.value = state.value.copy(
                settings = state.value.settings.copy(speechRate = 1.3f),
                pendingSpeechRate = null
            )
        }
        assertDisplayedRate(1.3f)
        composeRule.runOnIdle { assertEquals(listOf(1.6f, 1.3f), rates) }
    }

    @Test
    fun content_reopenedDuringSaveShowsPendingRateThenRestoresPersistedRateOnFailure() {
        val state = mutableStateOf(readyState())
        val visible = mutableStateOf(true)
        var commits = 0
        renderContent(
            state = { state.value },
            visible = { visible.value },
            onRateChange = { rate ->
                commits++
                state.value = state.value.copy(rateChangeId = commits.toLong(), pendingSpeechRate = rate)
                commits.toLong()
            }
        )

        changeRate(1.6f)
        assertDisplayedRate(1.6f)
        composeRule.runOnIdle { visible.value = false }
        composeRule.onNodeWithTag("reading-voice-rate").assertDoesNotExist()
        composeRule.runOnIdle { visible.value = true }
        assertDisplayedRate(1.6f)
        composeRule.runOnIdle { state.value = state.value.copy(pendingSpeechRate = null) }
        assertDisplayedRate(1.0f)
        composeRule.runOnIdle { assertEquals(1, commits) }
    }

    private fun changeRate(rate: Float) {
        composeRule.onNodeWithTag("reading-voice-rate")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(rate) }
    }

    private fun assertDisplayedRate(expected: Float) {
        val range = composeRule.onNodeWithTag("reading-voice-rate")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(expected, range.current, 0.001f)
    }

    private fun renderContent(
        state: () -> ReadingVoiceSettingsState,
        onVoiceChange: (String?) -> Unit = {},
        onRateChange: (Float) -> Long? = { null },
        onNetworkAllowedChange: (Boolean) -> Unit = {},
        onPreview: () -> Unit = {},
        onStopPreview: () -> Unit = {},
        onReset: () -> Unit = {},
        onRecheck: () -> Unit = {},
        onSystemAction: (TtsSystemAction) -> Unit = {},
        visible: () -> Boolean = { true }
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(640.dp).fillMaxWidth()) {
                    if (visible()) {
                        ReadingVoiceSettingsContent(
                            state = state(),
                            onDismiss = {},
                            onVoiceChange = onVoiceChange,
                            onRateChange = onRateChange,
                            onNetworkAllowedChange = onNetworkAllowedChange,
                            onPreview = onPreview,
                            onStopPreview = onStopPreview,
                            onReset = onReset,
                            onRecheck = onRecheck,
                            onSystemAction = onSystemAction
                        )
                    }
                }
            }
        }
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
