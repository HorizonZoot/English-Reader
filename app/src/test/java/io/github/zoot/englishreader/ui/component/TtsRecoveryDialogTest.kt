package io.github.zoot.englishreader.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.model.ReadingTtsFailure
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.TtsFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsRecoveryDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun recovery_networkChoiceRequiresClickAndOtherFailuresExposeLocalRecovery() {
        val reason = mutableStateOf(TtsFailureReason.NETWORK_VOICE_DISABLED)
        val retries = mutableListOf<Boolean>()
        val systemActions = mutableListOf<TtsSystemAction>()
        composeRule.setContent {
            MaterialTheme {
                TtsRecoveryDialog(ReadingTtsFailure(1, reason.value), retries::add, systemActions::add, {})
            }
        }
        composeRule.runOnIdle { assertTrue(retries.isEmpty()) }
        composeRule.onNodeWithTag("tts-recovery-network").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(true), retries)
            reason.value = TtsFailureReason.LANGUAGE_DATA_MISSING
        }
        composeRule.onNodeWithTag("tts-recovery-network").assertDoesNotExist()
        composeRule.onNodeWithTag("tts-recovery-install").performScrollTo().performClick()
        composeRule.onNodeWithTag("tts-recovery-settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("tts-recovery-retry").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), retries)
            assertEquals(listOf(TtsSystemAction.INSTALL_DATA, TtsSystemAction.OPEN_SETTINGS), systemActions)
        }
    }
}
