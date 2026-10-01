package io.github.zoot.englishreader.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migration: original articles, vocabulary and translation state stay untouched. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `excerpts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`articleId` INTEGER, `originArticleId` INTEGER NOT NULL, " +
                "`sourceTitle` TEXT NOT NULL, `contentFingerprint` TEXT NOT NULL, " +
                "`paragraphIndex` INTEGER NOT NULL, `startOffset` INTEGER NOT NULL, " +
                "`endOffset` INTEGER NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`articleId`) REFERENCES `articles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_excerpts_articleId` ON `excerpts` (`articleId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_excerpts_originArticleId_paragraphIndex_startOffset_endOffset_text` " +
                "ON `excerpts` (`originArticleId`, `paragraphIndex`, `startOffset`, `endOffset`, `text`)"
        )
    }
}
