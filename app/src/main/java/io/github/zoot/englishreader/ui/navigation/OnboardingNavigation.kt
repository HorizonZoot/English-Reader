package io.github.zoot.englishreader.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.zoot.englishreader.ui.screen.OnboardingScreen

internal const val ONBOARDING_REPLAY_ROUTE = "settings/onboarding"

internal fun NavGraphBuilder.onboardingReplayDestination(navController: NavController) {
    composable(ONBOARDING_REPLAY_ROUTE) {
        // Replay is presentation only: no startup ViewModel and no completion/reset write.
        OnboardingScreen(replay = true, onFinish = { navController.popBackStack() })
    }
}
