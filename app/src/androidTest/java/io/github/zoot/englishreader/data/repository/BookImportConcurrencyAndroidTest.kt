package io.github.zoot.englishreader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.dao.BookDao
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.importer.BookFormat
import io.github.zoot.englishreader.data.importer.BookMetadata
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.ImportedBook
import io.github.zoot.englishreader.data.importer.ImportedChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookImportConcurrencyAndroidTest {
    private lateinit var db: EnglishReaderDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            EnglishReaderDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun persist_concurrentMatchingIdentifier_rejectsOneWholeBook() = runBlocking {
        assertOneDuplicate(concurrentImports(book("same", "first"), book("same", "second")))
    }

    @Test
    fun persist_concurrentMatchingFingerprint_rejectsOneWholeBook() = runBlocking {
        assertOneDuplicate(concurrentImports(book(null, "same"), book(null, "same")))
    }

    @Test
    fun persist_concurrentDifferentBooks_keepsBothWholeBooks() = runBlocking {
        val results = concurrentImports(book("first", "first"), book("second", "second"))
        assertTrue(results.all { it.isSuccess })
        assertEquals(2, results.map { it.getOrThrow() }.distinct().size)
        assertStoredBookCounts(2)
    }

    @Test
    fun persist_concurrentExplicitDuplicates_keepsBothWholeBooks() = runBlocking {
        val results = concurrentImports(book("same", "same"), book("same", "same"), allowDuplicate = true)
        assertTrue(results.all { it.isSuccess })
        assertEquals(2, results.map { it.getOrThrow() }.distinct().size)
        assertStoredBookCounts(2)
    }

    @Test
    fun persist_cancelBeforeCommit_rollsBackWithoutReservingIdentity() = runBlocking {
        val repository = BookRepository(db.bookDao(), db.articleDao())
        val written = CompletableDeferred<Unit>()
        val import = launch(Dispatchers.IO) {
            db.withTransaction {
                repository.persist(book("same", "same"))
                written.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(5_000) { written.await() }
        } finally {
            import.cancelAndJoin()
        }
        assertStoredBookCounts(0)
        repository.persist(book("same", "same"))
        assertStoredBookCounts(1)
    }

    private suspend fun concurrentImports(
        first: ImportedBook,
        second: ImportedBook,
        allowDuplicate: Boolean = false
    ): List<Result<Long>> = coroutineScope {
        val entered = Channel<Unit>(2)
        val release = CompletableDeferred<Unit>()
        val dao = db.bookDao()
        // Synchronize at the transaction boundary: any repository-side precheck has already run.
        val gatedDao = object : BookDao by dao {
            override suspend fun insertBookWithChapters(
                book: BookEntity,
                chapters: List<Pair<ArticleEntity, BookChapterEntity>>,
                allowDuplicate: Boolean
            ): Long {
                entered.send(Unit)
                release.await()
                return dao.insertBookWithChapters(book, chapters, allowDuplicate)
            }
        }
        val pending = listOf(first, second).map { imported ->
            val repository = BookRepository(gatedDao, db.articleDao())
            async(Dispatchers.IO) {
                try {
                    Result.success(repository.persist(imported, allowDuplicate))
                } catch (error: ImportException) {
                    Result.failure<Long>(error)
                }
            }
        }
        try {
            withTimeout(5_000) {
                repeat(2) { entered.receive() }
                release.complete(Unit)
                pending.map { it.await() }
            }
        } finally {
            pending.forEach { it.cancel() }
            entered.close()
        }
    }

    private suspend fun assertOneDuplicate(results: List<Result<Long>>) {
        val winner = results.single { it.isSuccess }.getOrThrow()
        val error = results.single { it.isFailure }.exceptionOrNull() as ImportException
        val duplicate = error.failure as ImportFailure.DuplicateBook
        assertEquals(winner, duplicate.existingBookId)
        assertEquals("Book", duplicate.existingTitle)
        assertStoredBookCounts(1)
        assertEquals(2, db.bookDao().getChaptersOnce(winner).size)
    }

    private suspend fun assertStoredBookCounts(expected: Int) = withContext(Dispatchers.IO) {
        for ((table, rows) in listOf(
            "books" to expected,
            "book_chapters" to expected * 2,
            "articles" to expected * 2,
            "book_reading_progress" to expected
        )) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(table, rows, it.getInt(0))
            }
        }
    }

    private fun book(identifier: String?, fingerprint: String) = ImportedBook(
        metadata = BookMetadata(
            title = "Book",
            author = null,
            language = "en",
            identifier = identifier,
            contentFingerprint = fingerprint,
            sourceFormat = BookFormat.EPUB3
        ),
        chapters = (0..1).map { index ->
            ImportedChapter(index, "Chapter $index", "chapter$index.xhtml", null, "Body $index")
        },
        toc = emptyList()
    )
}
