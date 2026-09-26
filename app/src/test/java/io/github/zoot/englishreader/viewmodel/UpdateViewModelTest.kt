package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.UpdateCheckResult
import io.github.zoot.englishreader.data.repository.UpdateRepository
import io.github.zoot.englishreader.data.repository.UpdateDownloadRepository
import io.github.zoot.englishreader.data.update.PendingUpdateDownload
import io.github.zoot.englishreader.data.update.PlatformUpdateDownload
import io.github.zoot.englishreader.data.update.UpdateDownloadBackend
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import io.github.zoot.englishreader.data.update.UpdateDownloadException
import io.github.zoot.englishreader.data.update.UpdateDownloadFailure
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
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
    private val downloadUpdates = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    private val downloads = mockk<UpdateDownloadRepository>(relaxed = true) {
        every { observe() } returns downloadUpdates
    }
    private val available = UpdateCheckResult.UpdateAvailable(
        "v0.2.0", "Release", "Notes", "https://github.com/HorizonZoot/English-Reader/releases/tag/v0.2.0"
    )

    @Test
    fun automaticCheck_nonUpdateResults_neverEmitManualFeedback() = runTest {
        for (result in listOf(UpdateCheckResult.UpToDate, UpdateCheckResult.Skipped, UpdateCheckResult.Failed)) {
            coEvery { repository.checkOnLaunch() } returns result
            val vm = UpdateViewModel(repository, downloads)
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
                modelClass.cast(UpdateViewModel(repository, downloads))!!
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
        val vm = UpdateViewModel(repository, downloads)
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
        val vm = UpdateViewModel(repository, downloads)
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
        val vm = UpdateViewModel(repository, downloads)
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

    @Test
    fun manualCheck_preservesActionableFailureReasons() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        val vm = UpdateViewModel(repository, downloads)
        for ((result, outcome) in listOf(
            UpdateCheckResult.Offline to ManualCheckOutcome.OFFLINE,
            UpdateCheckResult.TimedOut to ManualCheckOutcome.TIMED_OUT,
            UpdateCheckResult.RateLimited to ManualCheckOutcome.RATE_LIMITED
        )) {
            coEvery { repository.check(true) } returns result
            vm.manualOutcomes.test {
                vm.checkManually()
                runCurrent()
                assertEquals(outcome, awaitItem())
                assertFalse(vm.checkingManually.value)
            }
        }
    }

    @Test
    fun foregroundCheck_retriesAfterLaunchFailureWithoutManualFeedback() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Failed
        coEvery { repository.check(false) } returns available
        val vm = UpdateViewModel(repository, downloads)
        runCurrent()
        vm.onForeground()
        runCurrent()
        assertEquals(available, vm.pendingUpdate.value)
        vm.onForeground()
        coVerify(exactly = 1) { repository.check(false) }
        vm.manualOutcomes.test { expectNoEvents() }
    }

    @Test
    fun confirmedDownload_isDeduplicatedAndClosingDialogDoesNotCancelIt() = runTest {
        coEvery { repository.checkOnLaunch() } returns available
        val gate = CompletableDeferred<Unit>()
        coEvery { downloads.start(available.versionName, false) } coAnswers { gate.await() }
        val vm = UpdateViewModel(repository, downloads)
        runCurrent()
        coVerify(exactly = 0) { downloads.start(any(), any()) }
        vm.downloadUpdate()
        runCurrent()
        assertTrue(vm.downloadActionBusy.value)
        assertNull(vm.pendingUpdate.value)
        vm.downloadUpdate()
        vm.dismissDownload()
        assertFalse(vm.downloadDialogVisible.value)
        coVerify(exactly = 1) { downloads.start(available.versionName, false) }
        coVerify(exactly = 0) { downloads.cancel() }
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.downloadActionBusy.value)
    }

    @Test
    fun pendingDownload_manualCheckReopensProgressWithoutAnotherNetworkCheck() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        val vm = UpdateViewModel(repository, downloads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
        downloadUpdates.value = UpdateDownloadState.Downloading("v0.2.0", 10, 100, false)
        runCurrent()
        vm.checkManually()
        assertTrue(vm.downloadDialogVisible.value)
        coVerify(exactly = 0) { repository.check(true) }
        assertFalse(vm.checkingManually.value)
    }

    @Test
    fun downloadRestoration_readyAfterCheck_retiresUpdatePromptBeforeInstallDialog() =
        assertRestoredDownloadRetiresPrompt(ready = true)

    @Test
    fun downloadRestoration_transferAfterCheck_doesNotPromptToDownloadAgain() =
        assertRestoredDownloadRetiresPrompt(ready = false)

    @Test
    fun unavailableDownloadMetadata_doesNotBlockManualUpdateCheck() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        coEvery { repository.check(true) } returns available
        val vm = UpdateViewModel(repository, downloads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
        downloadUpdates.value = UpdateDownloadState.Failed("", UpdateDownloadFailure.STORAGE)
        runCurrent()
        vm.checkManually()
        runCurrent()
        assertEquals(available, vm.pendingUpdate.value)
        coVerify(exactly = 1) { repository.check(true) }
    }

    @Test
    fun readyDownload_notifiesOncePerFileAndInstallsOnlyAfterExplicitValidation() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        val file = File("verified-update.apk")
        val ready = UpdateDownloadState.Ready("v0.2.0", file)
        coEvery { downloads.readyForInstall("v0.2.0") } returns file
        val vm = UpdateViewModel(repository, downloads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
        downloadUpdates.value = ready
        runCurrent()
        vm.onDownloadStateVisible(ready)
        assertTrue(vm.downloadDialogVisible.value)
        vm.dismissDownload()
        vm.onDownloadStateVisible(ready)
        assertFalse(vm.downloadDialogVisible.value)
        vm.installRequests.test {
            expectNoEvents()
            vm.installUpdate()
            runCurrent()
            assertEquals(file, awaitItem())
        }
        coVerify(exactly = 1) { downloads.readyForInstall("v0.2.0") }
    }

    @Test
    fun unknownDownloadFailure_doesNotForceRestartWhenConfirmingAnUpdate() = runTest {
        coEvery { repository.checkOnLaunch() } returns available
        val vm = UpdateViewModel(repository, downloads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
        downloadUpdates.value = UpdateDownloadState.Failed("", UpdateDownloadFailure.STORAGE)
        runCurrent()
        assertEquals(available, vm.pendingUpdate.value)
        vm.downloadUpdate()
        runCurrent()
        coVerify(exactly = 1) { downloads.start(available.versionName, false) }
        coVerify(exactly = 0) { downloads.start(any(), true) }
    }

    @Test
    fun cancelledDownload_staleReadyCallbackCannotReopenTheDialog() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        coEvery { downloads.cancel() } coAnswers { downloadUpdates.value = UpdateDownloadState.Idle }
        val vm = UpdateViewModel(repository, downloads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
        val ready = UpdateDownloadState.Ready("v0.2.0", File("cancelled-update.apk"))
        downloadUpdates.value = ready
        runCurrent()
        vm.cancelDownload()
        runCurrent()
        assertEquals(UpdateDownloadState.Idle, vm.downloadState.value)
        vm.onDownloadStateVisible(ready)
        assertFalse(vm.downloadDialogVisible.value)
    }

    @Test
    fun failedRevalidation_neverEmitsInstallerRequestAndAllowsFreshDownload() = runTest {
        coEvery { repository.checkOnLaunch() } returns UpdateCheckResult.Skipped
        coEvery { downloads.readyForInstall("v0.2.0") } throws UpdateDownloadException(UpdateDownloadFailure.WRONG_SIGNATURE)
        val vm = UpdateViewModel(repository, downloads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
        downloadUpdates.value = UpdateDownloadState.Ready("v0.2.0", File("untrusted.apk"))
        runCurrent()
        vm.installRequests.test {
            vm.installUpdate()
            runCurrent()
            expectNoEvents()
            assertEquals(UpdateDownloadState.Failed("v0.2.0", UpdateDownloadFailure.WRONG_SIGNATURE), vm.downloadState.value)
        }
        vm.downloadUpdate()
        runCurrent()
        coVerify(exactly = 1) { downloads.start("v0.2.0", true) }
    }

    private fun assertRestoredDownloadRetiresPrompt(ready: Boolean) = runTest {
        val tag = available.versionName
        val record = PendingUpdateDownload(42, tag)
        val file = File("restored-update.apk")
        val records = MutableStateFlow<PendingUpdateDownload?>(record)
        val preferences = mockk<SettingsPreferences> {
            every { pendingUpdateDownload } returns records
        }
        val restoreEntered = CompletableDeferred<Unit>()
        val allowRestore = CompletableDeferred<Unit>()
        val backend = mockk<UpdateDownloadBackend> {
            coEvery { preparedFile(record) } coAnswers {
                restoreEntered.complete(Unit)
                allowRestore.await()
                if (ready) file else null
            }
            coEvery { status(record.id) } returns PlatformUpdateDownload.Progress(10, 100, false)
        }
        val restoredDownloads = UpdateDownloadRepository(backend, preferences, "0.1.3-beta")
        val checkResult = CompletableDeferred<UpdateCheckResult>()
        coEvery { repository.checkOnLaunch() } coAnswers { checkResult.await() }
        val vm = UpdateViewModel(repository, restoredDownloads)
        val store = ViewModelStore().apply { put("update", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.downloadState.collect {} }
            restoreEntered.await()
            assertEquals(UpdateDownloadState.Idle, vm.downloadState.value)
            checkResult.complete(available)
            runCurrent()
            assertEquals(available, vm.pendingUpdate.value)

            allowRestore.complete(Unit)
            runCurrent()
            val expected = if (ready) UpdateDownloadState.Ready(tag, file)
                else UpdateDownloadState.Downloading(tag, 10, 100, false)
            assertEquals(expected, vm.downloadState.value)
            assertNull(vm.pendingUpdate.value)
            vm.onDownloadStateVisible(expected)
            assertEquals(ready, vm.downloadDialogVisible.value)
            vm.dismissDownload()
            assertNull(vm.pendingUpdate.value)
        } finally {
            store.clear()
        }
    }
}
