package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.data.entity.ContextReviewEntry
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContextReviewUiState(
    val round: ContextReviewRound? = null,
    val busy: Boolean = true,
    val failed: Boolean = false
)

enum class ContextReviewEvent { SAVE_FAILED, CARD_CHANGED, SOURCE_UNAVAILABLE }

data class ContextReviewSource(val requestId: Long, val entry: ContextReviewEntry)

@HiltViewModel
class ContextReviewViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository
) : ViewModel() {
    private data class Control(
        val day: Long,
        val token: String? = null,
        val busy: Boolean = true,
        val failed: Boolean = false
    )

    private val round = MutableStateFlow<ContextReviewRound?>(null)
    private val observationFailed = MutableStateFlow(false)
    private val control = MutableStateFlow(Control(vocabularyRepository.currentReviewDay()))
    private val events = Channel<ContextReviewEvent>(Channel.BUFFERED)
    val uiEvent = events.receiveAsFlow()
    private val sourceToOpen = MutableSharedFlow<ContextReviewSource>()
    val contextToOpen = sourceToOpen.asSharedFlow()
    private var sourceRequestId = 0L
    private var sourceRequest: Job? = null
    private var observation: Job? = null
    private var refreshAfterAction = false

    val state = combine(round, control, observationFailed) { saved, current, loadFailed ->
        val visible = saved?.takeIf {
            it.session.sessionToken == current.token && it.session.localDay == current.day
        }
        val failed = current.failed || loadFailed
        ContextReviewUiState(visible, current.busy || (visible == null && !failed), failed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContextReviewUiState())

    init {
        observeRound()
        loadRound()
    }

    private fun observeRound() {
        observation?.cancel()
        observationFailed.value = false
        observation = viewModelScope.launch {
            try {
                vocabularyRepository.observeContextReview().collect { saved ->
                    if (round.value?.current() != saved?.current()) cancelContextNavigation()
                    round.value = saved
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                cancelContextNavigation()
                observationFailed.value = true
            }
        }
    }

    fun retry() {
        if (control.value.busy) return
        observeRound()
        loadRound()
    }

    fun startNextRound() {
        if (control.value.busy || state.value.round?.current() != null) return
        loadRound(startNext = true)
    }

    fun refreshDate() {
        val day = vocabularyRepository.currentReviewDay()
        if (day == control.value.day) return
        cancelContextNavigation()
        val busy = control.value.busy
        control.value = control.value.copy(day = day, token = null)
        if (busy) refreshAfterAction = true else loadRound()
    }

    private fun loadRound(startNext: Boolean = false) {
        cancelContextNavigation()
        control.value = control.value.copy(busy = true, failed = false)
        viewModelScope.launch {
            try {
                val saved = if (startNext) {
                    vocabularyRepository.startNextContextReview()
                } else {
                    vocabularyRepository.startOrResumeContextReview()
                }
                control.value = control.value.copy(day = saved.session.localDay, token = saved.session.sessionToken)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                control.value = control.value.copy(failed = true)
            } finally {
                finishAction()
            }
        }
    }

    fun reveal(itemToken: String) = updateCurrent(itemToken, requireRevealed = false) { entry ->
        vocabularyRepository.revealContextAnswer(entry.item.itemToken)
    }

    fun grade(itemToken: String, grade: ContextGrade) = updateCurrent(itemToken, requireRevealed = true) { entry ->
        vocabularyRepository.gradeContextAnswer(entry.item.itemToken, grade)
    }

    fun cancelContextNavigation() {
        sourceRequestId++
        sourceRequest?.cancel()
        sourceRequest = null
    }

    fun openContextSource(itemToken: String) {
        val entry = currentEntry(itemToken, requireRevealed = true) ?: return
        cancelContextNavigation()
        val source = ContextReviewSource(sourceRequestId, entry)
        sourceRequest = viewModelScope.launch {
            val available = try {
                vocabularyRepository.hasContextSource(entry.card)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentSource(source)) return@launch
            if (available && entry.card.articleId != null) sourceToOpen.emit(source)
            else events.send(ContextReviewEvent.SOURCE_UNAVAILABLE)
        }
    }

    fun consumeContextSource(source: ContextReviewSource): ContextCardEntity? {
        if (!isCurrentSource(source)) return null
        cancelContextNavigation()
        return source.entry.card
    }

    private fun isCurrentSource(source: ContextReviewSource): Boolean =
        source.requestId == sourceRequestId &&
            currentEntry(source.entry.item.itemToken, requireRevealed = true) == source.entry

    private fun currentEntry(itemToken: String, requireRevealed: Boolean): ContextReviewEntry? {
        val current = control.value
        if (current.busy || current.failed || observationFailed.value) return null
        if (vocabularyRepository.currentReviewDay() != current.day) {
            refreshDate()
            return null
        }
        val saved = round.value?.takeIf {
            it.session.sessionToken == current.token && it.session.localDay == current.day
        } ?: return null
        return saved.current()?.takeIf { it.item.itemToken == itemToken && it.item.revealed == requireRevealed }
    }

    private fun updateCurrent(
        itemToken: String,
        requireRevealed: Boolean,
        update: suspend (ContextReviewEntry) -> Boolean
    ) {
        val entry = currentEntry(itemToken, requireRevealed) ?: return
        cancelContextNavigation()
        control.value = control.value.copy(busy = true)
        viewModelScope.launch {
            try {
                if (!update(entry)) events.send(ContextReviewEvent.CARD_CHANGED)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                events.send(ContextReviewEvent.SAVE_FAILED)
            } finally {
                finishAction()
            }
        }
    }

    private fun finishAction() {
        control.value = control.value.copy(busy = false)
        if (refreshAfterAction) {
            refreshAfterAction = false
            loadRound()
        } else {
            refreshDate()
        }
    }
}
