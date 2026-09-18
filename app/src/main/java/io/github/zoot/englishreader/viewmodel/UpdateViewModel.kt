package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.data.repository.UpdateCheckResult
import io.github.zoot.englishreader.data.repository.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ManualCheckOutcome { UP_TO_DATE, FAILED }

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository
) : ViewModel() {
    private val _pendingUpdate = MutableStateFlow<UpdateCheckResult.UpdateAvailable?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()
    private val _checkingManually = MutableStateFlow(false)
    val checkingManually = _checkingManually.asStateFlow()
    private val _manualOutcomes = Channel<ManualCheckOutcome>(Channel.BUFFERED)
    val manualOutcomes = _manualOutcomes.receiveAsFlow()
    private var manualCheckJob: Job? = null
    private var generation = 0L

    private val automaticCheckJob = viewModelScope.launch {
        val request = generation
        val result = updateRepository.checkOnLaunch()
        if (request == generation && result is UpdateCheckResult.UpdateAvailable) {
            _pendingUpdate.value = result
        }
    }

    fun checkManually() {
        if (manualCheckJob?.isActive == true) return
        generation++
        automaticCheckJob.cancel()
        _pendingUpdate.value = null
        _checkingManually.value = true
        manualCheckJob = viewModelScope.launch {
            try {
                when (val result = updateRepository.check(manual = true)) {
                    is UpdateCheckResult.UpdateAvailable -> _pendingUpdate.value = result
                    UpdateCheckResult.UpToDate -> _manualOutcomes.trySend(ManualCheckOutcome.UP_TO_DATE)
                    UpdateCheckResult.Skipped,
                    UpdateCheckResult.Failed -> _manualOutcomes.trySend(ManualCheckOutcome.FAILED)
                }
            } finally {
                _checkingManually.value = false
            }
        }
    }

    fun dismissUpdate() {
        generation++
        _pendingUpdate.value = null
    }
}
