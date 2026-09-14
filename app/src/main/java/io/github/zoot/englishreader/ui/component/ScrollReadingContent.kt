package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.util.ParagraphAligner
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

internal data class ReadingBlockKey(val paragraphIndex: Int, val kind: ReadingTextKind)

internal data class ReadingLayoutKey(
    val viewport: IntSize,
    val fontSize: FontSizeOption,
    val density: Float,
    val fontScale: Float,
    val showTranslation: Boolean
)

internal fun SnapshotStateMap<ReadingBlockKey, TextLayoutResult>.recordLayout(
    key: ReadingBlockKey,
    layout: TextLayoutResult
) {
    val previous = this[key]
    if (previous?.layoutInput != layout.layoutInput || previous.size != layout.size) this[key] = layout
}

@Composable
internal fun ScrollReadingContent(
    paragraphs: List<ParagraphAligner.AlignedParagraph>,
    blocks: List<ReadingTextBlock>,
    listState: LazyListState,
    layouts: SnapshotStateMap<ReadingBlockKey, TextLayoutResult>,
    layoutKey: ReadingLayoutKey,
    anchor: ReadingAnchor,
    selectedAnchor: ReadingAnchor?,
    target: ReadingPositionTarget?,
    enabled: Boolean,
    bottomInset: Dp,
    onRestored: (ReadingAnchor, ReadingPositionTarget?) -> Unit,
    onPositionChanged: (ReadingAnchor) -> Unit,
    onPositionSettled: (ReadingAnchor) -> Unit,
    onProgressChanged: (Float) -> Unit,
    originalContent: @Composable (Int, Boolean) -> Unit
) {
    val density = LocalDensity.current
    val contentWidth = (layoutKey.viewport.width - with(density) { 28.dp.roundToPx() } * 2)
        .coerceIn(0, with(density) { 600.dp.roundToPx() })
    val translationGap = with(density) { 8.dp.roundToPx() }
    val sourceGap = with(density) { 14.dp.roundToPx() }
    val currentAnchor by rememberUpdatedState(anchor)
    val currentSelectedAnchor by rememberUpdatedState(selectedAnchor)
    val currentTarget by rememberUpdatedState(target)
    val currentOnRestored by rememberUpdatedState(onRestored)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnPositionSettled by rememberUpdatedState(onPositionSettled)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val paragraphOffsets = remember(paragraphs) {
        paragraphs.runningFold(0) { offset, paragraph -> offset + paragraph.english.length }
    }
    val currentEnabled by rememberUpdatedState(enabled)
    var consumedRequestId by remember { mutableLongStateOf(0) }
    val requestId = target?.requestId ?: consumedRequestId
    var restored by remember(layoutKey, requestId) { mutableStateOf(false) }

    fun blockLayout(key: ReadingBlockKey): TextLayoutResult? {
        val block = blocks.firstOrNull { it.paragraphIndex == key.paragraphIndex && it.kind == key.kind } ?: return null
        return layouts[key]?.takeIf { layout ->
            layout.layoutInput.text.text == block.text && layout.layoutInput.constraints.maxWidth == contentWidth &&
                layout.layoutInput.density.density == density.density && layout.layoutInput.density.fontScale == density.fontScale &&
                layout.layoutInput.style.fontSize == block.style.fontSize && layout.layoutInput.style.lineHeight == block.style.lineHeight
        }
    }

    fun observedAnchor(): ReadingAnchor? {
        val item = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        val paragraph = (item - 1).coerceIn(0, paragraphs.lastIndex)
        val source = blockLayout(ReadingBlockKey(0, ReadingTextKind.SOURCE))
        val original = blockLayout(ReadingBlockKey(paragraph, ReadingTextKind.ORIGINAL))
        val kind: ReadingTextKind
        val localY: Int
        if (item == 0) {
            val sourceHeight = source?.size?.height ?: 0
            kind = if (source != null && offset < sourceHeight) ReadingTextKind.SOURCE else ReadingTextKind.TITLE
            localY = if (kind == ReadingTextKind.TITLE && source != null) offset - sourceHeight - sourceGap else offset
        } else {
            val originalHeight = original?.size?.height ?: return null
            val translation = blockLayout(ReadingBlockKey(paragraph, ReadingTextKind.TRANSLATION))
            kind = if (translation != null && offset >= originalHeight + translationGap) ReadingTextKind.TRANSLATION else ReadingTextKind.ORIGINAL
            localY = if (kind == ReadingTextKind.TRANSLATION) offset - originalHeight - translationGap else offset
        }
        val layout = blockLayout(ReadingBlockKey(paragraph, kind)) ?: return ReadingAnchor(paragraph)
        val line = layout.getLineForVerticalPosition(localY.coerceAtLeast(0).toFloat())
        return ReadingAnchor(paragraph, kind, layout.getLineStart(line))
    }

    LaunchedEffect(layoutKey, requestId) {
        if (contentWidth <= 0 || layoutKey.viewport.height <= 0) return@LaunchedEffect
        val restoreTarget = currentTarget
        var desired = restoreTarget?.position?.anchor ?: currentSelectedAnchor ?: currentAnchor
        if (restoreTarget?.entry == ReadingEntry.START) {
            desired = ReadingAnchor(textKind = blocks.first().kind)
        } else if (restoreTarget?.entry == ReadingEntry.END) {
            val last = blocks.last()
            desired = ReadingAnchor(last.paragraphIndex, last.kind, (last.text.length - 1).coerceAtLeast(0))
        }
        desired = desired.copy(paragraphIndex = desired.paragraphIndex.coerceAtMost(paragraphs.lastIndex))
        if (blocks.none { it.paragraphIndex == desired.paragraphIndex && it.kind == desired.textKind }) {
            desired = ReadingAnchor(desired.paragraphIndex)
        }
        val header = desired.textKind == ReadingTextKind.TITLE || desired.textKind == ReadingTextKind.SOURCE
        val item = if (header) 0 else desired.paragraphIndex + 1
        listState.scrollToItem(item)
        val key = ReadingBlockKey(desired.paragraphIndex, desired.textKind)
        val measured = snapshotFlow { blockLayout(key) }.first { it != null }!!
        val character = desired.characterOffset.coerceAtMost((measured.layoutInput.text.length - 1).coerceAtLeast(0))
        val base = when (desired.textKind) {
            ReadingTextKind.TRANSLATION -> {
                val original = snapshotFlow { blockLayout(ReadingBlockKey(desired.paragraphIndex, ReadingTextKind.ORIGINAL)) }.first { it != null }!!
                original.size.height + translationGap
            }
            ReadingTextKind.TITLE -> {
                if (blocks.any { it.kind == ReadingTextKind.SOURCE }) {
                    val source = snapshotFlow { blockLayout(ReadingBlockKey(0, ReadingTextKind.SOURCE)) }.first { it != null }!!
                    source.size.height + sourceGap
                } else 0
            }
            else -> 0
        }
        listState.scrollToItem(item, base + measured.getLineTop(measured.getLineForOffset(character)).roundToInt())
        withFrameNanos { }
        consumedRequestId = restoreTarget?.requestId ?: consumedRequestId
        val restoredAnchor = if (restoreTarget?.entry == ReadingEntry.END) observedAnchor() ?: desired
            else desired.copy(characterOffset = character)
        currentOnRestored(restoredAnchor, restoreTarget)
        restored = true
    }

    LaunchedEffect(listState, layoutKey, restored) {
        if (!restored) return@LaunchedEffect
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        var lastObservedAnchor = currentAnchor
        var moved = false
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            val endVisible = lastItem != null && lastItem.index == info.totalItemsCount - 1 &&
                lastItem.offset + lastItem.size <= info.viewportEndOffset
            val progress = scrollReadingProgress(
                paragraphOffsets = paragraphOffsets,
                anchor = observedAnchor() ?: currentAnchor,
                atStart = !listState.canScrollBackward,
                atEnd = endVisible || !listState.canScrollForward
            )
            Triple(
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
                progress
            )
        }.collect { (position, scrolling, progress) ->
            currentOnProgressChanged(progress)
            if (!currentEnabled) return@collect
            if (previous != position) {
                previous = position
                observedAnchor()?.let {
                    lastObservedAnchor = it
                    currentOnPositionChanged(it)
                }
                moved = true
            }
            if (!scrolling && moved) {
                moved = false
                currentOnPositionSettled(lastObservedAnchor)
            }
        }
    }
    DisposableEffect(restored) {
        onDispose { if (restored && currentEnabled) currentOnPositionSettled(currentAnchor) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        userScrollEnabled = enabled && restored,
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 16.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "reading-header") {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(bottom = 8.dp)) {
                blocks.filter { it.kind == ReadingTextKind.SOURCE || it.kind == ReadingTextKind.TITLE }.forEachIndexed { index, block ->
                    if (index > 0) Spacer(Modifier.height(block.gapBeforeDp.dp))
                    Text(
                        text = block.text,
                        style = block.style,
                        color = if (block.kind == ReadingTextKind.TITLE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        onTextLayout = { layouts.recordLayout(ReadingBlockKey(0, block.kind), it) }
                    )
                }
            }
        }
        itemsIndexed(paragraphs, key = { index, _ -> index }) { index, _ ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                originalContent(index, enabled && restored)
                blocks.firstOrNull { it.paragraphIndex == index && it.kind == ReadingTextKind.TRANSLATION }?.let { block ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = block.text,
                        style = block.style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                        onTextLayout = { layouts.recordLayout(ReadingBlockKey(index, block.kind), it) }
                    )
                }
            }
        }
    }
}

internal fun scrollReadingProgress(
    paragraphOffsets: List<Int>,
    anchor: ReadingAnchor,
    atStart: Boolean,
    atEnd: Boolean
): Float {
    val total = paragraphOffsets.lastOrNull() ?: return 0f
    if (total <= 0) return 0f
    if (atEnd) return 1f
    if (atStart || anchor.textKind == ReadingTextKind.TITLE || anchor.textKind == ReadingTextKind.SOURCE) {
        return 0f
    }
    val paragraph = anchor.paragraphIndex.coerceIn(0, paragraphOffsets.lastIndex - 1)
    val start = paragraphOffsets[paragraph]
    val end = paragraphOffsets[paragraph + 1]
    val offset = if (anchor.textKind == ReadingTextKind.TRANSLATION) end
        else start + anchor.characterOffset.coerceIn(0, end - start)
    return (offset.toFloat() / total).coerceIn(0f, 1f)
}
