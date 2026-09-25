package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiExplanationTextNormalizer
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiSheetAttachment
import io.github.zoot.englishreader.model.AiSheetRequestToken
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.SelectedSentence
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentenceAiResultContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hidden_rendersUnavailableAndReachableClose() {
        val dismisses = AtomicInteger()
        setContent(AiSheetState.Hidden, onDismiss = { dismisses.incrementAndGet() })

        composeRule.onNodeWithText("暂时没有可用解释").assertIsDisplayed()
        composeRule.onAllNodesWithText("朗读").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.runOnIdle { assertEquals(1, dismisses.get()) }
    }

    @Test
    fun loading_rendersImmediateFeedbackDisclosureAndCancelCallback() {
        val cancels = AtomicInteger()
        setContent(
            state = AiSheetState.Loading(
                AiSheetRequestToken(1),
                AiExplanationTarget.Article(7)
            ),
            onCancel = { cancels.incrementAndGet() }
        )

        composeRule.onNodeWithText("正在生成解释").assertIsDisplayed()
        composeRule.onNodeWithText(
            "AI 请求会将你提供的内容发送给第三方服务商，数据可能跨境传输；服务商的留存和管辖范围由其政策决定。"
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("取消").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, cancels.get()) }
    }

    @Test
    fun attachedWithoutOutcome_keepsLoadingAndExplicitCancel() {
        val cancels = AtomicInteger()
        setContent(visible(null), onCancel = { cancels.incrementAndGet() })

        composeRule.onNodeWithText("正在生成解释").assertIsDisplayed()
        composeRule.onAllNodesWithText("朗读").assertCountEquals(0)
        composeRule.onNodeWithText("取消").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, cancels.get()) }
    }

    @Test
    fun success_rendersExplanationAndDismissCallback() {
        val dismisses = AtomicInteger()
        setContent(
            state = visible(AiOperationOutcome.Success("A private explanation")),
            onDismiss = { dismisses.incrementAndGet() }
        )

        composeRule.onNodeWithText("A private explanation").assertIsDisplayed()
        composeRule.onNodeWithText("译意与讲解").assertIsDisplayed()
        // 内部标识不得随解释正文一起渲染。visible() 刻意把 operationRef 填成
        // semantic-secret / operation-secret，此前全文件却没有一条断言用到它们——
        // 命名让这个 fixture 看起来在守脱敏，实际不守。现有脱敏断言都在
        // ViewModel / Repository 层（AiSheetCoordinatorTest 等），渲染树这一层是空的。
        composeRule.onAllNodesWithText("semantic-secret", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("operation-secret", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("关闭").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, dismisses.get()) }
    }

    @Test
    fun longSuccess_closeRemainsReachableAndBodyActionsCanScroll() {
        val dismisses = AtomicInteger()
        setContent(
            state = visible(AiOperationOutcome.Success("Explanation line.\n".repeat(80))),
            onDismiss = { dismisses.incrementAndGet() },
            height = 220.dp
        )

        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, dismisses.get()) }
    }

    @Test
    fun smallLoading_disclosureAndCancelRemainScrollable() {
        val cancels = AtomicInteger()
        setContent(
            state = AiSheetState.Loading(AiSheetRequestToken(1), AiExplanationTarget.Article(7)),
            onCancel = { cancels.incrementAndGet() },
            height = 180.dp
        )

        composeRule.onNodeWithText("取消").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, cancels.get()) }
    }

    @Test
    fun typedFailure_rendersLocalizedSafeMessageWithoutExplanationRetry() {
        setContent(visible(AiOperationOutcome.Failure(AiError.Offline)))

        composeRule.onNodeWithText("当前设备没有网络连接").assertIsDisplayed()
        composeRule.onAllNodesWithText("重试").assertCountEquals(0)
        composeRule.onAllNodesWithText("朗读").assertCountEquals(0)
    }

    @Test
    fun translationFailure_retryDispatchesOnce() {
        val retries = AtomicInteger()
        setContent(
            state = AiSheetState.Rejected(AiError.NoActiveProfile, AiExplanationTarget.Article(7)),
            mode = SentencePopupMode.TRANSLATION,
            onRetry = { retries.incrementAndGet() },
            height = 180.dp
        )

        composeRule.onNodeWithText("重试").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, retries.get()) }
    }

    @Test
    fun overLimitRejection_rendersActualAndMaximumCounts() {
        setContent(
            AiSheetState.Rejected(
                error = AiError.InputTooLong(actualChars = 8_001, maxChars = 8_000),
                target = AiExplanationTarget.Article(7)
            )
        )

        composeRule.onNodeWithText(
            "文章过长（8001 字符），全文解释上限为 8000 字符"
        ).assertIsDisplayed()
    }

    @Test
    fun sentenceResult_loadingSuccessAndRejectionKeepExactSnapshotSource() {
        val rawText = "  First\t sentence.\n"
        val target = sentenceTarget(rawText)
        val state = mutableStateOf<AiSheetState>(AiSheetState.Loading(AiSheetRequestToken(1), target))
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp).heightIn(max = 360.dp)) {
                    SentenceAiResultContent(
                        mode = SentencePopupMode.EXPLANATION,
                        state = state.value,
                        onDismiss = {},
                        onCancel = {},
                        onPlay = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("sentence-result-source").performScrollTo().assertTextEquals(rawText)
        composeRule.runOnIdle { state.value = visible(null).copy(target = target) }
        composeRule.onNodeWithTag("sentence-result-source").performScrollTo().assertTextEquals(rawText)
        composeRule.runOnIdle {
            state.value = visible(AiOperationOutcome.Success("First sentence：第一句；sentence 是名词。"))
                .copy(target = target)
        }
        composeRule.onNodeWithTag("sentence-result-source").performScrollTo().assertTextEquals(rawText)
        composeRule.onAllNodesWithText("译意与讲解").assertCountEquals(0)
        composeRule.onNodeWithTag("sentence-result-explanation")
            .performScrollTo().assertTextEquals("First sentence：第一句；sentence 是名词。")
        composeRule.runOnIdle { state.value = AiSheetState.Rejected(AiError.NoActiveProfile, target) }
        composeRule.onNodeWithTag("sentence-result-source").performScrollTo().assertTextEquals(rawText)
        composeRule.onNodeWithTag("sentence-result-explanation").assertDoesNotExist()
        composeRule.runOnIdle { state.value = AiSheetState.Hidden }
        composeRule.onNodeWithTag("sentence-result-source").assertDoesNotExist()
    }

    @Test
    fun sentenceResult_conciseExplanation_preservesMeaningAndPointsWithoutExtraHeading() {
        val source = "Reading is one of the most beneficial activities for our minds."
        val explanation = "译文\n阅读是最有益于心智的活动之一。\n\n" +
            "要点\n• Reading：动名词作主语，按单数处理，因此用 is。\n" +
            "• one of the most beneficial activities：最有益的活动之一；one of 后接复数名词。"
        setContent(
            state = visible(AiOperationOutcome.Success(explanation)).copy(target = sentenceTarget(source))
        )

        composeRule.onNodeWithTag("sentence-result-source").performScrollTo().assertTextEquals(source)
        composeRule.onNodeWithTag("sentence-result-explanation")
            .performScrollTo().assertTextEquals(explanation)
        composeRule.onAllNodesWithText("译意与讲解").assertCountEquals(0)
        composeRule.onAllNodesWithText("译文", substring = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("要点", substring = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
    }

    @Test
    fun sentenceTranslation_success_keepsTranslationOnlyWithoutExplanationHeadings() {
        setContent(
            state = visible(AiOperationOutcome.Success("阅读有益。"))
                .copy(target = sentenceTarget("Reading helps.")),
            mode = SentencePopupMode.TRANSLATION
        )

        composeRule.onNodeWithTag("sentence-result-explanation")
            .performScrollTo().assertTextEquals("阅读有益。")
        composeRule.onAllNodesWithText("译意与讲解").assertCountEquals(0)
        composeRule.onAllNodesWithText("要点", substring = true).assertCountEquals(0)
    }

    @Test
    fun sentenceResult_longSourceAndExplanationKeepActionsReachable() {
        val rawText = "The quoted sentence keeps its original wording.\n".repeat(80)
        var dismisses = 0
        var plays = 0
        setContent(
            state = visible(AiOperationOutcome.Success("Long explanation.\n".repeat(80)))
                .copy(target = sentenceTarget(rawText)),
            onDismiss = { dismisses++ },
            onPlay = { plays++ },
            height = 220.dp
        )

        composeRule.onNodeWithTag("sentence-result-source").performScrollTo().assertTextEquals(rawText)
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("播放当前句子语音")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, plays)
            assertEquals(0, dismisses)
        }
        composeRule.onNodeWithText("关闭").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, dismisses) }
    }

    private fun sentenceTarget(rawText: String) = AiExplanationTarget.Sentence(
        SelectedSentence(
            articleId = 7,
            sentenceIndex = 0,
            rawText = rawText,
            normalizedText = AiExplanationTextNormalizer.normalize(rawText),
            startOffset = 5,
            endOffset = 5 + rawText.length
        )
    )

    private fun setContent(
        state: AiSheetState,
        onDismiss: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRetry: () -> Unit = {},
        onPlay: () -> Unit = {},
        mode: SentencePopupMode = SentencePopupMode.EXPLANATION,
        height: Dp = 360.dp
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(300.dp).heightIn(max = height)) {
                    SentenceAiResultContent(
                        mode = mode,
                        state = state,
                        onDismiss = onDismiss,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onPlay = onPlay
                    )
                }
            }
        }
    }

    private fun visible(outcome: AiOperationOutcome?): AiSheetState.Visible =
        AiSheetState.Visible(
            attachment = AiSheetAttachment(
                generation = 1,
                operationRef = AiOperationRef("semantic-secret", "operation-secret")
            ),
            outcome = outcome,
            target = AiExplanationTarget.Article(7)
        )
}
