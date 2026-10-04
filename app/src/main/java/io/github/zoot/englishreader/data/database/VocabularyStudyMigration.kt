package io.github.zoot.englishreader.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN studyMeaning TEXT")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN answerRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN dueDay INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN lastReviewedAt INTEGER")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN intervalDays INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vocabulary ADD COLUMN pendingStep INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_vocabulary_dueDay ON vocabulary (dueDay)")
        db.execSQL("ALTER TABLE context_review_session ADD COLUMN mode TEXT NOT NULL DEFAULT 'DUE'")
        db.execSQL("""
            CREATE TABLE context_review_items_new (
                itemToken TEXT NOT NULL PRIMARY KEY,
                sessionToken TEXT NOT NULL,
                cardId INTEGER,
                answerRevision INTEGER NOT NULL,
                meaningSnapshot TEXT NOT NULL,
                position INTEGER NOT NULL,
                isRetry INTEGER NOT NULL,
                eligibleAfterCount INTEGER NOT NULL,
                revealed INTEGER NOT NULL,
                grade TEXT,
                ratedAt INTEGER,
                ratedDay INTEGER,
                appliedIntervalDays INTEGER,
                nextPendingStep INTEGER,
                skipped INTEGER NOT NULL,
                vocabularyId INTEGER,
                updatesSchedule INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(cardId) REFERENCES context_cards(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(vocabularyId) REFERENCES vocabulary(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO context_review_items_new (
                itemToken, sessionToken, cardId, answerRevision, meaningSnapshot, position,
                isRetry, eligibleAfterCount, revealed, grade, ratedAt, ratedDay,
                appliedIntervalDays, nextPendingStep, skipped, vocabularyId, updatesSchedule
            ) SELECT itemToken, sessionToken, cardId, answerRevision, meaningSnapshot, position,
                isRetry, eligibleAfterCount, revealed, grade, ratedAt, ratedDay,
                appliedIntervalDays, nextPendingStep, skipped, NULL, 1 FROM context_review_items
        """.trimIndent())
        db.execSQL("DROP TABLE context_review_items")
        db.execSQL("ALTER TABLE context_review_items_new RENAME TO context_review_items")
        db.execSQL("CREATE INDEX index_context_review_items_cardId ON context_review_items (cardId)")
        db.execSQL("CREATE INDEX index_context_review_items_vocabularyId ON context_review_items (vocabularyId)")
        db.execSQL("CREATE INDEX index_context_review_items_sessionToken_position ON context_review_items (sessionToken, position)")
        db.execSQL("CREATE UNIQUE INDEX index_context_review_items_sessionToken_cardId_isRetry ON context_review_items (sessionToken, cardId, isRetry)")
        db.execSQL("CREATE UNIQUE INDEX index_context_review_items_sessionToken_vocabularyId_isRetry ON context_review_items (sessionToken, vocabularyId, isRetry)")
    }
}
