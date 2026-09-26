package io.github.zoot.englishreader.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.update.PendingUpdateDownload
import io.github.zoot.englishreader.data.update.PlatformUpdateDownload
import io.github.zoot.englishreader.data.update.UpdateDownloadBackend
import io.github.zoot.englishreader.data.update.UpdateDownloadException
import io.github.zoot.englishreader.data.update.UpdateDownloadFailure
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import io.github.zoot.englishreader.data.update.UpdateReleasePolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDownloadRepositoryTest {
    private val tag = "v0.1.4-beta"
    private val record = PendingUpdateDownload(42, tag)
    private val records = MutableStateFlow<PendingUpdateDownload?>(null)
    private val preferences = mockk<SettingsPreferences> {
        every { pendingUpdateDownload } returns records
        coEvery { setPendingUpdateDownload(any()) } coAnswers { records.value = firstArg() }
    }
    private val backend = mockk<UpdateDownloadBackend>(relaxed = true) {
        coEvery { enqueue(any()) } returns 42
        coEvery { preparedFile(any()) } returns null
        coEvery { status(any()) } returns PlatformUpdateDownload.Progress(10, 100, false)
    }
    private fun repository() = UpdateDownloadRepository(backend, preferences, "0.1.3-beta")

    @Test
    fun start_duplicateAndRestoredRequest_reusesPersistedDownload() = runTest {
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { backend.enqueue(tag) } coAnswers {
            entered.complete(Unit)
            release.await()
            42
        }
        val first = async { repository.start(tag) }
        entered.await()
        val second = async { repository.start(tag) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(record, records.value)
        repository().start(tag)
        coVerify(exactly = 1) { backend.enqueue(tag) }
        coVerify(exactly = 0) { backend.remove(any()) }
    }

    @Test
    fun observationDetaches_downloadIsNotCancelledAndNewObserverRecoversIt() = runTest {
        records.value = record
        val expected = UpdateDownloadState.Downloading(tag, 10, 100, false)
        assertEquals(expected, repository().observe().first())
        assertEquals(record, records.value)
        assertEquals(expected, repository().observe().first())
        coVerify(exactly = 0) { backend.remove(any()) }
        coVerify(exactly = 0) { backend.enqueue(any()) }
    }

    @Test
    fun readFailure_canRecoverWithoutRecreatingTheViewModel() = runTest {
        var unavailable = true
        every { preferences.pendingUpdateDownload } returns flow {
            if (unavailable) throw IOException("temporarily unavailable")
            emitAll(records)
        }
        val repository = repository()
        repository.observe().test {
            assertEquals(UpdateDownloadState.Failed("", UpdateDownloadFailure.STORAGE), awaitItem())
            unavailable = false
            repository.start(tag)
            assertEquals(UpdateDownloadState.Downloading(tag, 10, 100, false), awaitItem())
        }
        assertEquals(record, records.value)
        coVerify(exactly = 1) { backend.enqueue(tag) }
    }

    @Test
    fun completedDownload_isVerifiedBeforeReadyAndAgainBeforeInstallation() = runTest {
        records.value = record
        val file = File("verified.apk")
        coEvery { backend.status(42) } returns PlatformUpdateDownload.Complete
        coEvery { backend.prepare(record) } returns file
        val states = repository().observe().take(2).toList()
        assertEquals(listOf(UpdateDownloadState.Verifying(tag), UpdateDownloadState.Ready(tag, file)), states)
        coEvery { backend.preparedFile(record) } throws UpdateDownloadException(UpdateDownloadFailure.WRONG_SIGNATURE)
        try {
            repository().readyForInstall(tag)
            fail("Installation must revalidate the file")
        } catch (error: UpdateDownloadException) {
            assertEquals(UpdateDownloadFailure.WRONG_SIGNATURE, error.reason)
        }
    }

    @Test
    fun invalidPackage_reportsFailureAndRetryRemovesItBeforeEnqueue() = runTest {
        records.value = record
        coEvery { backend.status(42) } returns PlatformUpdateDownload.Complete
        coEvery { backend.prepare(record) } throws UpdateDownloadException(UpdateDownloadFailure.WRONG_VERSION)
        val state = repository().observe().first { it is UpdateDownloadState.Failed }
        assertEquals(UpdateDownloadState.Failed(tag, UpdateDownloadFailure.WRONG_VERSION), state)
        coEvery { backend.enqueue(tag) } returns 43
        repository().start(tag, restart = true)
        assertEquals(PendingUpdateDownload(43, tag), records.value)
        coVerify(exactly = 1) { backend.remove(record) }
    }

    @Test
    fun oversizedDownload_isStoppedInsteadOfContinuingInTheBackground() = runTest {
        records.value = record
        coEvery { backend.status(42) } returns PlatformUpdateDownload.Progress(1, UpdateReleasePolicy.MAX_APK_BYTES + 1, false)
        assertEquals(
            UpdateDownloadState.Failed(tag, UpdateDownloadFailure.INVALID_PACKAGE),
            repository().observe().first()
        )
        coVerify(exactly = 1) { backend.remove(record) }
    }

    @Test
    fun persistenceFailure_removesUnrecordedTransferAndPropagatesTheError() = runTest {
        val error = IOException("storage unavailable")
        coEvery { preferences.setPendingUpdateDownload(record) } throws error
        try {
            repository().start(tag)
            fail("Unrecorded download must not appear successful")
        } catch (actual: IOException) {
            assertSame(error, actual.cause ?: actual)
        }
        assertNull(records.value)
        coVerify(exactly = 1) { backend.remove(record) }
    }

    @Test
    fun uiCancellationDuringEnqueue_preservesTheConfirmedBackgroundDownload() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { backend.enqueue(tag) } coAnswers {
            entered.complete(Unit)
            release.await()
            42
        }
        val request = launch { repository().start(tag) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(record, records.value)
        coVerify(exactly = 0) { backend.remove(any()) }
    }

    @Test
    fun cancel_explicitlyRemovesTransferAndClearsRestorationState() = runTest {
        records.value = record
        repository().cancel()
        assertNull(records.value)
        assertEquals(UpdateDownloadState.Idle, repository().observe().first())
        coVerify(exactly = 1) { backend.remove(record) }
    }

    @Test
    fun cancelledObserverDuringVerification_cannotRemoveThePersistedDownload() = runTest {
        records.value = record
        coEvery { backend.status(42) } returns PlatformUpdateDownload.Complete
        val entered = CompletableDeferred<Unit>()
        coEvery { backend.prepare(record) } coAnswers {
            entered.complete(Unit)
            CompletableDeferred<File>().await()
        }
        val observation = launch { repository().observe().first { it is UpdateDownloadState.Ready } }
        entered.await()
        observation.cancelAndJoin()
        assertEquals(record, records.value)
        coVerify(exactly = 0) { backend.remove(any()) }
    }

    @Test
    fun obsoleteRecord_doesNotOfferToInstallAnOlderVersion() = runTest {
        records.value = PendingUpdateDownload(41, "v0.1.2-beta")
        assertEquals(UpdateDownloadState.Idle, repository().observe().first())
        try {
            repository().start("v0.1.2-beta")
            fail("Downgrade must be rejected")
        } catch (_: IllegalArgumentException) {
            coVerify(exactly = 0) { backend.enqueue(any()) }
        }
    }
}
