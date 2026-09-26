package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.data.repository.UpdateCheckResult
import io.github.zoot.englishreader.data.repository.UpdateDownloadRepository
import io.github.zoot.englishreader.data.repository.UpdateRepository
import io.github.zoot.englishreader.data.update.UpdateDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ManualCheckOutcome { UP_TO_DATE, FAILED, OFFLINE, TIMED_OUT, RATE_LIMITED }

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val downloads: UpdateDownloadRepository
) : ViewModel() {
    private val _pendingUpdate = MutableStateFlow<UpdateCheckResult.UpdateAvailable?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()
    private val _checkingManually = MutableStateFlow(false)
    val checkingManually = _checkingManually.asStateFlow()
    private val _manualOutcomes = Channel<ManualCheckOutcome>(Channel.BUFFERED)
    val manualOutcomes = _manualOutcomes.receiveAsFlow()
    private val _downloadFailure = MutableStateFlow<UpdateDownloadState.Failed?>(null)
    val downloadState = combine(downloads.observe(), _downloadFailure) { state, failure -> failure ?: state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateDownloadState.Idle)
    private val _downloadDialogVisible = MutableStateFlow(false)
    val downloadDialogVisible = _downloadDialogVisible.asStateFlow()
    private val _downloadActionBusy = MutableStateFlow(false)
    val downloadActionBusy = _downloadActionBusy.asStateFlow()
    private val _installRequests = Channel<File>(Channel.BUFFERED)
    val installRequests = _installRequests.receiveAsFlow()
    private var manualCheckJob: Job? = null
    private var automaticCheckJob: Job? = null
    private var downloadActionJob: Job? = null
    private var generation = 0L
    private var notifiedDownloadFile: File? = null

    init {
        startAutomaticCheck(onLaunch = true)
    }

    fun onForeground() {
        downloads.refresh()
        startAutomaticCheck(onLaunch = false)
    }

    private fun startAutomaticCheck(onLaunch: Boolean) {
        if (automaticCheckJob?.isActive == true || manualCheckJob?.isActive == true ||
            _pendingUpdate.value != null || downloadState.value.hasDownloadTarget ||
            _downloadActionBusy.value
        ) return
        val request = generation
        automaticCheckJob = viewModelScope.launch {
            val result = if (onLaunch) updateRepository.checkOnLaunch() else updateRepository.check(manual = false)
            if (request == generation && result is UpdateCheckResult.UpdateAvailable &&
                !downloadState.value.hasDownloadTarget && !_downloadActionBusy.value
            ) _pendingUpdate.value = result
        }
    }

    fun checkManually() {
        if (_downloadActionBusy.value || downloadState.value.hasDownloadTarget) {
            _downloadDialogVisible.value = true
            return
        }
        if (manualCheckJob?.isActive == true) return
        _downloadFailure.value = null
        _downloadDialogVisible.value = false
        downloads.refresh()
        generation++
        automaticCheckJob?.cancel()
        _pendingUpdate.value = null
        _checkingManually.value = true
        manualCheckJob = viewModelScope.launch {
            try {
                when (val result = updateRepository.check(manual = true)) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        if (downloadState.value.hasDownloadTarget) _downloadDialogVisible.value = true
                        else _pendingUpdate.value = result
                    }
                    UpdateCheckResult.UpToDate -> _manualOutcomes.trySend(ManualCheckOutcome.UP_TO_DATE)
                    UpdateCheckResult.Offline -> _manualOutcomes.trySend(ManualCheckOutcome.OFFLINE)
                    UpdateCheckResult.TimedOut -> _manualOutcomes.trySend(ManualCheckOutcome.TIMED_OUT)
                    UpdateCheckResult.RateLimited -> _manualOutcomes.trySend(ManualCheckOutcome.RATE_LIMITED)
                    UpdateCheckResult.Skipped,
                    UpdateCheckResult.Failed -> _manualOutcomes.trySend(ManualCheckOutcome.FAILED)
                }
            } finally {
                _checkingManually.value = false
            }
        }
    }

    fun downloadUpdate() {
        val failed = downloadState.value as? UpdateDownloadState.Failed
        val tag = _pendingUpdate.value?.versionName ?: failed?.tag ?: return
        if (_downloadActionBusy.value) return
        generation++
        automaticCheckJob?.cancel()
        _pendingUpdate.value = null
        _downloadDialogVisible.value = true
        runDownloadAction(tag) { downloads.start(tag, restart = failed?.tag == tag) }
    }

    fun cancelDownload() {
        val tag = when (val state = downloadState.value) {
            is UpdateDownloadState.Downloading -> state.tag
            is UpdateDownloadState.Verifying -> state.tag
            is UpdateDownloadState.Ready -> state.tag
            is UpdateDownloadState.Failed -> state.tag
            UpdateDownloadState.Idle -> ""
        }
        runDownloadAction(tag) {
            downloads.cancel()
            _downloadDialogVisible.value = false
        }
    }

    fun installUpdate() {
        val ready = downloadState.value as? UpdateDownloadState.Ready ?: return
        runDownloadAction(ready.tag) { _installRequests.send(downloads.readyForInstall(ready.tag)) }
    }

    fun onDownloadStateVisible(state: UpdateDownloadState) {
        if (state is UpdateDownloadState.Ready && state == downloadState.value &&
            notifiedDownloadFile != state.file && !_downloadActionBusy.value
        ) {
            notifiedDownloadFile = state.file
            _downloadDialogVisible.value = true
        }
    }

    fun dismissDownload() {
        _downloadDialogVisible.value = false
    }

    private fun runDownloadAction(tag: String, action: suspend () -> Unit) {
        if (downloadActionJob?.isActive == true) return
        _downloadFailure.value = null
        _downloadActionBusy.value = true
        downloadActionJob = viewModelScope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _downloadFailure.value = UpdateDownloadState.Failed(tag, UpdateDownloadRepository.failureReason(error))
            } finally {
                _downloadActionBusy.value = false
            }
        }
    }

    fun dismissUpdate() {
        generation++
        _pendingUpdate.value = null
    }
}
