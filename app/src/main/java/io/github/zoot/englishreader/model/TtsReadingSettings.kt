package io.github.zoot.englishreader.model

import kotlin.math.roundToInt

/** Voice selection also applies to word TTS fallback; speech rate applies only to reading. */
data class TtsReadingSettings(
    val voiceId: String? = null,
    val speechRate: Float = DEFAULT_RATE
) {
    fun normalized() = copy(
        voiceId = voiceId?.takeIf { it.isNotBlank() },
        speechRate = normalizeRate(speechRate)
    )

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val DEFAULT_RATE = 1.0f

        fun normalizeRate(rate: Float): Float = if (rate.isFinite()) {
            (rate.coerceIn(MIN_RATE, MAX_RATE) * 10).roundToInt() / 10f
        } else DEFAULT_RATE
    }
}
