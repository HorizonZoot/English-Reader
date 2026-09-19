package io.github.zoot.englishreader.ui.component

import androidx.annotation.StringRes
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsVoiceMode

@StringRes
fun TtsFailureReason.messageRes(): Int = when (this) {
    TtsFailureReason.ENGINE_UNAVAILABLE -> R.string.tts_engine_unavailable
    TtsFailureReason.LANGUAGE_DATA_MISSING -> R.string.tts_language_data_missing
    TtsFailureReason.LANGUAGE_NOT_SUPPORTED -> R.string.tts_language_not_supported
    TtsFailureReason.NETWORK_VOICE_DISABLED -> R.string.tts_network_voice_disabled
    TtsFailureReason.NETWORK_UNAVAILABLE -> R.string.tts_network_unavailable
    TtsFailureReason.INITIALIZATION_FAILED -> R.string.tts_initialization_failed
    TtsFailureReason.SYNTHESIS_FAILED -> R.string.tts_synthesis_failed
    TtsFailureReason.MODEL_UNAVAILABLE -> R.string.tts_model_unavailable
}

@StringRes
fun TtsCapability.messageRes(): Int = when (this) {
    TtsCapability.Checking -> R.string.tts_checking
    is TtsCapability.Ready -> when (mode) {
        TtsVoiceMode.LOCAL -> R.string.tts_local_ready
        TtsVoiceMode.NETWORK -> R.string.tts_network_ready
        TtsVoiceMode.LOCAL_MODEL -> R.string.tts_model_ready
    }
    TtsCapability.EngineUnavailable -> R.string.tts_engine_unavailable
    TtsCapability.LanguageDataMissing -> R.string.tts_language_data_missing
    TtsCapability.LanguageNotSupported -> R.string.tts_language_not_supported
    TtsCapability.NetworkVoiceDisabled -> R.string.tts_network_voice_disabled
    TtsCapability.NetworkUnavailable -> R.string.tts_network_unavailable
    TtsCapability.InitializationFailed -> R.string.tts_initialization_failed
    TtsCapability.ModelUnavailable -> R.string.tts_model_unavailable
}
