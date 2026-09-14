package io.github.zoot.englishreader.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiSheetAttachment
import io.github.zoot.englishreader.model.AiSheetRequestToken
import io.github.zoot.englishreader.model.AiSheetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentenceActionPopupTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actions_dispatchesSentenceActionsWithoutDictionaryEntry() {
        var playCount = 0
        var translateCount = 0
        var explainCount = 0
        setPopup(
            target = target(word = null),
            onPlay = { playCount++ },
            onTranslate = { translateCount++ },
            onExplain = { explainCount++ }
        )

        composeRule.onNodeWithText("朗读").performClick()
        composeRule.onNodeWithText("翻译").performClick()
        composeRule.onNodeWithText("解释").performClick()
        composeRule.onAllNodesWithText("查词").assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(1, playCount)
            assertEquals(1, translateCount)
            assertEquals(1, explainCount)
        }
    }

    @Test
    fun actions_withWord_keepsOnlySentenceActions() {
        setPopup(target = target(word = "First"))

        composeRule.onAllNodesWithText("查词").assertCountEquals(0)
        listOf("播放当前句子语音", "翻译当前句子", "解释当前句子").forEach { description ->
            val bounds = composeRule.onNodeWithContentDescription(description)
                .fetchSemanticsNode()
                .touchBoundsInRoot
            val minimumPx = with(composeRule.density) { 48.dp.toPx() }
            assertTrue("$description width must be at least 48dp", bounds.width >= minimumPx - 0.5f)
            assertTrue("$description height must be at least 48dp", bounds.height >= minimumPx - 0.5f)
        }
    }

    /** 「更多」展开后才出现「全文翻译」；两级动作都在同一个 FlowRow 里，不弹嵌套 Popup。 */
    @Test
    fun actions_moreExpandsToWholeTranslation_andDispatchesOnce() {
        var wholeCount = 0
        setPopup(target = target(word = null), onWholeTranslation = { wholeCount++ })

        composeRule.onAllNodesWithTag("sentence-action-whole-translation").assertCountEquals(0)
        composeRule.onNodeWithTag("sentence-action-more").performClick()
        composeRule.onAllNodesWithTag("sentence-action-more").assertCountEquals(0)

        val node = composeRule.onNodeWithTag("sentence-action-whole-translation")
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("whole-translation touch height must be at least 48dp", bounds.height >= minimumPx - 0.5f)

        node.performClick()
        composeRule.runOnIdle { assertEquals(1, wholeCount) }
    }

    /** 没有全文翻译回调时（如非阅读页调用方），「更多」也不出现，不留一个空菜单。 */
    @Test
    fun actions_withoutWholeTranslationCallback_hidesMore() {
        setPopup(target = target(word = null), onWholeTranslation = null)

        composeRule.onAllNodesWithTag("sentence-action-more").assertCountEquals(0)
        composeRule.onAllNodesWithTag("sentence-action-whole-translation").assertCountEquals(0)
    }

    @Test
    fun popupFadeDurations_areShortAndAlphaOnly() {
        assertEquals(120, SENTENCE_POPUP_FADE_IN_DURATION_MS)
        assertEquals(90, SENTENCE_POPUP_FADE_OUT_DURATION_MS)
        assertEquals(120, SENTENCE_POPUP_CONTENT_CROSSFADE_DURATION_MS)
    }

    @Test
    fun actionPopup_doesNotOwnFocusOrOutsideDismissal() {
        val properties = sentencePopupProperties(SentencePopupMode.ACTIONS)

        assertEquals(false, properties.focusable)
        assertEquals(false, properties.dismissOnClickOutside)
    }

    @Test
    fun resultPopupModes_keepFocusAndOutsideDismissal() {
        listOf(
            SentencePopupMode.TRANSLATION,
            SentencePopupMode.EXPLANATION
        ).forEach { mode ->
            val properties = sentencePopupProperties(mode)

            assertEquals(mode.name, true, properties.focusable)
            assertEquals(mode.name, true, properties.dismissOnClickOutside)
        }
    }

    @Test
    fun popupDismiss_waitsForFadeOutAndDispatchesExactlyOnce() {
        composeRule.mainClock.autoAdvance = false
        var dismissCount = 0
        val translationTarget = AiExplanationTarget.Article(1)
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = target(word = null),
                    mode = SentencePopupMode.TRANSLATION,
                    translationState = AiSheetState.Visible(
                        attachment = AiSheetAttachment(1, AiOperationRef("key", "operation")),
                        outcome = AiOperationOutcome.Success("稳定译文"),
                        target = translationTarget
                    ),
                    onPlay = {},
                    onTranslate = {},
                    onCancelTranslation = {},
                    onRetryTranslation = {},
                    onDismiss = { dismissCount += 1 }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(SENTENCE_POPUP_FADE_IN_DURATION_MS.toLong())
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.runOnIdle { assertEquals(0, dismissCount) }

        composeRule.mainClock.advanceTimeBy((SENTENCE_POPUP_FADE_OUT_DURATION_MS - 1).toLong())
        composeRule.runOnIdle { assertEquals(0, dismissCount) }
        composeRule.mainClock.advanceTimeBy(1)
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun popupDismissGate_allowsOnlyOneTerminalCallback() {
        val gate = SentencePopupDismissGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isAcquired)
        assertTrue(!gate.tryAcquire())
    }

    @Test
    fun popupTargetIdentity_changesDuringExit_recreatesFadeAndDismissGate() {
        composeRule.mainClock.autoAdvance = false
        val popupTarget = mutableStateOf(target(word = null))
        var dismissCount = 0
        val translationTarget = AiExplanationTarget.Article(1)
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = popupTarget.value,
                    mode = SentencePopupMode.TRANSLATION,
                    translationState = AiSheetState.Visible(
                        attachment = AiSheetAttachment(1, AiOperationRef("key", "operation")),
                        outcome = AiOperationOutcome.Success("稳定译文"),
                        target = translationTarget
                    ),
                    onPlay = {},
                    onTranslate = {},
                    onCancelTranslation = {},
                    onRetryTranslation = {},
                    onDismiss = { dismissCount += 1 }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(SENTENCE_POPUP_FADE_IN_DURATION_MS.toLong())
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.runOnIdle {
            popupTarget.value = target(word = null, sentenceIndex = 1, startOffset = 7)
        }
        composeRule.mainClock.advanceTimeBy(SENTENCE_POPUP_FADE_IN_DURATION_MS.toLong())
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.mainClock.advanceTimeBy((SENTENCE_POPUP_FADE_OUT_DURATION_MS + 32).toLong())
        composeRule.waitForIdle()

        assertEquals(1, dismissCount)
    }

    @Test
    fun cancelTranslation_cancelsImmediatelyThenDismissesAfterFade() {
        composeRule.mainClock.autoAdvance = false
        var cancelCount = 0
        var dismissCount = 0
        val translationTarget = AiExplanationTarget.Article(1)
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = target(word = null),
                    mode = SentencePopupMode.TRANSLATION,
                    translationState = AiSheetState.Loading(
                        AiSheetRequestToken(1),
                        translationTarget
                    ),
                    onPlay = {},
                    onTranslate = {},
                    onCancelTranslation = { cancelCount += 1 },
                    onRetryTranslation = {},
                    onDismiss = { dismissCount += 1 }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(SENTENCE_POPUP_FADE_IN_DURATION_MS.toLong())
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertEquals(1, cancelCount)
            assertEquals(0, dismissCount)
        }
        composeRule.mainClock.advanceTimeBy((SENTENCE_POPUP_FADE_OUT_DURATION_MS + 32).toLong())
        composeRule.waitForIdle()

        assertEquals(1, cancelCount)
        assertEquals(1, dismissCount)
    }

    @Test
    fun explanation_usesIndependentStateAndCancelsOnlyExplanationBeforeFade() {
        var explanationCancels = 0
        var translationCancels = 0
        var dismissCount = 0
        val resultTarget = AiExplanationTarget.Article(1)
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = target(word = null),
                    mode = SentencePopupMode.EXPLANATION,
                    translationState = AiSheetState.Visible(
                        attachment = AiSheetAttachment(1, AiOperationRef("translation-key", "translation-op")),
                        outcome = AiOperationOutcome.Success("独立译文"),
                        target = resultTarget
                    ),
                    explanationState = AiSheetState.Loading(AiSheetRequestToken(2), resultTarget),
                    onPlay = {},
                    onTranslate = {},
                    onCancelTranslation = { translationCancels++ },
                    onCancelExplanation = { explanationCancels++ },
                    onRetryTranslation = {},
                    onDismiss = { dismissCount++ }
                )
            }
        }

        composeRule.onNodeWithText("正在生成解释").assertIsDisplayed()
        composeRule.onAllNodesWithText("独立译文").assertCountEquals(0)
        val cancelButton = composeRule.onNodeWithText("取消")
        cancelButton.performScrollTo().assertIsDisplayed().assertIsEnabled()
        // 语义滚动需要推进帧；就绪后才冻结时钟，检查取消先于淡出完成。
        composeRule.mainClock.autoAdvance = false
        cancelButton.performClick()
        composeRule.runOnIdle {
            assertEquals(1, explanationCancels)
            assertEquals(0, translationCancels)
            assertEquals(0, dismissCount)
        }
        composeRule.mainClock.advanceTimeBy((SENTENCE_POPUP_FADE_OUT_DURATION_MS + 32).toLong())
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun popupGeometry_changesDuringExit_keepsFadeAndDismissGate() {
        composeRule.mainClock.autoAdvance = false
        val popupTarget = mutableStateOf(target(word = null))
        var dismissCount = 0
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = popupTarget.value,
                    mode = SentencePopupMode.EXPLANATION,
                    translationState = AiSheetState.Hidden,
                    explanationState = AiSheetState.Rejected(
                        AiError.NoActiveProfile,
                        AiExplanationTarget.Article(1)
                    ),
                    onPlay = {},
                    onTranslate = {},
                    onCancelTranslation = {},
                    onRetryTranslation = {},
                    onDismiss = { dismissCount++ }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(SENTENCE_POPUP_FADE_IN_DURATION_MS.toLong())
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.runOnIdle {
            popupTarget.value = popupTarget.value.copy(
                anchorBounds = Rect(40f, 40f, 64f, 64f),
                sentenceBounds = Rect(0f, 40f, 220f, 120f),
                glyphOffset = 0
            )
        }
        composeRule.mainClock.advanceTimeBy((SENTENCE_POPUP_FADE_OUT_DURATION_MS + 32).toLong())
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun translation_samePopupRendersLoadingSuccessAndTypedError() {
        val target = AiExplanationTarget.Article(1)
        val state = mutableStateOf<AiSheetState>(
            AiSheetState.Loading(AiSheetRequestToken(1), target)
        )
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = target(word = null),
                    mode = SentencePopupMode.TRANSLATION,
                    translationState = state.value,
                    onPlay = {},
                    onTranslate = {},
                    onCancelTranslation = {},
                    onRetryTranslation = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("正在翻译").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = AiSheetState.Visible(
                attachment = AiSheetAttachment(1, AiOperationRef("key", "operation")),
                outcome = AiOperationOutcome.Success("稳定译文"),
                target = target
            )
        }
        composeRule.onNodeWithText("稳定译文").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = AiSheetState.Rejected(AiError.NoActiveProfile, target)
        }
        composeRule.onNodeWithText("尚未选择 AI profile").assertIsDisplayed()
    }

    private fun setPopup(
        target: InteractiveTextLongPressTarget,
        onPlay: () -> Unit = {},
        onTranslate: () -> Unit = {},
        onExplain: () -> Unit = {},
        onWholeTranslation: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                SentenceActionPopup(
                    target = target,
                    mode = SentencePopupMode.ACTIONS,
                    translationState = AiSheetState.Hidden,
                    onPlay = onPlay,
                    onTranslate = onTranslate,
                    onExplain = onExplain,
                    onWholeTranslation = onWholeTranslation,
                    onCancelTranslation = {},
                    onRetryTranslation = {},
                    onDismiss = {}
                )
            }
        }
    }

    private fun target(
        word: String?,
        sentenceIndex: Int = 0,
        startOffset: Int = 0
    ): InteractiveTextLongPressTarget =
        InteractiveTextLongPressTarget(
            sentenceIndex = sentenceIndex,
            sentenceRange = SentenceRange(sentenceIndex, "First.", startOffset, startOffset + 6),
            word = word,
            anchorBounds = Rect(0f, 0f, 12f, 16f),
            sentenceBounds = Rect(0f, 0f, 12f, 16f)
        )
}
