package io.github.zoot.englishreader.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.StringRes
import io.github.zoot.englishreader.model.TtsReadingSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import io.github.zoot.englishreader.data.tts.TtsModelCatalog

enum class TtsVoiceMode { LOCAL, NETWORK, LOCAL_MODEL }

/** Opaque, engine-scoped identity. UI displays locale/mode and an ordinal, never the key. */
data class TtsVoiceOption(
    val id: String,
    val localeTag: String,
    val mode: TtsVoiceMode,
    val quality: Int,
    @StringRes val nameRes: Int? = null
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
    object ModelUnavailable : TtsCapability()
}

enum class TtsFailureReason {
    ENGINE_UNAVAILABLE,
    LANGUAGE_DATA_MISSING,
    LANGUAGE_NOT_SUPPORTED,
    NETWORK_VOICE_DISABLED,
    NETWORK_UNAVAILABLE,
    INITIALIZATION_FAILED,
    SYNTHESIS_FAILED,
    MODEL_UNAVAILABLE
}

fun TtsCapability.failureReason(): TtsFailureReason? = when (this) {
    TtsCapability.Checking, is TtsCapability.Ready -> null
    TtsCapability.EngineUnavailable -> TtsFailureReason.ENGINE_UNAVAILABLE
    TtsCapability.LanguageDataMissing -> TtsFailureReason.LANGUAGE_DATA_MISSING
    TtsCapability.LanguageNotSupported -> TtsFailureReason.LANGUAGE_NOT_SUPPORTED
    TtsCapability.NetworkVoiceDisabled -> TtsFailureReason.NETWORK_VOICE_DISABLED
    TtsCapability.NetworkUnavailable -> TtsFailureReason.NETWORK_UNAVAILABLE
    TtsCapability.InitializationFailed -> TtsFailureReason.INITIALIZATION_FAILED
    TtsCapability.ModelUnavailable -> TtsFailureReason.MODEL_UNAVAILABLE
}

sealed class TtsPlaybackResult {
    data class Started(val utteranceId: String, val mode: TtsVoiceMode) : TtsPlaybackResult()
    data class Finished(val utteranceId: String) : TtsPlaybackResult()
    data class Failed(val reason: TtsFailureReason) : TtsPlaybackResult()
}

