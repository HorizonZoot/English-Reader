package io.github.zoot.englishreader.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ArticleSummary
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.importer.ImportBudgetValidator
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.ImportFormat
import io.github.zoot.englishreader.data.importer.ImportFormatProbe
import io.github.zoot.englishreader.data.repository.ArticleImporter
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookImporter
import io.github.zoot.englishreader.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 书架（文章 + 书籍）ViewModel。
 */
@HiltViewModel
class ArticleListViewModel @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val articleImporter: ArticleImporter,
    private val bookRepository: BookRepository,
    private val bookImporter: BookImporter,
    private val formatProbe: ImportFormatProbe
) : ViewModel() {

    val articles: StateFlow<List<ArticleEntity>> = articleRepository.getAllArticles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 书架条目：单篇文章 + 书籍，按时间倒序合并。
     *
     * 用 [ArticleRepository.getStandaloneArticles] 而不是 [articles]：章节也是
     * `ArticleEntity`，直接用全量文章会让一本 500 章的书在列表里铺出 500 行。
     *
     * 书的排序用 `lastReadAt ?: createdAt`：读过的书应该浮上来，而单篇文章目前
     * 没有可靠的阅读时间语义（`lastReadAt` 存在但很多路径不写），故先统一用
     * 创建时间，避免两类条目用不同语义的时间戳排在一起而顺序难以解释。
     */
    val libraryItems: StateFlow<List<LibraryItem>> = combine(
        articleRepository.getStandaloneArticles(),
        bookRepository.getAllBooks()
    ) { standalone, books ->
        (standalone.map { LibraryItem.Article(it) } + books.map { LibraryItem.Book(it) })
            .sortedByDescending { it.sortKey }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 单一事件流，界面只需一个 collector（详见 ArticleListUiEvent 的说明）
    private val _uiEvent = Channel<ArticleListUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun acknowledgeImport(importId: String) {
        val completed = _importState.value
        if (completed is ImportState.Finished && completed.importId == importId) {
            _importState.compareAndSet(completed, ImportState.Idle)
        }
    }

    fun deleteArticle(article: ArticleSummary) {
        viewModelScope.launch {
            try {
                articleRepository.deleteArticleById(article.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiEvent.trySend(ArticleListUiEvent.DeleteFailed)
            }
        }
    }

    /**
     * 从文件导入：TXT / Markdown 走 [ArticleImporter] 生成单篇文章，
     * EPUB 走 [BookImporter] 生成整本书（多章）。分流由 [ImportFormatProbe] 决定。
     *
     * 文件 IO 与 ContentResolver 访问全部委托给 importer（在 Dispatchers.IO 执行），
     * ViewModel 只负责协调、状态与事件分发。
     */
    fun importFromFile(uri: Uri) {
        launchImport {
            when (formatProbe.detect(uri)) {
                ImportFormat.EPUB -> importBook(uri)
                ImportFormat.MARKDOWN, ImportFormat.PLAIN_TEXT -> importArticle(uri)
            }
        }
    }

    /** 单篇导入（TXT / Markdown）。 */
    private suspend fun importArticle(uri: Uri): ArticleListUiEvent.ImportSucceeded {
        val imported = articleImporter.importFromUri(uri)
        val article = ArticleEntity(
            title = imported.title,
            content = imported.content,
            // source 保持 "file"：改成 txt/markdown 当前无任何消费方，
            // 只会给语义已混杂的字段再添无用信息。将来若真要按格式筛选，
            // 应新增类型明确的 importFormat 列并写 Migration。
            source = "file"
        )
        return persistArticle(article)
    }

    /**
     * 整本 EPUB 导入。
     *
     * 写库失败转 [ImportFailure.StorageFailed]，与单篇路径一致：解析成功不等于写库成功，
     * 而此刻报「源文件无法读取」会把用户往错的方向引——文件早就读完了。
     *
     * [ImportException] 原样上抛而不降级：查重命中的 [ImportFailure.DuplicateBook]
     * 带着已有书名，是给用户看的有效信息，压成 StorageFailed 就丢了。
     */
    private suspend fun importBook(uri: Uri): ArticleListUiEvent.BookImportSucceeded {
        val book = bookImporter.importFromUri(uri)
        val bookId = try {
            bookRepository.persist(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist imported book")
            throw ImportException(ImportFailure.StorageFailed)
        }
        return ArticleListUiEvent.BookImportSucceeded(
            bookId = bookId,
            title = book.metadata.title,
            chapterCount = book.chapters.size
        )
    }

    /**
     * 删除一本书。
     *
     * 走 [BookRepository.deleteBook] → `deleteBookCascade`：那里先去重并解绑生词，
     * 再删章节正文。vocabulary 对 articles 是 CASCADE，顺序颠倒会静默带走用户生词。
     */
    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            try {
                bookRepository.deleteBook(book.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete book")
                _uiEvent.trySend(ArticleListUiEvent.DeleteFailed)
            }
        }
    }

    /**
     * 从粘贴文本导入文章。
     *
     * 与文件导入走**同一套预算校验**——改造前粘贴入口完全无上限，是最明显的漏网口。
     */
    fun importFromPaste(title: String, content: String) {
        launchImport {
            val trimmedContent = content.trim()
            ImportBudgetValidator.validate(trimmedContent)

            val article = ArticleEntity(
                title = ImportBudgetValidator.normalizeTitle(title, ArticleImporter.DEFAULT_TITLE),
                content = trimmedContent,
                source = "paste"
            )
            persistArticle(article)
        }
    }

    private fun launchImport(importContent: suspend () -> ArticleListUiEvent.ImportResult) {
        val running = ImportState.Running(UUID.randomUUID().toString())
        while (true) {
            val current = _importState.value
            if (current is ImportState.Running) return
            if (_importState.compareAndSet(current, running)) break
        }
        // 同步进入 try/finally，取消也能清理已经登记的 Running。
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val event = try {
                    importContent()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: ImportException) {
                    ArticleListUiEvent.ImportFailed(failure.failure)
                } catch (_: Exception) {
                    Log.e(TAG, "Failed to import content")
                    ArticleListUiEvent.ImportFailed(ImportFailure.SourceUnreadable)
                }
                val outcome = when (event) {
                    is ArticleListUiEvent.ImportSucceeded,
                    is ArticleListUiEvent.BookImportSucceeded -> ImportOutcome.SUCCESS
                    is ArticleListUiEvent.ImportFailed -> ImportOutcome.FAILURE
                }
                _importState.compareAndSet(running, ImportState.Finished(running.importId, outcome))
                _uiEvent.trySend(event)
            } finally {
                _importState.compareAndSet(running, ImportState.Idle)
            }
        }
    }

    /**
     * 写库并发出成功事件。
     *
     * 成功事件必须在拿到 insert 返回的 ID 之后才发——解析成功不等于写库成功。
     *
     * 写库失败转成 [ImportFailure.StorageFailed] 而非落到外层的兜底 catch：那里会报
     * 「源文件无法读取」，而此刻源文件早已读完解析完，失败在存储侧。用户按错误提示
     * 去检查文件或换个文件重试都不会有任何效果，真正该做的是清理空间。
     */
    private suspend fun persistArticle(article: ArticleEntity): ArticleListUiEvent.ImportSucceeded {
        val articleId = try {
            articleRepository.insertArticle(article)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist imported article")
            throw ImportException(ImportFailure.StorageFailed)
        }
        return ArticleListUiEvent.ImportSucceeded(
            articleId = articleId,
            title = article.title,
            exceedsFullExplanationLimit =
                ImportBudgetValidator.exceedsFullExplanationLimit(article.content)
        )
    }

    private companion object {
        const val TAG = "ArticleListViewModel"
    }
}
