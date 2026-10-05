package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.model.TtsSystemAction
import io.github.zoot.englishreader.ui.component.LocalVoiceModelsSection
import io.github.zoot.englishreader.ui.component.messageRes
import io.github.zoot.englishreader.ui.theme.SettingsUiTheme
import io.github.zoot.englishreader.util.TtsCapability
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import io.github.zoot.englishreader.viewmodel.TtsModelsViewModel

@Composable
fun SettingsSpeechScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    modelsViewModel: TtsModelsViewModel = hiltViewModel()
) {
    val states by modelsViewModel.states.collectAsStateWithLifecycle()
    val capability by viewModel.ttsCapability.collectAsStateWithLifecycle()
    val allowNetwork by viewModel.allowNetworkTts.collectAsStateWithLifecycle()
    val saving by viewModel.savingTtsPreference.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshTtsCapability()
                Lifecycle.Event.ON_STOP -> viewModel.releaseTts()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releaseTts()
        }
    }
    LaunchedEffect(modelsViewModel) {
        modelsViewModel.refresh()
        modelsViewModel.events.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.ttsPreferenceErrors.collect {
            snackbar.showSnackbar(context.getString(R.string.tts_preference_save_failed))
        }
    }
    SettingsUiTheme {
        SettingsSubpage(stringResource(R.string.settings_tts_title), onBack, snackbar) {
            LocalVoiceModelsSection(states, modelsViewModel::install, modelsViewModel::cancel, modelsViewModel::remove)
            TtsSettingsSection(
                capability, allowNetwork, saving,
                viewModel::setAllowNetworkTts, viewModel::refreshTtsCapability, viewModel::openTtsSystemAction
            )
        }
    }
}

@Composable
internal fun TtsSettingsSection(
    capability: TtsCapability,
    allowNetwork: Boolean,
    saving: Boolean,
    onAllowNetworkChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSystemAction: (TtsSystemAction) -> Unit
) {
    SettingsGroup(stringResource(R.string.settings_network_voice)) {
        ListItem(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .testTag("settings-network-tts")
                .toggleable(value = allowNetwork, enabled = !saving, role = Role.Switch, onValueChange = onAllowNetworkChange),
            headlineContent = { Text(stringResource(R.string.settings_allow_network_tts), style = MaterialTheme.typography.bodyLarge) },
            trailingContent = { Switch(checked = allowNetwork, onCheckedChange = null, enabled = !saving) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
    SettingsFootnote(stringResource(R.string.tts_network_disclosure))
    SettingsGroup(stringResource(R.string.settings_troubleshooting)) {
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
            title = stringResource(R.string.tts_recheck), onClick = onRefresh,
            testTag = "settings-tts-refresh"
        )
        SettingsDivider()
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            title = stringResource(R.string.tts_system_settings),
            onClick = { onSystemAction(TtsSystemAction.OPEN_SETTINGS) }, testTag = "settings-tts-system"
        )
        SettingsDivider()
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            title = stringResource(R.string.tts_install_data),
            onClick = { onSystemAction(TtsSystemAction.INSTALL_DATA) }, testTag = "settings-tts-install"
        )
    }
    SettingsFootnote(stringResource(capability.messageRes()), Modifier.testTag("settings-tts-status"))
}
