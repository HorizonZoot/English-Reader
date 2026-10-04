package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.core.VocabularyStudyMode
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.WordPronunciation
import io.github.zoot.englishreader.data.entity.ContextReviewEntry
import io.github.zoot.englishreader.data.entity.ContextReviewRound
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContextReviewUiState(
    val round: ContextReviewRound? = null,
    val busy: Boolean = true,
    val failed: Boolean = false
)

data class StudyPronunciationDetails(
    val itemToken: String? = null,
    val phonetic: String? = null,
    val headword: String? = null,
    val failed: Boolean = false
)

enum class ContextReviewEvent { SAVE_FAILED, CARD_CHANGED, SOURCE_UNAVAILABLE, AUDIO_UNAVAILABLE }

data class ContextReviewSource(val requestId: Long, val entry: ContextReviewEntry)

@HiltViewModel
class ContextReviewViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository,
    private val dictionaryRepository: DictionaryRepository,
    audioPlayer: AudioPlayer,
    networkChecker: NetworkChecker,
    private val ttsPlayer: TtsPlayer,
    pronunciationAudioCache: PronunciationAudioCache,
    settingsPreferences: SettingsPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private data class Control(
        val day: Long,
        val token: String? = null,
        val busy: Boolean = true,
        val failed: Boolean = false
    )

    private val requestedMode = VocabularyStudyMode.entries.firstOrNull {
        it.name == savedStateHandle.get<String>("mode")
    } ?: VocabularyStudyMode.DUE
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
    private var pronunciationLookup: Job? = null
    private var pronunciationGeneration = 0L
    private val _pronunciationDetails = MutableStateFlow(StudyPronunciationDetails())
    val pronunciationDetails = _pronunciationDetails.asStateFlow()
    private val pronunciation = WordPronunciation(
        viewModelScope, audioPlayer, networkChecker, ttsPlayer, pronunciationAudioCache,
        settingsPreferences, onUnavailable = { events.trySend(ContextReviewEvent.AUDIO_UNAVAILABLE) }
    )
    val loadingAudioKey = pronunciation.loadingKey

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
        viewModelScope.launch {
            dictionaryRepository.revision.drop(1).collect { loadPronunciationDetails(round.value?.current()) }
        }
    }

    private fun observeRound() {
        observation?.cancel()
        observationFailed.value = false
        observation = viewModelScope.launch {
            try {
                vocabularyRepository.observeContextReview().collect { saved ->
                    val previous = round.value?.current()
                    val next = saved?.current()
                    if (previous != next) cancelContextNavigation()
                    if (previous?.item?.itemToken != next?.item?.itemToken || previous?.studyWord() != next?.studyWord()) {
                        stopAudio()
                        loadPronunciationDetails(next)
                    }
                    round.value = saved
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                cancelContextNavigation()
                stopAudio()
                observationFailed.value = true
            }
        }
    }

    private fun loadPronunciationDetails(entry: ContextReviewEntry?) {
        pronunciationLookup?.cancel()
        val generation = ++pronunciationGeneration
        val word = entry?.studyWord()
        val savedPhonetic = (word as? StudyWord.Legacy)?.vocabulary?.phonetic?.takeIf(String::isNotBlank)
        _pronunciationDetails.value = StudyPronunciationDetails(entry?.item?.itemToken, savedPhonetic)
        if (word == null) return
        pronunciationLookup = viewModelScope.launch {
            try {
                val hit = dictionaryRepository.lookupOffline(word.word)
                val phonetic = hit?.entry?.phonetic?.takeIf(String::isNotBlank)
                currentCoroutineContext().ensureActive()
                if (generation == pronunciationGeneration) {
                    _pronunciationDetails.value = StudyPronunciationDetails(
                        itemToken = entry.item.itemToken,
                        phonetic = phonetic ?: savedPhonetic,
                        headword = hit?.entry?.word?.takeIf { phonetic != null && hit.inflectedForm != null }
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == pronunciationGeneration) {
                    _pronunciationDetails.value = StudyPronunciationDetails(entry.item.itemToken, savedPhonetic, failed = true)
                }
            }
        }
    }

    fun playWordAudio(itemToken: String) {
        val word = currentEntry(itemToken)?.studyWord() ?: return
        pronunciation.play(word.key, word.word)
    }

    fun stopAudio() = pronunciation.stop()

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
        stopAudio()
        val busy = control.value.busy
        control.value = control.value.copy(day = day, token = null)
        if (busy) refreshAfterAction = true else loadRound()
    }

    private fun loadRound(startNext: Boolean = false) {
        cancelContextNavigation()
        stopAudio()
        control.value = control.value.copy(busy = true, failed = false)
        viewModelScope.launch {
            try {
                val saved = if (startNext) {
                    vocabularyRepository.startNextContextReview(requestedMode)
                } else {
                    vocabularyRepository.startOrResumeContextReview(requestedMode)
                }
                currentCoroutineContext().ensureActive()
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

    fun grade(itemToken: String, grade: ContextGrade) {
        val entry = currentEntry(itemToken, requireRevealed = true) ?: return
        if (entry.item.meaningSnapshot.isBlank()) return
        updateCurrent(itemToken, requireRevealed = true) {
            vocabularyRepository.gradeContextAnswer(it.item.itemToken, grade)
        }
    }

    fun skipMissingAnswer(itemToken: String) {
        val entry = currentEntry(itemToken, requireRevealed = true) ?: return
        if (entry.item.meaningSnapshot.isNotBlank()) return
        updateCurrent(itemToken, requireRevealed = true) {
            vocabularyRepository.skipContextAnswer(it.item.itemToken)
        }
    }

    fun cancelContextNavigation() {
        sourceRequestId++
        sourceRequest?.cancel()
        sourceRequest = null
    }

    fun openContextSource(itemToken: String) {
        val entry = currentEntry(itemToken, requireRevealed = true) ?: return
        val word = entry.studyWord() ?: return
        stopAudio()
        cancelContextNavigation()
        val source = ContextReviewSource(sourceRequestId, entry)
        sourceRequest = viewModelScope.launch {
            val available = try {
                vocabularyRepository.hasStudySource(word)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentSource(source)) return@launch
            if (available && word.articleId != null) sourceToOpen.emit(source)
            else events.send(ContextReviewEvent.SOURCE_UNAVAILABLE)
        }
    }

    fun consumeContextSource(source: ContextReviewSource): StudyWord? {
        if (!isCurrentSource(source)) return null
        cancelContextNavigation()
        stopAudio()
        return source.entry.studyWord()
    }

    private fun isCurrentSource(source: ContextReviewSource): Boolean =
        source.requestId == sourceRequestId &&
            currentEntry(source.entry.item.itemToken, requireRevealed = true) == source.entry

    private fun currentEntry(itemToken: String, requireRevealed: Boolean? = null): ContextReviewEntry? {
        val current = control.value
        if (current.busy || current.failed || observationFailed.value) return null
        if (vocabularyRepository.currentReviewDay() != current.day) {
            refreshDate()
            return null
        }
        val saved = round.value?.takeIf {
            it.session.sessionToken == current.token && it.session.localDay == current.day
        } ?: return null
        return saved.current()?.takeIf {
            it.item.itemToken == itemToken && (requireRevealed == null || it.item.revealed == requireRevealed)
        }
    }

    private fun updateCurrent(
        itemToken: String,
        requireRevealed: Boolean,
        update: suspend (ContextReviewEntry) -> Boolean
    ) {
        val entry = currentEntry(itemToken, requireRevealed) ?: return
        cancelContextNavigation()
        stopAudio()
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

    override fun onCleared() {
        stopAudio()
        ttsPlayer.shutdown()
        super.onCleared()
    }
}
