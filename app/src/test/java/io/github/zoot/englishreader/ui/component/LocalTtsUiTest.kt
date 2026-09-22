package io.github.zoot.englishreader.ui.component

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.model.ReadingTtsFailure
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
class LocalTtsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelDownload_requiresConfirmationAndShowsNamedVoices() {
        val installs = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LocalVoiceModelsSection(emptyMap(), installs::add, {}, {})
            }
        } }
        compose.onNodeWithText("下载模型").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(installs.isEmpty()) }
        compose.onNodeWithText("下载 Kokoro · 英语多音色？").assertExists()
        compose.onNodeWithText("将下载 103.2 MB", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(installs.isEmpty()) }
        compose.onNodeWithText("下载模型").performScrollTo().performClick()
        compose.onNode(hasText("下载模型") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf(TtsModelCatalog.kokoro.id), installs) }
    }

    @Test fun readingVoice_namedModel_canBeSelectedWithoutNetworkConsent() {
        val selections = mutableListOf<String?>()
        val voiceId = TtsModelCatalog.defaultVoiceId
        compose.setContent { MaterialTheme {
            ReadingVoiceSettingsContent(
                state = ReadingVoiceSettingsState(isOpen = true, settings = TtsReadingSettings(),
                    snapshot = TtsVoiceSnapshot(
                        voices = listOf(TtsVoiceOption(voiceId, "en-US", TtsVoiceMode.LOCAL_MODEL, 500, R.string.tts_voice_jen)),
                        capability = TtsCapability.Ready(TtsVoiceMode.LOCAL_MODEL), catalogLoaded = true)),
                onDismiss = {}, onVoiceChange = selections::add, onRateChange = { null },
                onNetworkAllowedChange = {}, onPreview = {}, onStopPreview = {}, onReset = {},
                onRecheck = {}, onSystemAction = {}
            )
        } }
        compose.onNodeWithTag("reading-voice-sheet").performScrollToNode(hasTestTag("reading-voice-option-0"))
        compose.onNodeWithTag("reading-voice-option-0").assertTextContains("Jen · 美式英语").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(voiceId), selections) }
    }

    @Test fun modelFailure_opensModelSettingsWithoutSystemLanguageInstaller() {
        val actions = mutableListOf<TtsSystemAction>()
        compose.setContent { MaterialTheme {
            TtsRecoveryDialog(ReadingTtsFailure(1, TtsFailureReason.MODEL_UNAVAILABLE), {}, actions::add, {})
        } }
        compose.onNodeWithTag("tts-recovery-install").assertDoesNotExist()
        compose.onNodeWithTag("tts-recovery-settings").assertDoesNotExist()
        compose.onNodeWithTag("tts-recovery-models").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(TtsSystemAction.OPEN_VOICE_MODELS), actions) }
    }
}
