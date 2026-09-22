package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.ArticleEditorDialog
import io.github.zoot.englishreader.model.ArticleEditorError
import io.github.zoot.englishreader.model.ArticleEditorState
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticleEditorScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val original = ArticleEditSnapshot(7L, "Original title", "First line.\nSecond line.")
    private val state = mutableStateOf(
        ArticleEditorState(original, original.title, original.content, isLoading = false)
    )
    private var saves = 0
    private var previews = 0
    private var applied = 0
    private var confirmations = 0
    private var retries = 0

    @Test
    fun editor_narrowLargeFont_prefillsAndKeepsSaveAndFormattingReachable() {
        render(fontScale = 1.6f)
        composeRule.onNodeWithTag("article-editor-title-field")
            .performScrollTo().assertIsDisplayed().assertTextContains(original.title)
            .performTextReplacement("Edited title")
        composeRule.onNodeWithTag("article-editor-content-field")
            .performScrollTo().assertIsDisplayed().assertTextContains(original.content)
        composeRule.onNodeWithTag("article-editor-format")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("article-editor-save").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals("Edited title", state.value.title)
            assertEquals(1, previews)
            assertEquals(1, saves)
        }
    }

    @Test
    fun editor_saving_disablesRepeatSaveBackAndInputs() {
        state.value = state.value.copy(title = "New title", isSaving = true)
        render()
        composeRule.onNodeWithTag("article-editor-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("article-editor-back").assertIsNotEnabled()
        composeRule.onNodeWithTag("article-editor-title-field").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("article-editor-content-field").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("article-editor-format").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun editor_bodySaveConfirmation_isExplicitAndDismissDoesNotConfirm() {
        state.value = state.value.copy(content = "Changed content.", dialog = ArticleEditorDialog.ConfirmContentSave)
        render()
        composeRule.onNodeWithTag("article-editor-confirm-save").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithTag("article-editor-confirm-save").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(0, saves)
            state.value = state.value.copy(dialog = ArticleEditorDialog.ConfirmContentSave)
        }
        composeRule.onNodeWithTag("article-editor-confirm-save").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun paragraphPreview_longPreview_keepsApplyReachableAndOnlyAppliesToDraft() {
        state.value = state.value.copy(
            dialog = ArticleEditorDialog.ParagraphPreview(
                List(80) { "Paragraph $it contains a line of text." }.joinToString("\n\n"), 80
            )
        )
        render(fontScale = 1.3f)
        composeRule.onNodeWithTag("article-editor-preview-apply")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, applied)
            assertEquals(0, saves)
            assertEquals(0, confirmations)
        }
    }

    @Test
    fun editor_loadFailure_showsRetryAndDoesNotExposeEditableFields() {
        state.value = ArticleEditorState(isLoading = false, error = ArticleEditorError.LoadFailed)
        render()
        composeRule.onNodeWithTag("article-editor-error").assertIsDisplayed()
        composeRule.onNodeWithTag("article-editor-retry").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("article-editor-title-field").assertDoesNotExist()
        composeRule.onNodeWithTag("article-editor-save").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    private fun render(fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                EnglishReaderTheme {
                    Box(Modifier.width(280.dp).height(500.dp)) {
                        ArticleEditorContent(
                            state = state.value,
                            onBack = {},
                            onTitleChange = { state.value = state.value.copy(title = it) },
                            onContentChange = { state.value = state.value.copy(content = it) },
                            onSave = { saves++ },
                            onConfirmContentSave = { confirmations++ },
                            onConfirmDiscard = {},
                            onPreviewParagraphs = { previews++ },
                            onApplyParagraphs = { applied++ },
                            onDismissDialog = { state.value = state.value.copy(dialog = null) },
                            onRetryLoad = { retries++ }
                        )
                    }
                }
            }
        }
    }
}
