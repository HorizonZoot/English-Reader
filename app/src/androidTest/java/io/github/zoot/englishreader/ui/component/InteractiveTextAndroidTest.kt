package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.ui.clickGlyph
import io.github.zoot.englishreader.ui.textLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InteractiveTextAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun customActions_sentenceActionTriggersMatchingCallback() {
        var clickedSentenceIndex: Int? = null
        var clickedRange: SentenceRange? = null

        composeRule.setContent {
            InteractiveText(
                text = "First sentence. Second sentence.",
                fontSize = 16.sp,
                onSentenceClick = { index, range ->
                    clickedSentenceIndex = index
                    clickedRange = range
                },
                onWordLongPress = {},
            )
        }

        performCustomAction("Highlight sentence 2")

        composeRule.runOnIdle {
            assertEquals(1, clickedSentenceIndex)
            assertEquals("Second sentence.", clickedRange?.text)
            assertEquals(16, clickedRange?.startOffset)
            assertEquals(32, clickedRange?.endOffset)
        }
    }

    @Test
    fun customActions_wordActionsAreUniqueLimitedAndTriggerCallback() {
        val extractedWords = mutableListOf<String>()
        val text = (1..20).joinToString(" ") { "Alpha." } +
            " " + (1..20).joinToString(" ") { "word$it" }

        composeRule.setContent {
            InteractiveText(
                text = text,
                fontSize = 16.sp,
                onSentenceClick = { _, _ -> },
                onWordLongPress = { extractedWords += it },
            )
        }

        val actionLabels = customActionLabels()
        val sentenceActionLabels = actionLabels.filter { it.startsWith("Highlight sentence ") }
        val wordActionLabels = actionLabels.filter { it.startsWith("Extract word ") }
        assertEquals(32, actionLabels.size)
        assertEquals(16, sentenceActionLabels.size)
        assertEquals(16, wordActionLabels.size)
        assertTrue(sentenceActionLabels.contains("Highlight sentence 16"))
        assertFalse(sentenceActionLabels.contains("Highlight sentence 17"))
        assertTrue(wordActionLabels.contains("Extract word word15"))
        assertFalse(wordActionLabels.contains("Extract word word16"))

        performCustomAction("Extract word word1")

        composeRule.runOnIdle {
            assertEquals(listOf("word1"), extractedWords)
        }
    }

    @Test
    fun tap_actualSentenceGlyph_triggersSentenceOnly() {
        var clickedSentenceIndex: Int? = null
        var clickedRange: SentenceRange? = null
        var clearCount = 0

        composeRule.setContent {
            InteractiveText(
                text = "Alpha sentence.",
                fontSize = 16.sp,
                modifier = Modifier.width(300.dp),
                onSentenceClick = { index, range ->
                    clickedSentenceIndex = index
                    clickedRange = range
                },
                onWordLongPress = {},
                onClearSelection = { clearCount++ },
            )
        }

        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        node.clickGlyph(0)

        composeRule.runOnIdle {
            assertEquals(0, clickedSentenceIndex)
            assertEquals("Alpha sentence.", clickedRange?.text)
            assertEquals(0, clickedRange?.startOffset)
            assertEquals(15, clickedRange?.endOffset)
            assertEquals(0, clearCount)
        }
    }

    @Test
    fun tap_trailingWhitespace_clearsWithoutSentenceCallback() {
        var clickedSentenceIndex: Int? = null
        var clearCount = 0

        composeRule.setContent {
            InteractiveText(
                text = "Alpha sentence.",
                fontSize = 16.sp,
                modifier = Modifier.width(300.dp),
                onSentenceClick = { index, _ -> clickedSentenceIndex = index },
                onWordLongPress = {},
                onClearSelection = { clearCount++ },
            )
        }

        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        val layout = node.textLayoutResult()
        val trailingWhitespaceX = (layout.getLineRight(0) + layout.size.width) / 2f
        node.performTouchInput {
            click(Offset(trailingWhitespaceX, layout.getBoundingBox(0).center.y))
        }

        composeRule.runOnIdle {
            assertEquals(null, clickedSentenceIndex)
            assertEquals(1, clearCount)
        }
    }

    @Test
    fun longPress_actualGlyph_returnsWord() {
        var heldTarget: InteractiveTextLongPressTarget? = null
        var releasedTarget: InteractiveTextLongPressTarget? = null

        composeRule.setContent {
            InteractiveText(
                text = "Alpha beta",
                fontSize = 16.sp,
                modifier = Modifier.width(300.dp),
                onSentenceClick = { _, _ -> },
                onWordLongPress = {},
                sentenceIndexOffset = 4,
                onWordPressStart = { heldTarget = it },
                onWordPressRelease = { releasedTarget = it },
            )
        }

        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        val glyph = node.textLayoutResult().getBoundingBox(0).center
        node.performTouchInput {
            longClick(glyph)
        }

        composeRule.runOnIdle {
            assertEquals(4, heldTarget?.sentenceIndex)
            assertEquals("Alpha beta", heldTarget?.sentenceRange?.text)
            assertEquals(0, heldTarget?.sentenceRange?.startOffset)
            assertEquals(10, heldTarget?.sentenceRange?.endOffset)
            assertEquals("Alpha", heldTarget?.word)
            assertEquals(heldTarget, releasedTarget)
            assertTrue(requireNotNull(heldTarget).anchorBounds.width > 0f)
            assertTrue(requireNotNull(heldTarget).anchorBounds.height > 0f)
            assertTrue(requireNotNull(heldTarget).sentenceBounds.contains(heldTarget!!.anchorBounds.center))
        }
    }

    @Test
    fun longPress_multilineSentence_targetBoundsCoverEveryRenderedLine() {
        var target: InteractiveTextLongPressTarget? = null

        composeRule.setContent {
            InteractiveText(
                text = "A sentence that wraps across several rendered lines before it ends.",
                fontSize = 16.sp,
                modifier = Modifier.width(90.dp),
                onSentenceClick = { _, _ -> },
                onWordLongPress = {},
                onWordPressStart = { target = it }
            )
        }

        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        val glyph = node.textLayoutResult().getBoundingBox(0).center
        node.performTouchInput { longClick(glyph) }

        composeRule.runOnIdle {
            val actual = requireNotNull(target)
            assertTrue(actual.sentenceBounds.height > actual.anchorBounds.height)
            assertTrue(actual.sentenceBounds.top <= actual.anchorBounds.top)
            assertTrue(actual.sentenceBounds.bottom >= actual.anchorBounds.bottom)
        }
    }

    @Test
    fun longPress_trailingAndMultilineBlankSpace_returnsNoWord() {
        val extractedWords = mutableListOf<String>()
        val targets = mutableListOf<InteractiveTextLongPressTarget>()
        val body = "Alpha.\n\nBeta."

        composeRule.setContent {
            InteractiveText(
                text = body,
                fontSize = 16.sp,
                modifier = Modifier.width(300.dp),
                precomputedSentences = listOf(
                    SentenceRange(0, "Alpha.\n\n", 0, 8),
                    SentenceRange(1, "Beta.", 8, 13)
                ),
                onSentenceClick = { _, _ -> },
                onWordLongPress = { extractedWords += it },
                onWordPressStart = { targets += it },
            )
        }

        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        val layout = node.textLayoutResult()
        assertEquals(body, layout.layoutInput.text.text)
        assertEquals(3, layout.lineCount)
        assertEquals("The second rendered line must really be blank",
            layout.getLineStart(1), layout.getLineEnd(1, visibleEnd = true))
        val trailingWhitespaceX = (layout.getLineRight(0) + layout.size.width) / 2f
        val blankLineY = (layout.getLineTop(1) + layout.getLineBottom(1)) / 2f
        node.performTouchInput {
            longClick(Offset(trailingWhitespaceX, layout.getBoundingBox(0).center.y))
            longClick(Offset(layout.getBoundingBox(0).center.x, blankLineY))
        }

        composeRule.runOnIdle {
            assertTrue(extractedWords.isEmpty())
            assertTrue(targets.isEmpty())
        }
    }

    @Test
    fun customActions_playTranslateAndExplain_useDedicatedCallbacks() {
        var playTarget: InteractiveTextLongPressTarget? = null
        var translateTarget: InteractiveTextLongPressTarget? = null
        val explanationTargets = mutableListOf<InteractiveTextLongPressTarget>()
        composeRule.setContent {
            InteractiveText(
                text = "Alpha sentence.",
                fontSize = 16.sp,
                highlightedSentenceIndex = 0,
                onSentenceClick = { _, _ -> },
                onWordLongPress = {},
                onSentencePlay = { playTarget = it },
                onSentenceTranslate = { translateTarget = it },
                onSentenceExplain = { explanationTargets += it }
            )
        }

        performCustomAction("播放句子语音")
        performCustomAction("翻译句子")
        performCustomAction("解释句子")

        composeRule.runOnIdle {
            assertEquals(0, playTarget?.sentenceIndex)
            assertEquals("Alpha sentence.", playTarget?.sentenceRange?.text)
            assertTrue(requireNotNull(playTarget).anchorBounds.width > 0f)
            assertEquals(playTarget, translateTarget)
            assertEquals(listOf(playTarget), explanationTargets)
        }
    }

    @Test
    fun customActions_threeSentenceActions_keepTotalWithinPlatformLimit() {
        composeRule.setContent {
            InteractiveText(
                text = "Alpha. ".repeat(20) + (1..20).joinToString(" ") { "word$it" },
                fontSize = 16.sp,
                highlightedSentenceIndex = 0,
                onSentenceClick = { _, _ -> },
                onWordLongPress = {},
                onSentencePlay = {},
                onSentenceTranslate = {},
                onSentenceExplain = {}
            )
        }

        val labels = customActionLabels()
        assertEquals(32, labels.size)
        assertEquals(labels.size, labels.distinct().size)
        assertTrue(labels.containsAll(listOf("播放句子语音", "翻译句子", "解释句子")))
        assertTrue(labels.any { it.startsWith("Highlight sentence ") })
        assertTrue(labels.any { it.startsWith("Extract word ") })
    }

    @Test
    fun selectedGlyph_fontReflow_refreshesBoundsWithoutSelectingAgain() {
        val body = "A sentence with several distinct words that wraps after a font change."
        val glyphOffset = body.indexOf("distinct")
        val fontSize = mutableStateOf(16.sp)
        val selected = mutableStateOf<InteractiveTextLongPressTarget?>(null)
        var selections = 0
        var geometryUpdates = 0
        composeRule.setContent {
            InteractiveText(
                text = body,
                fontSize = fontSize.value,
                modifier = Modifier.width(180.dp),
                onSentenceClick = { _, _ -> selections++ },
                onWordLongPress = {},
                onSentenceTapTarget = { selected.value = it },
                selectedSentenceTarget = selected.value,
                onSentenceTargetLayoutChanged = {
                    geometryUpdates++
                    selected.value = it
                }
            )
        }

        val node = composeRule.onNodeWithContentDescription("Interactive reading text")
        node.clickGlyph(glyphOffset)
        val initial = composeRule.runOnIdle { requireNotNull(selected.value) }
        composeRule.runOnIdle { fontSize.value = 22.sp }
        val newLayout = node.textLayoutResult()

        composeRule.runOnIdle {
            val updated = requireNotNull(selected.value)
            assertEquals(1, selections)
            assertTrue(geometryUpdates > 0)
            assertEquals(glyphOffset, updated.glyphOffset)
            assertEquals(initial.sentenceRange, updated.sentenceRange)
            assertTrue(initial.anchorBounds != updated.anchorBounds)
            assertEquals(newLayout.getBoundingBox(glyphOffset), updated.anchorBounds)
            assertEquals(newLayout.getLineBottom(newLayout.lineCount - 1),
                updated.sentenceBounds.bottom, 0.5f)
        }
    }

    private fun customActionLabels(): List<String> {
        val actions = composeRule
            .onNodeWithContentDescription("Interactive reading text")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)

        return actions.orEmpty().map { it.label }
    }

    private fun performCustomAction(label: String) {
        val action = composeRule
            .onNodeWithContentDescription("Interactive reading text")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            ?.firstOrNull { it.label == label }

        assertNotNull("Missing custom action: $label", action)
        composeRule.runOnIdle {
            assertTrue(action!!.action())
        }
    }
}
