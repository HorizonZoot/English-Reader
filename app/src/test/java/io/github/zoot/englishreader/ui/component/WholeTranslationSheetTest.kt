package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.ScopeOption
import io.github.zoot.englishreader.model.TranslationFailureReason
import io.github.zoot.englishreader.model.WholeTranslationProgress
import io.github.zoot.englishreader.model.WholeTranslationScopeChoice
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全文翻译 sheet 的用户可见行为：范围 radio、主按钮语义、进度/失败/完成展示、关闭与取消分离。
 *
 * 不断言 ViewModel 或 repository——状态由测试直接构造，回调只记录调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeTranslationSheetTest {
    @get:Rule val composeRule = createComposeRule()

    private val clicks = mutableListOf<String>()

    @Test
    fun choosingScope_chapterAvailable_radioRowsSelectAndReportCounts() {
        val state = mutableStateOf(
            WholeTranslationSheetState.ChoosingScope(
                articleId = 1,
                selected = WholeTranslationScopeChoice.CURRENT_ARTICLE,
                currentArticleOption = ScopeOption(paragraphCount = 12, articleCount = 1),
                chapterOption = ScopeOption(paragraphCount = 340, articleCount = 18),
                existing = null
            )
        )
        setContent(state) { choice ->
            state.value = (state.value as WholeTranslationSheetState.ChoosingScope).copy(selected = choice)
        }

        composeRule.onNodeWithTag("whole-translation-scope-current").assertIsSelected()
        composeRule.onNodeWithTag("whole-translation-scope-chapter").assertIsNotSelected()
        // 段落数即预计请求数
        composeRule.onNodeWithTag("whole-translation-scope-current").assertTextContains("12", substring = true)
        composeRule.onNodeWithTag("whole-translation-scope-chapter").assertTextContains("340", substring = true)
        composeRule.onNodeWithTag("whole-translation-scope-chapter").assertTextContains("18", substring = true)

        composeRule.onNodeWithTag("whole-translation-scope-chapter").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("whole-translation-scope-chapter").assertIsSelected()
        composeRule.onNodeWithTag("whole-translation-scope-current").assertIsNotSelected()
        // 整行可点，且触控高度 ≥ 48dp
        composeRule.onNodeWithTag("whole-translation-scope-chapter").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun choosingScope_standaloneArticle_hidesChapterRowAndStartsOnPrimary() {
        setContent(
            WholeTranslationSheetState.ChoosingScope(
                articleId = 1,
                selected = WholeTranslationScopeChoice.CURRENT_ARTICLE,
                currentArticleOption = ScopeOption(paragraphCount = 3, articleCount = 1),
                chapterOption = null,
                existing = null
            )
        )

        composeRule.onNodeWithTag("whole-translation-scope-current").assertIsDisplayed()
        composeRule.onAllNodesWithTagCount("whole-translation-scope-chapter", 0)
        composeRule.onNodeWithTag("whole-translation-primary").assertTextEquals("开始翻译")

        composeRule.onNodeWithTag("whole-translation-primary").performClick()
        assertEquals(listOf("start"), clicks)
    }

    @Test
    fun tracking_running_showsProgressAndBackgroundContinueDismissesWithoutCancel() {
        setContent(tracking(WholeTranslationTaskStatus.RUNNING, translated = 4, total = 10))

        composeRule.onNodeWithTag("whole-translation-progress").assertTextEquals("已翻译 4 / 10")
        composeRule.onNodeWithTag("whole-translation-status").assertTextEquals("正在翻译…")
        composeRule.onNodeWithTag("whole-translation-primary").assertTextEquals("后台继续")

        composeRule.onNodeWithTag("whole-translation-primary").performClick()

        // 「后台继续」只关闭面板，绝不取消任务
        assertEquals(listOf("dismiss"), clicks)
    }

    @Test
    fun tracking_pausedWithFailures_showsFailedCountAndRetryFailed() {
        setContent(tracking(WholeTranslationTaskStatus.PAUSED, translated = 7, total = 10, failed = 2))

        composeRule.onNodeWithTag("whole-translation-progress").assertTextEquals("已翻译 7 / 10，失败 2")
        composeRule.onNodeWithTag("whole-translation-primary").assertTextEquals("重试失败项")

        composeRule.onNodeWithTag("whole-translation-primary").performClick()
        assertEquals(listOf("retry"), clicks)
    }

    // 「暂停且无失败 → 继续翻译」不单独测：primaryAction 的四路推导由
    // WholeTranslationDomainTest.primaryAction_derivesFromStatusAndFailures 直接覆盖，
    // 本类只需一条用例证明主按钮把推导结果派发出去（上一条）。

    @Test
    fun tracking_cancelTask_isSeparateFromDismiss() {
        setContent(tracking(WholeTranslationTaskStatus.RUNNING, translated = 1, total = 5))

        composeRule.onNodeWithTag("whole-translation-cancel-task").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("whole-translation-cancel-task").performClick()

        assertEquals(listOf("cancel"), clicks)
    }

    @Test
    fun tracking_failedConfiguration_explainsAndHidesRetry() {
        setContent(
            tracking(
                WholeTranslationTaskStatus.FAILED,
                translated = 2,
                total = 10,
                failed = 1,
                failureReason = TranslationFailureReason.CONFIGURATION
            )
        )

        composeRule.onNodeWithTag("whole-translation-status")
            .assertTextContains("AI 配置或凭据不可用", substring = true)
        // FAILED 且有失败计数时主按钮仍是「重试失败项」：用户修好配置后可继续
        composeRule.onNodeWithTag("whole-translation-primary").assertTextEquals("重试失败项")
    }

    @Test
    fun tracking_completed_showsDoneAndNoCancel() {
        setContent(tracking(WholeTranslationTaskStatus.COMPLETED, translated = 10, total = 10))

        composeRule.onNodeWithTag("whole-translation-status").assertTextEquals("翻译完成，已显示中文对照")
        composeRule.onNodeWithTag("whole-translation-primary").assertTextEquals("完成")
        composeRule.onAllNodesWithTagCount("whole-translation-cancel-task", 0)

        composeRule.onNodeWithTag("whole-translation-primary").performClick()
        assertEquals(listOf("dismiss"), clicks)
    }

    @Test
    fun rejected_noContent_showsReasonAndClose() {
        setContent(WholeTranslationSheetState.Rejected(articleId = 1, error = AiError.NoContent))

        composeRule.onNodeWithTag("whole-translation-rejected").assertTextEquals("没有可翻译的段落")
        composeRule.onNodeWithTag("whole-translation-close").performClick()
        assertEquals(listOf("dismiss"), clicks)
    }

    // ---- helpers ----

    /**
     * 必须把 [State] 本身传进去、在 composition 内部读 `.value`。
     *
     * 传 `state.value`（快照值）会让 composable 捕获一个普通对象，Compose 观察不到后续赋值，
     * 于是「点击切换 radio」这类用例永远看不到新状态——失败时看起来像生产代码没响应点击，
     * 实际是测试没接上状态。
     */
    private fun setContent(
        state: State<WholeTranslationSheetState>,
        onSelectScope: (WholeTranslationScopeChoice) -> Unit = {}
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(720.dp).fillMaxWidth()) {
                    WholeTranslationContent(
                        state = state.value,
                        onDismiss = { clicks += "dismiss" },
                        onSelectScope = onSelectScope,
                        onStart = { clicks += "start" },
                        onResume = { clicks += "resume" },
                        onRetryFailed = { clicks += "retry" },
                        onCancelTask = { clicks += "cancel" }
                    )
                }
            }
        }
    }

    /** 状态在用例中不变时的便捷重载。 */
    private fun setContent(state: WholeTranslationSheetState) = setContent(mutableStateOf(state))

    private fun tracking(
        status: WholeTranslationTaskStatus,
        translated: Int,
        total: Int,
        failed: Int = 0,
        failureReason: TranslationFailureReason? = null
    ) = WholeTranslationSheetState.Tracking(
        taskId = 1,
        scopeKey = "article:1",
        status = status,
        progress = WholeTranslationProgress(
            total = total,
            translated = translated,
            translating = 0,
            failed = failed,
            untranslated = total - translated - failed
        ),
        failureReason = failureReason
    )

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
        assertEquals(expected, onAllNodesWithTag(tag).fetchSemanticsNodes().size)
    }
}
