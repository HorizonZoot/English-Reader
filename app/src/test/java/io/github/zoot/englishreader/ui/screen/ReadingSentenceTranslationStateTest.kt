package io.github.zoot.englishreader.ui.screen

import androidx.compose.ui.geometry.Rect
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiSheetAttachment
import io.github.zoot.englishreader.model.AiSheetRequestToken
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.SelectedSentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSentenceTranslationStateTest {

    @Test
    fun sentencePopupSafeScroll_usesImmediateMeasuredCorrection() {
        // 滚动位移本身刻意做成立即生效，只有 popup 的 alpha 才走动画。
        var scrolledDelta = 0f
        var consumed = false

        runTest {
            runSentencePopupScrollCorrectionOnce(
                correctionConsumed = false,
                delta = 48f,
                markConsumed = { consumed = true },
                scroll = { scrolledDelta = it }
            )
        }

        assertTrue(consumed)
        assertEquals(48f, scrolledDelta, 0.01f)
    }

    @Test
    fun runSentencePopupScrollCorrectionOnce_cancelledScroll_consumesBeforeSuspensionAndDoesNotRetry() = runTest {
        var consumed = false
        var animationCalls = 0
        var cancellationObserved = false

        try {
            runSentencePopupScrollCorrectionOnce(
                correctionConsumed = consumed,
                delta = 48f,
                markConsumed = { consumed = true },
                scroll = { measuredDelta ->
                    animationCalls += 1
                    assertTrue(consumed)
                    assertEquals(48f, measuredDelta, 0.01f)
                    throw CancellationException("popup remeasured")
                }
            )
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertTrue(consumed)
        runSentencePopupScrollCorrectionOnce(
            correctionConsumed = consumed,
            delta = 24f,
            markConsumed = { error("already-consumed correction must not be acquired again") },
            scroll = { animationCalls += 1 }
        )
        assertEquals(1, animationCalls)
    }

    @Test
    fun runSentencePopupScrollCorrectionOnce_zeroDelta_doesNotConsumeOrAnimate() = runTest {
        var consumed = false
        var animationCalls = 0

        runSentencePopupScrollCorrectionOnce(
            correctionConsumed = false,
            delta = 0f,
            markConsumed = { consumed = true },
            scroll = { animationCalls += 1 }
        )

        assertFalse(consumed)
        assertEquals(0, animationCalls)
    }

    @Test
    fun sentencePopupSafeScrollDelta_noUsefulCorrection_keepsSentenceInPlace() {
        data class Case(
            val name: String,
            val itemTop: Int,
            val bounds: Rect,
            val popupHeight: Int,
            val viewportEnd: Int = 800
        )
        val cases = listOf(
            Case("unmeasured", 650, Rect(0f, 80f, 20f, 104f), 0),
            Case("fits above", 650, Rect(0f, 80f, 20f, 104f), 220),
            Case("inside reading band", 200, Rect(0f, 80f, 20f, 104f), 220),
            Case("fits below", 20, Rect(0f, 0f, 20f, 60f), 200),
            Case("whole viewport result", 180, Rect(0f, 12f, 160f, 96f), 400, 400)
        )
        cases.forEach { case ->
            val delta = sentencePopupSafeScrollDelta(
                itemTop = case.itemTop,
                sentenceBounds = case.bounds,
                viewportStart = 0,
                viewportEnd = case.viewportEnd,
                popupHeightPx = case.popupHeight,
                preferAbove = false
            )
            assertEquals(case.name, 0f, delta, 0.01f)
        }
    }

    @Test
    fun sentencePopupSafeScrollDelta_neitherPlacementFits_usesSmallerAboveCorrection() {
        val delta = sentencePopupSafeScrollDelta(
            itemTop = 180,
            sentenceBounds = Rect(0f, 12f, 20f, 36f),
            viewportStart = 0,
            viewportEnd = 400,
            popupHeightPx = 220
        )

        assertEquals(-36f, delta, 0.01f)
    }

    @Test
    fun sentencePopupSafeScrollDelta_belowNeedsLessSpace_usesMinimalBelowCorrection() {
        val delta = sentencePopupSafeScrollDelta(
            itemTop = 138,
            sentenceBounds = Rect(0f, 12f, 20f, 36f),
            viewportStart = 0,
            viewportEnd = 400,
            popupHeightPx = 300
        )

        assertEquals(82f, delta, 0.01f)
    }

    @Test
    fun sentencePopupSafeScrollDelta_equalOverflow_prefersBelowForResults() {
        val delta = sentencePopupSafeScrollDelta(
            itemTop = 176,
            sentenceBounds = Rect(0f, 12f, 20f, 36f),
            viewportStart = 0,
            viewportEnd = 400,
            popupHeightPx = 220,
            preferAbove = false
        )

        assertEquals(40f, delta, 0.01f)
    }

    @Test
    fun wordSheetSafeScrollDelta_wordBehindMeasuredSheet_returnsMinimalCorrection() {
        val delta = wordSheetSafeScrollDelta(
            itemTop = 600,
            wordBounds = Rect(0f, 180f, 40f, 210f),
            viewportStart = 0,
            viewportEnd = 900,
            obstructionHeightPx = 250,
            gapPx = 8
        )

        assertEquals(168f, delta, 0.01f)
    }

    @Test
    fun wordSheetSafeScrollDelta_wordAboveSheet_returnsNoCorrection() {
        val delta = wordSheetSafeScrollDelta(
            itemTop = 120,
            wordBounds = Rect(0f, 40f, 40f, 70f),
            viewportStart = 0,
            viewportEnd = 900,
            obstructionHeightPx = 250,
            gapPx = 8
        )

        assertEquals(0f, delta, 0.01f)
    }

    @Test
    fun wordSheetSafeScrollDelta_wordCannotFitAboveSheet_keepsReadingPosition() {
        listOf(880, 900).forEach { obstructionHeight ->
            val delta = wordSheetSafeScrollDelta(
                itemTop = 120,
                wordBounds = Rect(0f, 40f, 40f, 70f),
                viewportStart = 0,
                viewportEnd = 900,
                obstructionHeightPx = obstructionHeight,
                gapPx = 8
            )

            assertEquals(0f, delta, 0.01f)
        }
    }

    @Test
    fun runWordSheetScrollCorrectionOnce_consumesBeforeScrollAndDoesNotRepeat() = runTest {
        var consumed = false
        var calls = 0

        runWordSheetScrollCorrectionOnce(
            correctionConsumed = false,
            delta = 24f,
            markConsumed = { consumed = true },
            scroll = {
                assertTrue(consumed)
                calls++
            }
        )
        runWordSheetScrollCorrectionOnce(
            correctionConsumed = consumed,
            delta = 12f,
            markConsumed = { error("correction must be one-shot") },
            scroll = { calls++ }
        )

        assertEquals(1, calls)
    }

    @Test
    fun forSelection_matchingTarget_preservesEveryVisibleStateShape() {
        val selection = sentence(index = 0, text = "First.", start = 0)
        val target = AiExplanationTarget.Sentence(selection)

        statesFor(target).forEach { state ->
            assertSame(state, state.forSelection(selection))
        }
    }

    @Test
    fun forSelection_articleOrDifferentSnapshot_hidesEveryStateShape() {
        val current = sentence(index = 0, text = "First.", start = 0)
        val mismatchedTargets = listOf(
            AiExplanationTarget.Article(current.articleId),
            AiExplanationTarget.Sentence(current.copy(articleId = 2)),
            AiExplanationTarget.Sentence(current.copy(sentenceIndex = 1)),
            AiExplanationTarget.Sentence(current.copy(startOffset = 7, endOffset = 13)),
            AiExplanationTarget.Sentence(current.copy(rawText = "First. ", endOffset = 7)),
            AiExplanationTarget.Sentence(current.copy(rawText = "Other.", normalizedText = "Other."))
        )

        val cases = mismatchedTargets.map { current to it } +
            (sentence(index = 1, text = "Second.", start = 7) to AiExplanationTarget.Sentence(current))
        cases.forEachIndexed { index, (selection, target) ->
            statesFor(target).forEach { state ->
                assertSame("mismatch $index: ${state::class.simpleName}", AiSheetState.Hidden, state.forSelection(selection))
            }
        }
        assertSame(AiSheetState.Hidden, AiSheetState.Hidden.forSelection(current))
    }

    private fun statesFor(target: AiExplanationTarget): List<AiSheetState> = listOf(
        AiSheetState.Loading(AiSheetRequestToken(1), target),
        AiSheetState.Visible(
            attachment = AiSheetAttachment(1, AiOperationRef("key", "operation")),
            outcome = null,
            target = target
        ),
        AiSheetState.Visible(
            attachment = AiSheetAttachment(1, AiOperationRef("key", "operation")),
            outcome = AiOperationOutcome.Success("句子结果"),
            target = target
        ),
        AiSheetState.Visible(
            attachment = AiSheetAttachment(1, AiOperationRef("key", "operation")),
            outcome = AiOperationOutcome.Failure(AiError.Offline),
            target = target
        ),
        AiSheetState.Rejected(AiError.NoActiveProfile, target)
    )

    private fun sentence(index: Int, text: String, start: Int): SelectedSentence =
        SelectedSentence(
            articleId = 1,
            sentenceIndex = index,
            rawText = text,
            normalizedText = text,
            startOffset = start,
            endOffset = start + text.length
        )
}
