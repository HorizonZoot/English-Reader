package io.github.zoot.englishreader.model

import io.github.zoot.englishreader.data.importer.ImportFailure

data class ArticleEditorState(
    val original: ArticleEditSnapshot? = null,
    val title: String = "",
    val content: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: ArticleEditorError? = null,
    val dialog: ArticleEditorDialog? = null
) {
    val isDirty: Boolean
        get() = original?.let { title != it.title || content != it.content } ?: false

    val canEdit: Boolean
        get() = original != null && !isLoading && !isSaving

    val canSave: Boolean
        get() = canEdit && isDirty && title.isNotBlank() && content.isNotBlank()

    override fun toString(): String =
        "ArticleEditorState(articleId=${original?.articleId}, title=[REDACTED], " +
            "content=[REDACTED], isLoading=$isLoading, isSaving=$isSaving, " +
            "error=${error?.javaClass?.simpleName}, dialog=${dialog?.javaClass?.simpleName})"
}

sealed interface ArticleEditorError {
    data object LoadFailed : ArticleEditorError
    data object NotFound : ArticleEditorError
    data object NotStandalone : ArticleEditorError
    data object Conflict : ArticleEditorError
    data object SaveFailed : ArticleEditorError
    data object TitleRequired : ArticleEditorError
    data class InvalidContent(val failure: ImportFailure) : ArticleEditorError
}

sealed interface ArticleEditorDialog {
    data object ConfirmContentSave : ArticleEditorDialog
    data object ConfirmDiscard : ArticleEditorDialog

    data class ParagraphPreview(val content: String, val paragraphCount: Int) : ArticleEditorDialog {
        override fun toString(): String =
            "ParagraphPreview(content=[REDACTED], paragraphCount=$paragraphCount)"
    }
}

enum class ArticleEditorEvent { NAVIGATE_BACK }
