package io.github.zoot.englishreader.ui.component

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.viewmodel.ImportOutcome
import io.github.zoot.englishreader.viewmodel.ImportState
import kotlinx.coroutines.delay

/**
 * 按 importId 隔离出现阈值与终态动画，不依赖观察到两次导入之间的 Idle。
 * 只有本次实际显示过 Loading 才播放终态；首次看到 Finished 时静默确认。
 * Snackbar 独立消费 VM 的提示队列，不参与这里的业务收尾。
 */
@Composable
fun ImportStatusOverlay(
    state: ImportState,
    onFinishedShown: (String) -> Unit,
    modifier: Modifier = Modifier
) = key(state.importId) {
    var visible by remember { mutableStateOf(false) }

    /**
     * 当前渲染的内容，**与 [visible] 分离**。
     *
     * 若让内容跟着可见性回落，退场那 140ms 里 ✓ 会变回旋转圆弧——`AnimatedVisibility`
     * 在退场期间仍然组合它的 content，那时 stage 已经回到隐藏态。分离之后淡出的始终是
     * 刚才看到的那个字形。
     */
    var shown by remember { mutableStateOf<Shown>(Shown.Loading) }
    val acknowledge by rememberUpdatedState(onFinishedShown)

    LaunchedEffect(state) {
        when (state) {
            is ImportState.Finished -> {
                if (visible) {
                    shown = Shown.Terminal(state.outcome)
                    delay(TERMINAL_HOLD_MS)
                    visible = false
                    delay(EXIT_MS.toLong())
                }
                acknowledge(state.importId)
            }

            is ImportState.Running -> {
                delay(APPEAR_DELAY_MS)
                shown = Shown.Loading
                visible = true
            }

            ImportState.Idle -> visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ENTER_MS, easing = CalmEasing)),
        exit = fadeOut(tween(EXIT_MS, easing = CalmEasing)),
        modifier = modifier
    ) {
        val rendered = shown
        val label = stringResource(
            when (rendered) {
                is Shown.Terminal -> when (rendered.outcome) {
                    ImportOutcome.SUCCESS -> R.string.import_status_succeeded
                    ImportOutcome.FAILURE -> R.string.import_status_failed
                }

                Shown.Loading -> R.string.import_status_importing
            }
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .testTag(IMPORT_STATUS_TEST_TAG)
                // liveRegion：导入是后台过程，TalkBack 用户不会主动去摸屏幕中央。
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = label
                }
        ) {
            Crossfade(
                targetState = (rendered as? Shown.Terminal)?.outcome,
                animationSpec = tween(GLYPH_MS, easing = CalmEasing),
                label = "import-glyph"
            ) { terminal ->
                if (terminal == null) {
                    ThinArcIndicator(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(INDICATOR_SIZE)
                    )
                } else {
                    TerminalGlyph(
                        outcome = terminal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(INDICATOR_SIZE)
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 细线圆弧，匀速自转。
 *
 * [LinearEasing] 是关键：任何 ease-in/out 都会让转速周期性忽快忽慢，读起来就是「一个
 * loading 控件在转」，而不是「系统在工作」。
 *
 * 系统关闭动画时（开发者选项或无障碍设置把 animator duration scale 调成 0）渲染静态圆弧：
 * 那个开关的用意就是不要有持续运动，而状态文字已经把「正在导入」说清楚了。
 */
@Composable
private fun ThinArcIndicator(color: Color, modifier: Modifier) {
    val context = LocalContext.current
    val animated = remember(context) { context.animatorDurationScale() > 0f }
    val angle = if (animated) {
        val spin = rememberInfiniteTransition(label = "import-arc")
        val value by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(ROTATION_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "import-arc-angle"
        )
        value
    } else 0f

    Canvas(modifier) {
        val stroke = STROKE_WIDTH.toPx()
        val box = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = TRACK_ALPHA),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = style
        )
        drawArc(
            color = color.copy(alpha = ARC_ALPHA),
            // -90 让弧的起点落在 12 点方向，与系统指示器的视觉起点一致。
            startAngle = angle - 90f,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = style
        )
    }
}

/**
 * 终态字形：极轻的 fade + scale，无 overshoot。
 *
 * fade 由外层 [Crossfade] 负责，这里**只做 scale**：两处都调 alpha 会相乘，✓ 会比设计的
 * 更暗、更慢地浮现。scale 只从 [GLYPH_START_SCALE] 到 1，肉眼几乎察觉不到缩放本身，
 * 只觉得它「落定」了。用 tween + 三次贝塞尔而非 spring：spring 的回弹正是要避免的 bounce。
 */
@Composable
private fun TerminalGlyph(outcome: ImportOutcome, color: Color, modifier: Modifier) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(outcome) { settled = true }
    val progress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(GLYPH_MS, easing = CalmEasing),
        label = "import-glyph-settle"
    )

    Icon(
        imageVector = when (outcome) {
            ImportOutcome.SUCCESS -> Icons.Rounded.Check
            ImportOutcome.FAILURE -> Icons.Rounded.Close
        },
        contentDescription = null,
        tint = color,
        modifier = modifier
            .size(GLYPH_SIZE)
            .graphicsLayer {
                val scale = GLYPH_START_SCALE + (1f - GLYPH_START_SCALE) * progress
                scaleX = scale
                scaleY = scale
            }
    )
}

