package io.github.zoot.englishreader.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.zoot.englishreader.util.readingProgress

/** Preserve old reading anchors and backfill the library's normalized progress once on upgrade. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_positions ADD COLUMN progress REAL NOT NULL DEFAULT 0")
        db.query(
            "SELECT p.articleId, a.content, p.paragraphIndex, p.textKind, p.characterOffset " +
                "FROM reading_positions p JOIN articles a ON a.id = p.articleId"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val progress = readingProgress(cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4))
                db.execSQL(
                    "UPDATE reading_positions SET progress = ? WHERE articleId = ?",
                    arrayOf(progress, cursor.getLong(0))
                )
            }
        }
    }
}
