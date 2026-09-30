package io.github.zoot.englishreader.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zoot.englishreader.ui.screen.OnboardingContent

internal const val ONBOARDING_REPLAY_ROUTE = "settings/onboarding"

internal fun NavGraphBuilder.onboardingReplayDestination(navController: NavController) {
    dialog(
        ONBOARDING_REPLAY_ROUTE,
        dialogProperties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        // Replay is presentation only: no startup ViewModel and no completion/reset write.
        var pageIndex by rememberSaveable { mutableIntStateOf(0) }
        OnboardingContent(
            replay = true,
            onFinish = { navController.popBackStack() },
            pageIndex = pageIndex,
            onPageChange = { pageIndex = it }
        )
    }
}
