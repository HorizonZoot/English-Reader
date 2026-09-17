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
    /**
     * 选出该用哪个英语语音，或说明为什么选不出来。
     *
     * @param fallBackWhenPreferredUnusable [preferredId] 当前用不了时，是否退回自动选择。
     *
     * 默认 `false`（整句朗读的语义）：用户明确挑的语音用不了时必须**报出来**，因为阅读页有恢复
     * 对话框，可以让用户「仅本句联网」或去系统设置装语言包——静默换个语音只会让他以为设置没生效。
     *
     * 单词发音传 `true`。它本身就是兜底路径（真人音频优先，TTS 只在离线/无缓存/播放失败时才出场），
     * 且失败时只有一条 snackbar、没有恢复入口。用户可以在没开联网授权的情况下选中一个网络语音
     * （`ReadingViewModel.setReadingVoice` 只校验语音存在，不校验授权），那时严格语义会让单词发音
     * 从「用本地语音响一下」退化成「弹一条错误」——对兜底路径来说这是纯损失。
     *
     * 退回**不会**放宽同意：走的是下面同一套桶逻辑，网络桶照样要过 [allowNetwork] 与
     * [networkAvailable]。preferred 是网络语音、授权关闭、且机器上没有可用本地语音时，
     * 结果仍是 `NETWORK_DISABLED`，不会因为「退回」就把文本发往网络。
     */
    fun select(
        candidates: List<TtsVoiceCandidate>,
        allowNetwork: Boolean,
        networkAvailable: Boolean,
        preferredId: String? = null,
        fallBackWhenPreferredUnusable: Boolean = false
    ): TtsVoiceDecision {
        val english = candidates.filter { it.language.equals("en", ignoreCase = true) }
        english.firstOrNull { it.id == preferredId }?.let { preferred ->
            val decision = when {
                !preferred.networkRequired -> TtsVoiceDecision(TtsVoiceAvailability.LOCAL, preferred.id)
                !allowNetwork -> TtsVoiceDecision(TtsVoiceAvailability.NETWORK_DISABLED)
                !networkAvailable -> TtsVoiceDecision(TtsVoiceAvailability.NETWORK_UNAVAILABLE)
                else -> TtsVoiceDecision(TtsVoiceAvailability.NETWORK, preferred.id)
            }
            // 判据用 `selectedId != null` 而不是枚举「哪些 availability 算失败」：新增失败态时
            // 这里不必跟着改，而「选出了语音」与「没选出语音」本来就是这个函数的二分。
            if (decision.selectedId != null || !fallBackWhenPreferredUnusable) return decision
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
