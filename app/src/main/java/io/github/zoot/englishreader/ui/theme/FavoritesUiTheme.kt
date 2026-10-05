package io.github.zoot.englishreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkFavoritesColors = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color.White,
    primaryContainer = Color(0xFF0A84FF).copy(alpha = 0.22f), onPrimaryContainer = Color(0xFF0A84FF),
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF1C1C1E), onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF8E8E93),
    secondaryContainer = Color(0xFF636366), onSecondaryContainer = Color.White,
    outline = Color(0xFF636366), outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A), onError = Color.White
)
private val LightFavoritesColors = lightColorScheme(
    primary = Color(0xFF0066CC), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FF), onPrimaryContainer = Color(0xFF004C99),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
    surface = Color.White, onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF68686D),
    secondaryContainer = Color(0xFFD1D1D6), onSecondaryContainer = Color(0xFF1C1C1E),
    outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFD1D1D6),
    error = Color(0xFFD70015), onError = Color.White
)

@Composable
fun FavoritesUiTheme(content: @Composable () -> Unit) {
    val dark = LocalArticleUiDarkTheme.current
    val colors = if (dark) DarkFavoritesColors else LightFavoritesColors
    val type = MaterialTheme.typography
    CompositionLocalProvider(
        LocalReaderHighlightColors provides ReaderHighlightColors(colors.primary.copy(alpha = 0.22f))
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = type.copy(
                headlineLarge = type.headlineLarge.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
                displaySmall = type.displaySmall.copy(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
                bodyLarge = type.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
                bodyMedium = type.bodyMedium.copy(fontSize = 17.sp, lineHeight = 25.sp),
                bodySmall = type.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                labelSmall = type.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
            ),
            content = content
        )
    }
}
