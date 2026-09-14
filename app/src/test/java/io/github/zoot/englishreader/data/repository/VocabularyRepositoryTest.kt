package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.VocabularyDao
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VocabularyRepositoryTest {

    private lateinit var dao: VocabularyDao
    private lateinit var repository: VocabularyRepository

    @Before
    fun setup() {
        dao = mockk()
        repository = VocabularyRepository(dao)
    }

    @Test
    fun insertVocabulary_mapsRowIdToInsertResult() = runTest {
        val vocabulary = VocabularyEntity(word = "life", articleId = 7)
        listOf(
            -1L to VocabularyInsertResult.AlreadyExists,
            42L to VocabularyInsertResult.Inserted(42L)
        ).forEach { (daoReturn, expected) ->
            coEvery { dao.insertVocabulary(vocabulary) } returns daoReturn
            assertEquals(expected, repository.insertVocabulary(vocabulary))
        }
    }
}
