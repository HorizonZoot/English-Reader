package io.github.zoot.englishreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val DarkSettingsColors = darkColorScheme(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93),
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF163B65),
    onPrimaryContainer = Color(0xFF0A84FF),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFF0A84FF),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A),
    onError = Color.Black,
    surfaceTint = Color.Transparent
)

private val LightSettingsColors = lightColorScheme(
    background = Color(0xFFF2F2F7),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6C6C70),
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EFFF),
    onPrimaryContainer = Color(0xFF007AFF),
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF007AFF),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFC6C6C8),
    error = Color(0xFFD70015),
    onError = Color.White,
    surfaceTint = Color.Transparent
)

/** 复用应用已解析的主题选择，仅覆盖设置目的地，不改变阅读/收藏的色彩和排版。 */
@Composable
fun SettingsUiTheme(content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = if (LocalArticleUiDarkTheme.current) DarkSettingsColors else LightSettingsColors,
        typography = typography.copy(
            headlineLarge = typography.headlineLarge.copy(
                fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            ),
            titleLarge = typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
            titleSmall = typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            bodyLarge = typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
            bodyMedium = typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
            bodySmall = typography.bodySmall.copy(
                fontSize = 13.sp, lineHeight = 18.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
            ),
            labelLarge = typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
            labelMedium = typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            labelSmall = typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
        ),
        content = content
    )
}
