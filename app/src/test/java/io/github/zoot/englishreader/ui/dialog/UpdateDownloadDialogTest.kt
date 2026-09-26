package io.github.zoot.englishreader.ui.dialog

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.data.update.UpdateDownloadFailure
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDownloadDialogTest {
    @get:Rule val compose = createComposeRule()
    private val busy = mutableStateOf(false)
    private var installed = 0
    private var retried = 0
    private var cancelled = 0
    private var dismissed = 0
    private var browser = 0

    private fun render(state: UpdateDownloadState) {
        compose.setContent {
            EnglishReaderTheme {
                UpdateDownloadDialog(
                    state, busy.value, { installed++ }, { retried++ }, { cancelled++ },
                    { dismissed++ }, { browser++ }
                )
            }
        }
    }

    @Test
    fun progress_backgroundActionDoesNotCancelOrInstall() {
        render(UpdateDownloadState.Downloading("v0.1.4-beta", 1024, 4096, false))
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)
        )).assertIsDisplayed()
        compose.onNodeWithText("后台下载").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, cancelled)
            assertEquals(0, installed)
        }
    }

    @Test
    fun ready_installRequiresClickAndCannotRepeatWhileBusy() {
        busy.value = true
        render(UpdateDownloadState.Ready("v0.1.4-beta", File("verified.apk")))
        compose.onNodeWithTag("update-download-install").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, installed); busy.value = false }
        compose.onNodeWithTag("update-download-install").performClick()
        compose.runOnIdle { assertEquals(1, installed) }
    }

    @Test
    @Config(qualifiers = "w480dp-h360dp-land")
    fun signatureFailure_cannotInstallButRetryAndBrowserRemainReachable() {
        render(UpdateDownloadState.Failed("v0.1.4-beta", UpdateDownloadFailure.WRONG_SIGNATURE))
        compose.onNodeWithTag("update-download-install").assertDoesNotExist()
        compose.onNodeWithText("安装包签名与当前应用不一致，已阻止安装").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开发布页面").performScrollTo().performClick()
        compose.onNodeWithTag("update-download-retry").performClick()
        compose.runOnIdle {
            assertEquals(1, browser)
            assertEquals(1, retried)
            assertEquals(0, installed)
        }
    }
}
