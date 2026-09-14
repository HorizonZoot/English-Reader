package io.github.zoot.englishreader.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import io.github.zoot.englishreader.model.TtsReadingSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

enum class TtsVoiceMode { LOCAL, NETWORK }

/** Opaque, engine-scoped identity. UI displays locale/mode and an ordinal, never the key. */
data class TtsVoiceOption(
    val id: String,
    val localeTag: String,
    val mode: TtsVoiceMode,
    val quality: Int
)

data class TtsVoiceSnapshot(
    val voices: List<TtsVoiceOption> = emptyList(),
    val capability: TtsCapability = TtsCapability.Checking,
    val catalogLoaded: Boolean = false
) {
    /** Keeps the last known list visible while a re-inspection runs in the background. */
    fun reinspecting() = copy(capability = TtsCapability.Checking)
}

sealed class TtsCapability {
    object Checking : TtsCapability()
    data class Ready(val mode: TtsVoiceMode) : TtsCapability()
    object NetworkVoiceDisabled : TtsCapability()
    object NetworkUnavailable : TtsCapability()
    object EngineUnavailable : TtsCapability()
    object LanguageDataMissing : TtsCapability()
    object LanguageNotSupported : TtsCapability()
    object InitializationFailed : TtsCapability()
}

enum class TtsFailureReason {
    ENGINE_UNAVAILABLE,
    LANGUAGE_DATA_MISSING,
    LANGUAGE_NOT_SUPPORTED,
    NETWORK_VOICE_DISABLED,
    NETWORK_UNAVAILABLE,
    INITIALIZATION_FAILED,
    SYNTHESIS_FAILED
}

fun TtsCapability.failureReason(): TtsFailureReason? = when (this) {
    TtsCapability.Checking, is TtsCapability.Ready -> null
    TtsCapability.EngineUnavailable -> TtsFailureReason.ENGINE_UNAVAILABLE
    TtsCapability.LanguageDataMissing -> TtsFailureReason.LANGUAGE_DATA_MISSING
    TtsCapability.LanguageNotSupported -> TtsFailureReason.LANGUAGE_NOT_SUPPORTED
    TtsCapability.NetworkVoiceDisabled -> TtsFailureReason.NETWORK_VOICE_DISABLED
    TtsCapability.NetworkUnavailable -> TtsFailureReason.NETWORK_UNAVAILABLE
    TtsCapability.InitializationFailed -> TtsFailureReason.INITIALIZATION_FAILED
}

sealed class TtsPlaybackResult {
    data class Started(val utteranceId: String, val mode: TtsVoiceMode) : TtsPlaybackResult()
    data class Finished(val utteranceId: String) : TtsPlaybackResult()
    data class Failed(val reason: TtsFailureReason) : TtsPlaybackResult()
}

