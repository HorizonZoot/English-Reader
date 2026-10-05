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
import io.github.zoot.englishreader.data.dictionary.DictionaryPackRemovalResult
import io.github.zoot.englishreader.ui.theme.SettingsUiTheme
import io.github.zoot.englishreader.viewmodel.SettingsViewModel

@Composable
fun SettingsDictionaryScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.dictionaryPackState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.refreshDictionaryPackState()
        viewModel.dictionaryPackRemovalEvents.collect { result ->
            val message = when (result) {
                DictionaryPackRemovalResult.REMOVED -> R.string.settings_dict_pack_removed
                DictionaryPackRemovalResult.FAILED -> R.string.settings_dict_pack_remove_failed
                DictionaryPackRemovalResult.BUSY -> return@collect
            }
            snackbar.showSnackbar(context.getString(message))
        }
    }
    SettingsUiTheme {
        SettingsSubpage(stringResource(R.string.settings_dict_pack_title), onBack, snackbar) {
            SettingsFootnote(stringResource(R.string.settings_dict_pack_confirm_message))
            SettingsGroup(stringResource(R.string.settings_dict_pack_title)) {
                DictionaryPackRow(state, viewModel::installDictionaryPack,
                    viewModel::cancelDictionaryPackInstall, viewModel::removeDictionaryPack)
            }
        }
    }
}
