package io.github.zoot.englishreader.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.model.AiSheetState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** [InteractiveText] 发出的字形级 UI 目标，不进入 AI 输入或缓存身份。 */
@Immutable
data class InteractiveTextLongPressTarget(
    val sentenceIndex: Int,
    val sentenceRange: SentenceRange,
    val word: String?,
    val anchorBounds: Rect,
    val sentenceBounds: Rect,
    val wordStartOffset: Int? = null,
    val wordEndOffset: Int? = null,
    /** 段落局部字形位置，用于字体和窗口变化后的重新测量。 */
    val glyphOffset: Int? = null
) {
    override fun toString(): String =
        "InteractiveTextLongPressTarget(sentenceIndex=$sentenceIndex, word=[REDACTED], bounds=[REDACTED])"
}

data class SentenceActionAnchor(
    val paragraphIndex: Int,
    val target: InteractiveTextLongPressTarget
)

enum class SentencePopupMode { ACTIONS, TRANSLATION, EXPLANATION }

internal const val SENTENCE_POPUP_FADE_IN_DURATION_MS = 120
internal const val SENTENCE_POPUP_FADE_OUT_DURATION_MS = 90
internal const val SENTENCE_POPUP_CONTENT_CROSSFADE_DURATION_MS = 120

/** 工具栏保留外部指针透传；结果窗口持有焦点并响应外点和 Back 关闭。 */
internal fun sentencePopupProperties(mode: SentencePopupMode): PopupProperties =
    PopupProperties(
        focusable = mode != SentencePopupMode.ACTIONS,
        dismissOnClickOutside = mode != SentencePopupMode.ACTIONS
    )

internal class SentencePopupDismissGate {
    private var acquired = false

    val isAcquired: Boolean
        get() = acquired

    fun tryAcquire(): Boolean {
        if (acquired) return false
        acquired = true
        return true
    }
}

data class SentencePopupSafeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/** 阅读视口排除了应用顶底栏，再与系统安全区域相交。 */
internal fun sentencePopupSafeBounds(
    windowSize: IntSize,
    safeInsets: SentencePopupSafeInsets,
    readingViewportBounds: Rect?
): IntRect {
    val left = safeInsets.left.coerceIn(0, windowSize.width)
    val top = safeInsets.top.coerceIn(0, windowSize.height)
    val right = (windowSize.width - safeInsets.right).coerceIn(left, windowSize.width)
    val bottom = (windowSize.height - safeInsets.bottom).coerceIn(top, windowSize.height)
    val viewport = readingViewportBounds ?: return IntRect(left, top, right, bottom)
    val safeLeft = viewport.left.roundToInt().coerceIn(left, right)
    val safeTop = viewport.top.roundToInt().coerceIn(top, bottom)
    return IntRect(
        left = safeLeft,
        top = safeTop,
        right = viewport.right.roundToInt().coerceIn(safeLeft, right),
        bottom = viewport.bottom.roundToInt().coerceIn(safeTop, bottom)
    )
}

class SentencePopupPositionProvider(
    private val localAnchor: Rect,
    private val localSentenceBounds: Rect = localAnchor,
    private val preferAbove: Boolean = false,
    private val safeInsets: SentencePopupSafeInsets = SentencePopupSafeInsets(),
    private val gapPx: Int = 8,
    private val readingViewportBounds: Rect? = null
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val anchorLeft = anchorBounds.left + localAnchor.left.roundToInt()
        val anchorTop = anchorBounds.top + localSentenceBounds.top.roundToInt()
        val anchorRight = anchorBounds.left + localAnchor.right.roundToInt()
        val anchorBottom = anchorBounds.top + localSentenceBounds.bottom.roundToInt()
        val safeBounds = sentencePopupSafeBounds(windowSize, safeInsets, readingViewportBounds)
        val minX = safeBounds.left
        val maxX = (safeBounds.right - popupContentSize.width).coerceAtLeast(minX)
        val x = (anchorLeft + (anchorRight - anchorLeft - popupContentSize.width) / 2)
            .coerceIn(minX, maxX)

        val minY = safeBounds.top
        val maxY = (safeBounds.bottom - popupContentSize.height).coerceAtLeast(minY)
        val above = anchorTop - popupContentSize.height - gapPx
        val below = anchorBottom + gapPx
        val aboveFits = above in minY..maxY
        val belowFits = below in minY..maxY
        val y = when {
            preferAbove && aboveFits -> above
            !preferAbove && belowFits -> below
            belowFits -> below
            aboveFits -> above
            else -> (if (preferAbove) above else below).coerceIn(minY, maxY)
        }
        return IntOffset(x, y)
    }
}

