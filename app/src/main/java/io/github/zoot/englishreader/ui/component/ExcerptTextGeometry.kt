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
    geometries: Collection<ExcerptTextGeometry>,
    caret: Boolean = false
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
    val visible = geometry.visibleExcerptRange(readingBounds) ?: return null
    val start = visible.startOffset - geometry.sourceStartOffset
    val end = visible.endOffset - geometry.sourceStartOffset
    val offset = geometry.layout.getOffsetForPosition(local).coerceIn(start, if (caret) end else end - 1)
    if (caret) return geometry.sourceStartOffset + offset
    val glyph = if (offset > start && geometry.layout.getBoundingBox(offset - 1).contains(local)) offset - 1 else offset
    return geometry.sourceStartOffset + glyph
}

internal data class ExcerptSelectionGeometry(val start: Rect, val end: Rect, val bounds: Rect)

internal fun excerptSelectionGeometry(
    paragraphIndex: Int,
    range: ExcerptRange,
    readingBounds: Rect,
    geometries: Collection<ExcerptTextGeometry>
): ExcerptSelectionGeometry? {
    val candidates = geometries.filter { it.paragraphIndex == paragraphIndex && it.coordinates.isAttached }
    fun endpoint(offset: Int): Rect? {
        val geometry = candidates.firstOrNull {
            val visible = it.visibleExcerptRange(readingBounds)
            visible != null && offset in visible.startOffset until visible.endOffset
        } ?: return null
        return geometry.layout.getBoundingBox(offset - geometry.sourceStartOffset)
            .translate(geometry.coordinates.localToWindow(Offset.Zero)).intersect(readingBounds)
    }
    val start = endpoint(range.startOffset) ?: return null
    val end = endpoint(range.endOffset - 1) ?: return null
    val rects = candidates.flatMap { geometry ->
        val bounds = geometry.visibleBounds(readingBounds) ?: return@flatMap emptyList()
        textRangeBackgroundRects(geometry.layout, range.startOffset - geometry.sourceStartOffset,
            range.endOffset - geometry.sourceStartOffset).map {
            it.translate(geometry.coordinates.localToWindow(Offset.Zero)).intersect(bounds)
        }.filter { it.width > 0 && it.height > 0 }
    }
    if (rects.isEmpty()) return null
    return ExcerptSelectionGeometry(start, end, Rect(rects.minOf { it.left }, rects.minOf { it.top },
        rects.maxOf { it.right }, rects.maxOf { it.bottom }))
}
