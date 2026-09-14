package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.data.audio.PronunciationCacheStats
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 发音缓存设置项：占用显示与清除闸门。
 *
 * ## 为什么占用数字值得判据
 *
 * 那个数字是用户决定要不要清除的唯一依据。若它显示 0 而磁盘上还占着（索引损坏时的初版
 * 行为，见 `PronunciationAudioCacheTest.stats_reportsDiskBytesIncludingUnindexedFiles`），
 * 用户不会去清，空间永远收不回来。所以这里验的是「非空时确实显示了占用」，
 * 而缓存层那边验「占用数字本身准确」——两层各管一段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPronunciationCacheTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** 空缓存：显示引导文案，且不可点（没东西可清时弹确认框比不可点更困惑）。 */
    @Test
    fun emptyCache_showsHintAndIsNotTappable() {
        val clears = AtomicInteger()
        render(stats = PronunciationCacheStats(0, 0L), onClear = { clears.incrementAndGet() })

        composeTestRule.onNodeWithText("暂无缓存（查词时自动缓存发音）").performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings-pron-cache").performClick()
        composeTestRule.runOnIdle { assertEquals(0, clears.get()) }
        composeTestRule.onAllNodesWithTag("settings-pron-cache-confirm").assertCountEquals(0)
    }

    /** 有缓存时显示词数与占用。1.5 MB 取整为「1.5 MB」，不是「1 MB」也不是「1536 KB」。 */
    @Test
    fun populatedCache_showsWordCountAndSize() {
        render(stats = PronunciationCacheStats(wordCount = 42, totalBytes = 1_572_864L))

        composeTestRule.onNodeWithText("已缓存 42 个词 · 1.5 MB").performScrollTo().assertIsDisplayed()
    }

    /**
     * 低于 1 MB 显示 KB。
     *
     * 若一律用 MB，几百 KB 会显示成「0.0 MB」——看起来像空的，而实际有东西可清。
     */
    @Test
    fun subMegabyteCache_showsKilobytes() {
        render(stats = PronunciationCacheStats(wordCount = 5, totalBytes = 96_000L))

        composeTestRule.onNodeWithText("已缓存 5 个词 · 93 KB").performScrollTo().assertIsDisplayed()
    }

    /**
     * 大小格式必须跟随设备 locale。
     *
     * 初版用 `"%.1f MB".format(...)`，它走 `Locale.getDefault()`：在逗号小数点的语言环境
     * （德/法/俄）下渲染成 `1,5 MB`，与同页面用 `NumberFormat` 的扩展词库行不一致。
     *
     * 这个缺陷在默认 locale 下**测不出来** —— 上面那条 `populatedCache_showsWordCountAndSize`
     * 断言 `"1.5 MB"`，在 Robolectric（US）永远绿。所以这条显式把 locale 换成德语，
     * 断言小数分隔符跟着变；若实现退回裸 `format`，德语下仍会得到点号而这条变红。
     */
    @Test
    fun cacheSize_followsDeviceLocaleForDecimalSeparator() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            render(stats = PronunciationCacheStats(wordCount = 42, totalBytes = 1_572_864L))

            composeTestRule.onNodeWithText("已缓存 42 个词 · 1,5 MB").performScrollTo()
                .assertIsDisplayed()
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun confirming_clearsOnlyAfterConfirmationAndExactlyOnce() {
        val clears = AtomicInteger()
        render(
            stats = PronunciationCacheStats(10, 200_000L),
            onClear = { clears.incrementAndGet() }
        )

        composeTestRule.onNodeWithTag("settings-pron-cache").performScrollTo().performClick()

        composeTestRule.runOnIdle {
            assertEquals("tapping the row must not clear the cache", 0, clears.get())
        }
        composeTestRule.onNodeWithTag("settings-pron-cache-confirm").assertIsDisplayed().performClick()

        composeTestRule.runOnIdle { assertEquals(1, clears.get()) }
    }

    /** 取消对话框不清除。与上一条成对：只验「确认能清」不足以说明闸门有效。 */
    @Test
    fun dismissing_clearsNothing() {
        val clears = AtomicInteger()
        render(
            stats = PronunciationCacheStats(10, 200_000L),
            onClear = { clears.incrementAndGet() }
        )

        composeTestRule.onNodeWithTag("settings-pron-cache").performScrollTo().performClick()
        composeTestRule.onNodeWithText("取消").performClick()

        composeTestRule.runOnIdle { assertEquals(0, clears.get()) }
    }

    private fun render(
        stats: PronunciationCacheStats,
        onClear: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsCacheManagementContent(
                    onRetryStorage = {},
                    onClearCredentials = {},
                    onClearCache = {},
                    pronunciationCacheStats = stats,
                    onClearPronunciationCache = onClear
                )
            }
        }
    }
}
