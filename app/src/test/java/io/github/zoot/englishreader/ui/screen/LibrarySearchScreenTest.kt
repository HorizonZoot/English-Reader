package io.github.zoot.englishreader.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import io.github.zoot.englishreader.data.entity.ArticleSummary
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookSummary
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.model.LibrarySort
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.viewmodel.ArticleListViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibrarySearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val articles = MutableStateFlow(
        listOf(ArticleSummary(id = 11, title = "A Quiet Walk", createdAt = 10, lastReadAt = 10))
    )
    private val books = MutableStateFlow(
        listOf(
            BookEntity(
                id = 22,
                title = "Reading Well",
                author = "Jane Austen",
                contentFingerprint = "fingerprint",
                sourceFormat = "epub3",
                chapterCount = 2,
                totalChars = 200,
                createdAt = 20,
                lastReadAt = 20
            )
        )
    )
    private val store = ViewModelStore()
    private val showLibrary = mutableStateOf(true)
    private val openedArticles = mutableListOf<Long>()
    private val openedBooks = mutableListOf<Long>()

    @After
    fun tearDown() {
        compose.runOnIdle { store.clear() }
    }

    @Test
    fun search_inputAndClear_filtersTitlesAndAuthorsWithoutChangingImportEntry() {
        render()
        search().assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithText("A Quiet Walk").assertIsDisplayed()
        compose.onNodeWithText("Reading Well").assertIsDisplayed()

        search().performTextInput("  QUIET  ")
        compose.onNodeWithText("A Quiet Walk").assertIsDisplayed()
        compose.onNodeWithText("Reading Well").assertDoesNotExist()
        search().performTextReplacement("austen")
        compose.onNodeWithText("Reading Well").assertIsDisplayed()
        compose.onNodeWithText("A Quiet Walk").assertDoesNotExist()
        search().performImeAction()
        search().assertIsNotFocused()

        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("A Quiet Walk").assertIsDisplayed()
        compose.onNodeWithText("Reading Well").assertIsDisplayed()
        compose.onNodeWithContentDescription("清空搜索").assertDoesNotExist()
        compose.onNodeWithContentDescription("导入文章").performClick()
        compose.onNodeWithText("粘贴文本").assertIsDisplayed()
    }

    @Test
    fun search_noMatch_differsFromEmptyLibraryAndClearRestoresEmptyHint() {
        articles.value = emptyList()
        books.value = emptyList()
        render()
        compose.onNodeWithText("还没有文章，快去添加吧～").assertIsDisplayed()
        search().performTextInput("unknown")
        compose.onNodeWithText("没有找到匹配的文章或书籍").assertIsDisplayed()
        compose.onNodeWithText("还没有文章，快去添加吧～").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("还没有文章，快去添加吧～").assertIsDisplayed()
    }

    @Test
    fun search_openResultAndReturn_keepsQueryAndCorrectNavigationIds() {
        render()
        search().performTextInput("quiet")
        compose.onNodeWithText("A Quiet Walk").performClick()
        compose.runOnIdle {
            assertEquals(listOf(11L), openedArticles)
            showLibrary.value = true
        }
        search().assertTextContains("quiet")
        compose.onNodeWithText("A Quiet Walk").assertIsDisplayed()
        compose.onNodeWithText("Reading Well").assertDoesNotExist()

        search().performTextReplacement("austen")
        compose.onNodeWithText("Reading Well").performClick()
        compose.runOnIdle {
            assertEquals(listOf(22L), openedBooks)
            showLibrary.value = true
        }
        search().assertTextContains("austen")
        compose.onNodeWithText("Reading Well").assertIsDisplayed()
        compose.onNodeWithText("A Quiet Walk").assertDoesNotExist()
    }

    @Test
    fun scrollPosition_restoredOnRecreation_keptUntilQueryChanges() {
        // 最近阅读倒序；标题位于列表外，index 29 对应 Article 10。
        articles.value = (0 until 40).map {
            ArticleSummary(id = it + 100L, title = "Article $it", createdAt = it.toLong(), lastReadAt = it.toLong())
        }
        books.value = emptyList()
        val viewModel = library()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            EnglishReaderTheme {
                ArticleListScreen(onArticleClick = {}, onBookClick = {}, viewModel = viewModel)
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        compose.onNodeWithText("Article 10").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Article 10").assertIsDisplayed()

        search().performTextInput("article 3")
        compose.onNodeWithText("Article 39").assertIsDisplayed()
    }

    private fun library(): ArticleListViewModel {
        val articleRepository = mockk<ArticleRepository> {
            every { getStandaloneArticles() } returns articles
            every { getAllArticles() } returns flow { error("Search must not load article bodies") }
        }
        val bookRepository = mockk<BookRepository> {
            every { getLibraryBooks() } returns books.map { entries -> entries.map { BookSummary(it) } }
        }
        val settingsPreferences = mockk<SettingsPreferences> {
            every { librarySort } returns flowOf(LibrarySort())
        }
        return ArticleListViewModel(
            articleRepository, mockk(), bookRepository, mockk(), mockk(), settingsPreferences
        ).also { store.put("library", it) }
    }

    private fun render() {
        val viewModel = library()
        compose.setContent {
            EnglishReaderTheme {
                if (showLibrary.value) {
                    ArticleListScreen(
                        onArticleClick = { openedArticles += it; showLibrary.value = false },
                        onBookClick = { openedBooks += it; showLibrary.value = false },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    private fun search() = compose.onNodeWithTag(LIBRARY_SEARCH_TAG)
}
