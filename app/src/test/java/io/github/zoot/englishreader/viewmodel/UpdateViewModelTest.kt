package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.zoot.englishreader.data.repository.UpdateCheckResult
import io.github.zoot.englishreader.data.repository.UpdateRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val repository = mockk<UpdateRepository>()
    private val available = UpdateCheckResult.UpdateAvailable(
        "v0.2.0", "Release", "Notes", "https://github.com/HorizonZoot/English-Reader/releases/tag/v0.2.0"
    )

    @Test
    fun automaticCheck_nonUpdateResults_neverEmitManualFeedback() = runTest {
        for (result in listOf(UpdateCheckResult.UpToDate, UpdateCheckResult.Skipped, UpdateCheckResult.Failed)) {
            coEvery { repository.checkOnLaunch() } returns result
            val vm = UpdateViewModel(repository)
            runCurrent()
            assertNull(vm.pendingUpdate.value)
            assertFalse(vm.checkingManually.value)
            vm.manualOutcomes.test { expectNoEvents() }
        }
    }

    @Test
    fun automaticCheck_available_survivesRetainedStoreAndDismisses() = runTest {
        coEvery { repository.checkOnLaunch() } returns available
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                modelClass.cast(UpdateViewModel(repository))!!
        }
        try {
            val first = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
            runCurrent()
            val recreatedOwner = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
            assertSame(first, recreatedOwner)
            assertEquals(available, recreatedOwner.pendingUpdate.value)
            coVerify(exactly = 1) { repository.checkOnLaunch() }
            recreatedOwner.dismissUpdate()
            assertNull(first.pendingUpdate.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun manualCheck_inFlight_deduplicatesAndAllowsRetryAfterCompletion() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        val gate = CompletableDeferred<UpdateCheckResult>()
        coEvery { repository.check(manual = true) } coAnswers { gate.await() }
        val vm = UpdateViewModel(repository)
        vm.manualOutcomes.test {
            vm.checkManually()
            runCurrent()
            assertTrue(vm.checkingManually.value)
            vm.checkManually()
            coVerify(exactly = 1) { repository.check(true) }
            gate.complete(UpdateCheckResult.Failed)
            runCurrent()
            assertEquals(ManualCheckOutcome.FAILED, awaitItem())
            assertFalse(vm.checkingManually.value)
            coEvery { repository.check(true) } returns UpdateCheckResult.UpToDate
            vm.checkManually()
            runCurrent()
            assertEquals(ManualCheckOutcome.UP_TO_DATE, awaitItem())
            coVerify(exactly = 2) { repository.check(true) }
        }
    }

    @Test
    fun manualCheck_supersedesAutomaticResult_noDialogReopensAfterDismiss() = runTest {
        val oldResult = CompletableDeferred<UpdateCheckResult>()
        coEvery { repository.checkOnLaunch() } coAnswers {
            withContext(NonCancellable) { oldResult.await() }
        }
        coEvery { repository.check(true) } returns available
        val vm = UpdateViewModel(repository)
        runCurrent()
        vm.checkManually()
        runCurrent()
        assertEquals(available, vm.pendingUpdate.value)
        vm.dismissUpdate()
        oldResult.complete(available.copy(versionName = "v0.1.9"))
        runCurrent()
        assertNull(vm.pendingUpdate.value)
        vm.manualOutcomes.test { expectNoEvents() }
    }

    @Test
    fun clearedDuringManualCheck_cancelsWorkAndResetsLoading() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        val gate = CompletableDeferred<UpdateCheckResult>()
        var cancelled = false
        coEvery { repository.check(true) } coAnswers {
            try { gate.await() } finally { cancelled = true }
        }
        val vm = UpdateViewModel(repository)
        val store = ViewModelStore().apply { put("update", vm) }
        vm.manualOutcomes.test {
            vm.checkManually()
            runCurrent()
            assertTrue(vm.checkingManually.value)
            store.clear()
            runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.checkingManually.value)
            assertNull(vm.pendingUpdate.value)
            expectNoEvents()
        }
    }
}
