package io.github.zoot.englishreader.data.audio

import android.util.Log
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import io.github.zoot.englishreader.util.WordLookupKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** One screen owns this playback request; stopping it cannot revoke another screen's media token. */
class WordPronunciation(
    private val scope: CoroutineScope,
    private val audioPlayer: AudioPlayer,
    private val networkChecker: NetworkChecker,
    private val ttsPlayer: TtsPlayer,
    private val cache: PronunciationAudioCache,
    private val settings: SettingsPreferences,
    private val onUnavailable: () -> Unit
) {
    private val _loadingKey = MutableStateFlow<String?>(null)
    val loadingKey = _loadingKey.asStateFlow()
    private var playJob: Job? = null
    private var fallbackJob: Job? = null
    private var generation = 0L
    private var requestToken: AudioPlayer.RequestToken? = null

    fun play(itemKey: String, text: String) {
        val word = text.trim()
        if (word.isEmpty()) return
        stop(preserveTtsPreparation = true)
        val currentGeneration = generation
        val token = audioPlayer.beginRequest()
        requestToken = token
        val key = WordLookupKey.normalize(word)
        playJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val cached = cache.get(key)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(currentGeneration, token)) return@launch
                if (cached == null && !networkChecker.isOnline()) {
                    speakViaTts(word, currentGeneration, token)
                    return@launch
                }
                if (cached == null) _loadingKey.value = itemKey
                val url = cached?.absolutePath ?: WordAudioUrl.forWord(word)
                audioPlayer.play(token, url, onError = {
                    scope.launch playbackFailure@ {
                        if (!isCurrent(currentGeneration, token)) return@playbackFailure
                        fallbackJob?.cancel()
                        fallbackJob = currentCoroutineContext().job
                        _loadingKey.value = null
                        if (cached != null) {
                            try {
                                cache.invalidate(key)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                Log.w(TAG, "Pronunciation cache invalidation failed")
                            }
                        }
                        speakViaTts(word, currentGeneration, token)
                    }
                })
                currentCoroutineContext().ensureActive()
                if (!isCurrent(currentGeneration, token)) return@launch
                _loadingKey.value = null
                if (cached == null && networkChecker.isOnline()) {
                    scope.launch {
                        try {
                            cache.download(key, url)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            Log.w(TAG, "Pronunciation cache fill failed")
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                speakViaTts(word, currentGeneration, token)
            } finally {
                if (currentGeneration == generation) _loadingKey.value = null
            }
        }
        playJob?.start()
    }

    private fun isCurrent(expected: Long, token: AudioPlayer.RequestToken): Boolean =
        generation == expected && requestToken === token && audioPlayer.isCurrent(token)

    private suspend fun speakViaTts(word: String, expected: Long, token: AudioPlayer.RequestToken) {
        if (!isCurrent(expected, token)) return
        try {
            val allowNetwork = settings.allowNetworkTts.first()
            val voiceId = settings.ttsReadingSettings.first().voiceId
            currentCoroutineContext().ensureActive()
            if (!isCurrent(expected, token)) return
            ttsPlayer.speakWord(word, voiceId, allowNetwork) {
                if (isCurrent(expected, token)) onUnavailable()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (isCurrent(expected, token)) onUnavailable()
        }
    }

    fun stop(preserveTtsPreparation: Boolean = false) {
        generation++
        playJob?.cancel()
        playJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        val token = requestToken
        requestToken = null
        token?.let(audioPlayer::stop)
        _loadingKey.value = null
        if (preserveTtsPreparation) ttsPlayer.stopBeforeReading() else ttsPlayer.stop()
    }

    private companion object {
        const val TAG = "WordPronunciation"
    }
}