/**
 * 指示器当前渲染的东西。
 *
 * 没有 `Hidden`：可见性由 `visible` 单独表达，二者分离正是为了让退场动画淡出的是
 * 刚才那个字形，而不是回落成圆弧。
 */
private sealed interface Shown {
    data object Loading : Shown
    data class Terminal(val outcome: ImportOutcome) : Shown
}

/**
 * 系统动画时长倍数。0 表示用户要求关闭动画。
 *
 * 读不到时按 1 处理（开着动画）：这个设置缺失属于常态而非用户表态，不该因此静默剥掉动画。
 */
private fun Context.animatorDurationScale(): Float = runCatching {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

/** 覆盖层根节点的 testTag，供 UI 测试定位。 */
const val IMPORT_STATUS_TEST_TAG = "import_status_overlay"

private val INDICATOR_SIZE = 28.dp
private val GLYPH_SIZE = 20.dp
private val STROKE_WIDTH = 1.5.dp
private const val ARC_SWEEP = 90f
private const val TRACK_ALPHA = 0.12f
private const val ARC_ALPHA = 0.7f
private const val GLYPH_START_SCALE = 0.92f

/** 平稳的对称缓动，两端都不过冲。 */
private val CalmEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

/** 一圈 1.1s：足够慢，读起来是「在工作」而不是「在转」。 */
private const val ROTATION_PERIOD_MS = 1100

/** 快速导入（TXT / 粘贴）在此之前就结束了，指示器因此完全不出现。 */
private const val APPEAR_DELAY_MS = 250L

/**
 * 终态字形出现后到开始淡出之间的时长。
 *
 * **与 [GLYPH_MS] 并发，不是串行**：`shown = Terminal` 与这个 `delay` 在同一瞬间启动，
 * 所以字形在 t=[GLYPH_MS] 落定，此处到点后才开始 [EXIT_MS] 的淡出：
 *
 * ```
 * t=0    字形落定(160) ┐同时
 *        停留计时(240) ┘
 * t=160  字形完全落定
 * t=240  开始淡出(140)
 * t=380  消失
 * ```
 *
 * 因此整段收尾是 `TERMINAL_HOLD_MS + EXIT_MS = 380ms`，不是三段相加的 540ms。
 * 本值**不得小于** [GLYPH_MS]，否则淡出会在字形还没成形时就开始，✓ 会「一边出现一边消失」。
 */
private const val TERMINAL_HOLD_MS = 240L
private const val ENTER_MS = 200
private const val EXIT_MS = 140
private const val GLYPH_MS = 160
