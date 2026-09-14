package io.github.zoot.englishreader.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration 的真实 SQLite 验证。
 *
 * 这里必须用 androidTest 而非 JVM：MigrationTestHelper 校验的是真实 SQLite 的
 * PRAGMA 结果，Robolectric 下的行为不足以证明用户升级后不丢数据。
 *
 * 历史 v1/v2 的导出文件已不存在，因此起点是 v3 的实际形态：后续 migration 必须在它
 * 之上构建，而不是凭空编造已废弃的 schema。
 */
@RunWith(AndroidJUnit4::class)
class EnglishReaderDatabaseMigrationAndroidTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EnglishReaderDatabase::class.java
    )

    @Test
    fun exportedV3Baseline_opensWithCurrentDatabaseAndMigrations() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 3).close()
        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            6,
            true,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6
        ).close()
    }

    /**
     * v3 → v4 不得丢失任何既有用户数据。
     *
     * 只验「新增表」会漏掉真正危险的失败模式：migration 里若误用 recreate-and-copy
     * 而漏掉某张表，schema 校验仍会通过，但用户的文章、生词和词典已经没了。
     * 因此先写入四张表的真实行，迁移后逐张回读。
     */
    @Test
    fun migration3To4_preservesExistingUserData() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 3).apply {
            execSQL(
                "INSERT INTO articles (id, title, content, source, createdAt, lastReadAt, translation) " +
                    "VALUES (1, 'Kept article', 'Body text.', 'user', 100, 200, 'ChineseText')"
            )
            execSQL(
                "INSERT INTO vocabulary " +
                    "(id, word, articleId, createdAt, phonetic, definitions, definitionSource) " +
                    "VALUES (1, 'kept', 1, 100, 'kept', 'a;b', 'offline')"
            )
            execSQL(
                "INSERT INTO dictionary (word, phonetic, chinese, english) " +
                    "VALUES ('kept', 'kept', 'BaoLiu', 'to keep')"
            )
            execSQL(
                "INSERT INTO explanation_cache " +
                    "(cacheKey, explanation, createdAt) VALUES ('k1', 'cached', 100)"
            )
            close()
        }

        val db = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            4,
            true,
            MIGRATION_3_4
        )

        db.query("SELECT title, translation FROM articles WHERE id = 1").use { c ->
            assertTrue("article row must survive migration", c.moveToFirst())
            assertEquals("Kept article", c.getString(0))
            assertEquals("ChineseText", c.getString(1))
        }
        db.query("SELECT word, articleId FROM vocabulary WHERE id = 1").use { c ->
            assertTrue("vocabulary row must survive migration", c.moveToFirst())
            assertEquals("kept", c.getString(0))
            assertEquals(1L, c.getLong(1))
        }
        db.query("SELECT chinese FROM dictionary WHERE word = 'kept'").use { c ->
            assertTrue("dictionary row must survive migration", c.moveToFirst())
            assertEquals("BaoLiu", c.getString(0))
        }
        db.query("SELECT explanation FROM explanation_cache WHERE cacheKey = 'k1'").use { c ->
            assertTrue("explanation cache row must survive migration", c.moveToFirst())
            assertEquals("cached", c.getString(0))
        }

        // 新表必须存在且为空
        for (table in listOf("books", "book_chapters", "book_reading_progress")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue("table $table must exist after migration", c.moveToFirst())
                assertEquals("new table $table must start empty", 0, c.getInt(0))
            }
        }

        db.close()
    }

    /**
     * v4 的三张新表必须具备真实生效的外键与唯一约束。
     *
     * schema 校验只比对 DDL 文本，不证明约束在运行时会拦截。这里直接写入违规行，
     * 断言 SQLite 抛错——否则「唯一章节序号」和「一 article 只属一书」都只是注释。
     */
    @Test
    fun migration3To4_newTablesEnforceConstraints() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 3).close()
        val db = migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            4,
            true,
            MIGRATION_3_4
        )
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL(
            "INSERT INTO articles (id, title, content, createdAt) " +
                "VALUES (10, 'Ch1', 'Body.', 100)"
        )
        db.execSQL(
            "INSERT INTO books " +
                "(id, title, contentFingerprint, sourceFormat, chapterCount, totalChars, createdAt) " +
                "VALUES (1, 'Book', 'fp', 'epub3', 1, 5, 100)"
        )
        db.execSQL(
            "INSERT INTO book_chapters (id, bookId, articleId, chapterIndex, sourceHref) " +
                "VALUES (1, 1, 10, 0, 'ch1.xhtml')"
        )

        // 同书同序号
        assertThrowsSqlite(db, "duplicate (bookId, chapterIndex) must be rejected") {
            it.execSQL(
                "INSERT INTO articles (id, title, content, createdAt) VALUES (11, 'Ch2', 'B.', 100)"
            )
            it.execSQL(
                "INSERT INTO book_chapters (id, bookId, articleId, chapterIndex, sourceHref) " +
                    "VALUES (2, 1, 11, 0, 'ch2.xhtml')"
            )
        }

        // 同一 article 属于两个章节位置
        assertThrowsSqlite(db, "duplicate articleId must be rejected") {
            it.execSQL(
                "INSERT INTO book_chapters (id, bookId, articleId, chapterIndex, sourceHref) " +
                    "VALUES (3, 1, 10, 1, 'dup.xhtml')"
            )
        }

        // 不存在的 bookId
        assertThrowsSqlite(db, "missing bookId must be rejected") {
            it.execSQL(
                "INSERT INTO book_chapters (id, bookId, articleId, chapterIndex, sourceHref) " +
                    "VALUES (4, 999, 11, 5, 'orphan.xhtml')"
            )
        }

        // 删书应级联清掉关系行与进度行，但不动 articles
        db.execSQL(
            "INSERT INTO book_reading_progress " +
                "(bookId, chapterArticleId, paragraphIndex, paragraphOffset, updatedAt) " +
                "VALUES (1, 10, 0, 0, 100)"
        )
        db.execSQL("DELETE FROM books WHERE id = 1")
        db.query("SELECT COUNT(*) FROM book_chapters").use { c ->
            c.moveToFirst()
            assertEquals("book_chapters must cascade on book delete", 0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM book_reading_progress").use { c ->
            c.moveToFirst()
            assertEquals("progress must cascade on book delete", 0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM articles WHERE id = 10").use { c ->
            c.moveToFirst()
            assertEquals("chapter article must NOT cascade on book delete", 1, c.getInt(0))
        }

        db.close()
    }

    private fun assertThrowsSqlite(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        message: String,
        block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit
    ) {
        val threw = try {
            block(db)
            false
        } catch (_: android.database.SQLException) {
            true
        }
        assertTrue(message, threw)
    }

    @Test
    fun migration4To5_preservesBookAndLegacyPixelsWhileSeedingCharacterAnchor() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 4).apply {
            execSQL("INSERT INTO articles (id, title, content, createdAt) VALUES (10, 'Chapter', 'Body.', 100)")
            execSQL(
                "INSERT INTO books (id, title, contentFingerprint, sourceFormat, chapterCount, totalChars, createdAt) " +
                    "VALUES (7, 'Kept book', 'fp', 'epub3', 1, 5, 100)"
            )
            execSQL("INSERT INTO book_chapters (id, bookId, articleId, chapterIndex, sourceHref) VALUES (1, 7, 10, 0, 'chapter.xhtml')")
            execSQL(
                "INSERT INTO book_reading_progress (bookId, chapterArticleId, paragraphIndex, paragraphOffset, updatedAt) " +
                    "VALUES (7, 10, 3, 130, 200)"
            )
            execSQL("INSERT INTO vocabulary (id, word, articleId, createdAt) VALUES (1, 'kept', 10, 100)")
            close()
        }
        val db = migrationHelper.runMigrationsAndValidate(TEST_DATABASE_NAME, 5, true, MIGRATION_4_5)
        db.query("SELECT paragraphIndex, textKind, characterOffset, updatedAt FROM reading_positions WHERE articleId = 10").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
            assertEquals("ORIGINAL", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(200L, c.getLong(3))
        }
        db.query("SELECT chapterArticleId, paragraphOffset FROM book_reading_progress WHERE bookId = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(10L, c.getLong(0))
            assertEquals(130, c.getInt(1))
        }
        for (table in listOf("articles", "books", "book_chapters", "vocabulary")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table data must survive", 1, c.getInt(0))
            }
        }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM articles WHERE id = 10")
        db.query("SELECT COUNT(*) FROM reading_positions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    /**
     * v5 → v6 只新增三张表，既有用户数据必须逐字不变。
     *
     * 全文翻译的 checkpoint 表与任何既有表都没有列级交集，因此这次迁移的风险不在「转换错」
     * 而在「误动」：若迁移里不小心 recreate 了 articles（例如为了加索引），用户的文章与已有
     * 译文会被静默清空，而 schema 校验照样通过。所以这里写入五张表的真实行再逐张回读。
     */
    @Test
    fun migration5To6_addsTranslationTablesAndPreservesExistingData() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 5).apply {
            execSQL(
                "INSERT INTO articles (id, title, content, translation, createdAt) " +
                    "VALUES (10, 'Kept', 'Body text.', 'YiWen', 100)"
            )
            execSQL("INSERT INTO vocabulary (id, word, articleId, createdAt) VALUES (1, 'kept', 10, 100)")
            execSQL("INSERT INTO dictionary (word, phonetic, chinese, english) VALUES ('kept', 'k', 'BaoLiu', 'keep')")
            execSQL("INSERT INTO explanation_cache (cacheKey, explanation, createdAt) VALUES ('k1', 'cached', 100)")
            execSQL(
                "INSERT INTO reading_positions (articleId, paragraphIndex, textKind, characterOffset, updatedAt) " +
                    "VALUES (10, 2, 'ORIGINAL', 42, 200)"
            )
            close()
        }

        val db = migrationHelper.runMigrationsAndValidate(TEST_DATABASE_NAME, 6, true, MIGRATION_5_6)

        // 既有数据逐字不变，尤其是 articles.translation：全文翻译的最终写入目标就是这一列，
        // 迁移阶段动了它等于在功能还没启用前就破坏用户已有译文。
        db.query("SELECT title, content, translation FROM articles WHERE id = 10").use { c ->
            assertTrue("article row must survive", c.moveToFirst())
            assertEquals("Kept", c.getString(0))
            assertEquals("Body text.", c.getString(1))
            assertEquals("YiWen", c.getString(2))
        }
        db.query("SELECT characterOffset FROM reading_positions WHERE articleId = 10").use { c ->
            assertTrue("reading position must survive", c.moveToFirst())
            assertEquals(42, c.getInt(0))
        }
        for (table in listOf("vocabulary", "dictionary", "explanation_cache")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table data must survive", 1, c.getInt(0))
            }
        }

        for (table in listOf(
            "whole_translation_tasks",
            "translation_task_articles",
            "translation_segments"
        )) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue("table $table must exist after migration", c.moveToFirst())
                assertEquals("new table $table must start empty", 0, c.getInt(0))
            }
        }

        db.close()
    }

    /**
     * v6 的新表必须真正级联清理段落 checkpoint。
     *
     * schema 校验只比对 DDL 文本，不证明外键在运行时生效。删任务时若段落行残留，下一次同源
     * 任务会读到上一个任务的段落，进度分母与实际请求量都会错。
     */
    @Test
    fun migration5To6_deletingTaskCascadesToSegmentsAndTargets() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 5).close()
        val db = migrationHelper.runMigrationsAndValidate(TEST_DATABASE_NAME, 6, true, MIGRATION_5_6)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO whole_translation_tasks " +
                "(taskId, scopeKey, bookId, status, failureReason, createdAt, updatedAt) " +
                "VALUES (1, 'article:10', NULL, 'paused', NULL, 100, 100)"
        )
        db.execSQL(
            "INSERT INTO translation_task_articles (taskId, articleId, ordinal, articleFingerprint) " +
                "VALUES (1, 10, 0, 'fp-article')"
        )
        db.execSQL(
            "INSERT INTO translation_segments " +
                "(taskId, articleId, paragraphIndex, sourceFingerprint, status, translatedText, " +
                "failureReason, attemptCount, leaseExpiresAt, updatedAt) " +
                "VALUES (1, 10, 0, 'fp-p0', 'untranslated', NULL, NULL, 0, NULL, 100)"
        )

        db.execSQL("DELETE FROM whole_translation_tasks WHERE taskId = 1")

        for (table in listOf("translation_task_articles", "translation_segments")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table must cascade when its task is deleted", 0, c.getInt(0))
            }
        }

        db.close()
    }

    private companion object {
        const val TEST_DATABASE_NAME = "english_reader_migration_baseline.db"
    }
}
