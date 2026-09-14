package io.github.zoot.englishreader.data.dao

import androidx.room.*
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import kotlinx.coroutines.flow.Flow

/**
 * 生词数据访问对象
 */
@Dao
interface VocabularyDao {

    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary WHERE articleId = :articleId ORDER BY createdAt DESC")
    fun getVocabularyByArticle(articleId: Long): Flow<List<VocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVocabulary(vocabulary: VocabularyEntity): Long

    @Delete
    suspend fun deleteVocabulary(vocabulary: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE articleId = :articleId")
    suspend fun deleteVocabularyByArticle(articleId: Long)
}
