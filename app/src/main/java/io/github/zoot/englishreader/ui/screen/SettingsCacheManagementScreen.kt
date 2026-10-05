package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.ui.theme.SettingsUiTheme
import io.github.zoot.englishreader.viewmodel.SettingsViewModel

@Composable
fun SettingsCacheManagementScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val stats by viewModel.pronunciationCacheStats.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refreshPronunciationCacheStats() }
    LaunchedEffect(viewModel) {
        viewModel.profileActionEvents.collect { event ->
            snackbar.showSnackbar(context.getString(event.action.settingsMessageRes()))
        }
    }
    SettingsUiTheme {
        SettingsSubpage(stringResource(R.string.settings_cache_management), onBack, snackbar) {
            SettingsFootnote(stringResource(R.string.settings_cache_management_summary))
            SettingsCacheManagementContent(
                pronunciationCacheStats = stats,
                onClearPronunciationCache = viewModel::clearPronunciationCache,
                onClearCache = viewModel::clearAiExplanationCache,
                onRetryStorage = viewModel::reinitializeCredentialStorage,
                onClearCredentials = viewModel::clearAllCredentials
            )
        }
    }
}
