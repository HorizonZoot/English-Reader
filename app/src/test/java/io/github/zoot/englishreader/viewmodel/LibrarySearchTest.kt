package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ArticleSummary
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val article = ArticleSummary(id = 7, title = "A Quiet Walk", createdAt = 10)
    private val book = BookEntity(
        id = 7,
        title = "Reading Well",
        author = "Jane Austen",
        contentFingerprint = "fingerprint",
        sourceFormat = "epub3",
        chapterCount = 2,
        totalChars = 200,
        createdAt = 20
    )
    private val articles = MutableStateFlow(listOf(article))
    private val books = MutableStateFlow(listOf(book))
    private val store = ViewModelStore()
    private lateinit var viewModel: ArticleListViewModel

    @Before
    fun setup() {
        val articleRepository = mockk<ArticleRepository> {
            every { getStandaloneArticles() } returns articles
            every { getAllArticles() } returns flow { error("Search must not load article bodies") }
        }
        val bookRepository = mockk<BookRepository> {
            every { getAllBooks() } returns books
        }
        viewModel = ArticleListViewModel(articleRepository, mockk(), bookRepository, mockk(), mockk())
        store.put("library", viewModel)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun search_titleAndAuthor_matchesIgnoringCaseAndOuterWhitespace() = runTest {
        viewModel.libraryItems.test {
            assertEquals(emptyList<LibraryItem>(), awaitItem())
            assertEquals(listOf("book-7", "article-7"), awaitItem().map { it.listKey })

            viewModel.updateSearchQuery("  QUIET  ")
            assertEquals(listOf(LibraryItem.Article(article)), awaitItem())
            assertEquals("  QUIET  ", viewModel.searchQuery.value)

            viewModel.updateSearchQuery("reading")
            assertEquals(listOf(LibraryItem.Book(book)), awaitItem())
            viewModel.updateSearchQuery("not found")
            assertEquals(emptyList<LibraryItem>(), awaitItem())
            viewModel.updateSearchQuery("AUSTEN")
            assertEquals(listOf(LibraryItem.Book(book)), awaitItem())
        }
    }

    @Test
    fun search_blankOrCleared_restoresOriginalOrderAndDistinctIds() = runTest {
        viewModel.libraryItems.test {
            awaitItem()
            val all = awaitItem()
            viewModel.updateSearchQuery("quiet")
            awaitItem()
            viewModel.updateSearchQuery(" \t ")
            assertEquals(all, awaitItem())
            viewModel.updateSearchQuery("no match")
            awaitItem()
            viewModel.updateSearchQuery("")
            assertEquals(all, awaitItem())
        }
    }

    @Test
    fun search_nonAsciiAndWildcardCharacters_areLiteralSubstrings() = runTest {
        articles.value = listOf(article.copy(title = "中文 100%_[.*]"))
        books.value = listOf(book.copy(author = null))
        viewModel.libraryItems.test {
            awaitItem()
            awaitItem()
            for (query in listOf("中文", "%_", "[.*]")) {
                viewModel.updateSearchQuery(query)
                assertEquals(listOf(LibraryItem.Article(articles.value.single())), awaitItem())
                viewModel.updateSearchQuery("missing")
                assertEquals(emptyList<LibraryItem>(), awaitItem())
            }
            viewModel.updateSearchQuery("fingerprint")
            runCurrent()
            assertEquals(emptyList<LibraryItem>(), viewModel.libraryItems.value)
        }
    }

    @Test
    fun search_repositoryChanges_reappliesFilterAndKeepsExistingSort() = runTest {
        viewModel.updateSearchQuery("walk")
        viewModel.libraryItems.test {
            awaitItem()
            assertEquals(listOf(LibraryItem.Article(article)), awaitItem())
            val added = book.copy(title = "Walk in the Park")
            books.value = listOf(added)
            assertEquals(listOf(LibraryItem.Book(added), LibraryItem.Article(article)), awaitItem())
            articles.value = emptyList()
            assertEquals(listOf(LibraryItem.Book(added)), awaitItem())
            books.value = listOf(added.copy(title = "Renamed"))
            assertEquals(emptyList<LibraryItem>(), awaitItem())
        }
    }

    @Test
    fun search_returnAfterCollectionStops_retainsQueryAndRefreshesResults() = runTest {
        viewModel.updateSearchQuery("austen")
        viewModel.libraryItems.test {
            awaitItem()
            assertEquals(listOf(LibraryItem.Book(book)), awaitItem())
        }
        advanceTimeBy(5_001)
        runCurrent()
        val newBook = book.copy(id = 8, title = "Another Book")
        books.value = listOf(newBook)
        assertEquals("austen", viewModel.searchQuery.value)
        viewModel.libraryItems.test {
            awaitItem()
            assertEquals(listOf(LibraryItem.Book(newBook)), awaitItem())
        }
    }
}
