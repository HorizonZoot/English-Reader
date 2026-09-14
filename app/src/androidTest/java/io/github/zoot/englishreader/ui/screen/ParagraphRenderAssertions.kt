package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue

internal const val PARAGRAPH_BENCHMARK_TAG = "paragraph-benchmark-text"

@Composable
internal fun ParagraphBenchmarkViewport(content: @Composable () -> Unit) {
    // Like a ReadingScreen lazy item, measure the whole paragraph beyond the viewport height.
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        content()
    }
}

internal fun ComposeContentTestRule.assertCompleteParagraph(expectedText: String, label: String) {
    require(expectedText.isNotEmpty())
    val nodes = onAllNodesWithTag(PARAGRAPH_BENCHMARK_TAG, useUnmergedTree = true)
        .fetchSemanticsNodes()
    assertTrue("$label: expected exactly one paragraph node", nodes.size == 1)

    val node = nodes.single()
    val texts = node.config.getOrNull(SemanticsProperties.Text)
    assertTrue("$label: semantic text mismatch", texts?.map { it.text } == listOf(expectedText))

    val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
    val layouts = mutableListOf<TextLayoutResult>()
    val returnedLayout = runOnIdle { getLayout?.invoke(layouts) == true }
    assertTrue("$label: missing text layout", returnedLayout && layouts.size == 1)

    val layout = layouts.single()
    assertTrue("$label: layout input mismatch", layout.layoutInput.text.text == expectedText)
    assertTrue(
        "$label: empty text layout",
        layout.size.width > 0 && layout.size.height > 0 && layout.lineCount > 0
    )
    assertTrue(
        "$label: text layout overflow",
        !layout.hasVisualOverflow && (0 until layout.lineCount).none(layout::isLineEllipsized)
    )

    val lastLine = layout.lineCount - 1
    assertTrue(
        "$label: final character not laid out",
        layout.getLineEnd(lastLine) == expectedText.length &&
            layout.getLineForOffset(expectedText.lastIndex) == lastLine
    )
    val finalCharacter = layout.getBoundingBox(expectedText.lastIndex)
    assertTrue(
        "$label: final character outside layout bounds",
        finalCharacter.width > 0f && finalCharacter.height > 0f &&
            finalCharacter.left >= 0f && finalCharacter.top >= 0f &&
            finalCharacter.right <= layout.size.width && finalCharacter.bottom <= layout.size.height
    )
}
