package io.github.zoot.englishreader.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCacheManagementTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clearCacheDialog_dismissDoesNotInvokeCallback() {
        var clearCount = 0
        setProfileSection(onClearCache = { clearCount++ })
        openClearCacheDialog()

        composeRule.onNodeWithText(text(R.string.settings_ai_cache_clear_cancel)).performClick()

        composeRule.runOnIdle { assertEquals(0, clearCount) }
        assertDialogClosed()
    }

    @Test
    fun clearCacheDialog_confirmInvokesCallbackExactlyOnceAndCloses() {
        var clearCount = 0
        setProfileSection(onClearCache = { clearCount++ })
        openClearCacheDialog()

        composeRule.onNodeWithText(text(R.string.settings_ai_cache_clear_title))
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, clearCount) }
        composeRule.onNodeWithText(text(R.string.settings_ai_cache_clear_confirm)).performClick()

        composeRule.runOnIdle { assertEquals(1, clearCount) }
        assertDialogClosed()
    }

    private fun setProfileSection(onClearCache: () -> Unit = {}) {
        composeRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsCacheClearAction(onClearCache)
            }
        }
    }

    private fun openClearCacheDialog() {
        composeRule.onNodeWithText(text(R.string.settings_ai_cache_clear))
            .performScrollTo()
            .performClick()
    }

    private fun assertDialogClosed() {
        val dialogs = composeRule
            .onAllNodesWithText(text(R.string.settings_ai_cache_clear_title))
            .fetchSemanticsNodes()
        assertTrue(dialogs.isEmpty())
    }

    private fun text(resourceId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resourceId)
}
