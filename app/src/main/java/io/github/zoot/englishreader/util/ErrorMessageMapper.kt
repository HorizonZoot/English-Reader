package io.github.zoot.englishreader.util

import android.content.Context
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.importer.ImportFailure

/**
 * 错误消息映射器
 *
 * 将 ViewModel 层的错误信息映射到本地化的用户友好消息
 */
object ErrorMessageMapper {

    /**
     * 将导入失败原因映射为具体提示。
     *
     * 每种失败都有自己的文案——改造前全部失败都显示「文件内容为空」，
     * 用户拿到的信息不足以判断该怎么处理。
     */
    fun mapImportFailure(context: Context, failure: ImportFailure): String = when (failure) {
        is ImportFailure.EmptyContent ->
            context.getString(R.string.error_import_empty)

        is ImportFailure.SourceTooLarge ->
            // 读到字节上限即停时并不知道总字符数，只能报安全上限本身
            context.getString(
                R.string.error_import_source_too_large,
                failure.limitBytes / (1024 * 1024)
            )

        is ImportFailure.ContentTooLong ->
            // 已成功解码，字数已知，可精确提示
            context.getString(
                R.string.error_import_content_too_long,
                failure.actualChars,
                failure.limitChars
            )

        is ImportFailure.ChapterTooLong ->
            // 与 ContentTooLong 分开：章节边界由出版方决定，用户无从干预。
            // 文案必须说明这是「这本书的章节结构不受支持」，而不是暗示某段内容有问题——
            // 后者会让用户去找更短的文章，而真正可行的动作只有换一本书。
            context.getString(
                R.string.error_import_chapter_too_long,
                failure.chapterTitle,
                failure.actualChars,
                failure.limitChars
            )

        is ImportFailure.TooManyParagraphs ->
            context.getString(
                R.string.error_import_too_many_paragraphs,
                failure.actualParagraphs,
                failure.limitParagraphs
            )

        is ImportFailure.ParagraphTooLong ->
            context.getString(
                R.string.error_import_paragraph_too_long,
                failure.actualChars,
                failure.limitChars
            )

        is ImportFailure.UnsupportedEncoding ->
            context.getString(R.string.error_import_unsupported_encoding)

        is ImportFailure.UnsupportedFormat ->
            context.getString(R.string.error_import_unsupported_format)

        is ImportFailure.InvalidEpub ->
            context.getString(R.string.error_import_invalid_epub)

        is ImportFailure.EncryptedEpub ->
            context.getString(R.string.error_import_encrypted_epub)

        is ImportFailure.SourceUnreadable ->
            context.getString(R.string.error_import_source_unreadable)

        // 与 SourceUnreadable 分开：源文件读得好好的，是写库/存储那一侧失败了。
        // 提示「无法读取文件」会让用户去检查文件本身，方向完全错了。
        is ImportFailure.StorageFailed ->
            context.getString(R.string.error_import_storage_failed)

        is ImportFailure.BookTooManyChapters ->
            context.getString(
                R.string.error_import_book_too_many_chapters,
                failure.actualChapters,
                failure.limitChapters
            )

        is ImportFailure.BookTooLong ->
            context.getString(
                R.string.error_import_book_too_long,
                failure.actualChars,
                failure.limitChars
            )

        is ImportFailure.BookArchiveTooManyEntries ->
            context.getString(
                R.string.error_import_book_archive_too_many_entries,
                failure.limitEntries
            )

        is ImportFailure.BookArchiveTooLarge ->
            context.getString(
                R.string.error_import_book_archive_too_large,
                failure.limitBytes
            )

        is ImportFailure.DuplicateBook ->
            context.getString(R.string.error_import_duplicate_book, failure.existingTitle)

        // 与 InvalidEpub 分开：纯图片书的结构完全合法，说它「已损坏」会让用户去排查文件本身
        is ImportFailure.NoReadableChapters ->
            context.getString(R.string.error_import_no_readable_chapters)
    }

    /**
     * 导入成功提示。
     *
     * 超过全文 AI 解释上限时**只提示、不拦截**——文章可以正常阅读与逐句解释。
     */
    fun mapImportSuccess(
        context: Context,
        title: String,
        exceedsFullExplanationLimit: Boolean
    ): String = if (exceedsFullExplanationLimit) {
        context.getString(R.string.import_succeeded_explanation_limit, title)
    } else {
        context.getString(R.string.import_succeeded, title)
    }
}
