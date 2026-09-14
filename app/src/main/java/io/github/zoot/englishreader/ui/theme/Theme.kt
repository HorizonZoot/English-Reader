package io.github.zoot.englishreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    error = md_light_error,
    onError = md_light_onError,
    outline = md_light_outline
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    error = md_dark_error,
    onError = md_dark_onError,
    outline = md_dark_outline
)

/**
 * 阅读页高亮色随主题切换。
 *
 * [InteractiveText] 不自行判断暗色，改从此 CompositionLocal 读取，
 * 由 [EnglishReaderTheme] 按当前 darkTheme 下发对应色值，保证暗色下的对比度。
 */
data class ReaderHighlightColors(
    val word: Color
)

// 单一真源：亮/暗高亮色各定义一次，供 CompositionLocal 默认值与 EnglishReaderTheme 复用，
// 避免同一组色值散落两处导致改一处漏一处的漂移。
private val LightReaderHighlightColors = ReaderHighlightColors(
    word = highlight_word_light
)
private val DarkReaderHighlightColors = ReaderHighlightColors(
    word = highlight_word_dark
)

val LocalReaderHighlightColors = staticCompositionLocalOf { LightReaderHighlightColors }

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用暗色。默认跟随系统；设置界面可传入固定值覆盖（Light/Dark/跟随系统）。
 */
@Composable
fun EnglishReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val highlightColors = if (darkTheme) {
        DarkReaderHighlightColors
    } else {
        LightReaderHighlightColors
    }

    CompositionLocalProvider(
        LocalReaderHighlightColors provides highlightColors,
        LocalArticleUiDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
