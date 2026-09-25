package io.github.zoot.englishreader.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.TtsCapability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsTtsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun ttsSection_compactWidth_exposesConsentStatusAndRecoveryActions() {
        var refreshes = 0
        val actions = mutableListOf<TtsSystemAction>()
        composeRule.setContent {
            var allowed by remember { mutableStateOf(false) }
            MaterialTheme {
                Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                    TtsSettingsSection(
                        TtsCapability.NetworkVoiceDisabled, allowed, false,
                        onAllowNetworkChange = { allowed = it },
                        onRefresh = { refreshes++ }, onSystemAction = actions::add
                    )
                }
            }
        }

        composeRule.onNodeWithTag("settings-tts-status").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-network-tts").performScrollTo().assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithText("系统语音设置").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings-tts-install").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings-tts-refresh").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(listOf(TtsSystemAction.OPEN_SETTINGS, TtsSystemAction.INSTALL_DATA), actions)
        }
    }
}
