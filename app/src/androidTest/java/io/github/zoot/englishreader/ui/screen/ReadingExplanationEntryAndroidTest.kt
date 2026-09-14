package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiSheetAttachment
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.SelectedSentence
import io.github.zoot.englishreader.ui.boundsOnScreen
import io.github.zoot.englishreader.ui.clickGlyph
import io.github.zoot.englishreader.ui.positionOnScreen
import io.github.zoot.englishreader.ui.textLayoutResult
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingExplanationEntryAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val firstSentence = "First sentence wraps across several lines in this reading window."
    private val article = ArticleEntity(
        id = 1,
        title = "Reading",
        content = firstSentence + " Second sentence.",
        translation = "第一句。第二句。"
    )

    @Test
    fun sentenceGlyph_threeActions_dispatchAndShowOwnedExplanationBelowSentence() {
        val contextParagraphs = (1..5).map { "Context paragraph number " + it + "." }
        val state = renderReading(
            article.copy(content = (contextParagraphs + article.content + contextParagraphs)
                .joinToString("\n\n"))
        )
        centerTargetParagraph(lazyItemIndex = contextParagraphs.size + 1)
        textNode().clickGlyph(0)

        composeRule.onNodeWithText(text(R.string.reading_sentence_play)).performClick()
        composeRule.onNodeWithText(text(R.string.reading_sentence_translate)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.reading_sentence_explain)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, state.selections.size)
            assertEquals(1, state.plays)
            assertEquals(listOf(requireNotNull(state.selection.value)), state.explanations)
            assertEquals(firstSentence + " ", state.explanations.single().rawText)
            assertEquals(firstSentence, state.explanations.single().normalizedText)
            assertEquals(0, state.explanations.single().startOffset)
            assertEquals(firstSentence.length + 1, state.explanations.single().endOffset)
            state.explanation.value = success(state.explanations.single(), "当前句子的解释")
        }

        composeRule.onNodeWithText("当前句子的解释").assertIsDisplayed()
        assertPopupBelowSelectedSentence(state, requireSpaceOnBothSides = true)
        composeRule.onNodeWithTag("reading-explain-sentence").assertDoesNotExist()
        composeRule.onNodeWithTag("reading-explain-article").assertDoesNotExist()
    }

    @Test
    fun sentenceActionsAndResultClose_meetMinimumTouchTarget() {
        renderReading()
        textNode().clickGlyph(0)

        listOf(
            R.string.reading_sentence_play_content_description,
            R.string.reading_sentence_translate_content_description,
            R.string.reading_sentence_explain_content_description
        ).forEach { description ->
            assertTouchTargetAtLeast48dp(
                composeRule.onNodeWithContentDescription(text(description)).fetchSemanticsNode()
            )
        }
        composeRule.onNodeWithText(text(R.string.reading_sentence_explain)).performClick()
        assertTouchTargetAtLeast48dp(
            composeRule.onNodeWithContentDescription(text(R.string.reading_sentence_popup_close))
                .fetchSemanticsNode()
        )
    }

    @Test
    fun sentenceTap_translationErrorAndRetryStayInOwnedPopup() {
        val state = renderReading()
        textNode().clickGlyph(0)
        composeRule.onNodeWithText(text(R.string.reading_sentence_translate)).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(requireNotNull(state.selection.value)), state.translations)
            state.translation.value = AiSheetState.Rejected(
                AiError.NoActiveProfile,
                AiExplanationTarget.Sentence(requireNotNull(state.selection.value))
            )
        }

        composeRule.onNodeWithText(text(R.string.settings_ai_error_no_active_profile))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_retry)).performClick()
        composeRule.runOnIdle { assertEquals(1, state.translationRetries) }
        assertPopupInsideReadingViewport()

        closePopup()
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()
        composeRule.onNodeWithTag("reading-explain-article").assertDoesNotExist()
    }

    @Test
    fun dismissAndSelectAnotherSentence_lateResultCannotReopenOrReplaceNewResult() {
        val state = renderReading()
        textNode().clickGlyph(0)
        composeRule.onNodeWithText(text(R.string.reading_sentence_explain)).performClick()
        val first = composeRule.runOnIdle { state.explanations.single() }
        closePopup()

        composeRule.runOnIdle { state.explanation.value = success(first, "旧句子的迟到结果") }
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()

        textNode().clickGlyph(firstSentence.length + 1)
        composeRule.onNodeWithText(text(R.string.reading_sentence_explain)).performClick()
        composeRule.onNodeWithText("旧句子的迟到结果").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.reading_sentence_explanation_unavailable))
            .assertIsDisplayed()

        composeRule.runOnIdle {
            val second = state.explanations.last()
            assertEquals(2, state.explanations.size)
            assertEquals(1, second.sentenceIndex)
            assertEquals("Second sentence.", second.rawText)
            state.explanation.value = success(second, "新句子的解释")
        }
        composeRule.onNodeWithText("新句子的解释").assertIsDisplayed()
        composeRule.onNodeWithText("旧句子的迟到结果").assertDoesNotExist()
    }

    @Test
    fun fontReflow_updatesPopupGeometryWithoutSelectingOrRequestingAgain() {
        val state = renderReading()
        textNode().clickGlyph(6)
        composeRule.onNodeWithText(text(R.string.reading_sentence_explain)).performClick()
        composeRule.runOnIdle {
            state.explanation.value = success(state.explanations.single(), "保留中的解释")
        }
        val oldGlyphHeight = textNode().textLayoutResult().getBoundingBox(6).height

        composeRule.runOnIdle { state.font.value = FontSizeOption.LARGE }
        val newGlyphHeight = textNode().textLayoutResult().getBoundingBox(6).height
        assertTrue("The text must actually reflow at the larger font", newGlyphHeight > oldGlyphHeight)
        composeRule.onNodeWithText("保留中的解释").assertIsDisplayed()
        assertPopupBelowSelectedSentence(state)
        composeRule.runOnIdle {
            assertEquals(1, state.selections.size)
            assertEquals(1, state.explanations.size)
            assertEquals(0, state.dismissals)
        }
    }

    @Test
    fun longExplanation_scrollsInsideBoundedPopupAndKeepsCloseReachable() {
        val state = renderReading()
        textNode().clickGlyph(0)
        composeRule.onNodeWithText(text(R.string.reading_sentence_explain)).performClick()
        composeRule.runOnIdle {
            state.explanation.value = success(
                state.explanations.single(),
                ("A longer explanation with several details and examples.\n").repeat(60)
            )
        }

        val scroll = composeRule.onNodeWithTag("sentence-result-scroll")
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Fixture must overflow the result viewport", before.maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, before.maxValue()))
        }
        composeRule.waitForIdle()
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Scrolling must move the result content", after.value() > 0f)
        assertPopupInsideReadingViewport()
        composeRule.onNodeWithContentDescription(text(R.string.reading_sentence_popup_close))
            .assertIsDisplayed()
        closePopup()
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()
    }

    @Test
    fun paragraphOutsideAndSettingsClicks_dismissActionsImmediately() {
        val state = renderReading()
        textNode().clickGlyph(0)
        composeRule.onNodeWithTag("sentence-action-popup").assertExists()

        composeRule.onNodeWithTag("reading-content").performTouchInput {
            click(Offset(1f, height / 2f))
        }
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, state.dismissals) }

        textNode().clickGlyph(0)
        composeRule.onNodeWithTag("sentence-action-popup").assertExists()
        composeRule.onNodeWithContentDescription(text(R.string.reading_settings)).performClick()
        composeRule.onNodeWithTag("sentence-action-popup").assertDoesNotExist()
        composeRule.onNodeWithTag("reading-font-small").assertExists()
        composeRule.runOnIdle { assertEquals(2, state.dismissals) }
    }

    private fun renderReading(readingArticle: ArticleEntity = article): ReadingState {
        val state = ReadingState()
        composeRule.setContent {
            EnglishReaderTheme(darkTheme = state.theme.value == ThemeOption.DARK) {
                ArticleUiTheme {
                    Box(Modifier.width(320.dp)) {
                        ReadingScreenContent(
                            article = readingArticle,
                            selectedSentence = state.selection.value,
                            selectedWord = null,
                            isLoadingDefinition = false,
                            isLoading = false,
                            fontSizeOption = state.font.value,
                            themeOption = state.theme.value,
                            showTranslation = false,
                            snackbarHostState = remember { SnackbarHostState() },
                            sentenceTranslationState = state.translation.value,
                            sentenceExplanationState = state.explanation.value,
                            onToggleTranslation = {},
                            onBack = {},
                            onExplainSentence = {
                                state.explanations += requireNotNull(state.selection.value)
                            },
                            onSentenceSelected = { articleId, sentenceIndex, range ->
                                val selected = SelectedSentence(
                                    articleId, sentenceIndex, range.text, range.text.trim(),
                                    range.startOffset, range.endOffset
                                )
                                state.selections += selected
                                state.selection.value = selected
                            },
                            onWordLongPress = {},
                            onSentenceTapTarget = { _, _ -> },
                            onClearSelection = { state.selection.value = null },
                            onPlaySentence = { state.plays++ },
                            onTranslateSentence = {
                                state.translations += requireNotNull(state.selection.value)
                            },
                            onRetryTranslation = { state.translationRetries++ },
                            onDismissSentencePopup = {
                                state.dismissals++
                                state.translation.value = AiSheetState.Hidden
                                state.explanation.value = AiSheetState.Hidden
                            },
                            showReadingSettings = state.showSettings.value,
                            onToggleReadingSettings = {
                                state.showSettings.value = !state.showSettings.value
                            },
                            onFontSizeChange = {
                                state.fontChanges += it
                                state.font.value = it
                            },
                            onThemeChange = {
                                state.themeChanges += it
                                state.theme.value = it
                            }
                        )
                    }
                }
            }
        }
        return state
    }

    private fun textNode() = composeRule.onNode(
        hasContentDescription(text(R.string.interactive_text_content_description)) and
            hasText(firstSentence, substring = true)
    )

    private fun centerTargetParagraph(lazyItemIndex: Int) {
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(lazyItemIndex)
        val layout = textNode().textLayoutResult()
        val body = textNode().fetchSemanticsNode()
        val viewport = composeRule.onNodeWithTag("reading-content").fetchSemanticsNode()
        val delta = composeRule.runOnIdle {
            val lastLine = layout.getLineForOffset(firstSentence.length - 1)
            val sentenceCenter = body.positionOnScreen().y + layout.getLineBottom(lastLine) / 2f
            sentenceCenter - viewport.boundsOnScreen().center.y
        }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, delta))
        }
        composeRule.waitForIdle()
    }

    private fun closePopup() {
        composeRule.onNodeWithContentDescription(text(R.string.reading_sentence_popup_close))
            .performClick()
        composeRule.waitForIdle()
    }

    private fun assertPopupBelowSelectedSentence(
        state: ReadingState,
        requireSpaceOnBothSides: Boolean = false
    ) {
        val selected = composeRule.runOnIdle { requireNotNull(state.selection.value) }
        val layout = textNode().textLayoutResult()
        val lastOffset = (selected.endOffset - 1 downTo selected.startOffset)
            .first { !layout.layoutInput.text[it].isWhitespace() }
        val lastLine = layout.getLineForOffset(lastOffset)
        val textSemantics = textNode().fetchSemanticsNode()
        val popupSemantics = composeRule.onNodeWithTag("sentence-action-popup").fetchSemanticsNode()
        val viewportSemantics = composeRule.onNodeWithTag("reading-content").fetchSemanticsNode()
        composeRule.runOnIdle {
            val textOrigin = textSemantics.positionOnScreen()
            val popupBounds = popupSemantics.boundsOnScreen()
            val viewport = viewportSemantics.boundsOnScreen()
            val sentenceTop = textOrigin.y + layout.getLineTop(layout.getLineForOffset(selected.startOffset))
            val sentenceBottom = textOrigin.y + layout.getLineBottom(lastLine)
            val gap = 8f * textSemantics.layoutInfo.density.density
            assertTrue(
                "Fixture needs space below the complete selected sentence",
                sentenceBottom + gap + popupBounds.height <= viewport.bottom + 1f
            )
            if (requireSpaceOnBothSides) {
                assertTrue(
                    "Fixture must also fit above to distinguish the preferred direction",
                    sentenceTop - gap - popupBounds.height >= viewport.top - 1f
                )
            }
            assertEquals("Popup must follow the last rendered sentence line", sentenceBottom + gap,
                popupBounds.top, 2f)
            assertContained(popupBounds, viewport)
        }
    }

    private fun assertPopupInsideReadingViewport() {
        val popup = composeRule.onNodeWithTag("sentence-action-popup").fetchSemanticsNode()
        val viewport = composeRule.onNodeWithTag("reading-content").fetchSemanticsNode()
        composeRule.runOnIdle { assertContained(popup.boundsOnScreen(), viewport.boundsOnScreen()) }
    }

    private fun assertContained(popup: Rect, viewport: Rect) {
        assertTrue("Popup must be inside reading viewport: " + popup + " vs " + viewport,
            popup.left >= viewport.left - 1f && popup.top >= viewport.top - 1f &&
                popup.right <= viewport.right + 1f && popup.bottom <= viewport.bottom + 1f)
    }

    private fun assertTouchTargetAtLeast48dp(node: SemanticsNode) {
        val bounds = node.touchBoundsInRoot
        val minPx = 48f * node.layoutInfo.density.density
        assertTrue("Touch height must be at least 48dp", bounds.height >= minPx - 0.5f)
        assertTrue("Touch width must be at least 48dp", bounds.width >= minPx - 0.5f)
    }

    private fun success(selection: SelectedSentence, result: String) = AiSheetState.Visible(
        attachment = AiSheetAttachment(1, AiOperationRef("test-key", "test-operation")),
        outcome = AiOperationOutcome.Success(result),
        target = AiExplanationTarget.Sentence(selection)
    )

    private fun text(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)

    private class ReadingState {
        val selection = mutableStateOf<SelectedSentence?>(null)
        val translation = mutableStateOf<AiSheetState>(AiSheetState.Hidden)
        val explanation = mutableStateOf<AiSheetState>(AiSheetState.Hidden)
        val font = mutableStateOf(FontSizeOption.MEDIUM)
        val theme = mutableStateOf(ThemeOption.SYSTEM)
        val showSettings = mutableStateOf(false)
        val selections = mutableListOf<SelectedSentence>()
        val explanations = mutableListOf<SelectedSentence>()
        val translations = mutableListOf<SelectedSentence>()
        val fontChanges = mutableListOf<FontSizeOption>()
        val themeChanges = mutableListOf<ThemeOption>()
        var plays = 0
        var translationRetries = 0
        var dismissals = 0
    }
}
