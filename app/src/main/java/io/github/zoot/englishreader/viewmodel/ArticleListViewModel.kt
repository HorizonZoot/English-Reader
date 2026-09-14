package io.github.zoot.englishreader.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zoot.englishreader.data.entity.ArticleEntity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    /**
     * 导入进行中。
     *
     * EPUB 的复制与解析明显耗时，须有加载态；界面据此禁止重复导入——
     * 连点两次会写入两篇重复文章。
     *
     * 这只是**给界面看的状态**，不承担并发闸门职责：闸门是 [importJob]，
     * 因为「读 value → launch → 在协程里置 true」之间存在窗口。
     */
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /**
     * 当前导入任务，兼作 single-flight 闸门。
     *
     * 不能用 `if (_isImporting.value) return` 把关：该标志直到协程体内才被置 true，
     * 两次同步调用可能都看到 false，于是启动两个导入协程——重复解析、重复写库、
     * 甚至文件导入与粘贴导入并发。而 `viewModelScope.launch` 返回的 Job 在**launch
     * 返回时**就已存在，赋值发生在调用方线程上，故检查 `isActive` 才是可靠的门禁。
     */
    private var importJob: Job? = null

    fun deleteArticle(article: ArticleEntity) {
        viewModelScope.launch {
            try {
                articleRepository.deleteArticle(article)
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
        // 导入期间拒绝新请求：EPUB 解析耗时长，用户很容易连点
        if (importJob?.isActive == true) return

        importJob = viewModelScope.launch {
            _isImporting.value = true
            try {
                // 先探测格式再分流：EPUB 走整本书（多章），TXT/Markdown 走单篇。
                //
                // EPUB 不再合成一篇文章：合并后正文可达单篇上限的十几倍，要么被
                // MAX_IMPORT_CHARS 直接拒掉，要么变成一个无法导航的巨大页面。
                when (formatProbe.detect(uri)) {
                    ImportFormat.EPUB -> importBook(uri)
                    ImportFormat.MARKDOWN, ImportFormat.PLAIN_TEXT -> importArticle(uri)
                }
            } catch (e: CancellationException) {
                // 协程取消（用户退出页面）：不写半成品，临时文件由 importer 的 finally 删除
                throw e
            } catch (e: ImportException) {
                _uiEvent.trySend(ArticleListUiEvent.ImportFailed(e.failure))
            } catch (e: Exception) {
                // 导入异常里可能嵌着 content URI，不要把来源标识写进 Logcat。
                Log.e(TAG, "Failed to import selected document")
                _uiEvent.trySend(ArticleListUiEvent.ImportFailed(ImportFailure.SourceUnreadable))
            } finally {
                _isImporting.value = false
            }
        }
    }

    /** 单篇导入（TXT / Markdown）。 */
    private suspend fun importArticle(uri: Uri) {
        val imported = articleImporter.importFromUri(uri)
        val article = ArticleEntity(
            title = imported.title,
            content = imported.content,
            // source 保持 "file"：改成 txt/markdown 当前无任何消费方，
            // 只会给语义已混杂的字段再添无用信息。将来若真要按格式筛选，
            // 应新增类型明确的 importFormat 列并写 Migration。
            source = "file"
        )
        emitImportSucceeded(article)
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
    private suspend fun importBook(uri: Uri) {
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
        _uiEvent.trySend(
            ArticleListUiEvent.BookImportSucceeded(
                bookId = bookId,
                title = book.metadata.title,
                chapterCount = book.chapters.size
            )
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
        if (importJob?.isActive == true) return

        importJob = viewModelScope.launch {
            _isImporting.value = true
            try {
                val trimmedContent = content.trim()
                ImportBudgetValidator.validate(trimmedContent)

                val article = ArticleEntity(
                    // 与文件导入共用 normalizeTitle：标题上限原先只在 ArticleImporter 私有
                    // 方法里，粘贴路径完全绕过它（singleLine 只控制显示，不限制输入长度）
                    title = ImportBudgetValidator.normalizeTitle(title, ArticleImporter.DEFAULT_TITLE),
                    content = trimmedContent,
                    source = "paste"
                )
                emitImportSucceeded(article)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                _uiEvent.trySend(ArticleListUiEvent.ImportFailed(e.failure))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import pasted text")
                _uiEvent.trySend(ArticleListUiEvent.ImportFailed(ImportFailure.SourceUnreadable))
            } finally {
                _isImporting.value = false
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
    private suspend fun emitImportSucceeded(article: ArticleEntity) {
        val articleId = try {
            articleRepository.insertArticle(article)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist imported article")
            throw ImportException(ImportFailure.StorageFailed)
        }
        _uiEvent.trySend(
            ArticleListUiEvent.ImportSucceeded(
                articleId = articleId,
                title = article.title,
                exceedsFullExplanationLimit =
                    ImportBudgetValidator.exceedsFullExplanationLimit(article.content)
            )
        )
    }

    private companion object {
        const val TAG = "ArticleListViewModel"
    }
}
