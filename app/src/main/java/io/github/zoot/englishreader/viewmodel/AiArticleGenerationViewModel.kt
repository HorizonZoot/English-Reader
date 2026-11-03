package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.data.ai.AiArticleDifficulty
import io.github.zoot.englishreader.data.ai.AiArticleLength
import io.github.zoot.englishreader.data.ai.AiArticlePromptPolicy
import io.github.zoot.englishreader.data.ai.aiFailureCause
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.repository.AiArticleGenerationRepository
import io.github.zoot.englishreader.data.repository.AiArticleGenerationState
import io.github.zoot.englishreader.data.repository.AiArticleSaveStatus
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class AiArticleGenerationUiState(
    val session: AiArticleGenerationState = AiArticleGenerationState(),
    val profiles: List<AiProviderProfile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val profilesLoadFailed: Boolean = false
) {
    val isBusy: Boolean get() = session.isGenerating || session.isSaving
    val selectedProfile: AiProviderProfile?
        get() = profiles.find { it.profileId == session.draft.profileId }
    val topicTooLong: Boolean
        get() = session.draft.parameters.topic.length > AiArticlePromptPolicy.MAX_TOPIC_CHARS
    val canGenerate: Boolean
        get() = !isBusy && profilesLoaded && selectedProfile != null && !topicTooLong
}

internal data class AiArticleReadRequest(val resultId: String, val articleId: Long)

@HiltViewModel
internal class AiArticleGenerationViewModel @Inject constructor(
    private val repository: AiArticleGenerationRepository,
    private val profiles: AiProfileRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiArticleGenerationUiState(session = repository.state.value))
    val uiState = mutableState.asStateFlow()
    private val readRequests = Channel<AiArticleReadRequest>(Channel.CONFLATED)
    val events = readRequests.receiveAsFlow()
    private var profileJob: Job? = null
    private var screenActive = false
    private var pendingSaveResultId: String? = null
    private var pendingRead: AiArticleReadRequest? = null

    init {
        viewModelScope.launch {
            repository.state.collect { session ->
                mutableState.update { it.copy(session = session) }
                completeSaveIntent(session)
            }
        }
        reloadProfiles()
    }

    fun reloadProfiles() {
        profileJob?.cancel()
        mutableState.update { it.copy(profilesLoaded = false, profilesLoadFailed = false) }
        profileJob = viewModelScope.launch {
            combine(profiles.profiles, profiles.activeProfileId) { available, activeId ->
                available to activeId
            }.catch { error ->
                currentCoroutineContext().ensureActive()
                (error.aiFailureCause() as? CancellationException)?.let { throw it }
                mutableState.update {
                    it.copy(profiles = emptyList(), profilesLoaded = true, profilesLoadFailed = true)
                }
            }.collect { (available, activeId) ->
                val draft = repository.state.value.draft
                // 只初始化尚未选过的会话；配置被删除后保留失效 ID，要求明确重选。
                if (draft.profileId == null && available.any { it.profileId == activeId }) {
                    repository.updateDraft(draft.copy(profileId = activeId))
                }
                mutableState.update {
                    it.copy(
                        session = repository.state.value,
                        profiles = available,
                        profilesLoaded = true,
                        profilesLoadFailed = false
                    )
                }
            }
        }
    }

    fun updateTopic(topic: String) {
        val draft = repository.state.value.draft
        repository.updateDraft(draft.copy(parameters = draft.parameters.copy(topic = topic)))
    }

    fun updateDifficulty(difficulty: AiArticleDifficulty) {
        val draft = repository.state.value.draft
        repository.updateDraft(draft.copy(parameters = draft.parameters.copy(difficulty = difficulty)))
    }

    fun updateLength(length: AiArticleLength) {
        val draft = repository.state.value.draft
        repository.updateDraft(draft.copy(parameters = draft.parameters.copy(length = length)))
    }

    fun selectProfile(profileId: String) {
        if (mutableState.value.profiles.none { it.profileId == profileId }) return
        repository.updateDraft(repository.state.value.draft.copy(profileId = profileId))
    }

    fun generate() {
        val current = mutableState.value.copy(session = repository.state.value)
        if (current.canGenerate && pendingRead == null && pendingSaveResultId == null) repository.generate()
    }

    fun discardPreview(resultId: String) {
        if (pendingRead == null && pendingSaveResultId == null) repository.discardPreview(resultId)
    }

    fun setScreenActive(active: Boolean) {
        screenActive = active
        if (!active) {
            // 离页仅撤销自动打开意图，不能取消 application-scope 保存或生成。
            pendingSaveResultId = null
            pendingRead = null
        }
    }

    fun saveAndRead(resultId: String) {
        if (!screenActive || pendingSaveResultId != null || pendingRead != null) return
        pendingSaveResultId = resultId
        if (!repository.savePreview(resultId)) {
            pendingSaveResultId = null
            return
        }
        completeSaveIntent(repository.state.value)
    }

    private fun completeSaveIntent(session: AiArticleGenerationState) {
        val resultId = pendingSaveResultId ?: return
        val preview = session.preview
        if (!screenActive || preview?.resultId != resultId) {
            pendingSaveResultId = null
            return
        }
        when (val saved = preview.saveStatus) {
            is AiArticleSaveStatus.Saved -> {
                pendingSaveResultId = null
                if (saved.lookupFailure != null) return
                val request = AiArticleReadRequest(resultId, saved.articleId)
                pendingRead = request
                readRequests.trySend(request)
            }
            AiArticleSaveStatus.Failed, AiArticleSaveStatus.Cancelled -> pendingSaveResultId = null
            else -> Unit
        }
    }

    /** 事件在离页或重订阅后可能过期；消费身份后导航，失败也不把 Saved 重置为未保存。 */
    fun consumeReadRequest(request: AiArticleReadRequest): Long? {
        if (!screenActive || pendingRead !== request) return null
        pendingRead = null
        val preview = repository.state.value.preview ?: return null
        return request.articleId.takeIf {
            preview.resultId == request.resultId && preview.saveStatus == AiArticleSaveStatus.Saved(it)
        }
    }
}
