package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val newInstall = mockk<ArticleRepository> {
        coEvery { hasUserData() } returns false
    }

    @Test
    fun startup_noFlagButUserData_skipsGuideAndPersistsCompletionOnce() = runTest {
        val preferences = preferences(completed = false)
        val existing = mockk<ArticleRepository> { coEvery { hasUserData() } returns true }
        val model = OnboardingViewModel(preferences, existing)
        model.events.test {
            advanceUntilIdle()
            assertEquals(OnboardingState.Ready, model.state.value)
            expectNoEvents()
        }
        coVerify(exactly = 1) { preferences.completeOnboarding() }
    }

    @Test
    fun startup_noFlagAndUserDataWriteFails_stillOpensAppWithoutError() = runTest {
        val preferences = preferences(completed = false)
        coEvery { preferences.completeOnboarding() } throws IOException("disk unavailable")
        val existing = mockk<ArticleRepository> { coEvery { hasUserData() } returns true }
        val model = OnboardingViewModel(preferences, existing)
        model.events.test {
            advanceUntilIdle()
            assertEquals(OnboardingState.Ready, model.state.value)
            expectNoEvents()
        }
    }

    @Test
    fun startup_noFlagAndUserDataCheckFails_showsGuideWithoutWriting() = runTest {
        val preferences = preferences(completed = false)
        val broken = mockk<ArticleRepository> {
            coEvery { hasUserData() } throws IllegalStateException("database unavailable")
        }
        val model = OnboardingViewModel(preferences, broken)
        advanceUntilIdle()
        assertEquals(OnboardingState.Guide(), model.state.value)
        coVerify(exactly = 0) { preferences.completeOnboarding() }
    }

    @Test
    fun startup_completedFlag_doesNotQueryUserData() = runTest {
        OnboardingViewModel(preferences(completed = true), newInstall)
        advanceUntilIdle()
        coVerify(exactly = 0) { newInstall.hasUserData() }
    }

    @Test
    fun startup_pendingRead_blocksEntryAndIgnoresFinishUntilFlagIsKnown() = runTest {
        val completion = MutableSharedFlow<Boolean>()
        val preferences = mockk<SettingsPreferences> {
            every { onboardingCompleted } returns completion
        }
        val model = OnboardingViewModel(preferences, newInstall)
        assertEquals(OnboardingState.Loading, model.state.value)
        model.finish()
        advanceUntilIdle()
        assertEquals(OnboardingState.Loading, model.state.value)
        coVerify(exactly = 0) { preferences.completeOnboarding() }
        completion.emit(false)
        advanceUntilIdle()
        assertEquals(OnboardingState.Guide(), model.state.value)
    }

    @Test
    fun startup_completedFlag_opensAppWithoutGuideOrAnotherWrite() = runTest {
        val preferences = preferences(completed = true)
        val model = OnboardingViewModel(preferences, newInstall)
        model.state.test {
            assertEquals(OnboardingState.Loading, awaitItem())
            advanceUntilIdle()
            assertEquals(OnboardingState.Ready, awaitItem())
            model.finish()
            expectNoEvents()
        }
        coVerify(exactly = 0) { preferences.completeOnboarding() }
    }

    @Test
    fun startup_unfinishedVisitAndNewInstance_showGuideWithoutPersistingCompletion() = runTest {
        val preferences = preferences(completed = false)
        val first = OnboardingViewModel(preferences, newInstall)
        advanceUntilIdle()
        assertEquals(OnboardingState.Guide(), first.state.value)
        first.viewModelScope.cancel()
        val restarted = OnboardingViewModel(preferences, newInstall)
        advanceUntilIdle()
        assertEquals(OnboardingState.Guide(), restarted.state.value)
        coVerify(exactly = 0) { preferences.completeOnboarding() }
    }

    @Test
    fun finish_repeatedWhileWritePending_persistsOnceBeforeOpeningApp() = runTest {
        val write = CompletableDeferred<Unit>()
        val preferences = preferences(completed = false)
        coEvery { preferences.completeOnboarding() } coAnswers { write.await() }
        val model = OnboardingViewModel(preferences, newInstall)
        advanceUntilIdle()
        model.finish()
        model.finish()
        assertEquals(OnboardingState.Guide(saving = true), model.state.value)
        advanceUntilIdle()
        coVerify(exactly = 1) { preferences.completeOnboarding() }
        assertEquals(OnboardingState.Guide(saving = true), model.state.value)
        write.complete(Unit)
        advanceUntilIdle()
        assertEquals(OnboardingState.Ready, model.state.value)
        model.finish()
        coVerify(exactly = 1) { preferences.completeOnboarding() }
    }

    @Test
    fun startup_readFailure_showsGuideAndOneShotErrorWithoutWriting() = runTest {
        val preferences = mockk<SettingsPreferences> {
            every { onboardingCompleted } returns flow { throw IOException("disk unavailable") }
        }
        val model = OnboardingViewModel(preferences, newInstall)
        model.events.test {
            advanceUntilIdle()
            assertEquals(OnboardingState.Guide(), model.state.value)
            assertEquals(OnboardingEvent.READ_FAILED, awaitItem())
            expectNoEvents()
        }
        model.events.test { expectNoEvents() }
        coVerify(exactly = 0) { preferences.completeOnboarding() }
    }

    @Test
    fun finish_writeFailure_allowsThisSessionAndReportsFailureOnlyOnce() = runTest {
        val preferences = preferences(completed = false)
        coEvery { preferences.completeOnboarding() } throws IOException("disk unavailable")
        val model = OnboardingViewModel(preferences, newInstall)
        advanceUntilIdle()
        model.events.test {
            model.finish()
            advanceUntilIdle()
            assertEquals(OnboardingState.Ready, model.state.value)
            assertEquals(OnboardingEvent.SAVE_FAILED, awaitItem())
            model.finish()
            expectNoEvents()
        }
        model.events.test { expectNoEvents() }
        val restarted = OnboardingViewModel(preferences, newInstall)
        advanceUntilIdle()
        assertEquals(OnboardingState.Guide(), restarted.state.value)
        coVerify(exactly = 1) { preferences.completeOnboarding() }
    }

    @Test
    fun startup_cancelledRead_doesNotOpenAppOrReportFailure() = runTest {
        val preferences = mockk<SettingsPreferences> {
            every { onboardingCompleted } returns flow { throw CancellationException() }
        }
        val model = OnboardingViewModel(preferences, newInstall)
        model.events.test {
            advanceUntilIdle()
            assertEquals(OnboardingState.Loading, model.state.value)
            expectNoEvents()
        }
    }

    @Test
    fun finish_cancelledWrite_resetsBusyWithoutOpeningAppOrReportingFailure() = runTest {
        val preferences = preferences(completed = false)
        coEvery { preferences.completeOnboarding() } throws CancellationException()
        val model = OnboardingViewModel(preferences, newInstall)
        advanceUntilIdle()
        model.events.test {
            model.finish()
            advanceUntilIdle()
            assertEquals(OnboardingState.Guide(), model.state.value)
            expectNoEvents()
            coEvery { preferences.completeOnboarding() } returns Unit
            model.finish()
            advanceUntilIdle()
            assertEquals(OnboardingState.Ready, model.state.value)
            expectNoEvents()
        }
    }

    private fun preferences(completed: Boolean): SettingsPreferences = mockk {
        every { onboardingCompleted } returns flowOf(completed)
        coEvery { completeOnboarding() } returns Unit
    }
}
