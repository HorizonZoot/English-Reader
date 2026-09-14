package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoicePolicyTest {

    @Test
    fun select_localVoice_prefersOfflineUsVoice() {
        val candidates = listOf(
            TtsVoiceCandidate("en-gb-network", "en", "GB", true),
            TtsVoiceCandidate("en-gb-local", "en", "GB", false),
            TtsVoiceCandidate("en-us-local", "en", "US", false)
        )
        for (networkEnabled in listOf(false, true)) {
            val result = TtsVoicePolicy.select(candidates, networkEnabled, networkEnabled)
            val scenario = "networkEnabled=$networkEnabled"
            assertEquals(scenario, TtsVoiceAvailability.LOCAL, result.availability)
            assertEquals(scenario, "en-us-local", result.selectedId)
        }
    }

    @Test
    fun select_networkVoice_requiresExplicitOptInAndConnectivity() {
        val voice = TtsVoiceCandidate("en-us-network", "en", "US", true)

        val disabled = TtsVoicePolicy.select(listOf(voice), false, true)
        assertEquals(TtsVoiceAvailability.NETWORK_DISABLED, disabled.availability)
        assertNull(disabled.selectedId)

        val offline = TtsVoicePolicy.select(listOf(voice), true, false)
        assertEquals(TtsVoiceAvailability.NETWORK_UNAVAILABLE, offline.availability)
        assertNull(offline.selectedId)

        val enabled = TtsVoicePolicy.select(listOf(voice), true, true)
        assertEquals(TtsVoiceAvailability.NETWORK, enabled.availability)
        assertEquals("en-us-network", enabled.selectedId)
    }

    @Test
    fun select_explicitVoice_overridesAutomaticButNeverConsent() {
        val voices = listOf(
            TtsVoiceCandidate("us", "en", "US", false),
            TtsVoiceCandidate("gb", "en", "GB", false),
            TtsVoiceCandidate("network", "en", "US", true)
        )
        assertEquals("gb", TtsVoicePolicy.select(voices, false, false, "gb").selectedId)
        assertEquals("network", TtsVoicePolicy.select(voices, true, true, "network").selectedId)
        assertEquals(TtsVoiceAvailability.NETWORK_DISABLED, TtsVoicePolicy.select(voices, false, true, "network").availability)
        assertEquals(TtsVoiceAvailability.NETWORK_UNAVAILABLE, TtsVoicePolicy.select(voices, true, false, "network").availability)
        assertEquals("us", TtsVoicePolicy.select(voices, true, true, "removed").selectedId)
    }

    @Test
    fun select_nonEnglishVoices_returnsNone() {
        val result = TtsVoicePolicy.select(
            candidates = listOf(TtsVoiceCandidate("zh-cn-local", "zh", "CN", false)),
            allowNetwork = true,
            networkAvailable = true
        )

        assertEquals(TtsVoiceAvailability.NONE, result.availability)
        assertNull(result.selectedId)
    }
}
