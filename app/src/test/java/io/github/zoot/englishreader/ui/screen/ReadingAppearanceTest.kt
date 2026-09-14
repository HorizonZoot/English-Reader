package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import io.github.zoot.englishreader.model.SelectedSentence
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.ui.component.ReadingAppearanceSheet
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingAppearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fontChoices_clickEachOption_dispatchesAndReflectsSelectedPreference() {
        val selected = mutableStateOf(FontSizeOption.MEDIUM)
        val changes = mutableListOf<FontSizeOption>()
        renderAppearance(
            fontSize = { selected.value },
            onFontSizeChange = { changes += it; selected.value = it }
        )

        composeRule.onNodeWithTag("reading-font-medium").assertIsSelected()
        composeRule.onNodeWithTag("reading-font-large").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("reading-font-medium").assertIsNotSelected()
        composeRule.onNodeWithTag("reading-font-small").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("reading-font-large").assertIsNotSelected()
        composeRule.onNodeWithTag("reading-font-medium").performScrollTo().performClick().assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(
                listOf(FontSizeOption.LARGE, FontSizeOption.SMALL, FontSizeOption.MEDIUM),
                changes
            )
        }
    }

    @Test
    fun themeChoices_clickEachOption_dispatchesAndReflectsSelectedPreference() {
        val selected = mutableStateOf(ThemeOption.SYSTEM)
        val changes = mutableListOf<ThemeOption>()
        renderAppearance(
            theme = { selected.value },
            onThemeChange = { changes += it; selected.value = it }
        )

        composeRule.onNodeWithTag("reading-theme-system").assertIsSelected()
        composeRule.onNodeWithTag("reading-theme-dark").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("reading-theme-system").assertIsNotSelected()
        composeRule.onNodeWithTag("reading-theme-light").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("reading-theme-dark").assertIsNotSelected()
        composeRule.onNodeWithTag("reading-theme-system").performScrollTo().performClick().assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(ThemeOption.DARK, ThemeOption.LIGHT, ThemeOption.SYSTEM), changes)
        }
    }

    @Test
    fun readingModeChoices_switchBothWays_dispatchesAndReflectsSelection() {
        val selected = mutableStateOf(ReadingMode.SCROLL)
        val changes = mutableListOf<ReadingMode>()
        renderAppearance(
            readingMode = { selected.value },
            onReadingModeChange = { changes += it; selected.value = it }
        )
        composeRule.onNodeWithTag("reading-mode-scroll").assertIsSelected()
        composeRule.onNodeWithTag("reading-mode-paged").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("reading-mode-scroll").performScrollTo().performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals(listOf(ReadingMode.PAGED, ReadingMode.SCROLL), changes) }
    }

    @Test
    fun articleWithoutTranslation_appearanceHasNoTranslationSwitch() {
        renderReading(translation = null)

        composeRule.onNodeWithTag("reading-settings-translation").assertDoesNotExist()
    }

    @Test
    fun articleWithTranslation_toggleShowsAndHidesExistingInlineText() {
        var toggles = 0
        renderReading(translation = "第一句。", onToggle = { toggles++ })

        composeRule.onNodeWithText("第一句。").assertDoesNotExist()
        composeRule.onNodeWithTag("reading-settings-translation")
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithContentDescription("关闭").performScrollTo().performClick()
        composeRule.onNodeWithText("第一句。").assertExists()

        composeRule.onNodeWithContentDescription("阅读设置").performClick()
        composeRule.onNodeWithTag("reading-settings-translation")
            .performScrollTo().assertIsOn().performClick().assertIsOff()
        composeRule.onNodeWithContentDescription("关闭").performScrollTo().performClick()
        composeRule.onNodeWithText("第一句。").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(2, toggles) }
    }

    @Test
    fun readingSettings_openAfterSentenceActions_dismissesPopupAndKeepsItClosed() {
        val selection = mutableStateOf<SelectedSentence?>(null)
        val settings = mutableStateOf(false)
        var dismissals = 0
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    ReadingScreenContent(
                        article = ArticleEntity(1L, "Reading", "First sentence."),
                        selectedSentence = selection.value,
                        selectedWord = null,
                        isLoadingDefinition = false,
                        isLoading = false,
                        fontSizeOption = FontSizeOption.MEDIUM,
                        showTranslation = false,
                        snackbarHostState = remember { SnackbarHostState() },
                        onToggleTranslation = {},
                        onBack = {},
                        onExplainSentence = {},
                        onSentenceSelected = { id, index, range ->
                            selection.value = SelectedSentence(id, index, range.text, range.text.trim(),
                                range.startOffset, range.endOffset)
                        },
                        onSentenceAccessibilityTarget = { _, _ -> },
                        onPlaySentence = {},
                        onWordLongPress = {},
                        onClearSelection = {},
                        onDismissSentencePopup = { dismissals++ },
                        showReadingSettings = settings.value,
                        onToggleReadingSettings = { settings.value = !settings.value }
                    )
                }
            }
        }
        fun invokeAction(label: String) {
            val action = composeRule.onNodeWithContentDescription("Interactive reading text")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
            composeRule.runOnIdle { action.action() }
        }
        invokeAction("Highlight sentence 1")
        invokeAction("播放句子语音")
        composeRule.onNodeWithTag("sentence-action-popup").assertExists()
        val beforeOpeningSettings = composeRule.runOnIdle { dismissals }

        composeRule.onNodeWithContentDescription("阅读设置").performClick()
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(beforeOpeningSettings + 1, dismissals) }
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()
    }

    private fun renderAppearance(
        fontSize: () -> FontSizeOption = { FontSizeOption.DEFAULT },
        theme: () -> ThemeOption = { ThemeOption.DEFAULT },
        readingMode: () -> ReadingMode = { ReadingMode.DEFAULT },
        onFontSizeChange: (FontSizeOption) -> Unit = {},
        onThemeChange: (ThemeOption) -> Unit = {},
        onReadingModeChange: (ReadingMode) -> Unit = {}
    ) {
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    ReadingAppearanceSheet(
                        currentFontSize = fontSize(),
                        currentTheme = theme(),
                        currentReadingMode = readingMode(),
                        hasTranslation = false,
                        showTranslation = false,
                        onDismiss = {},
                        onFontSizeChange = onFontSizeChange,
                        onThemeChange = onThemeChange,
                        onReadingModeChange = onReadingModeChange,
                        onToggleTranslation = {}
                    )
                }
            }
        }
    }

    private fun renderReading(translation: String?, onToggle: () -> Unit = {}) {
        val showTranslation = mutableStateOf(false)
        val showSettings = mutableStateOf(true)
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    ReadingScreenContent(
                        article = ArticleEntity(
                            id = 1L,
                            title = "Reading",
                            content = "First sentence.",
                            translation = translation
                        ),
                        selectedSentence = null,
                        selectedWord = null,
                        isLoadingDefinition = false,
                        isLoading = false,
                        fontSizeOption = FontSizeOption.MEDIUM,
                        showTranslation = showTranslation.value,
                        snackbarHostState = remember { SnackbarHostState() },
                        onToggleTranslation = {
                            onToggle()
                            showTranslation.value = !showTranslation.value
                        },
                        onBack = {},
                        onExplainSentence = {},
                        onSentenceSelected = { _, _, _ -> },
                        onWordLongPress = {},
                        onClearSelection = {},
                        showReadingSettings = showSettings.value,
                        onToggleReadingSettings = { showSettings.value = !showSettings.value }
                    )
                }
            }
        }
    }
}
