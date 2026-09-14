package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.ui.component.InteractiveText
import io.github.zoot.englishreader.util.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class ParagraphRenderAssertionAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val expectedText =
        "The evening light fell across the narrow street and the shutters were closed. "
            .repeat(181).take(14_052) + "."

    @Test
    fun assertCompleteParagraph_fullLongInteractiveText_acceptsLayout() {
        showInteractiveText(expectedText)

        composeRule.assertCompleteParagraph(expectedText, CONTROL_LABEL)
    }

    @Test
    fun assertCompleteParagraph_absentParagraph_rejectsMissingNode() {
        composeRule.setContent {
            ParagraphBenchmarkViewport {
                BasicText("Control host without a paragraph")
            }
        }
        composeRule.waitForIdle()

        assertRejected("expected exactly one paragraph node")
    }

    @Test
    fun assertCompleteParagraph_wrongTextWithSamePrefixAndLength_rejectsSemanticText() {
        showInteractiveText(expectedText.dropLast(1) + "?")

        assertRejected("semantic text mismatch")
    }

    @Test
    fun assertCompleteParagraph_textTruncatedAtBudget_rejectsSemanticText() {
        showInteractiveText(expectedText.take(ImportBudget.MAX_PARAGRAPH_CHARS))

        assertRejected("semantic text mismatch")
    }

    @Test
    fun assertCompleteParagraph_fullTextWithMaxLines_rejectsLayoutOverflow() {
        composeRule.setContent {
            ParagraphBenchmarkViewport {
                BasicText(
                    text = expectedText,
                    modifier = Modifier.fillMaxWidth().testTag(PARAGRAPH_BENCHMARK_TAG),
                    style = TextStyle(fontSize = 16.sp, lineHeight = 25.6.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        composeRule.waitForIdle()

        assertRejected("text layout overflow")
    }

    private fun showInteractiveText(text: String) {
        val sentences = SentenceSplitter.split(text)
        composeRule.setContent {
            ParagraphBenchmarkViewport {
                InteractiveText(
                    text = text,
                    fontSize = 16.sp,
                    precomputedSentences = sentences,
                    modifier = Modifier.fillMaxWidth().testTag(PARAGRAPH_BENCHMARK_TAG),
                    onSentenceClick = { _, _ -> },
                    onWordLongPress = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertRejected(reason: String) {
        val failure = assertThrows(AssertionError::class.java) {
            composeRule.assertCompleteParagraph(expectedText, CONTROL_LABEL)
        }
        assertEquals("$CONTROL_LABEL: $reason", failure.message)
    }

    private companion object {
        const val CONTROL_LABEL = "paragraph assertion control"
    }
}
