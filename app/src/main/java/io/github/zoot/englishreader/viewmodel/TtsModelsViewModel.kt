package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.data.tts.TtsModelRepository
import io.github.zoot.englishreader.data.tts.TtsModelException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TtsModelsViewModel @Inject constructor(
    private val repository: TtsModelRepository,
    private val preferences: SettingsPreferences
) : ViewModel() {
    val states = repository.states
    private val eventsChannel = Channel<Int>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun install(id: String) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = viewModelScope.launch {
            try {
                repository.install(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TtsModelException) {
                // The repository publishes the actionable failure on this model's card.
            } catch (_: Exception) {
                eventsChannel.send(R.string.tts_model_operation_failed)
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun remove(id: String) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = viewModelScope.launch {
            try {
                repository.remove(id) {
                    val selected = preferences.ttsReadingSettings.first().voiceId
                    if (selected != null && TtsModelCatalog.findVoice(selected)?.first?.id == id) {
                        preferences.clearTtsVoiceIf(selected)
                    }
                }
                eventsChannel.send(R.string.tts_model_removed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventsChannel.send(R.string.tts_model_operation_failed)
            }
        }
    }
}