/** All engine access and callbacks are serialized on the main thread. */
class TtsPlayer internal constructor(
    private val networkChecker: NetworkChecker,
    private val createEngine: (TextToSpeech.OnInitListener) -> TextToSpeech
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        networkChecker: NetworkChecker
    ) : this(networkChecker, { listener -> TextToSpeech(context, listener) })

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initializing = false
    private var engineReady = false
    private var engineGeneration = 0L
    private var speechGeneration = 0L
    private var languageFallback: TtsCapability = TtsCapability.LanguageDataMissing
    @Volatile private var capability: TtsCapability = TtsCapability.Checking
    private var pendingSpeak: PendingSpeak? = null
    private var pendingRefresh: PendingRefresh? = null
    private var activeSpeech: ActiveSpeech? = null
    private var nextUtteranceId = 0L
    private var voiceSnapshot = TtsVoiceSnapshot()

    private class PendingSpeak(
        val text: String,
        val allowNetwork: Boolean,
        val settings: TtsReadingSettings,
        val applySpeechRateStrictly: Boolean,
        val callback: (TtsPlaybackResult) -> Unit
    )

    private class PendingRefresh(
        val allowNetwork: Boolean,
        val settings: TtsReadingSettings,
        val callback: (TtsVoiceSnapshot) -> Unit
    )

    private class ActiveSpeech(
        val id: String,
        val callback: (TtsPlaybackResult) -> Unit
    )

    private class VoiceSelection(val voice: Voice?, val capability: TtsCapability)

    /** Word pronunciation retains its local-only fallback. */
    fun speak(text: String, onUnavailable: () -> Unit = {}) {
        speakInternal(text, TtsReadingSettings(), allowNetwork = false, applySpeechRateStrictly = false) { result ->
            if (result is TtsPlaybackResult.Failed) onUnavailable()
        }
    }

    fun speak(
        text: String,
        allowNetwork: Boolean,
        onResult: (TtsPlaybackResult) -> Unit
    ) = speakReading(text, TtsReadingSettings(), allowNetwork, onResult)

    fun speakReading(
        text: String,
        settings: TtsReadingSettings,
        allowNetwork: Boolean,
        onResult: (TtsPlaybackResult) -> Unit
    ) = speakInternal(text, settings, allowNetwork, applySpeechRateStrictly = true, onResult)

    private fun speakInternal(
        text: String,
        settings: TtsReadingSettings,
        allowNetwork: Boolean,
        applySpeechRateStrictly: Boolean,
        onResult: (TtsPlaybackResult) -> Unit
    ) = onMain {
        if (text.isBlank()) return@onMain
        stop()
        val request = PendingSpeak(
            text,
            allowNetwork,
            settings.normalized(),
            applySpeechRateStrictly,
            onResult
        )
        if (engineReady) {
            speakWithCurrentEngine(request)
        } else {
            pendingSpeak = request
            if (!initializing) {
                shutdownEngine()
                ensureInitialized()
            }
        }
    }

    /** Recreates the engine so installed voices and Android settings are inspected again. */
    fun refresh(allowNetwork: Boolean, onResult: (TtsCapability) -> Unit) =
        refreshVoices(TtsReadingSettings(), allowNetwork) { onResult(it.capability) }

    fun refreshVoices(
        settings: TtsReadingSettings,
        allowNetwork: Boolean,
        onResult: (TtsVoiceSnapshot) -> Unit
    ) = onMain {
        stop()
        pendingRefresh = PendingRefresh(allowNetwork, settings.normalized(), onResult)
        shutdownEngine()
        ensureInitialized()
    }

    fun currentCapability(): TtsCapability = capability

    fun currentVoiceSnapshot(): TtsVoiceSnapshot = voiceSnapshot

    fun stop() = onMain {
        speechGeneration++
        pendingSpeak = null
        activeSpeech = null
        try {
            tts?.stop()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Log.e(TAG, "Failed to stop TTS")
        }
    }

    fun shutdown() = onMain {
        stop()
        pendingRefresh = null
        shutdownEngine()
    }

    private fun ensureInitialized() {
        initializing = true
        val generation = ++engineGeneration
        try {
            tts = createEngine { status ->
                // Some engines call onInit inside their constructor; defer until tts is assigned.
                mainHandler.post {
                    if (generation == engineGeneration && initializing) {
                        tts?.let { handleInitialization(it, status) }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            initializing = false
            engineGeneration++
            throw cancellation
        } catch (_: Exception) {
            initializing = false
            engineGeneration++
            capability = TtsCapability.InitializationFailed
            finishPendingRequests()
        }
    }

    private fun handleInitialization(engine: TextToSpeech, status: Int) {
        initializing = false
        if (status != TextToSpeech.SUCCESS) {
            capability = try {
                if (engine.engines.isNullOrEmpty()) TtsCapability.EngineUnavailable
                else TtsCapability.InitializationFailed
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                TtsCapability.InitializationFailed
            }
            finishPendingRequests()
            return
        }
        try {
            if (engine.setOnUtteranceProgressListener(progressListener) != TextToSpeech.SUCCESS) {
                capability = TtsCapability.InitializationFailed
            } else {
                languageFallback = when (engine.setLanguage(Locale.US)) {
                    TextToSpeech.LANG_NOT_SUPPORTED -> TtsCapability.LanguageNotSupported
                    else -> TtsCapability.LanguageDataMissing
                }
                engineReady = true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            capability = TtsCapability.InitializationFailed
        }
        // setLanguage may fail even when an English network Voice is available.
        finishPendingRequests()
    }

    private fun finishPendingRequests() {
        val generation = speechGeneration
        val request = pendingSpeak
        pendingSpeak = null
        val refresh = pendingRefresh
        pendingRefresh = null
        if (refresh != null) {
            inspectVoice(refresh.allowNetwork, refresh.settings.voiceId)
            refresh.callback(voiceSnapshot)
        }
        if (request != null && generation == speechGeneration) speakWithCurrentEngine(request)
    }

    private fun inspectVoice(allowNetwork: Boolean, preferredId: String?): VoiceSelection {
        val engine = tts
        if (!engineReady || engine == null) {
            voiceSnapshot = TtsVoiceSnapshot(capability = capability)
            return VoiceSelection(null, capability)
        }
        return try {
            val voices = engine.voices.orEmpty()
                .filter { it.locale.language.equals("en", ignoreCase = true) }
                .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
                .sortedWith(compareBy<Voice>({ it.locale.toLanguageTag() }, { it.isNetworkConnectionRequired }, { it.name }))
            // A same-named voice in a different engine must not silently inherit the preference.
            val engineId = engine.defaultEngine.orEmpty()
            fun id(voice: Voice) = "$engineId/${voice.name}"
            val preferred = voices.firstOrNull { id(it) == preferredId }
            val needsNetwork = allowNetwork && (preferred?.isNetworkConnectionRequired
                ?: voices.none { !it.isNetworkConnectionRequired })
            val decision = TtsVoicePolicy.select(
                voices.map {
                    TtsVoiceCandidate(id(it), it.locale.language, it.locale.country, it.isNetworkConnectionRequired)
                },
                allowNetwork,
                networkAvailable = needsNetwork && networkChecker.isOnline(),
                preferredId = preferredId
            )
            val current = when (decision.availability) {
                TtsVoiceAvailability.LOCAL -> TtsCapability.Ready(TtsVoiceMode.LOCAL)
                TtsVoiceAvailability.NETWORK -> TtsCapability.Ready(TtsVoiceMode.NETWORK)
                TtsVoiceAvailability.NETWORK_DISABLED -> TtsCapability.NetworkVoiceDisabled
                TtsVoiceAvailability.NETWORK_UNAVAILABLE -> TtsCapability.NetworkUnavailable
                TtsVoiceAvailability.NONE -> languageFallback
            }
            capability = current
            voiceSnapshot = TtsVoiceSnapshot(
                voices = voices.map {
                    TtsVoiceOption(id(it), it.locale.toLanguageTag(),
                        if (it.isNetworkConnectionRequired) TtsVoiceMode.NETWORK else TtsVoiceMode.LOCAL,
                        it.quality)
                },
                capability = current,
                catalogLoaded = true
            )
            VoiceSelection(voices.firstOrNull { id(it) == decision.selectedId }, current)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            capability = TtsCapability.InitializationFailed
            voiceSnapshot = TtsVoiceSnapshot(capability = capability)
            VoiceSelection(null, capability)
        }
    }

    private fun speakWithCurrentEngine(request: PendingSpeak) {
        val selection = inspectVoice(request.allowNetwork, request.settings.voiceId)
        val voice = selection.voice
        val ready = selection.capability as? TtsCapability.Ready
        val engine = tts
        if (voice == null || ready == null || engine == null) {
            request.callback(TtsPlaybackResult.Failed(
                selection.capability.failureReason() ?: TtsFailureReason.INITIALIZATION_FAILED
            ))
            return
        }
        val utteranceId = "english_reader_tts_" + ++nextUtteranceId
        val accepted = try {
            val voiceAccepted = engine.setVoice(voice) == TextToSpeech.SUCCESS
            val rateAccepted = engine.setSpeechRate(request.settings.speechRate) == TextToSpeech.SUCCESS
            if (!voiceAccepted || (request.applySpeechRateStrictly && !rateAccepted)) {
                false
            } else {
                activeSpeech = ActiveSpeech(utteranceId, request.callback)
                engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
            }
        } catch (cancellation: CancellationException) {
            activeSpeech = null
            throw cancellation
        } catch (_: Exception) {
            false
        }
        if (accepted) {
            request.callback(TtsPlaybackResult.Started(utteranceId, ready.mode))
        } else {
            activeSpeech = null
            request.callback(TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED))
        }
    }

    private fun shutdownEngine() {
        val engine = tts
        tts = null
        engineGeneration++
        initializing = false
        engineReady = false
        capability = TtsCapability.Checking
        voiceSnapshot = TtsVoiceSnapshot()
        try {
            engine?.shutdown()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Log.e(TAG, "Failed to shut down TTS")
        }
    }

    private fun complete(utteranceId: String, result: TtsPlaybackResult) {
        mainHandler.post {
            val request = activeSpeech?.takeIf { it.id == utteranceId } ?: return@post
            activeSpeech = null
            request.callback(result)
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            complete(utteranceId, TtsPlaybackResult.Finished(utteranceId))
        }

        @Deprecated("Required by UtteranceProgressListener")
        override fun onError(utteranceId: String) {
            complete(utteranceId, TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED))
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            val reason = when (errorCode) {
                TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> TtsFailureReason.NETWORK_UNAVAILABLE
                TextToSpeech.ERROR_NOT_INSTALLED_YET -> TtsFailureReason.LANGUAGE_DATA_MISSING
                else -> TtsFailureReason.SYNTHESIS_FAILED
            }
            complete(utteranceId, TtsPlaybackResult.Failed(reason))
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            complete(utteranceId, TtsPlaybackResult.Failed(TtsFailureReason.SYNTHESIS_FAILED))
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private companion object {
        const val TAG = "TtsPlayer"
    }
}
