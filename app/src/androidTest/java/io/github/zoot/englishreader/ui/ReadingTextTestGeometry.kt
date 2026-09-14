package io.github.zoot.englishreader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue

internal fun SemanticsNodeInteraction.textLayoutResult(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults ->
        assertTrue("Text must expose its measured layout", getResults(results))
    }
    return results.single()
}

internal fun SemanticsNodeInteraction.clickGlyph(offset: Int) {
    val layout = textLayoutResult()
    assertTrue("Tap must target a visible glyph", !layout.layoutInput.text[offset].isWhitespace())
    val node = fetchSemanticsNode()
    val glyphInRoot = node.positionInRoot + layout.getBoundingBox(offset).center
    assertTrue("Target glyph must be inside the visible text bounds", node.boundsInRoot.contains(glyphInRoot))
    val touchPosition = glyphInRoot - node.boundsInRoot.topLeft
    performTouchInput { click(touchPosition) }
}

/** Popup and Activity have separate roots; translate both through their Android view. */
internal fun SemanticsNode.positionOnScreen(): Offset {
    val location = IntArray(2)
    (root as ViewRootForTest).view.getLocationOnScreen(location)
    return positionInRoot + Offset(location[0].toFloat(), location[1].toFloat())
}

/** Use the full layout size, so ancestor clipping cannot hide an overflowing Popup. */
internal fun SemanticsNode.boundsOnScreen(): Rect {
    val origin = positionOnScreen()
    return Rect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
}
