package io.github.zoot.englishreader.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * 全 App 统一的界面过渡时长。
 *
 * 只约束「状态切换」类动画：页面转场、淡入淡出、颜色/位移/旋转的状态过渡、翻页与列表重排。
 * 不适用于语义上的停留或周期（导入圆弧一圈、段落高亮停留、短导入不显示指示器的阈值），
 * 那些数值表达的是时间本身而不是过渡快慢，压短会改变含义。
 */
object Motion {
    const val DURATION_MS = 100

    fun <T> spec(): FiniteAnimationSpec<T> = tween(DURATION_MS)

    val enter: EnterTransition = fadeIn(tween(DURATION_MS))
    val exit: ExitTransition = fadeOut(tween(DURATION_MS))
}
