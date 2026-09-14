package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.util.TtsFailureReason

enum class ReadingTtsPhase { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, FAILED }

data class ReadingTtsState(
    val phase: ReadingTtsPhase = ReadingTtsPhase.IDLE,
    val requestId: Long = 0,
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val continuous: Boolean = false,
    val failure: TtsFailureReason? = null
)

data class ReadingTtsFailure(
    val requestId: Long,
    val reason: TtsFailureReason
)
