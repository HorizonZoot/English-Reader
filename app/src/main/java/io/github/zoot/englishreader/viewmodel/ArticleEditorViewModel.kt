package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.zoot.englishreader.data.importer.ImportBudgetValidator
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.repository.ArticleImporter
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.ArticleEditorDialog
import io.github.zoot.englishreader.model.ArticleEditorError
import io.github.zoot.englishreader.model.ArticleEditorEvent
import io.github.zoot.englishreader.model.ArticleEditorState
import io.github.zoot.englishreader.util.ArticleParagraphFormatting
import io.github.zoot.englishreader.util.ParagraphAligner
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ArticleEditorViewModel @Inject constructor(
    private val articleRepository: ArticleRepository,
    private val bookRepository: BookRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val articleId = when (val value = savedStateHandle.get<Any>(ARG_ARTICLE_ID)) {
        is Long -> value
        is String -> value.toLongOrNull()
        else -> null
    }
    private val _uiState = MutableStateFlow(ArticleEditorState())
    val uiState = _uiState.asStateFlow()
    private val _events = Channel<ArticleEditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var loadJob: Job? = null
    private var pendingEdit: PendingEdit? = null
    private var hasExited = false

    private class PendingEdit(
        val original: ArticleEditSnapshot,
        val title: String,
        val content: String
    )

    init {
        retryLoad()
    }

    fun retryLoad() {
        if (hasExited || loadJob?.isActive == true || _uiState.value.original != null) return
        val id = articleId?.takeIf { it > 0 }
        if (id == null) {
            _uiState.value = ArticleEditorState(isLoading = false, error = ArticleEditorError.NotFound)
            return
        }
        _uiState.value = ArticleEditorState()
        loadJob = viewModelScope.launch {
            try {
                val article = articleRepository.getArticleById(id)
                val error = when {
                    article == null -> ArticleEditorError.NotFound
                    bookRepository.findChapterByArticleId(id) != null -> ArticleEditorError.NotStandalone
                    else -> null
                }
                if (hasExited) return@launch
                if (error != null) {
                    _uiState.value = ArticleEditorState(isLoading = false, error = error)
                } else {
                    requireNotNull(article)
                    _uiState.value = ArticleEditorState(
                        original = ArticleEditSnapshot(article.id, article.title, article.content),
                        title = article.title,
                        content = article.content,
                        isLoading = false
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = ArticleEditorState(isLoading = false, error = ArticleEditorError.LoadFailed)
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun updateTitle(title: String) {
        if (!canEdit()) return
        pendingEdit = null
        _uiState.value = _uiState.value.copy(title = title, error = null, dialog = null)
    }

    fun updateContent(content: String) {
        if (!canEdit()) return
        pendingEdit = null
        _uiState.value = _uiState.value.copy(content = content, error = null, dialog = null)
    }

    fun requestSave() {
        val state = _uiState.value
        if (!canEdit() || !state.isDirty || state.dialog != null) return
        val draft = validatedDraft(state) ?: return
        if (draft.content != draft.original.content) {
            // 确认绑定当前草稿；继续编辑会使该确认失效，不能用旧确认保存新正文。
            pendingEdit = draft
            _uiState.value = state.copy(error = null, dialog = ArticleEditorDialog.ConfirmContentSave)
        } else {
            save(draft)
        }
    }

    fun confirmContentSave() {
        if (!canEdit() || _uiState.value.dialog != ArticleEditorDialog.ConfirmContentSave) return
        val draft = pendingEdit ?: return
        save(draft)
    }

    private fun validatedDraft(state: ArticleEditorState): PendingEdit? {
        val original = state.original ?: return null
        if (state.title.isBlank()) {
            _uiState.value = state.copy(error = ArticleEditorError.TitleRequired)
            return null
        }
        val content = if (state.content == original.content) original.content else state.content.trim()
        if (content != original.content) {
            try {
                ImportBudgetValidator.validate(content)
            } catch (failure: ImportException) {
                _uiState.value = state.copy(error = ArticleEditorError.InvalidContent(failure.failure))
                return null
            }
        }
        return PendingEdit(
            original,
            ImportBudgetValidator.normalizeTitle(state.title, ArticleImporter.DEFAULT_TITLE),
            content
        )
    }

    private fun save(draft: PendingEdit) {
        pendingEdit = null
        // 在 launch 前关闸：同步双击和仍未重组的确认按钮也只能提交一次。
        _uiState.value = _uiState.value.copy(isSaving = true, error = null, dialog = null)
        viewModelScope.launch {
            try {
                val error = when (val result = articleRepository.saveEdit(draft.original, draft.title, draft.content)) {
                    ArticleEditResult.Saved -> {
                        exit()
                        null
                    }
                    ArticleEditResult.NotFound -> ArticleEditorError.NotFound
                    ArticleEditResult.NotStandalone -> ArticleEditorError.NotStandalone
                    ArticleEditResult.Conflict -> ArticleEditorError.Conflict
                    is ArticleEditResult.InvalidContent -> ArticleEditorError.InvalidContent(result.failure)
                }
                _uiState.value = _uiState.value.copy(error = error)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(error = ArticleEditorError.SaveFailed)
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    fun requestBack() {
        if (hasExited || _uiState.value.isSaving) return
        if (_uiState.value.dialog != null) {
            dismissDialog()
        } else if (_uiState.value.isDirty) {
            _uiState.value = _uiState.value.copy(dialog = ArticleEditorDialog.ConfirmDiscard)
        } else {
            exit()
        }
    }

    fun confirmDiscard() {
        if (hasExited || _uiState.value.isSaving || _uiState.value.dialog != ArticleEditorDialog.ConfirmDiscard) return
        _uiState.value = _uiState.value.copy(dialog = null)
        exit()
    }

    fun requestParagraphPreview() {
        val state = _uiState.value
        if (!canEdit() || state.dialog != null || state.content.isBlank()) return
        val candidate = ArticleParagraphFormatting.separateLines(state.content)
        if (candidate == state.content) return
        try {
            ImportBudgetValidator.validate(candidate)
        } catch (failure: ImportException) {
            _uiState.value = state.copy(error = ArticleEditorError.InvalidContent(failure.failure))
            return
        }
        _uiState.value = state.copy(
            error = null,
            dialog = ArticleEditorDialog.ParagraphPreview(candidate, ParagraphAligner.splitParagraphs(candidate).size)
        )
    }

    fun applyParagraphPreview() {
        if (!canEdit()) return
        val preview = _uiState.value.dialog as? ArticleEditorDialog.ParagraphPreview ?: return
        updateContent(preview.content)
    }

    fun dismissDialog() {
        if (_uiState.value.isSaving) return
        pendingEdit = null
        _uiState.value = _uiState.value.copy(dialog = null)
    }

    private fun canEdit(): Boolean = !hasExited && _uiState.value.canEdit

    private fun exit() {
        if (hasExited) return
        hasExited = true
        loadJob?.cancel()
        _events.trySend(ArticleEditorEvent.NAVIGATE_BACK)
    }

    companion object {
        const val ARG_ARTICLE_ID = "articleId"
    }
}