/** System engine access and public callbacks run on main; local inference owns its background executor. */
class TtsPlayer internal constructor(
    private val networkChecker: NetworkChecker,
    private val localBackend: LocalTtsBackend? = null,
    private val createEngine: (TextToSpeech.OnInitListener) -> TextToSpeech
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        networkChecker: NetworkChecker,
        localBackend: LocalModelTtsBackend
    ) : this(networkChecker, localBackend, { listener -> TextToSpeech(context, listener) })

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
    private var catalogGeneration = 0L
    private var refreshingLocal = false

    private class PendingSpeak(
        val text: String,
        val allowNetwork: Boolean,
        val settings: TtsReadingSettings,
        val applySpeechRateStrictly: Boolean,
        val fallBackWhenPreferredUnusable: Boolean,
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

    /**
     * 单词发音的 TTS 兜底。
     *
     * [allowNetwork] 必须由调用方从 `SettingsPreferences.allowNetworkTts` 读出来传进来，
     * 不能像原先那样硬编码 `false`：那让单词发音永远只能用本地语音，而 Google 的本地语音是
     * 老式拼接音，网络语音才是神经网络音。用户既然已经为整句朗读授权了联网，单词没有理由
     * 被排除在外——同一个开关，同一个用户意图。
     *
     * 传 `false` 时行为与改动前完全一致（只挑本地语音），所以未授权的用户不受影响。
     *
     * [voiceId] 同理，必须由调用方从 `SettingsPreferences.ttsReadingSettings` 读出来传进来。
     * 原先这里传 `TtsReadingSettings()`，即 `voiceId = null`，于是**用户在设置里挑的语音对单词
     * 发音完全无效**。那在 09-10 引入语音设置时是刻意缩小爆炸半径（见该任务 `prd.md:61`），但
     * 与自动选择的「离线优先」叠加后会变成用户听得见的割裂：好语音基本都是网络语音，于是一个
     * 已授权联网、且明确挑了网络神经语音的用户，整句是神经音、点单词却掉回本地拼接音。
     *
     * 本参数**不是**整个 [TtsReadingSettings]：语速刻意不跟随。那个滑杆是为连续阅读调的，
     * 2.0x 的单句朗读合理，2.0x 的单个词只会听不清；单词恒用 `DEFAULT_RATE`。
     * 没有默认值是刻意的——两个生产调用点都必须显式表态，否则「单词无视你选的语音」这个 bug
     * 会被一个省略的实参悄悄写回来。
     *
     * 选中的语音当前用不了时**退回自动选择而不是报错**，理由见
     * [TtsVoicePolicy.select] 的 `fallBackWhenPreferredUnusable`。
     *
     * 不叫 `speak` 而另起名字，是为了避开与三参 `speak(text, allowNetwork, onResult)` 的重载
     * 歧义：两者的尾随 lambda 一个是 `() -> Unit`、一个是 `(TtsPlaybackResult) -> Unit`，
     * 空 lambda `{}` 对两者都成立，编译器选哪个取决于调用点写法，那种脆弱性不值得省一个名字。
     */
    fun speakWord(
        text: String,
        voiceId: String?,
        allowNetwork: Boolean,
        onUnavailable: () -> Unit = {}
    ) {
        speakInternal(
            text,
            TtsReadingSettings(voiceId = voiceId),
            allowNetwork,
            applySpeechRateStrictly = false,
            fallBackWhenPreferredUnusable = true
        ) { result ->
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
    ) = speakInternal(
        text,
        settings,
        allowNetwork,
        applySpeechRateStrictly = true,
        fallBackWhenPreferredUnusable = false,
        onResult = onResult
    )

    private fun speakInternal(
        text: String,
        settings: TtsReadingSettings,
        allowNetwork: Boolean,
        applySpeechRateStrictly: Boolean,
        fallBackWhenPreferredUnusable: Boolean,
        onResult: (TtsPlaybackResult) -> Unit
    ) = onMain {
        if (text.isBlank()) return@onMain
        stop()
        // 命名实参：下面两个 Boolean 相邻且语义相反，位置传参时一次字段重排就会静默翻转
        // 二者（整句变成可退回、单词变成语速严格），且照样编译。
        val request = PendingSpeak(
            text = text,
            allowNetwork = allowNetwork,
            settings = settings.normalized(),
            applySpeechRateStrictly = applySpeechRateStrictly,
            fallBackWhenPreferredUnusable = fallBackWhenPreferredUnusable,
            callback = onResult
        )
        if (localBackend != null && (request.settings.voiceId == null || TtsModelCatalog.isModelVoice(request.settings.voiceId))) {
            speakLocal(request)
        } else {
            enqueueSystemSpeech(request)
        }
    }

    private fun enqueueSystemSpeech(request: PendingSpeak) {
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

    private fun speakLocal(request: PendingSpeak) {
        val backend = localBackend ?: return enqueueSystemSpeech(request)
        val generation = speechGeneration
        val voiceId = request.settings.voiceId ?: TtsModelCatalog.defaultVoiceId
        capability = TtsCapability.Ready(TtsVoiceMode.LOCAL_MODEL)
        voiceSnapshot = voiceSnapshot.copy(
            voices = backend.voices() + voiceSnapshot.voices.filter { it.mode != TtsVoiceMode.LOCAL_MODEL },
            capability = capability
        )
        backend.speak(request.text, voiceId, request.settings.speechRate) { result ->
            onMain {
                if (generation != speechGeneration) return@onMain
                if (result is TtsPlaybackResult.Failed && request.fallBackWhenPreferredUnusable) {
                    enqueueSystemSpeech(PendingSpeak(
                        request.text, request.allowNetwork, TtsReadingSettings(),
                        request.applySpeechRateStrictly, true, request.callback
                    ))
                } else {
                    if (result is TtsPlaybackResult.Failed) {
                        capability = TtsCapability.ModelUnavailable
                        voiceSnapshot = voiceSnapshot.copy(capability = capability)
                    }
                    request.callback(result)
                }
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
        val generation = ++catalogGeneration
        refreshingLocal = localBackend != null
        ensureInitialized()
        localBackend?.refresh {
            onMain {
                if (generation == catalogGeneration) {
                    refreshingLocal = false
                    if (!initializing) finishPendingRequests()
                }
            }
        }
    }

    fun currentCapability(): TtsCapability = capability

    fun currentVoiceSnapshot(): TtsVoiceSnapshot = voiceSnapshot

    fun stop() = onMain {
        speechGeneration++
        pendingSpeak = null
        activeSpeech = null
        localBackend?.stop()
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
        catalogGeneration++
        refreshingLocal = false
        localBackend?.shutdown()
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
            if (localBackend != null) mainHandler.postDelayed({
                if (generation == engineGeneration && initializing) {
                    initializing = false
                    engineGeneration++
                    capability = TtsCapability.InitializationFailed
                    finishPendingRequests()
                }
            }, 3_000)
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
        if (refreshingLocal) return
        val generation = speechGeneration
        val request = pendingSpeak
        pendingSpeak = null
        val refresh = pendingRefresh
        pendingRefresh = null
        if (refresh != null) {
            // 严格语义：设置页要看到「你选的语音现在到底能不能用」的真实结论，
            // 退回自动选择会让面板显示 Ready 而用户以为自己选的那个生效了。
            inspectVoice(refresh.allowNetwork, refresh.settings.voiceId, fallBackWhenPreferredUnusable = false)
            refresh.callback(voiceSnapshot)
        }
        if (request != null && generation == speechGeneration) speakWithCurrentEngine(request)
    }

    private fun inspectVoice(
        allowNetwork: Boolean,
        preferredId: String?,
        fallBackWhenPreferredUnusable: Boolean,
        includeLocal: Boolean = true
    ): VoiceSelection {
        val engine = tts
        val localVoices = if (includeLocal) localBackend?.voices().orEmpty() else emptyList()
        val selectedLocal = localVoices.firstOrNull { it.id == preferredId }
            ?: localVoices.firstOrNull().takeIf { preferredId == null }
        if (!engineReady || engine == null) {
            capability = when {
                selectedLocal != null -> TtsCapability.Ready(TtsVoiceMode.LOCAL_MODEL)
                includeLocal && TtsModelCatalog.isModelVoice(preferredId) -> TtsCapability.ModelUnavailable
                else -> capability
            }
            voiceSnapshot = TtsVoiceSnapshot(voices = localVoices, capability = capability)
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
            val needsNetwork = selectedLocal == null && allowNetwork && (preferred?.isNetworkConnectionRequired
                ?: voices.none { !it.isNetworkConnectionRequired })
            val decision = TtsVoicePolicy.select(
                voices.map {
                    TtsVoiceCandidate(
                        id(it),
                        it.locale.language,
                        it.locale.country,
                        it.isNetworkConnectionRequired,
                        it.quality
                    )
                },
                allowNetwork,
                networkAvailable = needsNetwork && networkChecker.isOnline(),
                preferredId = preferredId,
                fallBackWhenPreferredUnusable = fallBackWhenPreferredUnusable
            )
            val current = if (selectedLocal != null) TtsCapability.Ready(TtsVoiceMode.LOCAL_MODEL)
            else if (includeLocal && TtsModelCatalog.isModelVoice(preferredId)) TtsCapability.ModelUnavailable
            else when (decision.availability) {
                TtsVoiceAvailability.LOCAL -> TtsCapability.Ready(TtsVoiceMode.LOCAL)
                TtsVoiceAvailability.NETWORK -> TtsCapability.Ready(TtsVoiceMode.NETWORK)
                TtsVoiceAvailability.NETWORK_DISABLED -> TtsCapability.NetworkVoiceDisabled
                TtsVoiceAvailability.NETWORK_UNAVAILABLE -> TtsCapability.NetworkUnavailable
                TtsVoiceAvailability.NONE -> languageFallback
            }
            capability = current
            voiceSnapshot = TtsVoiceSnapshot(
                voices = localVoices + voices.map {
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
            capability = if (selectedLocal != null) TtsCapability.Ready(TtsVoiceMode.LOCAL_MODEL)
                else TtsCapability.InitializationFailed
            voiceSnapshot = TtsVoiceSnapshot(voices = localVoices, capability = capability)
            VoiceSelection(null, capability)
        }
    }

    private fun speakWithCurrentEngine(request: PendingSpeak) {
        val selection = inspectVoice(
            request.allowNetwork,
            request.settings.voiceId,
            request.fallBackWhenPreferredUnusable,
            includeLocal = false
        )
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
