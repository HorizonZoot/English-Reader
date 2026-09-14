package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [ImportException.message] 不得携带用户书名。
 *
 * 为什么值得一个专门用例：本项目对 AI request snapshot / remote DTO 强制 redacted `toString()`，
 * 而 [ImportFailure.DuplicateBook] 是 data class，其自动 `toString()` 会把 `existingTitle`
 * ——用户的书名——拼进去。只要 `ImportException` 用 `failure.toString()` 当 message，
 * 上游任何一处 `Log.e(TAG, msg, e)` 或崩溃上报都会把书名打出去。
 *
 * 当前书路径确实没有把 throwable 交给 Log，所以这不是已发生的泄漏；但「现在没人这么写」
 * 不是不变量。这个用例把它变成不变量。
 *
 * typed 信息并未丢失：调用方读 [ImportException.failure]，UI 文案由
 * [io.github.zoot.englishreader.util.ErrorMessageMapper] 从 typed 字段生成。
 */
class ImportExceptionRedactionTest {

    @Test
    fun message_duplicateBook_doesNotContainUserBookTitle() {
        val title = "Pride and Prejudice"
        val e = ImportException(ImportFailure.DuplicateBook(existingBookId = 7L, existingTitle = title))

        assertFalse(
            "user book title leaked into ImportException.message: ${e.message}",
            e.message.orEmpty().contains(title)
        )
        assertEquals("DuplicateBook", e.message)
    }

    @Test
    fun failure_remainsReadableForTypedHandling() {
        val failure = ImportFailure.DuplicateBook(existingBookId = 7L, existingTitle = "Some Book")
        val e = ImportException(failure)

        // 脱敏只作用于 message；typed 字段必须完整，否则界面无法提示是哪一本书。
        assertEquals(failure, e.failure)
        assertEquals("Some Book", (e.failure as ImportFailure.DuplicateBook).existingTitle)
    }

    @Test
    fun message_isTypeNameForEveryFailure() {
        val cases = listOf(
            ImportFailure.InvalidEpub to "InvalidEpub",
            ImportFailure.EncryptedEpub to "EncryptedEpub",
            ImportFailure.BookArchiveTooManyEntries(2_000) to "BookArchiveTooManyEntries",
            ImportFailure.BookTooLong(5_000_000, 4_000_000) to "BookTooLong"
        )
        cases.forEach { (failure, expected) ->
            assertEquals(expected, ImportException(failure).message)
        }
    }
}
