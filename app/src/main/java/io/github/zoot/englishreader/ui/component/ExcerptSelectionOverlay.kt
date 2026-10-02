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
import androidx.compose.ui.geometry.Size
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
    val touchRadius = with(density) { 24.dp.toPx() }
    val knobRadius = with(density) { 7.dp.toPx() }
    val start = excerptHandleGeometry(geometry.start, true, readingBounds, touchRadius, knobRadius)
    val end = excerptHandleGeometry(geometry.end, false, readingBounds, touchRadius, knobRadius)
    Box(Modifier.fillMaxSize()) {
        if (!dragging) {
            val localBounds = excerptControlsBounds(geometry, start, end).translate(-readingBounds.topLeft)
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
        ExcerptHandle(start, end, readingBounds, onMove, onNudge, { dragging = it }, onCancel)
        ExcerptHandle(end, start, readingBounds, onMove, onNudge, { dragging = it }, onCancel)
    }
}

@Composable
private fun ExcerptHandle(
    handle: ExcerptHandleGeometry,
    other: ExcerptHandleGeometry,
    readingBounds: Rect,
    onMove: (Boolean, Offset) -> Unit,
    onNudge: (Boolean, Int) -> Boolean,
    onDragging: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val startHandle = handle.startHandle
    val local = handle.touchBounds.topLeft - readingBounds.topLeft
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(if (startHandle) R.string.excerpt_start_handle else R.string.excerpt_end_handle)
    val previous = stringResource(R.string.excerpt_handle_backward)
    val next = stringResource(R.string.excerpt_handle_forward)
    val move by rememberUpdatedState(onMove)
    val nudge by rememberUpdatedState(onNudge)
    val cancel by rememberUpdatedState(onCancel)
    val dragging by rememberUpdatedState(onDragging)
    val currentHandle by rememberUpdatedState(handle)
    val otherHandle by rememberUpdatedState(other)
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
                    // Overlapping 48dp targets dispatch to the closest visible knob, not the top sibling.
                    val selectedHandle = excerptHandleAt(downInWindow, currentHandle, otherHandle)
                    val initialFocus = Offset(selectedHandle.tip.x, selectedHandle.glyph.center.y)
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
                            if (delta != Offset.Zero) move(selectedHandle.startHandle, initialFocus + delta)
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
            val knob = handle.knob.translate(-handle.touchBounds.topLeft)
            val tip = handle.tip - handle.touchBounds.topLeft
            drawCircle(color, radius = knob.width / 2, center = knob.center)
            drawRect(
                color,
                topLeft = Offset(minOf(tip.x, knob.center.x), minOf(tip.y, knob.center.y)),
                size = Size(knob.width / 2, knob.height / 2)
            )
        }
    }
}

internal data class ExcerptHandleGeometry(
    val startHandle: Boolean,
    val glyph: Rect,
    val knob: Rect,
    val touchBounds: Rect
) {
    val tip: Offset
        get() = Offset(
            if (startHandle) glyph.left else glyph.right,
            if (knob.bottom <= glyph.top) glyph.top else glyph.bottom
        )
}

internal fun excerptControlsBounds(
    geometry: ExcerptSelectionGeometry,
    start: ExcerptHandleGeometry,
    end: ExcerptHandleGeometry
): Rect = Rect(
    minOf(geometry.bounds.left, start.touchBounds.left, end.touchBounds.left),
    minOf(geometry.bounds.top, start.touchBounds.top, end.touchBounds.top),
    maxOf(geometry.bounds.right, start.touchBounds.right, end.touchBounds.right),
    maxOf(geometry.bounds.bottom, start.touchBounds.bottom, end.touchBounds.bottom)
)

internal fun excerptHandleGeometry(
    glyph: Rect,
    startHandle: Boolean,
    bounds: Rect,
    touchRadius: Float,
    knobRadius: Float
): ExcerptHandleGeometry {
    val diameter = knobRadius * 2
    val x = if (startHandle) glyph.left else glyph.right
    val extendsLeft = if (startHandle) x - diameter >= bounds.left else x + diameter > bounds.right
    val left = if (extendsLeft) x - diameter else x
    val top = if (glyph.bottom + diameter <= bounds.bottom) glyph.bottom else glyph.top - diameter
    val knob = Rect(left, top, left + diameter, top + diameter)
    val center = Offset(
        knob.center.x.coerceIn(bounds.left + touchRadius, maxOf(bounds.left + touchRadius, bounds.right - touchRadius)),
        knob.center.y.coerceIn(bounds.top + touchRadius, maxOf(bounds.top + touchRadius, bounds.bottom - touchRadius))
    )
    return ExcerptHandleGeometry(
        startHandle, glyph, knob,
        Rect(center - Offset(touchRadius, touchRadius), Size(touchRadius * 2, touchRadius * 2))
    )
}

internal fun excerptHandleAt(
    position: Offset,
    handle: ExcerptHandleGeometry,
    other: ExcerptHandleGeometry
): ExcerptHandleGeometry = if (
    other.touchBounds.contains(position) &&
    (position - other.knob.center).getDistanceSquared() < (position - handle.knob.center).getDistanceSquared()
) other else handle
