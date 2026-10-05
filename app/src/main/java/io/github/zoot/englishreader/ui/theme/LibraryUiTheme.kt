package io.github.zoot.englishreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkLibraryColors = darkColorScheme(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93),
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C1C1E),
    onPrimaryContainer = Color(0xFF0A84FF),
    outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A),
    surfaceTint = Color.Transparent
)

private val LightLibraryColors = lightColorScheme(
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color(0xFF007AFF),
    outlineVariant = Color(0xFFC6C6C8),
    error = Color(0xFFD70015),
    surfaceTint = Color.Transparent
)

/** Only the library and its navigation bar use these tokens; the user's theme mode is unchanged. */
@Composable
fun LibraryUiTheme(content: @Composable () -> Unit) {
    val type = MaterialTheme.typography
    MaterialTheme(
        colorScheme = if (LocalArticleUiDarkTheme.current) DarkLibraryColors else LightLibraryColors,
        typography = type.copy(
            headlineLarge = type.headlineLarge.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
            titleMedium = type.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
            bodyLarge = type.bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp),
            bodyMedium = type.bodyMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
            bodySmall = type.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = type.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
            labelMedium = type.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            labelSmall = type.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
        ),
        content = content
    )
}