@Composable
fun SentenceActionPopup(
    target: InteractiveTextLongPressTarget,
    mode: SentencePopupMode,
    translationState: AiSheetState,
    onPlay: () -> Unit,
    onTranslate: () -> Unit,
    onCancelTranslation: () -> Unit,
    onRetryTranslation: () -> Unit,
    onDismiss: () -> Unit,
    onPopupSizeChanged: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
    explanationState: AiSheetState = AiSheetState.Hidden,
    onExplain: () -> Unit = {},
    onCancelExplanation: () -> Unit = {},
    onWholeTranslation: (() -> Unit)? = null,
    readingViewportBounds: Rect? = null
) {
    key(
        target.sentenceIndex,
        target.sentenceRange
    ) {
        SentenceActionPopupForTarget(
            target = target,
            mode = mode,
            translationState = translationState,
            explanationState = explanationState,
            onPlay = onPlay,
            onTranslate = onTranslate,
            onExplain = onExplain,
            onWholeTranslation = onWholeTranslation,
            onCancelTranslation = onCancelTranslation,
            onCancelExplanation = onCancelExplanation,
            onRetryTranslation = onRetryTranslation,
            onDismiss = onDismiss,
            onPopupSizeChanged = onPopupSizeChanged,
            readingViewportBounds = readingViewportBounds,
            modifier = modifier
        )
    }
}

