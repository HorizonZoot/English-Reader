package io.github.zoot.englishreader.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `context_cards` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `articleId` INTEGER, `originArticleId` INTEGER NOT NULL,
                `sourceTitle` TEXT NOT NULL, `contentFingerprint` TEXT NOT NULL,
                `paragraphIndex` INTEGER NOT NULL, `sentenceStartOffset` INTEGER NOT NULL,
                `sentenceEndOffset` INTEGER NOT NULL, `sentence` TEXT NOT NULL,
                `targetStartOffset` INTEGER NOT NULL, `targetEndOffset` INTEGER NOT NULL,
                `word` TEXT NOT NULL, `dictionaryWord` TEXT NOT NULL, `meaning` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `dueDay` INTEGER NOT NULL,
                `pendingStep` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL,
                `lastReviewedAt` INTEGER, `answerRevision` INTEGER NOT NULL,
                FOREIGN KEY(`articleId`) REFERENCES `articles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX `index_context_cards_articleId` ON `context_cards` (`articleId`)")
        db.execSQL("CREATE INDEX `index_context_cards_dueDay` ON `context_cards` (`dueDay`)")
        db.execSQL(
            """CREATE UNIQUE INDEX `index_context_cards_occurrence` ON `context_cards`
                (`originArticleId`, `contentFingerprint`, `paragraphIndex`, `sentenceStartOffset`,
                 `sentenceEndOffset`, `targetStartOffset`, `targetEndOffset`, `word`)""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `context_review_session` (
                `id` INTEGER NOT NULL, `sessionToken` TEXT NOT NULL, `localDay` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `initialCount` INTEGER NOT NULL,
                `answerCount` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `context_review_items` (
                `itemToken` TEXT NOT NULL, `sessionToken` TEXT NOT NULL, `cardId` INTEGER NOT NULL,
                `answerRevision` INTEGER NOT NULL, `meaningSnapshot` TEXT NOT NULL,
                `position` INTEGER NOT NULL, `isRetry` INTEGER NOT NULL,
                `eligibleAfterCount` INTEGER NOT NULL, `revealed` INTEGER NOT NULL,
                `grade` TEXT, `ratedAt` INTEGER, `ratedDay` INTEGER,
                `appliedIntervalDays` INTEGER, `nextPendingStep` INTEGER, `skipped` INTEGER NOT NULL,
                PRIMARY KEY(`itemToken`),
                FOREIGN KEY(`cardId`) REFERENCES `context_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX `index_context_review_items_cardId` ON `context_review_items` (`cardId`)")
        db.execSQL(
            "CREATE INDEX `index_context_review_items_sessionToken_position` ON `context_review_items` (`sessionToken`, `position`)"
        )
        db.execSQL(
            """CREATE UNIQUE INDEX `index_context_review_items_sessionToken_cardId_isRetry`
                ON `context_review_items` (`sessionToken`, `cardId`, `isRetry`)""".trimIndent()
        )
    }
}
