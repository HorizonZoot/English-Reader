package io.github.zoot.englishreader.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移：版本 1 → 2
 *
 * 变更内容：
 * - vocabulary 表添加 phonetic（音标）字段
 * - vocabulary 表添加 definitions（词性+解释 JSON）字段
 * - vocabulary 表添加 definitionSource（解释来源）字段
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 添加音标字段
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN phonetic TEXT")

        // 添加词性和解释字段（JSON 格式）
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN definitions TEXT")

        // 添加解释来源字段
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN definitionSource TEXT")
    }
}

/**
 * 数据库迁移：版本 2 → 3
 *
 * 变更内容：
 * - 创建 dictionary 表（离线词典）
 * - articles 表添加 translation（中文译文）字段
 *
 * **重要提示**：
 * - 不支持降级（v3 → v2）。降级时直接抛异常即为预期行为——**不要**添加
 *   `fallbackToDestructiveMigrationOnDowngrade()`：迁移缺失时崩溃优于静默删库。
 * - 中文释义使用"；"分隔符（不支持转义，字段内不应包含此字符）
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建离线词典表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dictionary (
                word TEXT PRIMARY KEY NOT NULL,
                phonetic TEXT,
                chinese TEXT NOT NULL,
                english TEXT
            )
        """.trimIndent())

        // 添加文章译文字段
        db.execSQL("ALTER TABLE articles ADD COLUMN translation TEXT")
    }
}

/**
 * 数据库迁移：版本 3 → 版本 4
 *
 * 变更内容：新增整本 EPUB 导入所需的三张表
 * - books：书籍元数据与去重键
 * - book_chapters：章节归属与阅读顺序（正文仍在 articles）
 * - book_reading_progress：每本书的阅读位置
 *
 * **SQL 必须与 Room 导出的 4.json 完全一致**（列顺序、NOT NULL、FK 子句、index 名），
 * 否则 MigrationTestHelper 的 schema 校验会失败。这不是形式主义：不一致意味着
 * 「迁移后的库」与「全新安装的库」结构不同，后续迁移会在两种结构上产生分叉行为。
 *
 * 章节正文复用 articles，故 book_chapters.articleId 对 articles 是 CASCADE。
 * 但删书流程**不能**只删 books 行就指望级联清理正文——那只会级联删掉关系行，
 * 留下孤儿 article。正确顺序见 BookDao.deleteBookCascade。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `books` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`author` TEXT, " +
                "`language` TEXT, " +
                "`identifier` TEXT, " +
                "`contentFingerprint` TEXT NOT NULL, " +
                "`sourceFormat` TEXT NOT NULL, " +
                "`chapterCount` INTEGER NOT NULL, " +
                "`totalChars` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastReadAt` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_identifier` ON `books` (`identifier`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_books_contentFingerprint` " +
                "ON `books` (`contentFingerprint`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `book_chapters` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, " +
                "`articleId` INTEGER NOT NULL, " +
                "`chapterIndex` INTEGER NOT NULL, " +
                "`sourceHref` TEXT NOT NULL, " +
                "`navigationTitle` TEXT, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`articleId`) REFERENCES `articles`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_chapters_bookId_chapterIndex` " +
                "ON `book_chapters` (`bookId`, `chapterIndex`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_chapters_articleId` " +
                "ON `book_chapters` (`articleId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_book_chapters_bookId` " +
                "ON `book_chapters` (`bookId`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `book_reading_progress` (" +
                "`bookId` INTEGER NOT NULL, " +
                "`chapterArticleId` INTEGER NOT NULL, " +
                "`paragraphIndex` INTEGER NOT NULL, " +
                "`paragraphOffset` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bookId`), " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`chapterArticleId`) REFERENCES `articles`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_book_reading_progress_chapterArticleId` " +
                "ON `book_reading_progress` (`chapterArticleId`)"
        )
    }
}

/** 旧段内偏移是像素，只迁移可确认的章节和段落位置，保留所有旧记录。 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_positions` (" +
                "`articleId` INTEGER NOT NULL, " +
                "`paragraphIndex` INTEGER NOT NULL, " +
                "`textKind` TEXT NOT NULL, " +
                "`characterOffset` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`articleId`), " +
                "FOREIGN KEY(`articleId`) REFERENCES `articles`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT INTO reading_positions (articleId, paragraphIndex, textKind, characterOffset, updatedAt) " +
                "SELECT chapterArticleId, MAX(paragraphIndex, 0), 'ORIGINAL', 0, updatedAt " +
                "FROM book_reading_progress"
        )
    }
}

/**
 * 数据库迁移：版本 5 → 版本 6
 *
 * 变更内容：新增可恢复全文段落翻译所需的三张表
 * - whole_translation_tasks：任务状态与范围标识
 * - translation_task_articles：目标 article 的处理顺序与创建时正文指纹
 * - translation_segments：逐段 checkpoint、lease 与失败分类
 *
 * **纯新增**：不改动 articles、vocabulary 或任何既有表的列，因此旧数据零改写。
 * 段落译文在全部成功前只留在 translation_segments 里；`ArticleEntity.translation`
 * 由 materialization 事务一次性写入，故本次迁移不需要回填任何译文。
 *
 * **SQL 必须与 Room 导出的 6.json 完全一致**（列顺序、NOT NULL、FK 子句、index 名），
 * 否则 MigrationTestHelper 的 schema 校验会失败。理由与 MIGRATION_3_4 相同：不一致意味着
 * 「迁移后的库」与「全新安装的库」结构不同，后续迁移会在两种结构上产生分叉行为。
 *
 * 两张子表对 whole_translation_tasks 是 CASCADE，删任务即清理其 article 与段落行。
 * 但**都不**对 articles 设外键：article 被删时应当让 materialization 显式检测到
 * 「目标文章已不存在」并拒绝提交，而不是让 CASCADE 静默缩小任务范围。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `whole_translation_tasks` (" +
                "`taskId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`scopeKey` TEXT NOT NULL, " +
                "`bookId` INTEGER, " +
                "`status` TEXT NOT NULL, " +
                "`failureReason` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_whole_translation_tasks_scopeKey` " +
                "ON `whole_translation_tasks` (`scopeKey`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `translation_task_articles` (" +
                "`taskId` INTEGER NOT NULL, " +
                "`articleId` INTEGER NOT NULL, " +
                "`ordinal` INTEGER NOT NULL, " +
                "`articleFingerprint` TEXT NOT NULL, " +
                "PRIMARY KEY(`taskId`, `articleId`), " +
                "FOREIGN KEY(`taskId`) REFERENCES `whole_translation_tasks`(`taskId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_translation_task_articles_taskId` " +
                "ON `translation_task_articles` (`taskId`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `translation_segments` (" +
                "`taskId` INTEGER NOT NULL, " +
                "`articleId` INTEGER NOT NULL, " +
                "`paragraphIndex` INTEGER NOT NULL, " +
                "`sourceFingerprint` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`translatedText` TEXT, " +
                "`failureReason` TEXT, " +
                "`attemptCount` INTEGER NOT NULL, " +
                "`leaseExpiresAt` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`taskId`, `articleId`, `paragraphIndex`), " +
                "FOREIGN KEY(`taskId`) REFERENCES `whole_translation_tasks`(`taskId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_translation_segments_taskId_status` " +
                "ON `translation_segments` (`taskId`, `status`)"
        )
    }
}
