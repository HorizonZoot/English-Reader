package io.github.zoot.englishreader.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.ai.AI_DRAFT_CONNECTION_TEST_ID
import io.github.zoot.englishreader.data.ai.AiClient
import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiConnectionDraft
import io.github.zoot.englishreader.data.ai.AiConnectionTestEvent
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.PronunciationCacheStats
import io.github.zoot.englishreader.data.dictionary.DictionaryPackInstaller
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.dictionary.DictionaryPackRemovalResult
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import io.github.zoot.englishreader.data.repository.ProfileMutationResult
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProfileActionResult {
    CREATED,
    SELECTED,
    UPDATED,
    ROTATED,
    DELETED,
    STORAGE_REINITIALIZED,
    CREDENTIALS_CLEARED,
    CONFIGURATIONS_DELETED,
    CACHE_CLEARED,
    FAILED
}

data class ProfileActionEvent(
    val action: ProfileActionResult,
    val profileId: String? = null,
    val failure: ProfileMutationResult? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsPreferences: SettingsPreferences,
    private val profileRepository: AiProfileRepository,
    private val explanationCacheRepository: ExplanationCacheRepository,
    private val aiClient: AiClient,
    private val dictionaryPackInstaller: DictionaryPackInstaller,
    private val pronunciationAudioCache: PronunciationAudioCache,
    private val ttsPlayer: TtsPlayer
) : ViewModel() {

    val fontSizeOption: StateFlow<FontSizeOption> = settingsPreferences.fontSizeOption
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FontSizeOption.DEFAULT)

    val themeOption: StateFlow<ThemeOption> = settingsPreferences.themeOption
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeOption.DEFAULT)

    val allowNetworkTts: StateFlow<Boolean> = settingsPreferences.allowNetworkTts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _ttsCapability = MutableStateFlow<TtsCapability>(TtsCapability.Checking)
    val ttsCapability = _ttsCapability.asStateFlow()
    private val _savingTtsPreference = MutableStateFlow(false)
    val savingTtsPreference = _savingTtsPreference.asStateFlow()
    private val _ttsPreferenceErrors = Channel<Unit>(Channel.BUFFERED)
    val ttsPreferenceErrors = _ttsPreferenceErrors.receiveAsFlow()
    private val _ttsSystemActions = Channel<TtsSystemAction>(Channel.BUFFERED)
    val ttsSystemActions = _ttsSystemActions.receiveAsFlow()
    private var ttsCheckGeneration = 0L
    private var ttsCheckJob: Job? = null
    private var ttsScreenVisible = false

    fun refreshTtsCapability() {
        ttsScreenVisible = true
        val generation = ++ttsCheckGeneration
        ttsCheckJob?.cancel()
        _ttsCapability.value = TtsCapability.Checking
        ttsCheckJob = viewModelScope.launch {
            val allowed = settingsPreferences.allowNetworkTts.first()
            ttsPlayer.refresh(allowed) { capability ->
                if (generation == ttsCheckGeneration) _ttsCapability.value = capability
            }
        }
    }

    fun setAllowNetworkTts(allowed: Boolean) {
        if (!_savingTtsPreference.compareAndSet(expect = false, update = true)) return
        ttsCheckGeneration++
        viewModelScope.launch {
            try {
                settingsPreferences.setAllowNetworkTts(allowed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _ttsPreferenceErrors.send(Unit)
            } finally {
                _savingTtsPreference.value = false
            }
            if (ttsScreenVisible) refreshTtsCapability()
        }
    }

    fun openTtsSystemAction(action: TtsSystemAction) {
        _ttsSystemActions.trySend(action)
    }

    fun releaseTts() {
        ttsScreenVisible = false
        ttsCheckGeneration++
        ttsCheckJob?.cancel()
        ttsPlayer.shutdown()
    }

    val profiles: StateFlow<List<AiProviderProfile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfileId: StateFlow<String?> = profileRepository.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _profileActionEvents = Channel<ProfileActionEvent>(Channel.BUFFERED)
    val profileActionEvents: Flow<ProfileActionEvent> = _profileActionEvents.receiveAsFlow()

    val connectionTestInFlightProfileIds: StateFlow<Set<String>> =
        aiClient.inFlightProfileIds

    private val _connectionTestEvents = Channel<AiConnectionTestEvent>(Channel.BUFFERED)
    val connectionTestEvents: Flow<AiConnectionTestEvent> =
        _connectionTestEvents.receiveAsFlow()

    private val _draftConnectionTestInFlight = MutableStateFlow(false)
    val draftConnectionTestInFlight: StateFlow<Boolean> =
        _draftConnectionTestInFlight.asStateFlow()

    private val _profileMutationInFlight = MutableStateFlow(false)
    val profileMutationInFlight: StateFlow<Boolean> =
        _profileMutationInFlight.asStateFlow()

    fun testConnection(profileId: String, requestId: String) {
        viewModelScope.launch {
            val result = try {
                aiClient.testConnection(profileId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
            _connectionTestEvents.send(
                AiConnectionTestEvent(requestId, profileId, result)
            )
        }
    }

    fun testConnectionDraft(
        requestId: String,
        providerTemplate: AiProviderTemplate,
        baseUrl: String,
        modelId: String,
        apiKey: String,
        temperatureInput: String
    ): Boolean {
        if (!_draftConnectionTestInFlight.compareAndSet(expect = false, update = true)) {
            return false
        }
        viewModelScope.launch {
            val temperature = temperatureInput.trim().toDoubleOrNull()
            val draft = AiConnectionDraft(
                providerTemplate = providerTemplate,
                baseUrl = baseUrl.trim(),
                modelId = modelId.trim(),
                authStrategy = AiAuthStrategy.API_KEY,
                temperature = temperature ?: Double.NaN,
                apiKey = apiKey.trim()
            )
            if (draft.baseUrl.isBlank() || draft.modelId.isBlank() || draft.apiKey.isBlank() ||
                !draft.temperature.isFinite()
            ) {
                try {
                    _connectionTestEvents.send(
                        AiConnectionTestEvent(
                            requestId,
                            AI_DRAFT_CONNECTION_TEST_ID,
                            AiClientResult.Failure(
                                AiError.ProfileNotFound
                            )
                        )
                    )
                } finally {
                    _draftConnectionTestInFlight.value = false
                }
                return@launch
            }
            val result = try {
                aiClient.testConnectionDraft(draft)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                _draftConnectionTestInFlight.value = false
            }
            _connectionTestEvents.send(
                AiConnectionTestEvent(requestId, AI_DRAFT_CONNECTION_TEST_ID, result)
            )
        }
        return true
    }

    fun setFontSizeOption(option: FontSizeOption) {
        viewModelScope.launch {
            try {
                settingsPreferences.setFontSizeOption(option)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "Failed to persist font size", error)
            }
        }
    }

    fun setThemeOption(option: ThemeOption) {
        viewModelScope.launch {
            try {
                settingsPreferences.setThemeOption(option)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "Failed to persist theme", error)
            }
        }
    }

    fun createProfile(
        displayName: String,
        providerTemplate: AiProviderTemplate,
        baseUrl: String,
        modelId: String,
        apiKey: String,
        temperature: Double = DEFAULT_TEMPERATURE
    ) {
        viewModelScope.launch {
            val normalizedName = displayName.trim()
            val normalizedBaseUrl = baseUrl.trim()
            val normalizedModelId = modelId.trim()
            val normalizedApiKey = apiKey.trim()
            if (normalizedName.isBlank() || normalizedBaseUrl.isBlank() ||
                normalizedModelId.isBlank() || normalizedApiKey.isBlank() ||
                !temperature.isFinite()
            ) {
                _profileActionEvents.send(ProfileActionEvent(ProfileActionResult.FAILED))
                return@launch
            }

            if (!_profileMutationInFlight.compareAndSet(expect = false, update = true)) {
                return@launch
            }
            val profileId = UUID.randomUUID().toString()
            var event: ProfileActionEvent
            try {
                val result = profileRepository.createProfile(
                    profile = AiProviderProfile(
                        profileId = profileId,
                        displayName = normalizedName,
                        providerTemplate = providerTemplate,
                        baseUrl = normalizedBaseUrl,
                        modelId = normalizedModelId,
                        authStrategy = AiAuthStrategy.API_KEY,
                        temperature = temperature
                    ),
                    apiKey = normalizedApiKey
                )
                event = result.toActionEvent(ProfileActionResult.CREATED, profileId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Log.e(TAG, "Failed to create AI profile")
                event = ProfileActionEvent(ProfileActionResult.FAILED, profileId)
            } finally {
                _profileMutationInFlight.value = false
            }
            _profileActionEvents.send(event)
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            val result = profileRepository.selectActiveProfile(profileId)
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.SELECTED, profileId)
            )
        }
    }

    fun updateProfileSettings(
        profileId: String,
        displayName: String,
        temperatureInput: String
    ) {
        viewModelScope.launch {
            val normalizedDisplayName = displayName.trim()
            val temperature = temperatureInput.trim().toDoubleOrNull()
            if (normalizedDisplayName.isBlank() || temperature == null || !temperature.isFinite()) {
                _profileActionEvents.send(
                    ProfileActionEvent(ProfileActionResult.FAILED, profileId)
                )
                return@launch
            }
            val result = profileRepository.updateProfileSettings(
                profileId,
                normalizedDisplayName,
                temperature
            )
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.UPDATED, profileId)
            )
        }
    }

    fun updateProfile(
        profileId: String,
        displayName: String,
        providerTemplate: AiProviderTemplate,
        baseUrl: String,
        modelId: String,
        replacementApiKey: String,
        temperatureInput: String
    ) {
        viewModelScope.launch {
            val normalizedDisplayName = displayName.trim()
            val normalizedBaseUrl = baseUrl.trim()
            val normalizedModelId = modelId.trim()
            val temperature = temperatureInput.trim().toDoubleOrNull()
            if (profileId.isBlank() || normalizedDisplayName.isBlank() ||
                normalizedBaseUrl.isBlank() || normalizedModelId.isBlank() ||
                temperature == null || !temperature.isFinite()
            ) {
                _profileActionEvents.send(
                    ProfileActionEvent(
                        ProfileActionResult.FAILED,
                        profileId,
                        ProfileMutationResult.InvalidProfile
                    )
                )
                return@launch
            }
            if (!_profileMutationInFlight.compareAndSet(expect = false, update = true)) {
                return@launch
            }
            var event: ProfileActionEvent
            try {
                val result = profileRepository.updateProfile(
                    proposedProfile = AiProviderProfile(
                        profileId = profileId,
                        displayName = normalizedDisplayName,
                        providerTemplate = providerTemplate,
                        baseUrl = normalizedBaseUrl,
                        modelId = normalizedModelId,
                        authStrategy = AiAuthStrategy.API_KEY,
                        temperature = temperature
                    ),
                    replacementApiKey = replacementApiKey.trim().takeIf { it.isNotBlank() }
                )
                event = result.toActionEvent(ProfileActionResult.UPDATED, profileId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Log.e(TAG, "Failed to update AI profile")
                event = ProfileActionEvent(ProfileActionResult.FAILED, profileId)
            } finally {
                _profileMutationInFlight.value = false
            }
            _profileActionEvents.send(event)
        }
    }

    fun rotateApiKey(profileId: String, apiKey: String) {
        viewModelScope.launch {
            val normalizedApiKey = apiKey.trim()
            if (normalizedApiKey.isBlank()) {
                _profileActionEvents.send(
                    ProfileActionEvent(ProfileActionResult.FAILED, profileId)
                )
                return@launch
            }
            val result = profileRepository.rotateApiKey(profileId, normalizedApiKey)
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.ROTATED, profileId)
            )
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val result = profileRepository.deleteProfile(profileId)
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.DELETED, profileId)
            )
        }
    }

    fun reinitializeCredentialStorage() {
        viewModelScope.launch {
            val result = profileRepository.reinitializeCredentialStorage()
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.STORAGE_REINITIALIZED)
            )
        }
    }

    fun clearAllCredentials() {
        viewModelScope.launch {
            val result = profileRepository.clearAllCredentials()
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.CREDENTIALS_CLEARED)
            )
        }
    }

    fun deleteAllProfiles() {
        viewModelScope.launch {
            val result = profileRepository.deleteAllProfiles()
            _profileActionEvents.send(
                result.toActionEvent(ProfileActionResult.CONFIGURATIONS_DELETED)
            )
        }
    }

    /** 扩展词库的安装状态，供设置页显示「未安装 / 下载中 / 安装中 / 已安装 / 失败」。 */
    val dictionaryPackState: StateFlow<DictionaryPackState> = dictionaryPackInstaller.state

    private var dictionaryPackJob: Job? = null
    private val _dictionaryPackRemovalEvents = Channel<DictionaryPackRemovalResult>(Channel.BUFFERED)
    val dictionaryPackRemovalEvents = _dictionaryPackRemovalEvents.receiveAsFlow()

    fun removeDictionaryPack() {
        if (dictionaryPackJob?.isActive == true) return
        dictionaryPackJob = viewModelScope.launch {
            val result = dictionaryPackInstaller.remove()
            if (result != DictionaryPackRemovalResult.BUSY) _dictionaryPackRemovalEvents.send(result)
        }
    }

    /** 设置页出现时刷新一次：判据是数据库实际条数，不是「装过」这个记录。 */
    fun refreshDictionaryPackState() {
        viewModelScope.launch { dictionaryPackInstaller.refreshState() }
    }

    /**
     * 开始下载并安装扩展词库。
     *
     * **调用方必须已取得用户明确同意** —— 这是 60 MB 级流量。UI 侧由确认对话框保证，
     * 本方法不重复校验，但也绝不在任何自动路径上被调用。
     *
     * 用 `viewModelScope` 而非应用作用域：用户离开设置页即取消下载，因为这不是
     * 付费操作（与 AI 请求不同），中断没有代价，而让一个 60 MB 下载在后台跑完
     * 是用户没要求的。
     */
    fun installDictionaryPack() {
        if (dictionaryPackJob?.isActive == true) return
        dictionaryPackJob = viewModelScope.launch {
            dictionaryPackInstaller.install()
        }
    }

    /** 取消进行中的下载。 */
    fun cancelDictionaryPackInstall() {
        dictionaryPackJob?.cancel()
        dictionaryPackJob = null
    }

    /**
     * 发音缓存占用，供设置页显示「已缓存 N 个词 · X.X MB」。
     *
     * 字节数是磁盘实际占用（含索引损坏后失去记录的孤儿文件），也就是用户点「清除」
     * 后真正能释放的空间。词数只有索引知道 —— 文件名是 hash，从磁盘反推不出词。
     */
    private val _pronunciationCacheStats = MutableStateFlow(PronunciationCacheStats(0, 0L))
    val pronunciationCacheStats: StateFlow<PronunciationCacheStats> =
        _pronunciationCacheStats.asStateFlow()

    /** 进设置页时刷新一次。只读磁盘与索引，无网络。 */
    fun refreshPronunciationCacheStats() {
        viewModelScope.launch {
            _pronunciationCacheStats.value = pronunciationAudioCache.stats()
        }
    }

    /**
     * 清除发音缓存。
     *
     * 清完立刻刷新占用，否则界面会继续显示旧数字，用户以为没清掉又点一次。
     */
    fun clearPronunciationCache() {
        viewModelScope.launch {
            pronunciationAudioCache.clear()
            _pronunciationCacheStats.value = pronunciationAudioCache.stats()
        }
    }

    fun clearAiExplanationCache() {
        viewModelScope.launch {
            try {
                explanationCacheRepository.clearAllCache()
                _profileActionEvents.send(ProfileActionEvent(ProfileActionResult.CACHE_CLEARED))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "Failed to clear AI explanation cache", error)
                _profileActionEvents.send(ProfileActionEvent(ProfileActionResult.FAILED))
            }
        }
    }

    private fun ProfileMutationResult.toActionEvent(
        success: ProfileActionResult,
        profileId: String? = null
    ): ProfileActionEvent = if (this is ProfileMutationResult.Success) {
        ProfileActionEvent(success, profileId)
    } else {
        ProfileActionEvent(ProfileActionResult.FAILED, profileId, this)
    }

    override fun onCleared() {
        releaseTts()
        super.onCleared()
    }

    private companion object {
        const val TAG = "SettingsViewModel"
        const val DEFAULT_TEMPERATURE = 0.2
    }
}
