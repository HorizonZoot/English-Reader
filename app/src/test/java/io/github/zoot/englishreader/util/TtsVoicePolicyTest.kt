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

    // ---- preferredId 用不了时的退回（单词发音语义） ----

    /**
     * 用户挑了网络语音却没开联网授权时，单词发音退回本地语音而不是报错。
     *
     * 这个状态是可达的：`ReadingViewModel.setReadingVoice` 只校验语音存在于快照，不校验授权。
     * 整句朗读在这种状态下报 `NETWORK_DISABLED` 是对的——阅读页有恢复对话框，能让用户「仅本句
     * 联网」或去装语言包。但单词发音只有一条 snackbar、没有恢复入口，且它本身就是兜底路径
     * （真人音频优先），所以把「能用本地语音响一下」换成「弹一条错误」是纯损失。
     */
    @Test
    fun select_preferredNetworkVoiceWithoutConsent_fallsBackToLocalInsteadOfFailing() {
        val candidates = listOf(
            TtsVoiceCandidate("net", "en", "US", true, quality = 500),
            TtsVoiceCandidate("local", "en", "US", false, quality = 100)
        )

        val result = TtsVoicePolicy.select(
            candidates,
            allowNetwork = false,
            networkAvailable = false,
            preferredId = "net",
            fallBackWhenPreferredUnusable = true
        )

        assertEquals(TtsVoiceAvailability.LOCAL, result.availability)
        assertEquals("local", result.selectedId)
    }

    /** 同一输入，不开退回（整句朗读语义）时必须仍然报错——默认值不能被改掉。 */
    @Test
    fun select_preferredNetworkVoiceWithoutConsent_stillFailsWhenFallbackNotRequested() {
        val candidates = listOf(
            TtsVoiceCandidate("net", "en", "US", true),
            TtsVoiceCandidate("local", "en", "US", false)
        )

        val strict = TtsVoicePolicy.select(candidates, false, false, preferredId = "net")

        assertEquals(TtsVoiceAvailability.NETWORK_DISABLED, strict.availability)
        assertNull(strict.selectedId)
    }

    /**
     * **退回不得放宽同意。** preferred 是网络语音、授权关闭、且机器上没有可用本地语音时，
     * 结果仍须是 `NETWORK_DISABLED`。
     *
     * 没有这条判据，把退回实现成「失败就挑 network.first()」的写法照样能让上面那条用例绿，
     * 而那种实现会在用户明确关闭联网的情况下把文本发往网络。
     */
    @Test
    fun select_fallbackWithNoLocalVoice_neverBypassesNetworkConsent() {
        val candidates = listOf(
            TtsVoiceCandidate("net-a", "en", "US", true, quality = 500),
            TtsVoiceCandidate("net-b", "en", "GB", true, quality = 400)
        )

        val result = TtsVoicePolicy.select(
            candidates,
            allowNetwork = false,
            networkAvailable = false,
            preferredId = "net-a",
            fallBackWhenPreferredUnusable = true
        )

        assertEquals(TtsVoiceAvailability.NETWORK_DISABLED, result.availability)
        assertNull(result.selectedId)
    }

    /** 已授权但当前离线：同样退回本地，而不是把「离线」报成失败。 */
    @Test
    fun select_preferredNetworkVoiceWhileOffline_fallsBackToLocal() {
        val candidates = listOf(
            TtsVoiceCandidate("net", "en", "US", true),
            TtsVoiceCandidate("local", "en", "GB", false)
        )

        val result = TtsVoicePolicy.select(
            candidates,
            allowNetwork = true,
            networkAvailable = false,
            preferredId = "net",
            fallBackWhenPreferredUnusable = true
        )

        assertEquals(TtsVoiceAvailability.LOCAL, result.availability)
        assertEquals("local", result.selectedId)
    }

    /**
     * 开了退回也不影响「选中的语音本来就能用」这一路：仍然用用户挑的那个，
     * 不会因为有更高质量的语音就改主意。
     */
    @Test
    fun select_usablePreferredVoice_isUnaffectedByTheFallbackFlag() {
        val candidates = listOf(
            TtsVoiceCandidate("chosen", "en", "GB", false, quality = 100),
            TtsVoiceCandidate("better", "en", "US", false, quality = 500)
        )

        val result = TtsVoicePolicy.select(
            candidates,
            allowNetwork = false,
            networkAvailable = false,
            preferredId = "chosen",
            fallBackWhenPreferredUnusable = true
        )

        assertEquals(TtsVoiceAvailability.LOCAL, result.availability)
        assertEquals("chosen", result.selectedId)
    }

    /** 已授权且在线时，选中的网络语音正常生效——退回标记不该顺手把网络语音也降级掉。 */
    @Test
    fun select_preferredNetworkVoiceWithConsentAndConnectivity_isUsedEvenWithFallbackAllowed() {
        val candidates = listOf(
            TtsVoiceCandidate("net", "en", "US", true),
            TtsVoiceCandidate("local", "en", "US", false)
        )

        val result = TtsVoicePolicy.select(
            candidates,
            allowNetwork = true,
            networkAvailable = true,
            preferredId = "net",
            fallBackWhenPreferredUnusable = true
        )

        assertEquals(TtsVoiceAvailability.NETWORK, result.availability)
        assertEquals("net", result.selectedId)
    }
}
