package io.github.zoot.englishreader.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsConnectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmation_cancelDoesNotStartRequest() {
        val calls = AtomicInteger()
        setContent { calls.incrementAndGet() }

        composeRule.onNodeWithText(text(R.string.settings_ai_connection_test)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_ai_connection_test_cancel)).performClick()

        composeRule.runOnIdle { assertEquals(0, calls.get()) }
    }

    @Test
    fun confirmation_confirmStartsExactlyOneRequestAndCanRepeat() {
        val calls = AtomicInteger()
        setContent { calls.incrementAndGet() }

        composeRule.onNodeWithText(text(R.string.settings_ai_connection_test)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_ai_connection_test_confirm)).performClick()
        composeRule.runOnIdle { assertEquals(1, calls.get()) }

        composeRule.onNodeWithText(text(R.string.settings_ai_connection_test)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_ai_connection_test_confirm)).performClick()
        composeRule.runOnIdle { assertEquals(2, calls.get()) }
    }

    @Test
    fun disclosure_isVisibleNearProfileControls() {
        setContent {}
        composeRule.onNodeWithText(
            text(R.string.settings_ai_third_party_disclosure)
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun connectionControl_disabledTestingAndReadyInputs_setButtonEnabledState() {
        composeRule.setContent {
            Column {
                ProfileConnectionTestControl(
                    enabled = false,
                    testing = true,
                    onConfirmed = {},
                    modifier = Modifier.testTag("test_connection_disabled")
                )
                ProfileConnectionTestControl(
                    enabled = true,
                    testing = false,
                    onConfirmed = {},
                    modifier = Modifier.testTag("test_connection_ready")
                )
            }
        }

        composeRule.onNodeWithTag("test_connection_disabled").assertIsNotEnabled()
        composeRule.onNodeWithTag("test_connection_ready").assertIsEnabled()
    }

    private fun setContent(
        onTest: () -> Unit
    ) {
        composeRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AiProfileDisclosure()
                ProfileConnectionTestControl(
                    enabled = true,
                    testing = false,
                    onConfirmed = onTest
                )
            }
        }
    }

    private fun text(resourceId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resourceId)
}
