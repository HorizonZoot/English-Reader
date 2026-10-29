package io.github.zoot.englishreader.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
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
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiArticleGenerationAppearanceTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var view: View

    @Test
    fun page_darkHost_rendersLightSurfaceAndBlueSelection() {
        render(darkHost = true)
        val top = capture("light-from-dark-host-top")
        assertEquals(0xFFF2F2F7.toInt(), top.getPixel(1, 1))
        formTag("ai_article_difficulty_BEGINNER").assertIsDisplayed()
        val options = capture("light-options")
        val pixels = IntArray(options.width * options.height)
        options.getPixels(pixels, 0, options.width, 0, 0, options.width, options.height)
        assertTrue("Expected a blue selected segment", pixels.count { it == 0xFF007AFF.toInt() } > 100)
        formTag("ai_article_generate").assertIsDisplayed()
        temporaryNotice().performScrollTo().assertIsDisplayed()
        capture("light-topic-and-generate")
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
    fun page_readyProfile_rendersBlueGenerateAction() {
        render(darkHost = false, state = AiArticleGenerationUiState(
            session = AiArticleGenerationState(draft = AiArticleGenerationDraft(profileId = "test-profile")),
            profiles = listOf(AiProviderProfile(
                "test-profile", "UI test profile", AiProviderTemplate.OPENAI_COMPATIBLE,
                "https://example.com", "test-model", AiAuthStrategy.API_KEY, 0.7
            )),
            profilesLoaded = true
        ))
        formTag("ai_article_generate").assertIsDisplayed().assertIsEnabled()
        temporaryNotice().performScrollTo().assertIsDisplayed()
        capture("light-ready-generate")
    }

    private fun render(
        darkHost: Boolean,
        fontScale: Float = 1f,
        state: AiArticleGenerationUiState = AiArticleGenerationUiState(profilesLoaded = true)
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            val localView = LocalView.current
            SideEffect { view = localView }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                EnglishReaderTheme(darkTheme = darkHost) {
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
