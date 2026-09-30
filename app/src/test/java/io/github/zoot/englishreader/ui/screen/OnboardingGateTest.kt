package io.github.zoot.englishreader.ui.screen

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.UpdateCheckResult
import io.github.zoot.englishreader.data.repository.UpdateDownloadRepository
import io.github.zoot.englishreader.data.repository.UpdateRepository
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.OnboardingViewModel
import io.github.zoot.englishreader.viewmodel.UpdateViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingGateTest {
    @get:Rule val compose = createComposeRule()
    private val completed = MutableStateFlow<Boolean?>(null)
    private val preferences = mockk<SettingsPreferences> {
        every { onboardingCompleted } returns completed.filterNotNull()
        coEvery { completeOnboarding() } coAnswers { completed.value = true }
    }
    private val updates = mockk<UpdateRepository> {
        coEvery { checkOnLaunch() } returns UpdateCheckResult.UpToDate
    }
    private val downloads = mockk<UpdateDownloadRepository> {
        every { observe() } returns flowOf(UpdateDownloadState.Idle)
    }
    private lateinit var model: OnboardingViewModel
    private var updateModel: UpdateViewModel? = null
    private var backgroundComposed = false
    private lateinit var back: OnBackPressedDispatcher

    @After
    fun closeModels() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        updateModel?.viewModelScope?.cancel()
    }

    @Test
    fun gate_unknownThenIncomplete_neverComposesNormalContentOrStartsUpdateCheck() {
        render()
        compose.onNodeWithText(text(R.string.onboarding_loading)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(backgroundComposed) }
        assertNormalContentNotCreated()
        compose.runOnIdle { completed.value = false }
        compose.onNodeWithText(text(R.string.onboarding_title)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(backgroundComposed) }
        compose.onNodeWithText("Library background").assertDoesNotExist()
        assertNormalContentNotCreated()
        repeat(4) { compose.onNodeWithText(text(R.string.onboarding_next)).performClick() }
        assertNormalContentNotCreated()
        coVerify(exactly = 0) { preferences.completeOnboarding() }
        compose.onNodeWithText(text(R.string.onboarding_start)).performClick()
        assertNormalContentEntered()
    }

    @Test
    fun gate_existingCompletedUser_entersNormalContentWithoutGuideOrWrite() {
        render()
        compose.runOnIdle { completed.value = true }
        compose.onNodeWithText(text(R.string.nav_articles)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_title)).assertDoesNotExist()
        coVerify(exactly = 0) { preferences.completeOnboarding() }
        coVerify(exactly = 1) { updates.checkOnLaunch() }
    }

    @Test
    fun skip_incompleteUser_persistsThenEntersNormalContent() {
        completed.value = false
        render()
        compose.onNodeWithText(text(R.string.onboarding_skip)).performClick()
        assertNormalContentEntered()
    }

    @Test
    fun systemBack_incompleteUser_persistsThenEntersNormalContent() {
        completed.value = false
        render()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.runOnIdle { back.onBackPressed() }
        assertNormalContentEntered()
    }

    @Test
    fun finish_writeFailure_entersAppAndKeepsFailureSnackbarVisible() {
        completed.value = false
        coEvery { preferences.completeOnboarding() } throws IOException("disk unavailable")
        render()
        compose.onNodeWithText(text(R.string.onboarding_skip)).performClick()
        assertNormalContentEntered()
        compose.onNodeWithText(text(R.string.onboarding_save_failed)).assertIsDisplayed()
    }

    @Test
    fun gate_readFailure_showsRecoverableGuideAndErrorWithoutStartingUpdate() {
        every { preferences.onboardingCompleted } returns flow { throw IOException("disk unavailable") }
        render()
        compose.onNodeWithText(text(R.string.onboarding_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_read_failed)).assertIsDisplayed()
        assertNormalContentNotCreated()
    }

    private fun render() {
        model = OnboardingViewModel(preferences)
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            EnglishReaderTheme {
                OnboardingGate(
                    viewModel = model,
                    background = {
                        DisposableEffect(Unit) {
                            backgroundComposed = true
                            onDispose { backgroundComposed = false }
                        }
                        Text("Library background")
                    }
                ) {
                    // A real update VM exercises its automatic-check side effect at the gate boundary.
                    DisposableEffect(Unit) {
                        val createdModel = UpdateViewModel(updates, downloads)
                        updateModel = createdModel
                        onDispose { createdModel.viewModelScope.cancel() }
                    }
                    Text(text(R.string.nav_articles))
                }
            }
        }
    }

    private fun assertNormalContentNotCreated() {
        compose.onNodeWithText(text(R.string.nav_articles)).assertDoesNotExist()
        compose.runOnIdle { assertNull(updateModel) }
        coVerify(exactly = 0) { updates.checkOnLaunch() }
    }

    private fun assertNormalContentEntered() {
        compose.runOnIdle { assertFalse(backgroundComposed) }
        compose.onNodeWithText(text(R.string.nav_articles)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_title)).assertDoesNotExist()
        coVerify(exactly = 1) { preferences.completeOnboarding() }
        coVerify(exactly = 1) { updates.checkOnLaunch() }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
