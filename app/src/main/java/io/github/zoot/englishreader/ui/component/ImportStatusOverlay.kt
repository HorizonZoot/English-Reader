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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

/** 导入的终态。只区分成功与失败——具体原因仍由既有的 Snackbar 文案承担。 */
enum class ImportOutcome { SUCCESS, FAILURE }

/**
 * 导入状态指示：一段细线圆弧 + 一行状态文字，导入结束时化为 ✓ / ×。
 *
 * ## 为什么只有一行「正在导入…」，没有阶段
 *
 * `ArticleListViewModel.isImporting` 是布尔量，没有阶段信息。要做「读取 → 解析 → 保存」
 * 三段文字，必须让 `BookImporter` / `EpubBookParser` 回吐进度——那是核心解析逻辑，不在
 * 本次范围。所以这里固定显示一行，不为动画去改业务。
 *
 * ## 出现阈值
 *
 * [isImporting] 为 TXT / Markdown / 粘贴导入所共用，而那几条路是毫秒级的：立即显示会让
 * 覆盖层「闪」一下，比没有动画更廉价。因此导入持续超过 [APPEAR_DELAY_MS] 才淡入，快速导入
 * 在指示器出现之前就已结束，用户完全看不到它。这道阈值只推迟**指示器自己的出现**，不推迟
 * 导入、也不推迟既有的完成流程（Snackbar 仍在事件到达的瞬间弹出）。
 *
 * ## 为什么状态机只用一个 LaunchedEffect
 *
 * 出现阈值与终态收尾若各用一个 effect，二者会在同一帧内竞争：导入成功时
 * `isImporting` 转 false 与终态事件几乎同时到达，先跑的那个会把后跑的结论覆盖掉
 * （按声明顺序，「导入结束」会先把指示器收掉，✓ 再也没机会出现）。合并成一个带优先级的
 * effect 后，终态优先于「导入结束」，顺序不再依赖声明位置。
 *
 * 同时它必须处理**取消路径**：`importFromFile` 对 `CancellationException` 原样上抛、
 * **不发终态事件**（用户中途离开页面就是这条路）。只靠终态事件收尾会留下一个永远空转的
 * 指示器，所以 `else` 分支在「没在导入且无终态」时无条件收掉。
 *
 * @param outcome 终态闩锁；由调用方在既有 `uiEvent` collector 里置位，播完后通过
 *   [onOutcomeShown] 清除。刻意不在本组件内部订阅 `uiEvent`：那会是第二个 collector，
 *   而 `ArticleListScreen` 的注释明确警告过它会放大 Snackbar 的顺序竞争。
 */
@Composable
fun ImportStatusOverlay(
    isImporting: Boolean,
    outcome: ImportOutcome?,
    onOutcomeShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    /**
     * 当前渲染的内容，**与 [visible] 分离**。
     *
     * 若让内容跟着可见性回落，退场那 140ms 里 ✓ 会变回旋转圆弧——`AnimatedVisibility`
     * 在退场期间仍然组合它的 content，那时 stage 已经回到隐藏态。分离之后淡出的始终是
     * 刚才看到的那个字形。
     */
    var shown by remember { mutableStateOf<Shown>(Shown.Loading) }

    /**
     * 本轮导入已出过终态。
     *
     * `importBook` 先 `trySend` 终态事件、再在 `finally` 里落下 `isImporting`，所以存在
     * 一个「终态已到、导入态还没落」的窗口。没有这道闩锁时，播完 ✓ 后 `onOutcomeShown`
     * 会把 outcome 清空，effect 随即以「仍在导入」重启，250ms 后又淡入一个转圈——
     * 用户看到的是 ✓ 之后闪一下 loading。
     */
    var settled by remember { mutableStateOf(false) }

    LaunchedEffect(isImporting, outcome) {
        when {
            // 终态优先：此时 isImporting 往往已经是 false，若让下面的分支先跑就没有 ✓ 了。
            outcome != null -> {
                // 指示器没真的出现过（快速导入）就不要凭空弹一个 ✓——那正是要避免的闪现。
                // visible 为真必然意味着 shown 是 Loading：终态分支收尾时会把它置回 false。
                if (visible) {
                    shown = Shown.Terminal(outcome)
                    delay(TERMINAL_HOLD_MS)
                    visible = false
                }
                settled = true
                onOutcomeShown()
            }

            isImporting -> {
                if (!settled) {
                    delay(APPEAR_DELAY_MS)
                    shown = Shown.Loading
                    visible = true
                }
            }

            // 导入结束：取消路径（不发终态事件）也走这里，不能留一个空转的指示器。
            // 同时解锁 settled，让下一次导入能正常显示。
            else -> {
                visible = false
                settled = false
            }
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
