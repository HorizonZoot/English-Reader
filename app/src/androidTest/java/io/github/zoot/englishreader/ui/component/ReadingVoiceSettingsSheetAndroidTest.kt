package io.github.zoot.englishreader.ui.component

import android.view.ViewConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.model.ReadingVoiceSettingsState
import io.github.zoot.englishreader.model.TtsReadingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingVoiceSettingsSheetAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    private val state = mutableStateOf(ReadingVoiceSettingsState(isOpen = true))
    private val callbackVersion = mutableStateOf(0)
    private val commits = mutableListOf<Pair<Int, Float>>()

    @Test
    fun rate_dragAcrossRecompositions_tracksBothDirectionsAndCommitsOnRelease() {
        checkDrag(holdAndReplaceOwner = false)
    }

    @Test
    fun rate_holdThenDragDuringOwnerUpdate_keepsGestureAndUsesLatestCallback() {
        checkDrag(holdAndReplaceOwner = true)
    }

    @Test
    fun rate_tapTrack_commitsOnceAndKeepsSelectedValue() {
        renderSheet()
        val slider = rateSlider()
        val before = slider.displayedRate()

        slider.performTouchInput { click(Offset(width * 0.8f, centerY)) }

        val selected = slider.displayedRate()
        assertTrue(selected > before)
        composeRule.runOnIdle {
            assertEquals(listOf(0 to selected), commits)
            assertEquals(selected, requireNotNull(state.value.pendingSpeechRate), 0.001f)
        }
    }

    private fun checkDrag(holdAndReplaceOwner: Boolean) {
        renderSheet()
        val slider = rateSlider()
        slider.performTouchInput {
            down(Offset(width / 3f, centerY))
            if (holdAndReplaceOwner) {
                advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 100)
                moveTo(Offset(width / 3f, centerY))
            }
        }

        // 分开发送 MOVE，并在中间等待重组；一次性 swipe 会漏掉回调变化导致的手势重置。
        slider.performTouchInput { moveTo(Offset(width * 0.6f, centerY), delayMillis = 50) }
        composeRule.waitForIdle()
        val first = slider.displayedRate()
        composeRule.runOnIdle { assertTrue("Dragging must not save preferences", commits.isEmpty()) }

        slider.performTouchInput { moveTo(Offset(width * 0.8f, centerY), delayMillis = 50) }
        composeRule.waitForIdle()
        val right = slider.displayedRate()
        assertTrue("The same pointer must keep moving after recomposition", right > first + 0.1f)

        if (holdAndReplaceOwner) {
            composeRule.runOnIdle {
                callbackVersion.value = 1
                state.value = state.value.copy(settings = TtsReadingSettings(speechRate = 0.7f))
            }
            assertEquals("An old preference must not replace the drag", right, slider.displayedRate(), 0.001f)
        }

        slider.performTouchInput { moveTo(Offset(width * 0.25f, centerY), delayMillis = 50) }
        composeRule.waitForIdle()
        val left = slider.displayedRate()
        assertTrue("The same gesture must also move back to the left", left < right - 0.1f)
        composeRule.runOnIdle { assertTrue("Only release may commit the rate", commits.isEmpty()) }

        slider.performTouchInput { up() }
        composeRule.waitForIdle()
        val expected = TtsReadingSettings.normalizeRate(left)
        assertEquals(expected, slider.displayedRate(), 0.001f)
        composeRule.runOnIdle {
            assertEquals(listOf((if (holdAndReplaceOwner) 1 else 0) to expected), commits)
        }
    }

    private fun renderSheet() {
        composeRule.setContent {
            MaterialTheme {
                val version = callbackVersion.value
                if (state.value.isOpen) {
                    ReadingVoiceSettingsSheet(
                        state = state.value,
                        onDismiss = { state.value = state.value.copy(isOpen = false) },
                        onVoiceChange = {},
                        onRateChange = { rate ->
                            commits += version to rate
                            val requestId = state.value.rateChangeId + 1
                            state.value = state.value.copy(
                                rateChangeId = requestId,
                                pendingSpeechRate = rate
                            )
                            requestId
                        },
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
        composeRule.waitForIdle()
    }

    private fun rateSlider(): SemanticsNodeInteraction =
        composeRule.onNodeWithTag("reading-voice-rate").performScrollTo().assertIsDisplayed()

    private fun SemanticsNodeInteraction.displayedRate(): Float =
        fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
}
