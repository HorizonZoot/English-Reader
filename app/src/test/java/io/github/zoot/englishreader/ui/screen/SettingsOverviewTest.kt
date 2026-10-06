package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ThemeOption
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsOverviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aiProfileRow_reportsOpenActionAndConfiguredStatus() {
        val openCount = AtomicInteger()
        render(
            hasConfiguredProfile = true,
            onOpenAiProfile = { openCount.incrementAndGet() }
        )

        composeTestRule.onNodeWithText("已配置").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_ai_profile_entry")
            .performScrollTo()
            .performClick()

        composeTestRule.runOnIdle { assertEquals(1, openCount.get()) }
    }

    @Test
    fun overview_remainsScrollableInsideCompactWidth() {
        render(modifier = Modifier.width(240.dp))

        composeTestRule.onNodeWithText("字体大小").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_ai_profile_entry").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cacheManagement_entryNavigatesAndGroupsMaintenanceAwayFromOverview() {
        var opened = 0
        render(onOpenCacheManagement = { opened++ })

        composeTestRule.onNodeWithTag("settings-cache-management").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, opened) }
        composeTestRule.onNodeWithTag("settings-dictionary-entry").assertExists()
        composeTestRule.onNodeWithTag("settings-dict-pack").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings-pron-cache").assertDoesNotExist()
        composeTestRule.onNodeWithText("清除全部 AI 缓存").assertDoesNotExist()
    }

    private fun render(
        modifier: Modifier = Modifier,
        hasConfiguredProfile: Boolean = false,
        onOpenAiProfile: () -> Unit = {},
        onOpenCacheManagement: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            Column(modifier.verticalScroll(rememberScrollState())) {
                SettingsOverviewContent(
                    fontSize = FontSizeOption.MEDIUM,
                    onFontSizeChange = {},
                    theme = ThemeOption.SYSTEM,
                    onThemeChange = {},
                    hasConfiguredProfile = hasConfiguredProfile,
                    installedVoiceCount = 0,
                    dictionaryPackState = DictionaryPackState.NotInstalled,
                    onOpenAiProfile = onOpenAiProfile,
                    onOpenCacheManagement = onOpenCacheManagement,
                    onOpenOnboarding = {},
                    onOpenSpeech = {},
                    onOpenDictionary = {},
                    onOpenAbout = {}
                )
            }
        }
    }

}
