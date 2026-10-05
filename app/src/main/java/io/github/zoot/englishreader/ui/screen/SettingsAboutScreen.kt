package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.BuildConfig
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.ui.theme.SettingsUiTheme
import io.github.zoot.englishreader.viewmodel.ManualCheckOutcome
import kotlinx.coroutines.flow.Flow

@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onCheckForUpdate: () -> Unit,
    checkingForUpdate: Boolean,
    hasUpdateDownload: Boolean,
    manualUpdateOutcomes: Flow<ManualCheckOutcome>
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(manualUpdateOutcomes) {
        manualUpdateOutcomes.collect { outcome ->
            val message = when (outcome) {
                ManualCheckOutcome.UP_TO_DATE -> R.string.update_check_up_to_date
                ManualCheckOutcome.FAILED -> R.string.update_check_failed
                ManualCheckOutcome.OFFLINE -> R.string.update_check_offline
                ManualCheckOutcome.TIMED_OUT -> R.string.update_check_timeout
                ManualCheckOutcome.RATE_LIMITED -> R.string.update_check_rate_limited
            }
            snackbar.showSnackbar(context.getString(message))
        }
    }
    SettingsUiTheme {
        SettingsSubpage(stringResource(R.string.app_name), onBack, snackbar) {
            AboutSection(onCheckForUpdate, checkingForUpdate, hasUpdateDownload, onOpenLicenses)
        }
    }
}

@Composable
internal fun AboutSection(
    onCheckForUpdate: () -> Unit,
    checking: Boolean,
    hasDownload: Boolean,
    onOpenLicenses: () -> Unit
) {
    SettingsGroup(stringResource(R.string.settings_about)) {
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = stringResource(R.string.app_name),
            supporting = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME)
        )
        SettingsDivider()
        SettingsActionRow(
            icon = {
                if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, contentDescription = null)
            },
            title = stringResource(when {
                checking -> R.string.settings_check_update_checking
                hasDownload -> R.string.settings_update_download
                else -> R.string.settings_check_update
            }),
            onClick = if (checking) null else onCheckForUpdate,
            testTag = CHECK_UPDATE_TEST_TAG
        )
        SettingsDivider()
        SettingsActionRow(
            icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
            title = stringResource(R.string.settings_licenses),
            onClick = onOpenLicenses,
            testTag = "settings-licenses"
        )
    }
}

internal const val CHECK_UPDATE_TEST_TAG = "settings-check-update"

@Composable
fun SettingsLicensesScreen(onBack: () -> Unit) {
    SettingsUiTheme {
        SettingsSubpage(
            title = stringResource(R.string.settings_licenses),
            onBack = onBack,
            backLabel = stringResource(R.string.settings_about)
        ) {
            SettingsGroup(stringResource(R.string.settings_license_engine)) {
                SettingsActionRow(
                    icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    title = stringResource(R.string.settings_license_engine),
                    supporting = stringResource(R.string.tts_model_licenses)
                )
            }
            SettingsGroup(stringResource(R.string.settings_license_models)) {
                TtsModelCatalog.entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    SettingsActionRow(
                        icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                        title = stringResource(entry.nameRes),
                        supporting = entry.license
                    )
                }
            }
        }
    }
}
