package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.data.local.SettingsPreferences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface OnboardingState {
    data object Loading : OnboardingState
    data class Guide(val saving: Boolean = false) : OnboardingState
    data object Ready : OnboardingState
}

enum class OnboardingEvent { READ_FAILED, SAVE_FAILED }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsPreferences: SettingsPreferences
) : ViewModel() {
    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Loading)
    val state = _state.asStateFlow()
    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val completed = settingsPreferences.onboardingCompleted.first()
                currentCoroutineContext().ensureActive()
                _state.value = if (completed) OnboardingState.Ready else OnboardingState.Guide()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = OnboardingState.Guide()
                _events.send(OnboardingEvent.READ_FAILED)
            }
        }
    }

    // 只有跳过、开始使用及系统返回触发此动作；浏览页面和设置重看不写标记。
    fun finish() {
        val guide = _state.value as? OnboardingState.Guide ?: return
        if (guide.saving) return
        _state.value = OnboardingState.Guide(saving = true)
        viewModelScope.launch {
            try {
                settingsPreferences.completeOnboarding()
                currentCoroutineContext().ensureActive()
                _state.value = OnboardingState.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 不把磁盘失败当作已保存；仅放行本次会话，提示下次可能再次显示。
                _events.send(OnboardingEvent.SAVE_FAILED)
                _state.value = OnboardingState.Ready
            } finally {
                if (_state.value is OnboardingState.Guide) {
                    _state.value = OnboardingState.Guide()
                }
            }
        }
    }
}
