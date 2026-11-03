package io.github.zoot.englishreader.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.AiArticleGenerationDraft
import io.github.zoot.englishreader.data.repository.AiArticleGenerationState
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.AiArticleGenerationUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiArticleGenerationAppearanceTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var view: View
    private val darkThemeOverride = mutableStateOf<Boolean?>(null)

    @Test
    @Config(qualifiers = "w393dp-h852dp-night")
    fun page_systemDark_rendersDarkSurfaceAndBlueSelection() {
        render()
        val top = capture("system-dark-top")
        assertEquals(0xFF000000.toInt(), top.getPixel(1, 1))
        assertFillColor("ai_article_profile", 0xFF2C2C2E.toInt())
        assertFillColor("ai_article_difficulty_BEGINNER", 0xFF0A84FF.toInt())
        assertFillColor("ai_article_generate", 0xFF3A3A3C.toInt())
        temporaryNotice().performScrollTo().assertIsDisplayed()
        capture("system-dark-topic-and-generate")
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-night")
    fun page_systemDarkWithLightOverride_keepsLightSurface() {
        render(darkHost = false)
        assertEquals(0xFFF2F2F7.toInt(), capture("light-override-top").getPixel(1, 1))
        assertFillColor("ai_article_difficulty_BEGINNER", 0xFF007AFF.toInt())
        assertFillColor("ai_article_generate", 0xFFD1D1D6.toInt())
    }

    @Test
    fun page_systemLightWithDarkOverride_keepsDarkSurface() {
        render(darkHost = true)
        assertEquals(0xFF000000.toInt(), capture("dark-override-top").getPixel(1, 1))
        assertFillColor("ai_article_difficulty_BEGINNER", 0xFF0A84FF.toInt())
        assertFillColor("ai_article_generate", 0xFF3A3A3C.toInt())
    }

    @Test
    fun page_resolvedThemeChanges_updatesWithoutRecreatingContent() {
        render()
        assertEquals(0xFFF2F2F7.toInt(), capture("theme-switch-light").getPixel(1, 1))
        composeRule.runOnIdle { darkThemeOverride.value = true }
        assertEquals(0xFF000000.toInt(), capture("theme-switch-dark").getPixel(1, 1))
        assertFillColor("ai_article_generate", 0xFF3A3A3C.toInt())
        composeRule.runOnIdle { darkThemeOverride.value = false }
        assertEquals(0xFFF2F2F7.toInt(), capture("theme-switch-back-light").getPixel(1, 1))
        assertFillColor("ai_article_generate", 0xFFD1D1D6.toInt())
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun page_compactLargeText_keepsFormActionsScrollable() {
        render(darkHost = false, fontScale = 1.8f)
        capture("compact-large-top")
        formTag("ai_article_length_LONG").assertIsDisplayed()
        composeRule.onNodeWithText("800词", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture("compact-large-length")
        formTag("ai_article_generate").assertIsDisplayed()
        temporaryNotice().performScrollTo().assertIsDisplayed()
        capture("compact-large-generate")
    }

    @Test
    fun page_systemLightWithReadyProfile_rendersBlueGenerateAction() {
        render(state = AiArticleGenerationUiState(
            session = AiArticleGenerationState(draft = AiArticleGenerationDraft(profileId = "test-profile")),
            profiles = listOf(AiProviderProfile(
                "test-profile", "UI test profile", AiProviderTemplate.OPENAI_COMPATIBLE,
                "https://example.com", "test-model", AiAuthStrategy.API_KEY, 0.7
            )),
            profilesLoaded = true
        ))
        assertEquals(0xFFF2F2F7.toInt(), capture("system-light-top").getPixel(1, 1))
        formTag("ai_article_generate").assertIsDisplayed().assertIsEnabled()
        assertFillColor("ai_article_generate", 0xFF007AFF.toInt())
        temporaryNotice().performScrollTo().assertIsDisplayed()
        capture("light-ready-generate")
    }

    private fun render(
        darkHost: Boolean? = null,
        fontScale: Float = 1f,
        state: AiArticleGenerationUiState = AiArticleGenerationUiState(profilesLoaded = true)
    ) {
        darkThemeOverride.value = darkHost
        composeRule.setContent {
            val density = LocalDensity.current
            val localView = LocalView.current
            SideEffect { view = localView }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                EnglishReaderTheme(darkTheme = darkThemeOverride.value ?: isSystemInDarkTheme()) {
                    AiArticleGenerationContent(
                        state = state,
                        onBack = {}, onOpenAiProfile = {}, onReloadProfiles = {}, onSelectProfile = {},
                        onTopicChange = {}, onDifficultyChange = {}, onLengthChange = {},
                        onGenerate = {}, onSaveAndRead = {}, onDiscard = {}
                    )
                }
            }
        }
    }

    private fun formTag(tag: String) = composeRule.onNodeWithTag("ai_article_content")
        .performScrollToIndex(0).let { composeRule.onNodeWithTag(tag).performScrollTo() }

    private fun temporaryNotice() = composeRule.onNodeWithText(
        "未保存文章仅临时保留，应用被系统关闭后可能丢失；不会自动加入文章库。"
    )

    private fun assertFillColor(tag: String, color: Int) {
        val bounds = formTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bitmap = capture("$tag-${color.toUInt().toString(16)}")
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        val right = bounds.right.toInt().coerceAtMost(bitmap.width)
        val bottom = bounds.bottom.toInt().coerceAtMost(bitmap.height)
        val area = (right - left) * (bottom - top)
        assertTrue("Expected visible pixels for $tag", area > 0)
        var matchingPixels = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (bitmap.getPixel(x, y) == color) matchingPixels++
            }
        }
        assertTrue("Expected theme fill in $tag", matchingPixels > area / 3)
    }

    private fun capture(name: String): Bitmap {
        val bitmap = composeRule.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        val file = File("build/outputs/ai-article-ui/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        return bitmap
    }
}
