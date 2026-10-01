package io.github.zoot.englishreader.ui.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.ReadingArticleState
import io.github.zoot.englishreader.model.ReadingTtsState
import io.github.zoot.englishreader.model.ReadingVoiceSettingsState
import io.github.zoot.englishreader.model.WholeTranslationProgress
import io.github.zoot.englishreader.model.WholeTranslationSheetState
import io.github.zoot.englishreader.model.WholeTranslationTaskStatus
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.ReadingViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingWholeTranslationCompletionTest {
    @get:Rule val composeRule = createComposeRule()

    private val article = ArticleEntity(id = 1, title = "Completed translation", content = "A finished sentence.")
    private val articleState = MutableStateFlow<ReadingArticleState?>(ReadingArticleState(article))
    private val translationState = MutableStateFlow<WholeTranslationSheetState>(WholeTranslationSheetState.Hidden)

    @Test
    fun completion_scrollMode_displaysTranslationAndKeepsItVisible() {
        assertCompletionShowsTranslation(ReadingMode.SCROLL)
    }

    @Test
    fun completion_pagedMode_displaysTranslationAndKeepsItVisible() {
        assertCompletionShowsTranslation(ReadingMode.PAGED)
    }

    @Test
    fun runningTask_continueInBackground_keepsExistingTranslationHidden() {
        articleState.value = ReadingArticleState(article.copy(translation = TRANSLATION))
        render(ReadingMode.SCROLL)

        composeRule.onNodeWithTag("whole-translation-primary")
            .performScrollTo().assertTextEquals("后台继续").performClick()

        composeRule.onNodeWithTag("whole-translation-sheet").assertDoesNotExist()
        composeRule.onNodeWithText(TRANSLATION).assertDoesNotExist()
        composeRule.onNodeWithText(article.content).assertIsDisplayed()
    }

    private fun assertCompletionShowsTranslation(mode: ReadingMode) {
        render(mode)
        composeRule.onNodeWithText(TRANSLATION).assertDoesNotExist()

        composeRule.runOnIdle {
            articleState.value = ReadingArticleState(article.copy(translation = TRANSLATION))
            translationState.value = tracking(WholeTranslationTaskStatus.COMPLETED)
        }
        clickDoneAndAssertTranslation()

        composeRule.runOnIdle {
            translationState.value = tracking(WholeTranslationTaskStatus.COMPLETED)
        }
        clickDoneAndAssertTranslation()
    }

    private fun clickDoneAndAssertTranslation() {
        composeRule.onNodeWithTag("whole-translation-primary")
            .performScrollTo().assertTextEquals("完成").performClick()
        composeRule.onNodeWithTag("whole-translation-sheet").assertDoesNotExist()
        composeRule.onNodeWithText(TRANSLATION).assertIsDisplayed()
    }

    private fun render(mode: ReadingMode) {
        translationState.value = tracking(WholeTranslationTaskStatus.RUNNING)
        val model = mockk<ReadingViewModel>(relaxed = true) {
            every { readingArticle } returns articleState
            every { selectedSentence } returns MutableStateFlow(null)
            every { selectedWord } returns MutableStateFlow(null)
            every { wordDefinition } returns MutableStateFlow(null)
            every { isLoadingDefinition } returns MutableStateFlow(false)
            every { isLoadingAudio } returns MutableStateFlow(false)
            every { isLoadingArticle } returns MutableStateFlow(false)
            every { vocabularyWords } returns MutableStateFlow(emptySet())
            every { fontSizeOption } returns MutableStateFlow(FontSizeOption.DEFAULT)
            every { themeOption } returns MutableStateFlow(ThemeOption.DEFAULT)
            every { aiSheetState } returns MutableStateFlow(AiSheetState.Hidden)
            every { sentenceTranslationState } returns MutableStateFlow(AiSheetState.Hidden)
            every { chapterContext } returns MutableStateFlow(null)
            every { pendingPositionTarget } returns MutableStateFlow(null)
            every { highlightedParagraph } returns MutableStateFlow(null)
            every { highlightedExcerpt } returns MutableStateFlow(null)
            every { readingMode } returns MutableStateFlow(mode)
            every { readingTtsState } returns MutableStateFlow(ReadingTtsState())
            every { voiceSettings } returns MutableStateFlow(ReadingVoiceSettingsState())
            every { wholeTranslationState } returns translationState
            every { aiConfigurationPrompt } returns MutableStateFlow(false)
            every { ttsPositionTarget } returns MutableStateFlow(null)
            every { ttsFailures } returns emptyFlow()
            every { vocabularySaved } returns emptyFlow()
            every { excerptSaved } returns emptyFlow()
            every { definitionError } returns emptyFlow()
            every { readingErrors } returns emptyFlow()
            every { dismissWholeTranslation() } answers {
                translationState.value = WholeTranslationSheetState.Hidden
            }
        }
        composeRule.setContent {
            EnglishReaderTheme {
                ReadingScreen(articleId = article.id, onBack = {}, viewModel = model)
            }
        }
    }

    private fun tracking(status: WholeTranslationTaskStatus): WholeTranslationSheetState.Tracking {
        val completed = status == WholeTranslationTaskStatus.COMPLETED
        return WholeTranslationSheetState.Tracking(
            taskId = 7,
            scopeKey = "article:${article.id}",
            status = status,
            progress = WholeTranslationProgress(
                total = 1,
                translated = if (completed) 1 else 0,
                translating = if (completed) 0 else 1,
                failed = 0,
                untranslated = 0
            ),
            failureReason = null
        )
    }

    private companion object {
        const val TRANSLATION = "全文翻译完成后的中文结果。"
    }
}
