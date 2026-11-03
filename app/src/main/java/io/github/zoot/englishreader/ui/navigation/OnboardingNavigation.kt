package io.github.zoot.englishreader.ui.navigation

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zoot.englishreader.ui.screen.OnboardingContent

internal const val ONBOARDING_REPLAY_ROUTE = "settings/onboarding"

/**
 * @param hostBackDispatcher 宿主 Activity 的返回分发器，由 NavHost 所在的组合层传入。
 *
 * 弹窗内容里的 `BackHandler` 只能拿到弹窗窗口自己的分发器（`LocalOnBackPressedDispatcherOwner`
 * 经 View 树解析，此处是弹窗 View）。返回事件经 Activity 分发时（弹窗尚未取得焦点等），
 * Navigation 2.5 由 NavHost 接管并弹出栈顶；2.7 起 NavHost 只在可组合页面栈多于一项时启用
 * 返回，叠在单个页面上的弹窗目的地不会被弹出。因此在宿主分发器上补一个回调，与
 * OnboardingScreen 对启动引导的处理一致。
 */
internal fun NavGraphBuilder.onboardingReplayDestination(
    navController: NavController,
    hostBackDispatcher: () -> OnBackPressedDispatcher?
) {
    dialog(
        ONBOARDING_REPLAY_ROUTE,
        dialogProperties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        // Replay is presentation only: no startup ViewModel and no completion/reset write.
        var pageIndex by rememberSaveable { mutableIntStateOf(0) }
        // 只弹出本条目自己：弹窗窗口与宿主两个回调可能先后收到同一次返回，按路由弹出到
        // 本弹窗（含）为止，第二次调用找不到它就什么也不做，不会把下面的设置页也弹掉。
        val close = { navController.popBackStack(ONBOARDING_REPLAY_ROUTE, inclusive = true) }
        HostBackHandler(hostBackDispatcher()) { close() }
        OnboardingContent(
            replay = true,
            onFinish = { close() },
            pageIndex = pageIndex,
            onPageChange = { pageIndex = it }
        )
    }
}

@Composable
private fun HostBackHandler(dispatcher: OnBackPressedDispatcher?, onBack: () -> Unit) {
    dispatcher ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = currentOnBack()
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}
