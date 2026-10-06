package io.github.zoot.englishreader.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.ui.screen.OnboardingGate
import io.github.zoot.englishreader.ui.screen.SettingsScreen
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.OnboardingViewModel
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import io.github.zoot.englishreader.viewmodel.TtsModelsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val completedKey = booleanPreferencesKey("onboarding_completed")
    private var writes = 0
    private val store = object : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf(completedKey to true))
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            return transform(data.value).also { data.value = it }
        }
    }
    private lateinit var startup: OnboardingViewModel
    private val voiceModels = mockk<TtsModelsViewModel>(relaxed = true).also {
        every { it.installedCount } returns MutableStateFlow(0)
    }
    private val settings = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.fontSizeOption } returns MutableStateFlow(FontSizeOption.DEFAULT)
        every { it.themeOption } returns MutableStateFlow(ThemeOption.DEFAULT)
        every { it.hasConfiguredProfile } returns MutableStateFlow(false)
        every { it.dictionaryPackState } returns MutableStateFlow(DictionaryPackState.NotInstalled)
    }

    @After
    fun closeStartup() {
        if (::startup.isInitialized) startup.viewModelScope.cancel()
    }

    @Test
    fun settingsReplay_skipBackAndFinalAction_returnToSettingsWithoutWritingOrResettingCompletion() {
        lateinit var nav: NavHostController
        lateinit var back: OnBackPressedDispatcher
        startup = OnboardingViewModel(SettingsPreferences(store), mockk())
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            EnglishReaderTheme {
                OnboardingGate(viewModel = startup) {
                    nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "settings") {
                        composable("settings") {
                            SettingsScreen(
                                viewModel = settings,
                                modelsViewModel = voiceModels,
                                onOpenOnboarding = { nav.navigate(ONBOARDING_REPLAY_ROUTE) },
                                onOpenAiProfile = {},
                                onOpenCacheManagement = {},
                                onOpenSpeech = {},
                                onOpenDictionary = {},
                                onOpenAbout = {}
                            )
                        }
                        onboardingReplayDestination(nav)
                    }
                }
            }
        }
        repeat(3) { exit ->
            compose.onNodeWithTag("settings-onboarding-entry").performScrollTo().performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNode(hasText(text(R.string.onboarding_title)) and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
            compose.onNodeWithTag("settings-onboarding-entry").assertExists()
            compose.onNodeWithText(position(1)).assertIsDisplayed()
            compose.runOnIdle { assertEquals(ONBOARDING_REPLAY_ROUTE, nav.currentDestination?.route) }
            when (exit) {
                0 -> {
                    compose.onNodeWithText(text(R.string.onboarding_skip)).assertDoesNotExist()
                    compose.onNodeWithText(text(R.string.onboarding_close)).performClick()
                }
                1 -> {
                    compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
                    compose.runOnIdle { back.onBackPressed() }
                }
                else -> {
                    repeat(4) { compose.onNodeWithText(text(R.string.onboarding_next)).performClick() }
                    compose.onNodeWithText(text(R.string.onboarding_return_settings)).performClick()
                }
            }
            compose.onNodeWithTag("settings-onboarding-entry").performScrollTo().assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("settings", nav.currentDestination?.route)
                assertNull(nav.previousBackStackEntry)
                assertEquals(0, writes)
                assertTrue(store.data.value[completedKey] == true)
            }
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun position(page: Int) = RuntimeEnvironment.getApplication()
        .getString(R.string.onboarding_page_position, page, 5)
}
