package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.R

@Composable
internal fun SettingsPage(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 16.dp, vertical = if (compact) 0.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 14.dp),
            content = content
        )
    }
}

@Composable
internal fun SettingsHeading(title: String, compact: Boolean = false) {
    Text(
        title,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.semantics { heading() }.then(
            if (compact) Modifier.height(41.dp).wrapContentHeight(unbounded = true) else Modifier
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    onBack: () -> Unit,
    backLabel: String = stringResource(R.string.settings_title),
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                Text(backLabel, modifier = Modifier.padding(start = 4.dp))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
internal fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    backLabel: String = stringResource(R.string.settings_title),
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = { SettingsTopBar(onBack, backLabel) },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        SettingsPage(Modifier.padding(padding)) {
            SettingsHeading(title)
            content()
        }
    }
}

@Composable
internal fun SettingsGroup(
    title: String,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 6.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 16.dp).semantics { heading() }.then(
                if (compact) Modifier.height(16.dp).wrapContentHeight(unbounded = true) else Modifier
            ),
            style = if (compact) MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp)
                else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SettingsDivider(start: Dp = 56.dp) {
    Divider(
        modifier = Modifier.padding(start = start),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
internal fun SettingsActionRow(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    testTag: String? = null
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .semantics(mergeDescendants = true) {}
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        headlineContent = { Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = icon,
        trailingContent = if (onClick == null) null else {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
internal fun SettingsOverviewRow(
    title: String,
    status: String?,
    icon: ImageVector,
    iconColor: Color,
    tag: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(
            min = 52.dp,
            max = if (LocalDensity.current.fontScale <= 1f) 52.dp else Dp.Infinity
        ).clickable(onClick = onClick).testTag(tag),
        leadingContent = {
            Box(
                Modifier.size(29.dp).clip(RoundedCornerShape(6.dp)).background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
internal fun settingsAccentColor(): Color = MaterialTheme.colorScheme.primary
