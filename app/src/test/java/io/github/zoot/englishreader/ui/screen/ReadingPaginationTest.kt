package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.ChapterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 页面导航与状态接线；实际滑动和字形裁切由 instrumentation 覆盖。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingPaginationTest {
    @get:Rule val composeRule = createComposeRule()

    private val longArticle = ArticleEntity(42, "Reading", (1..240).joinToString(" ") { "word$it" } + ".")

    @Test
    fun shortArticle_disablesBothPageDirections() {
        render(ArticleEntity(42, "Reading", "One sentence."))
        composeRule.onNodeWithText("第 1 / 1 页").assertExists()
        composeRule.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("下一页").assertIsNotEnabled()
    }

    @Test
    fun pageButtons_moveOriginalPositionAndClearTransientSelection() {
        val saves = mutableListOf<ReadingPosition>()
        var cleared = 0
        render(longArticle, onSave = { saves += it }, onClear = { cleared++ })

        composeRule.onNodeWithContentDescription("下一页").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertTrue("Expected a later source position, saved $saves", saves.last().anchor.characterOffset > 0)
            assertEquals(1, cleared)
        }
        composeRule.onNodeWithContentDescription("上一页").performClick()
        composeRule.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(2, cleared) }
    }

    @Test
    fun modeAndFontChanges_keepSourceAnchorAcrossReflow() {
        val mode = mutableStateOf(ReadingMode.PAGED)
        val font = mutableStateOf(FontSizeOption.MEDIUM)
        val saves = mutableListOf<ReadingPosition>()
        render(longArticle, mode = mode, font = font, onSave = { saves += it })
        composeRule.onNodeWithContentDescription("下一页").assertIsEnabled().performClick()
        lateinit var originalPosition: ReadingPosition
        composeRule.runOnIdle {
            originalPosition = saves.last()
            assertTrue("Expected a later source position, saved $saves", originalPosition.anchor.characterOffset > 0)
            mode.value = ReadingMode.SCROLL
        }
        composeRule.runOnIdle {
            assertEquals(originalPosition, saves.last())
            font.value = FontSizeOption.LARGE
        }
        composeRule.runOnIdle {
            assertEquals(originalPosition, saves.last())
            mode.value = ReadingMode.PAGED
        }
        composeRule.runOnIdle { assertEquals(originalPosition, saves.last()) }
    }

    @Test
    fun pageBoundary_usesNextChapterStartAndPreviousChapterEnd() {
        val navigations = mutableListOf<Pair<Long, ReadingEntry>>()
        render(
            ArticleEntity(42, "Reading", "One sentence."),
            chapter = ChapterContext(7, 1, 3, "Chapter", 41, 43),
            onBoundary = { id, entry -> navigations += id to entry }
        )
        composeRule.onNodeWithContentDescription("下一页").performClick()
        composeRule.onNodeWithContentDescription("上一页").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(43L to ReadingEntry.START, 41L to ReadingEntry.END), navigations)
        }
    }

    private fun render(
        article: ArticleEntity,
        mode: MutableState<ReadingMode> = mutableStateOf(ReadingMode.PAGED),
        font: MutableState<FontSizeOption> = mutableStateOf(FontSizeOption.DEFAULT),
        chapter: ChapterContext? = null,
        onSave: (ReadingPosition) -> Unit = {},
        onClear: () -> Unit = {},
        onBoundary: (Long, ReadingEntry) -> Unit = { _, _ -> }
    ) {
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    ReadingScreenContent(
                        article = article, selectedSentence = null, selectedWord = null,
                        isLoadingDefinition = false, isLoading = false,
                        fontSizeOption = font.value, showTranslation = false,
                        snackbarHostState = remember { SnackbarHostState() },
                        onToggleTranslation = {}, onBack = {}, onExplainSentence = {},
                        onSentenceSelected = { _, _, _ -> }, onWordLongPress = {},
                        onClearSelection = onClear,
                        readingMode = mode.value,
                        onReadingModeChange = { mode.value = it },
                        onSaveReadingPosition = onSave,
                        chapterContext = chapter,
                        onNavigatePageBoundary = onBoundary
                    )
                }
            }
        }
    }
}
