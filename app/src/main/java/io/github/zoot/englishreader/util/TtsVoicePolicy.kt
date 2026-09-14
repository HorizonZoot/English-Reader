package io.github.zoot.englishreader.util

/** Minimal platform-independent description used to select an Android TTS voice. */
data class TtsVoiceCandidate(
    val id: String,
    val language: String,
    val country: String,
    val networkRequired: Boolean
)

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

    private val voiceComparator = compareBy<TtsVoiceCandidate>(
        { if (it.language.equals("en", ignoreCase = true) && it.country.equals("US", ignoreCase = true)) 0 else 1 },
        { it.country },
        { it.id }
    )
}
