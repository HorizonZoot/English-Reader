package io.github.zoot.englishreader.ui.screen

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val titles = listOf(
        R.string.onboarding_library_title, R.string.onboarding_dictionary_title,
        R.string.onboarding_vocabulary_title, R.string.onboarding_tts_title, R.string.onboarding_ai_title
    )
    private val bodies = listOf(
        R.string.onboarding_library_body, R.string.onboarding_dictionary_body,
        R.string.onboarding_vocabulary_body, R.string.onboarding_tts_body, R.string.onboarding_ai_body
    )
    private val images = listOf(
        R.string.onboarding_library_image, R.string.onboarding_dictionary_image,
        R.string.onboarding_vocabulary_image, R.string.onboarding_tts_image, R.string.onboarding_ai_image
    )

    @Test
    fun pages_nextAndPrevious_showFiveDistinctTopicsAndFinishOnlyOnStart() {
        var finishes = 0
        compose.setContent { EnglishReaderTheme { OnboardingScreen(onFinish = { finishes++ }) } }
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_previous)).assertIsNotEnabled()
        titles.indices.forEach { index ->
            compose.onNodeWithText(position(index)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(images[index])).assertExists()
            compose.onNodeWithText(text(titles[index])).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(bodies[index])).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, finishes) }
            if (index < titles.lastIndex) compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        }
        compose.onNodeWithText(text(R.string.onboarding_next)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_previous)).performClick()
        compose.onNodeWithText(position(3)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_start)).performClick()
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun skip_firstPage_finishesWithoutVisitingOtherPages() {
        var finishes = 0
        compose.setContent { EnglishReaderTheme { OnboardingScreen(onFinish = { finishes++ }) } }
        compose.onNodeWithText(text(R.string.onboarding_skip)).performClick()
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun systemBack_laterPage_finishesInsteadOfNavigatingToPreviousPage() {
        var finishes = 0
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            EnglishReaderTheme { OnboardingScreen(onFinish = { finishes++ }) }
        }
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(1, finishes) }
        compose.onNodeWithText(position(1)).assertIsDisplayed()
    }

    @Test
    fun saving_allExitControlsDisabledAndBackConsumed_untilWriteFinishes() {
        var finishes = 0
        val saving = mutableStateOf(true)
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            EnglishReaderTheme { OnboardingScreen(onFinish = { finishes++ }, saving = saving.value) }
        }
        compose.onNodeWithText(text(R.string.onboarding_skip)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.onboarding_saving)).assertIsNotEnabled()
        compose.runOnIdle { back.onBackPressed(); assertEquals(0, finishes); saving.value = false }
        compose.onNodeWithText(text(R.string.onboarding_next)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.onboarding_skip)).performClick()
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun savedState_restoredOnRecreation_keepsPageWithoutCompleting() {
        var finishes = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EnglishReaderTheme { OnboardingScreen(onFinish = { finishes++ }) } }
        repeat(2) { compose.onNodeWithText(text(R.string.onboarding_next)).performClick() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(position(2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_previous)).performClick()
        compose.onNodeWithText(position(1)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, finishes) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactScreen_largeFontAndDarkTheme_keepsAllPageTextScrollableAndControlsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            EnglishReaderTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                        OnboardingScreen(onFinish = {})
                    }
                }
            }
        }
        titles.indices.forEach { index ->
            compose.onNodeWithText(text(bodies[index])).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.onboarding_skip)).assertIsDisplayed()
            val action = if (index == titles.lastIndex) R.string.onboarding_start else R.string.onboarding_next
            compose.onNodeWithText(text(action)).assertIsDisplayed()
            if (index < titles.lastIndex) compose.onNodeWithText(text(action)).performClick()
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun position(index: Int) = RuntimeEnvironment.getApplication()
        .getString(R.string.onboarding_page_position, index + 1, titles.size)
}
