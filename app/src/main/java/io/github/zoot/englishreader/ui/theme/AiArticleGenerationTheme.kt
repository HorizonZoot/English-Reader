package io.github.zoot.englishreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object AiArticleGenerationStyle {
    val PagePadding = 20.dp
    val CardPadding = 20.dp
    val SectionGap = 16.dp
    val ContentGap = 12.dp
    val CardShape = RoundedCornerShape(22.dp)
    val FieldShape = RoundedCornerShape(16.dp)
    val ButtonShape = RoundedCornerShape(18.dp)
    val DisabledBackground: Color
        @Composable get() = if (LocalArticleUiDarkTheme.current) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)
    val DisabledContent = Color(0xFF8E8E93)
}

private val AiArticleLightColors = lightColorScheme(
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF2F2F7),
    // 正文说明比禁用文字更深，保持浅色卡片上的可读性。
    onSurfaceVariant = Color(0xFF636366),
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF007AFF),
    outline = Color(0xFFE5E5EA),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFD70015),
    surfaceTint = Color.Transparent
)

private val AiArticleDarkColors = darkColorScheme(
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
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A),
    surfaceTint = Color.Transparent
)

/** 复用应用已解析的主题，默认跟随系统并尊重用户的浅色/深色设置。 */
@Composable
internal fun AiArticleGenerationTheme(content: @Composable () -> Unit) {
    val type = MaterialTheme.typography
    MaterialTheme(
        colorScheme = if (LocalArticleUiDarkTheme.current) AiArticleDarkColors else AiArticleLightColors,
        typography = type.copy(
            titleLarge = type.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = type.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = type.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = type.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = type.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = type.labelLarge.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        ),
        content = content
    )
}
