package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.ui.theme.SettingsUiTheme

@Composable
fun SettingsNavigationBar(onArticles: () -> Unit, onVocabulary: () -> Unit) {
    SettingsUiTheme {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = Color.Transparent
        )
        NavigationBar(
            modifier = if (LocalConfiguration.current.screenHeightDp < 660) {
                Modifier.height(60.dp + NavigationBarDefaults.windowInsets.asPaddingValues().calculateBottomPadding())
            } else Modifier,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = false, onClick = onArticles, colors = colors,
                icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_articles), style = MaterialTheme.typography.labelMedium) }
            )
            NavigationBarItem(
                selected = false, onClick = onVocabulary, colors = colors,
                icon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_vocabulary), style = MaterialTheme.typography.labelMedium) }
            )
            NavigationBarItem(
                selected = true, onClick = {}, colors = colors,
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}
