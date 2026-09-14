package io.github.zoot.englishreader.di

import android.content.Context
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.BookDao
import io.github.zoot.englishreader.data.dao.VocabularyDao
import io.github.zoot.englishreader.data.dao.ExplanationCacheDao
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.dao.WholeTranslationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库依赖注入模块
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EnglishReaderDatabase {
        return EnglishReaderDatabase.getInstance(context)
    }

    @Provides
    fun provideArticleDao(database: EnglishReaderDatabase): ArticleDao {
        return database.articleDao()
    }

    @Provides
    fun provideBookDao(database: EnglishReaderDatabase): BookDao {
        return database.bookDao()
    }

    @Provides
    fun provideVocabularyDao(database: EnglishReaderDatabase): VocabularyDao {
        return database.vocabularyDao()
    }

    @Provides
    fun provideExplanationCacheDao(database: EnglishReaderDatabase): ExplanationCacheDao {
        return database.explanationCacheDao()
    }

    @Provides
    fun provideDictionaryDao(database: EnglishReaderDatabase): DictionaryDao {
        return database.dictionaryDao()
    }

    @Provides
    fun provideWholeTranslationDao(database: EnglishReaderDatabase): WholeTranslationDao {
        return database.wholeTranslationDao()
    }
}
