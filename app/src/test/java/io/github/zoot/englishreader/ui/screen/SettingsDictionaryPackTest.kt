package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.data.dictionary.DictionaryPackFailure
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ThemeOption
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扩展词库下载入口的用户同意闸门与状态呈现。
 *
 * ## 为什么同意闸门值得一个独立测试类
 *
 * 这个下载是 22 MB 传输 / 63 MB 解压。用户在设置页误触一下就跑掉几十 MB 移动数据是不可接受的，
 * 而「点了行就开始下载」与「点了行弹对话框」在代码上只差一层 —— 那一层被误删时界面看起来
 * 完全正常（行还在、点了有反应），只有流量账单会告诉用户。所以它必须有判据。
 *
 * ## 断言的是「回调有没有被调用」，不是「对话框长什么样」
 *
 * 文案会改。真正不能变的性质是：**只点行不触发下载，只有按下确认按钮才触发**。
 * 所以每个用例都盯着 `onInstall` 的调用计数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDictionaryPackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun confirmingDialog_startsDownloadOnlyAfterConfirmationAndExactlyOnce() {
        val installs = AtomicInteger()
        render(state = DictionaryPackState.NotInstalled, onInstall = { installs.incrementAndGet() })

        composeTestRule.onNodeWithTag("settings-dict-pack").performScrollTo().performClick()

        composeTestRule.runOnIdle {
            assertEquals("tapping the row must not start a 22 MB download", 0, installs.get())
        }
        composeTestRule.onNodeWithTag("settings-dict-pack-confirm").assertIsDisplayed().performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, installs.get())
        }
    }

    /**
     * 取消对话框不发起下载。
     *
     * 与上一条成对：只验「确认能触发」不足以说明闸门有效，还要验「不确认不触发」。
     */
    @Test
    fun dismissingDialog_startsNoDownload() {
        val installs = AtomicInteger()
        render(state = DictionaryPackState.NotInstalled, onInstall = { installs.incrementAndGet() })

        composeTestRule.onNodeWithTag("settings-dict-pack").performScrollTo().performClick()
        composeTestRule.onNodeWithText("取消").performClick()

        composeTestRule.runOnIdle {
            assertEquals(0, installs.get())
        }
    }

    /**
     * 下载进行中时这一行不可点，且出现取消按钮。
     *
     * 不可点防的是重复发起；取消按钮是用户唯一的退出方式（60 MB 在慢网络上要几分钟）。
     */
    @Test
    fun downloadingState_offersCancelAndBlocksRepeatTaps() {
        val installs = AtomicInteger()
        val cancels = AtomicInteger()
        render(
            state = DictionaryPackState.Downloading(downloadedBytes = 5_000_000, totalBytes = 60_000_000),
            onInstall = { installs.incrementAndGet() },
            onCancel = { cancels.incrementAndGet() }
        )

        composeTestRule.onNodeWithTag("settings-dict-pack").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            assertEquals("in-flight row must not be tappable", 0, installs.get())
        }

        composeTestRule.onNodeWithTag("settings-dict-pack-cancel").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, cancels.get()) }
    }

    @Test
    fun installedState_confirmedRemoval_dispatchesOnceAndNeverDownloads() {
        val installs = AtomicInteger()
        val removals = AtomicInteger()
        render(
            state = DictionaryPackState.Installed(entryCount = 760_000),
            onInstall = { installs.incrementAndGet() },
            onRemove = { removals.incrementAndGet() }
        )

        composeTestRule.onNodeWithTag("settings-dict-pack").performScrollTo().performClick()
        composeTestRule.onNodeWithText("移除扩展词库？").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, removals.get()) }
        composeTestRule.onNodeWithText("取消").performClick()
        composeTestRule.runOnIdle { assertEquals(0, removals.get()) }

        composeTestRule.onNodeWithTag("settings-dict-pack").performClick()
        composeTestRule.onNodeWithTag("settings-dict-pack-remove-confirm")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }

        composeTestRule.runOnIdle {
            assertEquals(1, removals.get())
            assertEquals(0, installs.get())
        }
        composeTestRule.onAllNodesWithTag("settings-dict-pack-confirm").assertCountEquals(0)
    }

    /**
     * 失败后必须能重试，且必须告诉用户内置词库仍可用。
     *
     * 后半句是产品性质而非实现细节：安装走 `replaceAllStreaming` 事务，失败整体回滚，
     * 用户手里还有原来的词库。若提示只说「安装失败」，用户会以为查词坏了。
     */
    @Test
    fun failedState_allowsRetryAndSaysBuiltInStillWorks() {
        val installs = AtomicInteger()
        render(
            state = DictionaryPackState.Failed(DictionaryPackFailure.NETWORK),
            onInstall = { installs.incrementAndGet() }
        )

        // 直接滚到安抚文案本身。滚到上面那一行不够：文案在它下方，行滚进视口时文案仍在外面。
        // 失败信息「The component is not displayed」不区分「没渲染」和「渲染了但在视口外」，
        // 我第一版就是这么误判的。
        composeTestRule.onNodeWithText("内置词库仍可正常查词").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings-dict-pack").performClick()
        composeTestRule.onNodeWithTag("settings-dict-pack-confirm").performClick()

        composeTestRule.runOnIdle {
            assertEquals("failure must be retryable", 1, installs.get())
        }
    }

    /**
     * 用户取消后**不**显示「内置词库仍可用」那句安抚。
     *
     * 取消不是故障，多一句解释会让用户以为出了问题。
     */
    @Test
    fun cancelledState_doesNotShowReassurance() {
        render(state = DictionaryPackState.Failed(DictionaryPackFailure.CANCELLED))

        composeTestRule.onAllNodesWithText("内置词库仍可正常查词").assertCountEquals(0)
    }

    private fun render(
        state: DictionaryPackState,
        onInstall: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRemove: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsOverviewContent(
                    fontSize = FontSizeOption.MEDIUM,
                    onFontSizeChange = {},
                    theme = ThemeOption.SYSTEM,
                    onThemeChange = {},
                    profiles = emptyList(),
                    activeProfileId = null,
                    onOpenAiProfile = {},
                    dictionaryPackState = state,
                    onInstallDictionaryPack = onInstall,
                    onCancelDictionaryPack = onCancel,
                    onRemoveDictionaryPack = onRemove
                )
            }
        }
    }
}