@Composable
private fun SentenceActionPopupForTarget(
    target: InteractiveTextLongPressTarget,
    mode: SentencePopupMode,
    translationState: AiSheetState,
    explanationState: AiSheetState,
    onPlay: () -> Unit,
    onTranslate: () -> Unit,
    onExplain: () -> Unit,
    onWholeTranslation: (() -> Unit)?,
    onCancelTranslation: () -> Unit,
    onCancelExplanation: () -> Unit,
    onRetryTranslation: () -> Unit,
    onDismiss: () -> Unit,
    onPopupSizeChanged: (IntSize) -> Unit,
    readingViewportBounds: Rect?,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val popupAlpha = remember { Animatable(0f) }
    val dismissGate = remember { SentencePopupDismissGate() }
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnTranslate by rememberUpdatedState(onTranslate)
    val currentOnExplain by rememberUpdatedState(onExplain)
    val currentOnWholeTranslation by rememberUpdatedState(onWholeTranslation)
    val currentOnCancelTranslation by rememberUpdatedState(onCancelTranslation)
    val currentOnCancelExplanation by rememberUpdatedState(onCancelExplanation)
    val currentOnRetryTranslation by rememberUpdatedState(onRetryTranslation)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val safeInsets = SentencePopupSafeInsets(
        left = WindowInsets.safeDrawing.getLeft(density, layoutDirection),
        top = WindowInsets.safeDrawing.getTop(density),
        right = WindowInsets.safeDrawing.getRight(density, layoutDirection),
        bottom = WindowInsets.safeDrawing.getBottom(density)
    )
    val popupPaneTitle = stringResource(
        when (mode) {
            SentencePopupMode.ACTIONS -> R.string.reading_sentence_actions_title
            SentencePopupMode.TRANSLATION -> R.string.reading_sentence_translation_title
            SentencePopupMode.EXPLANATION -> R.string.reading_sentence_explanation_title
        }
    )

    LaunchedEffect(Unit) {
        popupAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(SENTENCE_POPUP_FADE_IN_DURATION_MS)
        )
    }

    fun requestFadeOut(
        onFadeStarted: () -> Unit = {},
        onFadeFinished: () -> Unit = currentOnDismiss
    ) {
        if (!dismissGate.tryAcquire()) return
        onFadeStarted()
        coroutineScope.launch {
            popupAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(SENTENCE_POPUP_FADE_OUT_DURATION_MS)
            )
            onFadeFinished()
        }
    }

    Popup(
        popupPositionProvider = SentencePopupPositionProvider(
            localAnchor = target.anchorBounds,
            localSentenceBounds = target.sentenceBounds,
            preferAbove = false,
            safeInsets = safeInsets,
            gapPx = with(density) { 8.dp.roundToPx() },
            readingViewportBounds = readingViewportBounds
        ),
        onDismissRequest = {
            if (mode != SentencePopupMode.ACTIONS) requestFadeOut()
        },
        properties = sentencePopupProperties(mode)
    ) {
        BoxWithConstraints {
            val safeBounds = sentencePopupSafeBounds(
                windowSize = IntSize(constraints.maxWidth, constraints.maxHeight),
                safeInsets = safeInsets,
                readingViewportBounds = readingViewportBounds
            )
            val maxPopupWidth = minOf(360.dp, with(density) { safeBounds.width.toDp() })
            val maxPopupHeight = with(density) { safeBounds.height.toDp() }
            Box(
                modifier = modifier
                    .widthIn(max = maxPopupWidth)
                    .heightIn(max = maxPopupHeight)
                    .graphicsLayer { alpha = popupAlpha.value }
                    .onSizeChanged(onPopupSizeChanged)
                    .testTag("sentence-action-popup")
                    .semantics { paneTitle = popupPaneTitle }
            ) {
                Crossfade(
                    targetState = mode,
                    animationSpec = tween(SENTENCE_POPUP_CONTENT_CROSSFADE_DURATION_MS),
                    label = "sentence-popup-content"
                ) { visibleMode ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        when (visibleMode) {
                            SentencePopupMode.ACTIONS -> ActionContent(
                                onPlay = { if (!dismissGate.isAcquired) currentOnPlay() },
                                onTranslate = { if (!dismissGate.isAcquired) currentOnTranslate() },
                                onExplain = { if (!dismissGate.isAcquired) currentOnExplain() },
                                onWholeTranslation = currentOnWholeTranslation?.let { action ->
                                    { if (!dismissGate.isAcquired) action() }
                                }
                            )
                            SentencePopupMode.TRANSLATION, SentencePopupMode.EXPLANATION ->
                                SentenceAiResultContent(
                                    mode = visibleMode,
                                    state = if (visibleMode == SentencePopupMode.TRANSLATION) {
                                        translationState
                                    } else {
                                        explanationState
                                    },
                                    onCancel = {
                                        requestFadeOut(
                                            onFadeStarted = if (visibleMode == SentencePopupMode.TRANSLATION) {
                                                currentOnCancelTranslation
                                            } else {
                                                currentOnCancelExplanation
                                            }
                                        )
                                    },
                                    onRetry = {
                                        if (!dismissGate.isAcquired) currentOnRetryTranslation()
                                    },
                                    onDismiss = { requestFadeOut() }
                                )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionContent(
    onPlay: () -> Unit,
    onTranslate: () -> Unit,
    onExplain: () -> Unit,
    onWholeTranslation: (() -> Unit)?
) {
    // 「更多」展开后在同一 FlowRow 内追加二级动作，不弹嵌套菜单：ACTIONS 模式的 Popup
    // 不持焦点且指针透传（见 sentencePopupProperties），嵌套 Popup 会破坏这个约定。
    var expanded by remember { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PopupAction(
            icon = Icons.Default.PlayArrow,
            label = stringResource(R.string.reading_sentence_play),
            contentDescription = stringResource(R.string.reading_sentence_play_content_description),
            onClick = onPlay
        )
        PopupAction(
            icon = Icons.Default.Translate,
            label = stringResource(R.string.reading_sentence_translate),
            contentDescription = stringResource(R.string.reading_sentence_translate_content_description),
            onClick = onTranslate
        )
        PopupAction(
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            label = stringResource(R.string.reading_sentence_explain),
            contentDescription = stringResource(R.string.reading_sentence_explain_content_description),
            onClick = onExplain
        )
        if (onWholeTranslation != null) {
            if (!expanded) {
                PopupAction(
                    icon = Icons.Default.MoreHoriz,
                    label = stringResource(R.string.reading_sentence_more),
                    contentDescription = stringResource(R.string.reading_sentence_more_content_description),
                    onClick = { expanded = true },
                    testTag = "sentence-action-more"
                )
            } else {
                PopupAction(
                    icon = Icons.Default.GTranslate,
                    label = stringResource(R.string.reading_sentence_whole_translation),
                    contentDescription = stringResource(R.string.reading_sentence_whole_translation_content_description),
                    onClick = onWholeTranslation,
                    testTag = "sentence-action-whole-translation"
                )
            }
        }
    }
}

@Composable
private fun PopupAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Icon(icon, contentDescription = contentDescription)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
