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
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
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
    fun inFlightState_disablesOnlyMatchingProfile() {
        setContent(
            onTest = {},
            profiles = listOf(profile("profile-1"), profile("profile-2")),
            inFlightProfileIds = setOf("profile-1")
        )

        composeRule.onNodeWithTag("test_connection_profile-1").assertIsNotEnabled()
        composeRule.onNodeWithTag("test_connection_profile-2").assertIsEnabled()
    }

    private fun setContent(
        profiles: List<AiProviderProfile> = listOf(profile()),
        inFlightProfileIds: Set<String> = emptySet(),
        onTest: () -> Unit
    ) {
        composeRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AiProfileDisclosure()
                profiles.forEach { profile ->
                    ProfileConnectionTestControl(
                        enabled = profile.profileId !in inFlightProfileIds,
                        testing = profile.profileId in inFlightProfileIds,
                        onConfirmed = onTest,
                        modifier = Modifier.testTag("test_connection_${profile.profileId}")
                    )
                }
            }
        }
    }

    private fun profile(profileId: String = "profile-1") = AiProviderProfile(
        profileId = profileId,
        displayName = "Test profile",
        providerTemplate = AiProviderTemplate.DEEPSEEK,
        baseUrl = "https://api.deepseek.com",
        modelId = "deepseek-chat",
        authStrategy = AiAuthStrategy.API_KEY,
        temperature = 0.2
    )

    private fun text(resourceId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resourceId)
}
