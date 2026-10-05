package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zoot.englishreader.BuildConfig
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.dictionary.DictionaryPackFailure
import io.github.zoot.englishreader.data.dictionary.DictionaryPackState
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.ui.theme.SettingsUiTheme
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import io.github.zoot.englishreader.viewmodel.TtsModelsViewModel

@Composable
fun SettingsScreen(
    onOpenAiProfile: () -> Unit,
    onOpenCacheManagement: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenSpeech: () -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    modelsViewModel: TtsModelsViewModel = hiltViewModel()
) {
    val fontSize by viewModel.fontSizeOption.collectAsStateWithLifecycle()
    val theme by viewModel.themeOption.collectAsStateWithLifecycle()
    val configured by viewModel.hasConfiguredProfile.collectAsStateWithLifecycle()
    val dictionaryState by viewModel.dictionaryPackState.collectAsStateWithLifecycle()
    val installedCount by modelsViewModel.installedCount.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, modelsViewModel) {
        viewModel.refreshDictionaryPackState()
        modelsViewModel.refresh()
    }

    SettingsUiTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.statusBars
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding)) {
                val compact = maxHeight < 600.dp && LocalDensity.current.fontScale <= 1f
                SettingsPage(compact = compact) {
                    SettingsHeading(stringResource(R.string.settings_title), compact)
                    SettingsOverviewContent(
                        compact = compact,
                        fontSize = fontSize,
                        onFontSizeChange = viewModel::setFontSizeOption,
                        theme = theme,
                        onThemeChange = viewModel::setThemeOption,
                        hasConfiguredProfile = configured,
                        installedVoiceCount = installedCount,
                        dictionaryPackState = dictionaryState,
                        onOpenAiProfile = onOpenAiProfile,
                        onOpenCacheManagement = onOpenCacheManagement,
                        onOpenOnboarding = onOpenOnboarding,
                        onOpenSpeech = onOpenSpeech,
                        onOpenDictionary = onOpenDictionary,
                        onOpenAbout = onOpenAbout
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsOverviewContent(
    fontSize: FontSizeOption,
    onFontSizeChange: (FontSizeOption) -> Unit,
    theme: ThemeOption,
    onThemeChange: (ThemeOption) -> Unit,
    hasConfiguredProfile: Boolean,
    installedVoiceCount: Int,
    dictionaryPackState: DictionaryPackState,
    onOpenAiProfile: () -> Unit,
    onOpenCacheManagement: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenSpeech: () -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenAbout: () -> Unit,
    compact: Boolean = false
) {
    var fontMenu by rememberSaveable { mutableStateOf(false) }
    var themeMenu by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.settings_appearance), compact) {
        Box {
            SettingsOverviewRow(
                stringResource(R.string.settings_font_size), stringResource(fontSize.fontSizeLabelRes()),
                Icons.Outlined.TextFields, Color(0xFF0A84FF), "settings-font-size"
            ) { fontMenu = true }
            Box(Modifier.align(Alignment.BottomEnd)) {
                DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                    FontSizeOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.fontSizeLabelRes())) },
                            onClick = { fontMenu = false; onFontSizeChange(option) }
                        )
                    }
                }
            }
        }
        SettingsDivider(61.dp)
        Box {
            SettingsOverviewRow(
                stringResource(R.string.settings_theme), stringResource(theme.themeLabelRes()),
                Icons.Outlined.Palette, Color(0xFFAF52DE), "settings-theme"
            ) { themeMenu = true }
            Box(Modifier.align(Alignment.BottomEnd)) {
                DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                    ThemeOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.themeLabelRes())) },
                            onClick = { themeMenu = false; onThemeChange(option) }
                        )
                    }
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.settings_group_speech_ai), compact) {
        SettingsOverviewRow(
            stringResource(R.string.settings_tts_title),
            stringResource(R.string.settings_voice_count, installedVoiceCount),
            Icons.Outlined.VolumeUp, Color(0xFFFF9F0A), "settings-speech", onOpenSpeech
        )
        SettingsDivider(61.dp)
        SettingsOverviewRow(
            stringResource(R.string.settings_ai_profile_short),
            stringResource(if (hasConfiguredProfile) R.string.settings_status_configured else R.string.settings_status_unconfigured),
            Icons.Outlined.SmartToy, Color(0xFFBF5AF2), "settings_ai_profile_entry", onOpenAiProfile
        )
    }
    SettingsGroup(stringResource(R.string.settings_group_data), compact) {
        SettingsOverviewRow(
            stringResource(R.string.settings_dict_pack_title), dictionarySummary(dictionaryPackState),
            Icons.AutoMirrored.Outlined.MenuBook, Color(0xFF30D158), "settings-dictionary-entry", onOpenDictionary
        )
        SettingsDivider(61.dp)
        SettingsOverviewRow(
            stringResource(R.string.settings_cache_management), null,
            Icons.Outlined.Cached, Color(0xFF636366), "settings-cache-management", onOpenCacheManagement
        )
    }
    SettingsGroup(stringResource(R.string.settings_about), compact) {
        SettingsOverviewRow(
            stringResource(R.string.onboarding_title), null,
            Icons.Outlined.TipsAndUpdates, Color(0xFF0A84FF), "settings-onboarding-entry", onOpenOnboarding
        )
        SettingsDivider(61.dp)
        SettingsOverviewRow(
            stringResource(R.string.app_name), BuildConfig.VERSION_NAME,
            Icons.Outlined.Info, Color(0xFF636366), "settings-about", onOpenAbout
        )
    }
}

@Composable
private fun dictionarySummary(state: DictionaryPackState): String = stringResource(when (state) {
    DictionaryPackState.NotInstalled -> R.string.settings_status_not_installed
    is DictionaryPackState.Installed -> R.string.tts_model_installed
    is DictionaryPackState.Downloading -> R.string.settings_status_downloading
    is DictionaryPackState.Installing -> R.string.settings_status_installing
    DictionaryPackState.Removing -> R.string.settings_dict_pack_removing
    is DictionaryPackState.Failed -> if (state.reason == DictionaryPackFailure.CANCELLED) {
        R.string.settings_status_not_installed
    } else R.string.settings_status_install_failed
})

private fun FontSizeOption.fontSizeLabelRes(): Int = when (this) {
    FontSizeOption.SMALL -> R.string.settings_font_size_small
    FontSizeOption.MEDIUM -> R.string.settings_font_size_medium
    FontSizeOption.LARGE -> R.string.settings_font_size_large
}

private fun ThemeOption.themeLabelRes(): Int = when (this) {
    ThemeOption.SYSTEM -> R.string.settings_theme_system
    ThemeOption.LIGHT -> R.string.settings_theme_light
    ThemeOption.DARK -> R.string.settings_theme_dark
}
