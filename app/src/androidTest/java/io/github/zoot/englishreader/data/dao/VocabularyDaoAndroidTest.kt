package io.github.zoot.englishreader.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabularyDaoAndroidTest {

    private lateinit var db: EnglishReaderDatabase
    private lateinit var articleDao: ArticleDao
    private lateinit var vocabularyDao: VocabularyDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        articleDao = db.articleDao()
        vocabularyDao = db.vocabularyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertVocabulary_duplicate_keepsOriginalRowAndMetadata() = runBlocking {
        val articleId = articleDao.insertArticle(
            ArticleEntity(title = "article", content = "content")
        )
        val original = VocabularyEntity(
            word = "life",
            articleId = articleId,
            createdAt = 1234L,
            phonetic = "/laɪf/",
            definitions = "n. existence",
            definitionSource = "offline"
        )

        val originalId = vocabularyDao.insertVocabulary(original)
        val ignoredId = vocabularyDao.insertVocabulary(
            original.copy(
                id = 0,
                createdAt = 9999L,
                phonetic = "/different/",
                definitions = "replacement",
                definitionSource = "online"
            )
        )

        val rows = vocabularyDao.getVocabularyByArticle(articleId).first()
        assertEquals(1, rows.size)
        assertEquals(-1L, ignoredId)
        assertEquals(originalId, rows.single().id)
        assertEquals(original.createdAt, rows.single().createdAt)
        assertEquals(original.phonetic, rows.single().phonetic)
        assertEquals(original.definitions, rows.single().definitions)
        assertEquals(original.definitionSource, rows.single().definitionSource)
    }
}
