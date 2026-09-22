package io.github.zoot.englishreader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.BookDao
import io.github.zoot.englishreader.data.dao.VocabularyDao
import io.github.zoot.englishreader.data.dao.ExplanationCacheDao
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.dao.WholeTranslationDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ArticleTranslationStateEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookReadingProgressEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.data.entity.TranslationSegmentEntity
import io.github.zoot.englishreader.data.entity.TranslationTaskArticleEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.WholeTranslationTaskEntity

/**
 * English Reader 数据库
 *
 * 版本 1：初始版本，包含 articles, vocabulary, explanation_cache 表
 * 版本 2：vocabulary 表添加 phonetic, definitions, definitionSource 字段
 * 版本 3：添加 dictionary 表（离线词典），articles 表添加 translation 字段
 * 版本 4：添加 books, book_chapters, book_reading_progress 表（整本 EPUB 导入）
 * 版本 5：添加 reading_positions 表（两种阅读模式共用字符锚点）
 * 版本 6：添加 whole_translation_tasks, translation_task_articles, translation_segments 表
 *         （可恢复的全文段落翻译 checkpoint）
 * 版本 7：翻译分块坐标。translation_task_articles 添加 segmentationMode/plannerVersion；
 *         translation_segments 添加可空的 sourceParagraphIndex/sourceStartOffset/sourceEndOffset；
 *         新增 article_translation_state 表（文章级分块偏好与已发布对照布局）
 */
@Database(
    entities = [
        ArticleEntity::class,
        VocabularyEntity::class,
        ExplanationCacheEntity::class,
        DictionaryEntry::class,
        BookEntity::class,
        BookChapterEntity::class,
        BookReadingProgressEntity::class,
        ReadingPositionEntity::class,
        WholeTranslationTaskEntity::class,
        TranslationTaskArticleEntity::class,
        TranslationSegmentEntity::class,
        ArticleTranslationStateEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class EnglishReaderDatabase : RoomDatabase() {

    abstract fun articleDao(): ArticleDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun explanationCacheDao(): ExplanationCacheDao
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun bookDao(): BookDao
    abstract fun wholeTranslationDao(): WholeTranslationDao

    companion object {
        @Volatile
        private var INSTANCE: EnglishReaderDatabase? = null

        fun getInstance(context: Context): EnglishReaderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): EnglishReaderDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                EnglishReaderDatabase::class.java,
                "english_reader.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
                .build()
        }
    }
}
