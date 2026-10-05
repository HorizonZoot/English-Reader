package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.ui.theme.LibraryUiTheme

@Composable
fun LibraryNavigationBar(
    onVocabulary: () -> Unit,
    onSettings: () -> Unit,
    onArticles: () -> Unit = {},
    vocabularySelected: Boolean = false
) = LibraryUiTheme {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        indicatorColor = Color.Transparent
    )
    Column {
        Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = !vocabularySelected,
                onClick = onArticles,
                icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                label = { Text(stringResource(R.string.nav_articles)) },
                colors = colors
            )
            NavigationBarItem(
                selected = vocabularySelected,
                onClick = onVocabulary,
                icon = { Icon(Icons.Outlined.BookmarkBorder, null) },
                label = { Text(stringResource(R.string.nav_vocabulary)) },
                colors = colors
            )
            NavigationBarItem(
                selected = false,
                onClick = onSettings,
                icon = { Icon(Icons.Outlined.Settings, null) },
                label = { Text(stringResource(R.string.nav_settings)) },
                colors = colors
            )
        }
    }
}
