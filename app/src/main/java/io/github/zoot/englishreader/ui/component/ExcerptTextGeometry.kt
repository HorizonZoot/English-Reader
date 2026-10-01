package io.github.zoot.englishreader.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import io.github.zoot.englishreader.core.ExcerptRange

/** Only mounted, interactive original blocks participate; never translation or off-page layouts. */
internal data class ExcerptTextGeometry(
    val paragraphIndex: Int,
    val sourceStartOffset: Int,
    val layout: TextLayoutResult,
    val coordinates: LayoutCoordinates,
    val viewport: ReadingTextViewport?
)

private fun ExcerptTextGeometry.visibleBounds(readingBounds: Rect): Rect? {
    if (!coordinates.isAttached) return null
    val origin = coordinates.localToWindow(Offset.Zero)
    val top = viewport?.top ?: 0
    val height = viewport?.height ?: layout.size.height
    return Rect(origin.x, origin.y + top, origin.x + layout.size.width, origin.y + top + height)
        .intersect(readingBounds).takeIf { it.width > 0f && it.height > 0f }
}

internal fun ExcerptTextGeometry.visibleExcerptRange(readingBounds: Rect): ExcerptRange? {
    val bounds = visibleBounds(readingBounds) ?: return null
    val top = coordinates.windowToLocal(bounds.topLeft).y
    val bottom = coordinates.windowToLocal(bounds.bottomRight).y - 0.01f
    val start = maxOf(viewport?.startOffset ?: 0, layout.getLineStart(layout.getLineForVerticalPosition(top)))
    val end = minOf(viewport?.endOffset ?: layout.layoutInput.text.length,
        layout.getLineEnd(layout.getLineForVerticalPosition(bottom)))
    return if (end > start) ExcerptRange(sourceStartOffset + start, sourceStartOffset + end) else null
}

internal fun excerptFocusOffset(
    paragraphIndex: Int,
    position: Offset,
    readingBounds: Rect,
    geometries: Collection<ExcerptTextGeometry>
): Int? {
    val candidates = geometries.filter { it.paragraphIndex == paragraphIndex && it.coordinates.isAttached }
        .mapNotNull { geometry -> geometry.visibleBounds(readingBounds)?.let { geometry to it } }
    val (geometry, bounds) = candidates.minByOrNull { (_, bounds) ->
        val x = position.x.coerceIn(bounds.left, bounds.right)
        val y = position.y.coerceIn(bounds.top, bounds.bottom)
        (position - Offset(x, y)).getDistanceSquared()
    } ?: return null
    val point = Offset(position.x.coerceIn(bounds.left, bounds.right - 0.01f),
        position.y.coerceIn(bounds.top, bounds.bottom - 0.01f))
    val local = geometry.coordinates.windowToLocal(point)
    val start = geometry.viewport?.startOffset ?: 0
    val end = geometry.viewport?.endOffset ?: geometry.layout.layoutInput.text.length
    if (end <= start) return null
    val caret = geometry.layout.getOffsetForPosition(local).coerceIn(start, end - 1)
    val offset = if (caret > start && geometry.layout.getBoundingBox(caret - 1).contains(local)) caret - 1 else caret
    return geometry.sourceStartOffset + offset
}
