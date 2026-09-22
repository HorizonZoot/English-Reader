package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

internal data class ReadingBlockKey(
    val paragraphIndex: Int,
    val kind: ReadingTextKind,
    val textStartOffset: Int = 0
)

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
    originalContent: @Composable (ReadingTextBlock, Boolean) -> Unit
) {
    val density = LocalDensity.current
    val contentWidth = (layoutKey.viewport.width - with(density) { 28.dp.roundToPx() } * 2)
        .coerceIn(0, with(density) { 600.dp.roundToPx() })
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
    var restored by remember(layoutKey, blocks, requestId) { mutableStateOf(false) }

    fun gap(index: Int): Int = if (index == 0) 0 else with(density) { blocks[index].gapBeforeDp.dp.roundToPx() }

    fun blockLayout(block: ReadingTextBlock): TextLayoutResult? = layouts[block.key]?.takeIf { layout ->
        layout.layoutInput.text.text == block.text && layout.layoutInput.constraints.maxWidth == contentWidth &&
            layout.layoutInput.density.density == density.density && layout.layoutInput.density.fontScale == density.fontScale &&
            layout.layoutInput.style.fontSize == block.style.fontSize && layout.layoutInput.style.lineHeight == block.style.lineHeight
    }

    fun observedAnchor(): ReadingAnchor? {
        val item = listState.firstVisibleItemIndex
        val block = blocks.getOrNull(item) ?: return null
        val layout = blockLayout(block) ?: return null
        val localY = (listState.firstVisibleItemScrollOffset - gap(item)).coerceAtLeast(0)
        val line = layout.getLineForVerticalPosition(localY.toFloat())
        return ReadingAnchor(block.paragraphIndex, block.kind, block.textStartOffset + layout.getLineStart(line))
    }

    LaunchedEffect(layoutKey, blocks, requestId) {
        if (contentWidth <= 0 || layoutKey.viewport.height <= 0 || blocks.isEmpty()) return@LaunchedEffect
        val restoreTarget = currentTarget
        var desired = restoreTarget?.position?.anchor ?: currentSelectedAnchor ?: currentAnchor
        if (restoreTarget?.entry == ReadingEntry.START) {
            val first = blocks.first()
            desired = ReadingAnchor(first.paragraphIndex, first.kind, first.textStartOffset)
        } else if (restoreTarget?.entry == ReadingEntry.END) {
            val last = blocks.last()
            desired = ReadingAnchor(last.paragraphIndex, last.kind, (last.textEndOffset - 1).coerceAtLeast(last.textStartOffset))
        }
        desired = desired.copy(paragraphIndex = desired.paragraphIndex.coerceAtMost(paragraphs.lastIndex))
        val block = blocks.blockFor(desired) ?: blocks.blockFor(ReadingAnchor(desired.paragraphIndex)) ?: blocks.first()
        val item = blocks.indexOf(block)
        val character = (desired.characterOffset - block.textStartOffset).coerceIn(0, (block.text.length - 1).coerceAtLeast(0))
        listState.scrollToItem(item)
        val measured = snapshotFlow { blockLayout(block) }.first { it != null }!!
        listState.scrollToItem(item, gap(item) + measured.getLineTop(measured.getLineForOffset(character)).roundToInt())
        withFrameNanos { }
        consumedRequestId = restoreTarget?.requestId ?: consumedRequestId
        val restoredAnchor = ReadingAnchor(block.paragraphIndex, block.kind, block.textStartOffset + character)
        currentOnRestored(if (restoreTarget?.entry == ReadingEntry.END) observedAnchor() ?: restoredAnchor else restoredAnchor, restoreTarget)
        restored = true
    }

    LaunchedEffect(listState, layoutKey, blocks, restored) {
        if (!restored) return@LaunchedEffect
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        var lastObservedAnchor = currentAnchor
        var moved = false
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            val endVisible = lastItem != null && lastItem.index == info.totalItemsCount - 1 &&
                lastItem.offset + lastItem.size <= info.viewportEndOffset
            val observed = observedAnchor() ?: currentAnchor
            val progressAnchor = if (observed.textKind == ReadingTextKind.TRANSLATION) {
                blocks.blockFor(observed)?.let { ReadingAnchor(it.paragraphIndex, ReadingTextKind.ORIGINAL, it.sourceEndOffset) } ?: observed
            } else observed
            Triple(
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
                scrollReadingProgress(paragraphOffsets, progressAnchor, !listState.canScrollBackward, endVisible || !listState.canScrollForward)
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(blocks, key = { _, block -> "${block.kind}:${block.paragraphIndex}:${block.textStartOffset}" }) { index, block ->
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = if (index == 0) 0.dp else block.gapBeforeDp.dp)) {
                if (block.kind == ReadingTextKind.ORIGINAL) {
                    originalContent(block, enabled && restored)
                } else {
                    Text(
                        text = block.text,
                        style = block.style,
                        color = if (block.kind == ReadingTextKind.TITLE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        onTextLayout = { layouts.recordLayout(block.key, it) }
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
    if (atStart || anchor.textKind == ReadingTextKind.TITLE || anchor.textKind == ReadingTextKind.SOURCE) return 0f
    val paragraph = anchor.paragraphIndex.coerceIn(0, paragraphOffsets.lastIndex - 1)
    val start = paragraphOffsets[paragraph]
    val end = paragraphOffsets[paragraph + 1]
    val offset = if (anchor.textKind == ReadingTextKind.TRANSLATION) end
        else start + anchor.characterOffset.coerceIn(0, end - start)
    return (offset.toFloat() / total).coerceIn(0f, 1f)
}
