package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.ArticleListViewModel
import io.github.zoot.englishreader.viewmodel.LibraryItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticleListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val article = ArticleEntity(
        id = 7L,
        title = "The Art of Noticing",
        content = "Small details reward a slower look."
    )
    private val book = BookEntity(
        id = 7L,
        title = "A Walk Through Wonder",
        contentFingerprint = "book-fingerprint",
        sourceFormat = "epub3",
        chapterCount = 12,
        totalChars = 5_000
    )
    private val libraryItemsState = MutableStateFlow<List<LibraryItem>>(emptyList())
    private val importingState = MutableStateFlow(false)
    private val openedArticles = mutableListOf<Long>()
    private val openedBooks = mutableListOf<Long>()
    private val viewModel = mockk<ArticleListViewModel>(relaxed = true).also { model ->
        every { model.libraryItems } returns libraryItemsState
        every { model.isImporting } returns importingState
        every { model.uiEvent } returns emptyFlow()
    }

    @Test
    fun library_sameNumericArticleAndBookIds_opensEachCorrectDestination() {
        render(items = listOf(LibraryItem.Article(article), LibraryItem.Book(book)))

        openTitle(article.title)
        openTitle(book.title)

        composeRule.runOnIdle {
            assertEquals(listOf(article.id), openedArticles)
            assertEquals(listOf(book.id), openedBooks)
        }
    }

    @Test
    fun articleDelete_menuAndCancelKeepArticle_confirmDispatchesOnce() {
        render(items = listOf(LibraryItem.Article(article), LibraryItem.Book(book)))

        openMore(article.title)
        assertNothingDeletedOrOpened()
        composeRule.onNodeWithText(string(R.string.article_delete_title)).performClick()
        val confirmation = string(R.string.article_delete_confirmation, article.title)
        composeRule.onNodeWithText(confirmation).assertIsDisplayed()
        assertNothingDeletedOrOpened()

        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        assertNothingDeletedOrOpened()

        openMore(article.title)
        composeRule.onNodeWithText(string(R.string.article_delete_title)).performClick()
        confirmDeleteTwiceBeforeRecomposition()

        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        verify(exactly = 1) { viewModel.deleteArticle(article) }
        verify(exactly = 0) { viewModel.deleteBook(any()) }
    }

    @Test
    fun bookDelete_menuAndCancelKeepBook_confirmDispatchesOnce() {
        render(items = listOf(LibraryItem.Article(article), LibraryItem.Book(book)))

        openMore(book.title)
        assertNothingDeletedOrOpened()
        composeRule.onNodeWithText(string(R.string.book_delete_title)).performClick()
        val confirmation = string(R.string.book_delete_confirmation, book.title)
        composeRule.onNodeWithText(confirmation).assertIsDisplayed()
        assertNothingDeletedOrOpened()

        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        assertNothingDeletedOrOpened()

        openMore(book.title)
        composeRule.onNodeWithText(string(R.string.book_delete_title)).performClick()
        confirmDeleteTwiceBeforeRecomposition()

        composeRule.onNodeWithText(confirmation).assertDoesNotExist()
        verify(exactly = 1) { viewModel.deleteBook(book) }
        verify(exactly = 0) { viewModel.deleteArticle(any()) }
    }

    @Test
    fun import_emptyLibrary_opensExistingImportChoices() {
        render(items = emptyList())

        composeRule.onNodeWithText(string(R.string.articles_empty)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.import_article))
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithText(string(R.string.import_choose_method)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_from_file)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_paste_text)).assertIsDisplayed()
        verify(exactly = 0) { viewModel.importFromFile(any()) }
        verify(exactly = 0) { viewModel.importFromPaste(any(), any()) }
    }

    @Test
    fun import_busyThenIdle_blocksRepeatedEntryAndEnablesItAfterCompletion() {
        render(items = emptyList(), isImporting = true)

        composeRule.onNodeWithContentDescription(string(R.string.library_importing))
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithText(string(R.string.import_choose_method)).assertDoesNotExist()

        composeRule.runOnIdle { importingState.value = false }
        composeRule.onNodeWithContentDescription(string(R.string.import_article))
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText(string(R.string.import_choose_method)).assertIsDisplayed()
    }

    @Test
    fun library_longTitleAndLargeFontInNarrowDarkLayout_keepsOpenAndMoreActionsReachable() {
        val longArticle = article.copy(
            title = "A Long Article Title About Finding Time to Read and Notice Everyday Details"
        )
        render(
            items = listOf(LibraryItem.Article(longArticle)),
            darkTheme = true,
            width = 280.dp,
            fontScale = 1.8f
        )

        openTitle(longArticle.title)
        composeRule.runOnIdle { assertEquals(listOf(longArticle.id), openedArticles) }

        openMore(longArticle.title)
        composeRule.onNodeWithText(string(R.string.article_delete_title))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(string(R.string.action_cancel)).assertIsDisplayed().performClick()
        verify(exactly = 0) { viewModel.deleteArticle(any()) }
    }

    private fun openTitle(title: String) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).assertIsDisplayed().performClick()
    }

    private fun openMore(title: String) {
        val description = string(R.string.library_more_actions, title)
        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription(description))
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed().performClick()
    }

    private fun confirmDeleteTwiceBeforeRecomposition() {
        // 两次确认共享尚未重组的按钮，页面仍只能派发一次删除。
        composeRule.onNodeWithText(string(R.string.action_delete))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { click ->
                click()
                click()
            }
    }

    private fun assertNothingDeletedOrOpened() {
        verify(exactly = 0) { viewModel.deleteArticle(any()) }
        verify(exactly = 0) { viewModel.deleteBook(any()) }
        composeRule.runOnIdle {
            assertEquals(emptyList<Long>(), openedArticles)
            assertEquals(emptyList<Long>(), openedBooks)
        }
    }

    private fun render(
        items: List<LibraryItem>,
        isImporting: Boolean = false,
        darkTheme: Boolean = false,
        width: Dp = 360.dp,
        fontScale: Float = 1f
    ) {
        libraryItemsState.value = items
        importingState.value = isImporting
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                EnglishReaderTheme(darkTheme = darkTheme) {
                    Box(modifier = Modifier.width(width).fillMaxHeight()) {
                        ArticleListScreen(
                            onArticleClick = { openedArticles += it },
                            onBookClick = { openedBooks += it },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }

    private fun string(resourceId: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resourceId, *args)
}
