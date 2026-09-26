package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.BuildConfig
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.update.AppVersion
import io.github.zoot.englishreader.data.update.PendingUpdateDownload
import io.github.zoot.englishreader.data.update.PlatformUpdateDownload
import io.github.zoot.englishreader.data.update.UpdateDownloadBackend
import io.github.zoot.englishreader.data.update.UpdateDownloadException
import io.github.zoot.englishreader.data.update.UpdateDownloadFailure
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import io.github.zoot.englishreader.data.update.UpdateReleasePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateDownloadRepository internal constructor(
    private val backend: UpdateDownloadBackend,
    private val preferences: SettingsPreferences,
    private val localVersion: String
) {
    @Inject
    constructor(backend: UpdateDownloadBackend, preferences: SettingsPreferences) :
        this(backend, preferences, BuildConfig.VERSION_NAME)

    private val mutex = Mutex()
    private val refreshes = MutableStateFlow(0L)

    fun refresh() { refreshes.update { it + 1 } }

    suspend fun start(tag: String, restart: Boolean = false) = mutex.withLock {
        require(UpdateReleasePolicy.isValidTag(tag) && AppVersion.isNewer(tag, localVersion))
        val previous = preferences.pendingUpdateDownload.first()
        if (previous != null) {
            if (!restart && previous.tag == tag && (backend.preparedFile(previous) != null ||
                    backend.status(previous.id) !is PlatformUpdateDownload.Failed)
            ) return@withLock
            backend.remove(previous)
            preferences.setPendingUpdateDownload(null)
        }
        // DownloadManager owns the transfer once enqueued; its id must survive UI cancellation.
        withContext(NonCancellable) {
            val record = PendingUpdateDownload(backend.enqueue(tag), tag)
            try {
                preferences.setPendingUpdateDownload(record)
                refresh()
            } catch (error: Exception) {
                try {
                    backend.remove(record)
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun readyForInstall(tag: String): java.io.File = mutex.withLock {
        val record = preferences.pendingUpdateDownload.first()
            ?.takeIf { it.tag == tag && AppVersion.isNewer(it.tag, localVersion) }
            ?: throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
        backend.preparedFile(record) ?: throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
    }

    suspend fun cancel() = mutex.withLock {
        val record = preferences.pendingUpdateDownload.first() ?: return@withLock
        withContext(NonCancellable) {
            backend.remove(record)
            preferences.setPendingUpdateDownload(null)
            refresh()
        }
        currentCoroutineContext().ensureActive()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<UpdateDownloadState> = refreshes.flatMapLatest { observeRecord() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRecord(): Flow<UpdateDownloadState> = preferences.pendingUpdateDownload.flatMapLatest { record ->
        flow<UpdateDownloadState> {
            if (record == null || !AppVersion.isNewer(record.tag, localVersion)) {
                emit(UpdateDownloadState.Idle)
                return@flow
            }
            val prepared = mutex.withLock {
                if (preferences.pendingUpdateDownload.first() == record) backend.preparedFile(record) else null
            }
            if (prepared != null) {
                emit(UpdateDownloadState.Ready(record.tag, prepared))
                return@flow
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                when (val state = backend.status(record.id)) {
                    is PlatformUpdateDownload.Progress -> {
                        if (state.bytes > UpdateReleasePolicy.MAX_APK_BYTES ||
                            state.totalBytes > UpdateReleasePolicy.MAX_APK_BYTES
                        ) {
                            mutex.withLock {
                                if (preferences.pendingUpdateDownload.first() == record) backend.remove(record)
                            }
                            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
                        }
                        emit(UpdateDownloadState.Downloading(record.tag, state.bytes, state.totalBytes, state.paused))
                    }
                    PlatformUpdateDownload.Complete -> {
                        emit(UpdateDownloadState.Verifying(record.tag))
                        val file = mutex.withLock {
                            if (preferences.pendingUpdateDownload.first() != record) return@flow
                            backend.prepare(record)
                        }
                        emit(UpdateDownloadState.Ready(record.tag, file))
                        return@flow
                    }
                    is PlatformUpdateDownload.Failed -> {
                        emit(UpdateDownloadState.Failed(record.tag, state.reason))
                        return@flow
                    }
                }
                delay(1000)
            }
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(UpdateDownloadState.Failed(record?.tag.orEmpty(), failureReason(error)))
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(UpdateDownloadState.Failed("", failureReason(error)))
    }

    companion object {
        fun failureReason(error: Throwable): UpdateDownloadFailure = when (error) {
            is UpdateDownloadException -> error.reason
            is IOException -> UpdateDownloadFailure.STORAGE
            else -> UpdateDownloadFailure.UNAVAILABLE
        }
    }
}
