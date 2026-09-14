package io.github.zoot.englishreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightArticleColors = lightColorScheme(
    primary = Color(0xFF0866C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FB),
    onPrimaryContainer = Color(0xFF0866C8),
    secondary = Color(0xFF0866C8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7F0FB),
    onSecondaryContainer = Color(0xFF0866C8),
    tertiary = Color(0xFF415A4E),
    onTertiary = Color(0xFFFAF9F3),
    tertiaryContainer = Color(0xFF425D7A),
    onTertiaryContainer = Color(0xFFFAF9F3),
    background = Color(0xFFFBFAF8),
    onBackground = Color(0xFF242529),
    surface = Color.White,
    onSurface = Color(0xFF242529),
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF6C6F75),
    outline = Color(0xFF96989D),
    outlineVariant = Color(0xFFE6E5E4),
    inverseSurface = Color(0xFF242529),
    inverseOnSurface = Color(0xFFFBFAF8),
    inversePrimary = Color(0xFF87BAFF),
    surfaceTint = Color(0xFF0866C8)
)

private val DarkArticleColors = darkColorScheme(
    primary = Color(0xFF87BAFF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF293D57),
    onPrimaryContainer = Color(0xFFB9D6FF),
    secondary = Color(0xFF87BAFF),
    onSecondary = Color(0xFF003063),
    secondaryContainer = Color(0xFF293D57),
    onSecondaryContainer = Color(0xFFB9D6FF),
    tertiary = Color(0xFF354B3F),
    onTertiary = Color(0xFFE1EAE3),
    tertiaryContainer = Color(0xFF354960),
    onTertiaryContainer = Color(0xFFE1EAF3),
    background = Color(0xFF1C1D1F),
    onBackground = Color(0xFFE7E4DF),
    surface = Color(0xFF292A2D),
    onSurface = Color(0xFFE7E4DF),
    surfaceVariant = Color(0xFF343539),
    onSurfaceVariant = Color(0xFFACADB1),
    outline = Color(0xFF8A8D92),
    outlineVariant = Color(0xFF3B3C40),
    inverseSurface = Color(0xFFE7E4DF),
    inverseOnSurface = Color(0xFF1C1D1F),
    inversePrimary = Color(0xFF0866C8),
    surfaceTint = Color(0xFF87BAFF)
)

private val LightArticleHighlightColors = ReaderHighlightColors(Color(0xFFD9E9FC))
private val DarkArticleHighlightColors = ReaderHighlightColors(Color(0xFF2C4769))

// 复用应用已解析的用户主题，不在局部表面重新判断系统设置。
internal val LocalArticleUiDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ArticleUiTheme(content: @Composable () -> Unit) {
    val darkTheme = LocalArticleUiDarkTheme.current
    val colors = if (darkTheme) DarkArticleColors else LightArticleColors
    val highlights = if (darkTheme) DarkArticleHighlightColors else LightArticleHighlightColors

    CompositionLocalProvider(LocalReaderHighlightColors provides highlights) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
