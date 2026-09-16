package io.github.zoot.englishreader.util

/**
 * Minimal platform-independent description used to select an Android TTS voice.
 *
 * [quality] 是 `android.speech.tts.Voice.getQuality()`：`QUALITY_VERY_LOW`(100) 到
 * `QUALITY_VERY_HIGH`(500)。默认值取 `QUALITY_NORMAL`(300)，这样既有调用方与测试不必
 * 逐个补参数，而缺失质量信息的候选者会落在中档而不是被当成最差。
 */
data class TtsVoiceCandidate(
    val id: String,
    val language: String,
    val country: String,
    val networkRequired: Boolean,
    val quality: Int = QUALITY_NORMAL
) {
    companion object {
        /** 与 `android.speech.tts.Voice.QUALITY_NORMAL` 同值，避免为一个常量引入 Android 依赖。 */
        const val QUALITY_NORMAL: Int = 300
    }
}

enum class TtsVoiceAvailability {
    LOCAL,
    NETWORK,
    NETWORK_DISABLED,
    NETWORK_UNAVAILABLE,
    NONE
}

data class TtsVoiceDecision(
    val availability: TtsVoiceAvailability,
    val selectedId: String? = null
)

/** Keeps voice selection deterministic and testable without constructing Android TextToSpeech. */
object TtsVoicePolicy {
    fun select(
        candidates: List<TtsVoiceCandidate>,
        allowNetwork: Boolean,
        networkAvailable: Boolean,
        preferredId: String? = null
    ): TtsVoiceDecision {
        val english = candidates.filter { it.language.equals("en", ignoreCase = true) }
        english.firstOrNull { it.id == preferredId }?.let { preferred ->
            return when {
                !preferred.networkRequired -> TtsVoiceDecision(TtsVoiceAvailability.LOCAL, preferred.id)
                !allowNetwork -> TtsVoiceDecision(TtsVoiceAvailability.NETWORK_DISABLED)
                !networkAvailable -> TtsVoiceDecision(TtsVoiceAvailability.NETWORK_UNAVAILABLE)
                else -> TtsVoiceDecision(TtsVoiceAvailability.NETWORK, preferred.id)
            }
        }
        val local = english.filterNot { it.networkRequired }.sortedWith(voiceComparator)
        if (local.isNotEmpty()) {
            return TtsVoiceDecision(TtsVoiceAvailability.LOCAL, local.first().id)
        }

        val network = english.filter { it.networkRequired }.sortedWith(voiceComparator)
        if (network.isEmpty()) return TtsVoiceDecision(TtsVoiceAvailability.NONE)
        if (!allowNetwork) return TtsVoiceDecision(TtsVoiceAvailability.NETWORK_DISABLED)
        if (!networkAvailable) return TtsVoiceDecision(TtsVoiceAvailability.NETWORK_UNAVAILABLE)
        return TtsVoiceDecision(TtsVoiceAvailability.NETWORK, network.first().id)
    }

    /**
     * 质量降序优先，其余维持原有的确定性顺序。
     *
     * 改动前本比较器完全不看 [TtsVoiceCandidate.quality]：一台机器上装了多个本地英语语音时，
     * 实际是按 country + id 的字典序在挑，等于随机拿到一个，可能正好是最差那个。而
     * `Voice.getQuality()` 这个信号早就取到手了（`TtsPlayer` 已经把它放进 `TtsVoiceOption`
     * 交给设置页的语音选择器），只是从未进入自动决策。
     *
     * 「本地优先」不在这里表达：[select] 先整体尝试本地桶、再尝试网络桶，所以本比较器只在同一桶
     * 内排序，离线承诺不受影响。en-US 偏好保留，但降为同质量下的 tiebreak——用户想固定某个语音
     * 时用设置页的 `preferredId`，那条路径优先级最高且不经过本比较器。
     */
    private val voiceComparator = compareBy<TtsVoiceCandidate>(
        { -it.quality },
        { if (it.language.equals("en", ignoreCase = true) && it.country.equals("US", ignoreCase = true)) 0 else 1 },
        { it.country },
        { it.id }
    )
}
