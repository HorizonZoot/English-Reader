package io.github.zoot.englishreader.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

data class ReadingTextViewport(
    val startOffset: Int,
    val endOffset: Int,
    val top: Int,
    val height: Int
) {
    fun contains(offset: Int): Boolean = offset in startOffset until endOffset

    fun toVisibleBounds(bounds: Rect): Rect = bounds.translate(Offset(0f, -top.toFloat()))
}

/** 保持整段测量与命中坐标，仅裁切当前页可见的完整行。 */
internal fun Modifier.readingTextViewport(viewport: ReadingTextViewport?): Modifier =
    if (viewport == null) this else clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        layout(placeable.width, viewport.height) {
            placeable.placeRelative(0, -viewport.top)
        }
    }

internal fun readingOriginalTextStyle(
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    color: Color
): TextStyle = TextStyle(
    fontSize = fontSize,
    fontFamily = fontFamily,
    color = color,
    textAlign = TextAlign.Start,
    lineHeight = fontSize * 1.6f
)
