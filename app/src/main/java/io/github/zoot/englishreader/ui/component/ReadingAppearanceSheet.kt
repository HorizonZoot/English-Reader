package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.ThemeOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadingAppearanceSheet(
    currentFontSize: FontSizeOption,
    currentTheme: ThemeOption,
    hasTranslation: Boolean,
    showTranslation: Boolean,
    onDismiss: () -> Unit,
    onFontSizeChange: (FontSizeOption) -> Unit,
    onThemeChange: (ThemeOption) -> Unit,
    onToggleTranslation: () -> Unit,
    currentReadingMode: ReadingMode = ReadingMode.DEFAULT,
    onReadingModeChange: (ReadingMode) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.reading_appearance_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.reading_sentence_popup_close)
                    )
                }
            }
            Text(
                text = stringResource(R.string.reading_mode),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReadingMode.entries.forEach { mode ->
                    AppearanceOption(
                        selected = mode == currentReadingMode,
                        onClick = { onReadingModeChange(mode) },
                        modifier = Modifier
                            .widthIn(min = 120.dp)
                            .weight(1f)
                            .testTag("reading-mode-" + mode.name.lowercase())
                    ) {
                        Text(stringResource(if (mode == ReadingMode.SCROLL) R.string.reading_mode_scroll else R.string.reading_mode_paged))
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_font_size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FontSizeOption.entries.forEach { option ->
                    AppearanceOption(
                        selected = option == currentFontSize,
                        onClick = { onFontSizeChange(option) },
                        modifier = Modifier
                            .widthIn(min = 88.dp)
                            .weight(1f)
                            .testTag("reading-font-" + option.name.lowercase())
                    ) {
                        Text(
                            text = stringResource(R.string.reading_appearance_sample),
                            fontFamily = FontFamily.Serif,
                            fontSize = option.sizeSp.sp
                        )
                        Text(
                            text = stringResource(option.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeOption.entries.forEach { option ->
                    AppearanceOption(
                        selected = option == currentTheme,
                        onClick = { onThemeChange(option) },
                        modifier = Modifier
                            .widthIn(min = 88.dp)
                            .weight(1f)
                            .testTag("reading-theme-" + option.name.lowercase())
                    ) {
                        Icon(
                            imageVector = when (option) {
                                ThemeOption.SYSTEM -> Icons.Default.BrightnessAuto
                                ThemeOption.LIGHT -> Icons.Default.LightMode
                                ThemeOption.DARK -> Icons.Default.DarkMode
                            },
                            contentDescription = null
                        )
                        Text(
                            text = stringResource(option.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            if (hasTranslation) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = showTranslation,
                            role = Role.Switch,
                            onValueChange = { onToggleTranslation() }
                        )
                        .testTag("reading-settings-translation"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.show_translation),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = showTranslation, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun AppearanceOption(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick
        ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 80.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            content = content
        )
    }
}

private fun FontSizeOption.labelRes(): Int = when (this) {
    FontSizeOption.SMALL -> R.string.settings_font_size_small
    FontSizeOption.MEDIUM -> R.string.settings_font_size_medium
    FontSizeOption.LARGE -> R.string.settings_font_size_large
}

private fun ThemeOption.labelRes(): Int = when (this) {
    ThemeOption.SYSTEM -> R.string.settings_theme_system
    ThemeOption.LIGHT -> R.string.settings_theme_light
    ThemeOption.DARK -> R.string.settings_theme_dark
}
