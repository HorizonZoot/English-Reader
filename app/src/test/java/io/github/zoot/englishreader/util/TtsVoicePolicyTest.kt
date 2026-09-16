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

    /**
     * 质量高的语音胜过 en-US 偏好。
     *
     * 改动前比较器完全不看 quality，同一台机器上装了多个本地英语语音时按 country + id 字典序
     * 挑，等于随机拿到一个。这里 en-GB 是 VERY_HIGH、en-US 是 VERY_LOW，若仍按旧顺序会选中
     * en-US 那个最差的。
     */
    @Test
    fun select_multipleLocalVoices_prefersHigherQualityOverUsCountry() {
        val candidates = listOf(
            TtsVoiceCandidate("en-us-low", "en", "US", false, quality = 100),
            TtsVoiceCandidate("en-gb-high", "en", "GB", false, quality = 500)
        )

        val result = TtsVoicePolicy.select(candidates, allowNetwork = false, networkAvailable = false)

        assertEquals(TtsVoiceAvailability.LOCAL, result.availability)
        assertEquals("en-gb-high", result.selectedId)
    }

    /** 同质量时 en-US 偏好仍然生效——quality 只是加在最前面的一维，没有替换原有顺序。 */
    @Test
    fun select_sameQuality_stillFallsBackToUsCountryTiebreak() {
        val candidates = listOf(
            TtsVoiceCandidate("en-gb", "en", "GB", false, quality = 400),
            TtsVoiceCandidate("en-us", "en", "US", false, quality = 400)
        )

        val result = TtsVoicePolicy.select(candidates, allowNetwork = false, networkAvailable = false)

        assertEquals("en-us", result.selectedId)
    }

    /**
     * 离线承诺不因质量排序而破裂：本地语音再差，也不会为了质量去选网络语音。
     *
     * 「本地优先」由 [TtsVoicePolicy.select] 的桶顺序表达，不在比较器里；比较器只在同一桶内
     * 排序。没有这条判据，把质量维度错误地提到桶选择之上就没人能发现。
     */
    @Test
    fun select_lowQualityLocalVoice_stillWinsOverHighQualityNetworkVoice() {
        val candidates = listOf(
            TtsVoiceCandidate("network-very-high", "en", "US", true, quality = 500),
            TtsVoiceCandidate("local-very-low", "en", "US", false, quality = 100)
        )

        val result = TtsVoicePolicy.select(candidates, allowNetwork = true, networkAvailable = true)

        assertEquals(TtsVoiceAvailability.LOCAL, result.availability)
        assertEquals("local-very-low", result.selectedId)
    }

    /** 用户在设置页固定的语音优先级最高，不被质量排序推翻。 */
    @Test
    fun select_preferredId_isNotOverriddenByAHigherQualityVoice() {
        val candidates = listOf(
            TtsVoiceCandidate("chosen", "en", "US", false, quality = 100),
            TtsVoiceCandidate("better", "en", "US", false, quality = 500)
        )

        val result = TtsVoicePolicy.select(
            candidates,
            allowNetwork = false,
            networkAvailable = false,
            preferredId = "chosen"
        )

        assertEquals("chosen", result.selectedId)
    }

    /** 网络桶内同样按质量排序，不是只对本地桶生效。 */
    @Test
    fun select_networkVoices_alsoOrderByQuality() {
        val candidates = listOf(
            TtsVoiceCandidate("net-normal", "en", "US", true, quality = 300),
            TtsVoiceCandidate("net-high", "en", "GB", true, quality = 500)
        )

        val result = TtsVoicePolicy.select(candidates, allowNetwork = true, networkAvailable = true)

        assertEquals(TtsVoiceAvailability.NETWORK, result.availability)
        assertEquals("net-high", result.selectedId)
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
