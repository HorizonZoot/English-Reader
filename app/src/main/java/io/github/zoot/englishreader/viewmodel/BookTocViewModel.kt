package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 目录页状态。
 *
 * `book` 可空而 `chapters` 不可空：书不存在（被删或 id 失效）与书存在但章节还在加载
 * 是两种不同状态，界面对前者要显示空态而非无限加载。
 */
data class BookTocUiState(
    val book: BookEntity? = null,
    val chapters: List<BookChapterEntity> = emptyList(),
    /** 上次读到的章节 articleId；无进度时为 null。用于高亮与「继续阅读」。 */
    val lastReadArticleId: Long? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false
)

/**
 * 一本书的目录。
 *
 * 章节与阅读进度持续观察；保留在返回栈中的 VM 也能收到晚于页面恢复提交的进度。
 */
@HiltViewModel
class BookTocViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val bookId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_BOOK_ID)) {
        "BookTocViewModel requires $ARG_BOOK_ID"
    }

    private val _uiState = MutableStateFlow(BookTocUiState())
    val uiState: StateFlow<BookTocUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    init {
        retryLoading()
    }

    fun retryLoading() {
        val generation = ++loadGeneration
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, loadFailed = false)
        loadJob = viewModelScope.launch {
            try {
                combine(
                    flow { emit(bookRepository.getBookById(bookId)) },
                    bookRepository.getChapters(bookId),
                    bookRepository.observeProgress(bookId)
                ) { book, chapters, progress ->
                    BookTocUiState(
                        book = book,
                        chapters = chapters,
                        lastReadArticleId = progress?.chapterArticleId,
                        isLoading = false
                    )
                }.collect { state ->
                    if (generation == loadGeneration) _uiState.value = state
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == loadGeneration) {
                    _uiState.value = _uiState.value.copy(loadFailed = true)
                }
            } finally {
                if (generation == loadGeneration) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    /**
     * 「继续阅读」的目标章节。
     *
     * 有进度时回到进度章节；无进度时回第一章。返回 null 只在书没有任何章节时发生
     * —— 导入路径保证 `chapters.isNotEmpty()`，所以这在实践中意味着书已被删。
     */
    fun resumeTargetArticleId(): Long? {
        val state = _uiState.value
        val lastRead = state.lastReadArticleId
        // 进度里的章节必须仍在当前章节列表中：书被重新导入后旧 articleId 会失效，
        // 直接跳过去会打开一篇不属于这本书的文章。
        if (lastRead != null && state.chapters.any { it.articleId == lastRead }) {
            return lastRead
        }
        return state.chapters.firstOrNull()?.articleId
    }

    companion object {
        const val ARG_BOOK_ID = "bookId"
    }
}
