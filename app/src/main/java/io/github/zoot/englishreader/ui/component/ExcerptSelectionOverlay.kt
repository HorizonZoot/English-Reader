package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.math.roundToInt
import io.github.zoot.englishreader.R

/** Window-space selection geometry; handles stay in the reading window, only the toolbar is a Popup. */
@Composable
internal fun ExcerptSelectionOverlay(
    geometry: ExcerptSelectionGeometry,
    readingBounds: Rect,
    safeInsets: SentencePopupSafeInsets,
    onMove: (startHandle: Boolean, position: Offset) -> Unit,
    onNudge: (startHandle: Boolean, delta: Int) -> Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val handleRadius = with(density) { 24.dp.toPx() }
    val centers = excerptHandleCenters(geometry, readingBounds, handleRadius, with(density) { 8.dp.toPx() })
    Box(Modifier.fillMaxSize()) {
        if (!dragging) {
            val localBounds = excerptControlsBounds(geometry, centers, handleRadius).translate(-readingBounds.topLeft)
            Popup(
                popupPositionProvider = SentencePopupPositionProvider(
                    localAnchor = localBounds,
                    preferAbove = true,
                    safeInsets = safeInsets,
                    gapPx = with(density) { 8.dp.roundToPx() },
                    readingViewportBounds = readingBounds
                ),
                properties = sentencePopupProperties(SentencePopupMode.ACTIONS)
            ) {
                Surface(
                    modifier = Modifier.testTag("excerpt-actions"),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp
                ) {
                    Row {
                        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.excerpt_cancel))
                        }
                        TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp).testTag("excerpt-save")) {
                            Text(stringResource(R.string.excerpt_save))
                        }
                    }
                }
            }
        }
        ExcerptHandle(true, geometry.start, centers.first, readingBounds, onMove, onNudge, { dragging = it }, onCancel)
        ExcerptHandle(false, geometry.end, centers.second, readingBounds, onMove, onNudge, { dragging = it }, onCancel)
    }
}

@Composable
private fun ExcerptHandle(
    startHandle: Boolean,
    glyph: Rect,
    center: Offset,
    readingBounds: Rect,
    onMove: (Boolean, Offset) -> Unit,
    onNudge: (Boolean, Int) -> Boolean,
    onDragging: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val density = LocalDensity.current
    val radius = with(density) { 24.dp.toPx() }
    val focus = Offset(if (startHandle) glyph.left else glyph.right, glyph.center.y)
    val local = center - readingBounds.topLeft - Offset(radius, radius)
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(if (startHandle) R.string.excerpt_start_handle else R.string.excerpt_end_handle)
    val previous = stringResource(R.string.excerpt_handle_backward)
    val next = stringResource(R.string.excerpt_handle_forward)
    val move by rememberUpdatedState(onMove)
    val nudge by rememberUpdatedState(onNudge)
    val cancel by rememberUpdatedState(onCancel)
    val dragging by rememberUpdatedState(onDragging)
    val currentFocus by rememberUpdatedState(focus)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier.offset { IntOffset(local.x.roundToInt(), local.y.roundToInt()) }
            .size(48.dp)
            .testTag(if (startHandle) "excerpt-start-handle" else "excerpt-end-handle")
            .semantics {
                contentDescription = label
                customActions = listOf(
                    CustomAccessibilityAction(previous) { nudge(startHandle, -1) },
                    CustomAccessibilityAction(next) { nudge(startHandle, 1) }
                )
            }
            .onGloballyPositioned { coordinates = it }
            .pointerInput(startHandle) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val initialCoordinates = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val downInWindow = initialCoordinates.localToWindow(down.position)
                    val initialFocus = currentFocus
                    down.consume()
                    dragging(true)
                    var ended = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                            val currentCoordinates = coordinates?.takeIf { it.isAttached } ?: break
                            val delta = currentCoordinates.localToWindow(change.position) - downInWindow
                            change.consume()
                            if (delta != Offset.Zero) move(startHandle, initialFocus + delta)
                            if (!change.pressed) {
                                ended = true
                                break
                            }
                        }
                    } finally {
                        dragging(false)
                        if (!ended) cancel()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val knob = Offset(size.width / 2, size.height / 2)
            val tip = Offset(focus.x, glyph.bottom) - center + knob
            drawLine(color, tip, knob, strokeWidth = 2.dp.toPx())
            drawCircle(color, radius = 8.dp.toPx(), center = knob)
        }
    }
}

internal fun excerptControlsBounds(
    geometry: ExcerptSelectionGeometry,
    centers: Pair<Offset, Offset>,
    radius: Float
): Rect = Rect(
    minOf(geometry.bounds.left, centers.first.x - radius, centers.second.x - radius),
    minOf(geometry.bounds.top, centers.first.y - radius, centers.second.y - radius),
    maxOf(geometry.bounds.right, centers.first.x + radius, centers.second.x + radius),
    maxOf(geometry.bounds.bottom, centers.first.y + radius, centers.second.y + radius)
)

internal fun excerptHandleCenters(
    geometry: ExcerptSelectionGeometry,
    bounds: Rect,
    radius: Float,
    gap: Float
): Pair<Offset, Offset> {
    val left = bounds.left + radius
    val right = maxOf(left, bounds.right - radius)
    fun center(glyph: Rect, start: Boolean) = Offset(
        (if (start) glyph.left - radius else glyph.right + radius).coerceIn(left, right),
        (glyph.bottom + gap + radius).coerceIn(bounds.top + radius, maxOf(bounds.top + radius, bounds.bottom - radius))
    )
    var start = center(geometry.start, true)
    var end = center(geometry.end, false)
    val diameter = radius * 2
    if (kotlin.math.abs(start.y - end.y) < diameter && kotlin.math.abs(end.x - start.x) < diameter && right - left >= diameter) {
        val leftCenter = ((start.x + end.x - diameter) / 2).coerceIn(left, right - diameter)
        if (start.x <= end.x) {
            start = start.copy(x = leftCenter)
            end = end.copy(x = leftCenter + diameter)
        } else {
            end = end.copy(x = leftCenter)
            start = start.copy(x = leftCenter + diameter)
        }
    }
    return start to end
}
