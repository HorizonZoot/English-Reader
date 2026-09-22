package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.ReadingPagination
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class ReadingPageControls(
    val page: Int,
    val count: Int,
    val moving: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val previous: () -> Unit,
    val next: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PagedReadingContent(
    blocks: List<ReadingTextBlock>,
    anchor: ReadingAnchor,
    selectedAnchor: ReadingAnchor?,
    target: ReadingPositionTarget?,
    enabled: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onBoundary: (Boolean) -> Unit,
    onRestored: (ReadingAnchor, ReadingPositionTarget?) -> Unit,
    onPageChanged: (ReadingAnchor) -> Unit,
    onControlsChanged: (ReadingPageControls?) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
    originalContent: @Composable (ReadingTextBlock, ReadingTextViewport, Boolean) -> Unit
) {
    val density = LocalDensity.current
    val currentOnRestored by rememberUpdatedState(onRestored)
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnBoundary by rememberUpdatedState(onBoundary)
    val currentOnControlsChanged by rememberUpdatedState(onControlsChanged)
    val currentAnchor by rememberUpdatedState(anchor)
    val currentSelectedAnchor by rememberUpdatedState(selectedAnchor)
    val currentTarget by rememberUpdatedState(target)
    var consumedRequestId by remember { mutableLongStateOf(0) }
    val requestId = target?.requestId ?: consumedRequestId
    DisposableEffect(Unit) {
        onDispose { currentOnControlsChanged(null) }
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val pageSize = IntSize(minOf(constraints.maxWidth, with(density) { 600.dp.roundToPx() }), constraints.maxHeight)
        val layout by rememberReadingPageLayout(blocks, pageSize)
        val ready = layout?.takeIf {
            it.blocks == blocks && it.size == pageSize && it.density == density.density && it.fontScale == density.fontScale
        }?.pagination
        when (ready) {
            null -> {
                SideEffect { currentOnControlsChanged(null) }
                CircularProgressIndicator()
            }
            ReadingPagination.ViewportTooSmall -> {
                SideEffect { currentOnControlsChanged(null) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reading_viewport_too_small))
                    TextButton(onClick = { onModeChange(ReadingMode.SCROLL) }) {
                        Text(stringResource(R.string.reading_mode_scroll))
                    }
                }
            }
            is ReadingPagination.Ready -> {
                if (ready.pages.isEmpty()) return@BoxWithConstraints
                val pager = rememberPagerState { ready.pages.size }
                var restored by remember(ready, requestId) { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val currentEnabled by rememberUpdatedState(enabled && restored)
                val previousChapter by rememberUpdatedState(hasPreviousChapter)
                val nextChapter by rememberUpdatedState(hasNextChapter)
                val previous = remember(pager, scope) {
                    {
                        if (currentEnabled && !pager.isScrollInProgress) {
                            if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                            else if (previousChapter) currentOnBoundary(false)
                        }
                        Unit
                    }
                }
                val nextPage = remember(pager, scope) {
                    {
                        if (currentEnabled && !pager.isScrollInProgress) {
                            if (pager.currentPage < pager.pageCount - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                            else if (nextChapter) currentOnBoundary(true)
                        }
                        Unit
                    }
                }
                LaunchedEffect(ready, requestId) {
                    val restoreTarget = currentTarget
                    val restoreAnchor = if (restoreTarget != null) restoreTarget.position.anchor
                        else currentSelectedAnchor ?: currentAnchor
                    val page = when (restoreTarget?.entry) {
                        ReadingEntry.START -> 0
                        ReadingEntry.END -> ready.pages.lastIndex
                        else -> ready.pageFor(restoreAnchor)
                    }
                    pager.scrollToPage(page)
                    withFrameNanos { }
                    consumedRequestId = restoreTarget?.requestId ?: consumedRequestId
                    val restoredAnchor = when (restoreTarget?.entry) {
                        ReadingEntry.START, ReadingEntry.END -> ready.pages[page].anchor
                        else -> if (blocks.any { it.kind == restoreAnchor.textKind && it.paragraphIndex == restoreAnchor.paragraphIndex }) {
                            restoreAnchor
                        } else ready.pages[page].anchor
                    }
                    currentOnRestored(restoredAnchor, restoreTarget)
                    restored = true
                }
                LaunchedEffect(pager, ready, restored) {
                    if (!restored) return@LaunchedEffect
                    var lastPage = pager.settledPage
                    snapshotFlow { pager.settledPage }.collect { page ->
                        if (page != lastPage) {
                            lastPage = page
                            currentOnPageChanged(ready.pages[page].anchor)
                        }
                    }
                }
                val canPrevious = enabled && restored && !pager.isScrollInProgress &&
                    (pager.currentPage > 0 || hasPreviousChapter)
                val canNext = enabled && restored && !pager.isScrollInProgress &&
                    (pager.currentPage < ready.pages.lastIndex || hasNextChapter)
                SideEffect {
                    currentOnControlsChanged(
                        ReadingPageControls(pager.settledPage, ready.pages.size, pager.isScrollInProgress,
                            canPrevious, canNext, previous, nextPage)
                    )
                }
                val previousLabel = stringResource(R.string.reading_page_previous)
                val nextLabel = stringResource(R.string.reading_page_next)
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = enabled && restored,
                    flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1)),
                    modifier = Modifier
                        .width(with(density) { pageSize.width.toDp() })
                        .fillMaxHeight()
                        .testTag("reading-pages")
                        .pageBoundaryGesture(pager, enabled && restored) { forward ->
                            if (forward && nextChapter || !forward && previousChapter) currentOnBoundary(forward)
                        }
                        .semantics {
                            customActions = buildList {
                                if (canPrevious) add(CustomAccessibilityAction(previousLabel) { previous(); true })
                                if (canNext) add(CustomAccessibilityAction(nextLabel) { nextPage(); true })
                            }
                        }
                ) { pageIndex ->
                    val active = pageIndex == pager.settledPage && !pager.isScrollInProgress && enabled && restored
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().then(
                            if (pageIndex != pager.settledPage) Modifier.clearAndSetSemantics { invisibleToUser() }
                            else Modifier
                        ),
                        userScrollEnabled = false
                    ) {
                        items(ready.pages[pageIndex].fragments, key = { "${it.blockIndex}:${it.anchor.characterOffset}" }) { fragment ->
                            if (fragment.gapBefore > 0) Spacer(Modifier.height(with(density) { fragment.gapBefore.toDp() }))
                            val viewport = ReadingTextViewport(fragment.localStartOffset, fragment.localEndOffset, fragment.top, fragment.height)
                            val block = blocks[fragment.blockIndex]
                            if (block.kind == ReadingTextKind.ORIGINAL) {
                                originalContent(block, viewport, active)
                            } else {
                                Text(
                                    text = block.text,
                                    style = block.style,
                                    color = if (block.kind == ReadingTextKind.TITLE) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                        .clearAndSetSemantics {
                                            text = AnnotatedString(block.text.substring(viewport.startOffset, viewport.endOffset))
                                        }
                                        .readingTextViewport(viewport)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 只观察从章节边界开始的横滑；章内拖动和吸附仍由 Pager 处理。 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pageBoundaryGesture(
    pager: PagerState,
    enabled: Boolean,
    onBoundary: (Boolean) -> Unit
): Modifier = pointerInput(pager, enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startPage = pager.currentPage
        if (pager.isScrollInProgress || startPage != 0 && startPage != pager.pageCount - 1) return@awaitEachGesture
        var dragging = false
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - down.position
            if (!dragging && change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) cancelled = true
            if (event.changes.any { it.id != down.id && it.pressed }) cancelled = true
            if (!dragging && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) cancelled = true
            if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y)) dragging = true
            if (change.isConsumed && !dragging) cancelled = true
            if (!change.pressed) {
                val threshold = minOf(size.width * 0.2f, 56.dp.toPx())
                if (!cancelled && dragging && abs(delta.x) >= threshold && pager.currentPage == startPage) {
                    if (delta.x < 0 && startPage == pager.pageCount - 1) onBoundary(true)
                    if (delta.x > 0 && startPage == 0) onBoundary(false)
                }
                break
            }
        }
    }
}
