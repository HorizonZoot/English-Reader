package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsVoiceSnapshot

data class ReadingVoiceSettingsState(
    val isOpen: Boolean = false,
    val settings: TtsReadingSettings = TtsReadingSettings(),
    val allowNetwork: Boolean = false,
    val snapshot: TtsVoiceSnapshot = TtsVoiceSnapshot(),
    val previewing: Boolean = false,
    val previewFailure: TtsFailureReason? = null
)
