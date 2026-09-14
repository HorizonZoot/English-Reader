package io.github.zoot.englishreader.data.dao

import androidx.room.*
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import kotlinx.coroutines.flow.Flow

/**
 * 生词数据访问对象
 */
@Dao
interface VocabularyDao {

    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun getAllVocabulary(): Flow<List<VocabularyEntity>>

    /**
     * 生词列表 + 来源文章标题，供生词本一次渲染。
     *
     * 排序与 [getAllVocabulary] 一致，保证两个入口看到的顺序相同。
     * `v.*` 的列顺序与 [VocabularyEntity] 的字段一一对应，Room 据此填充
     * [VocabularyWithSource] 的 `@Embedded` 部分。
     */
    @Query(
        """
        SELECT v.*, a.title AS articleTitle
        FROM vocabulary v
        LEFT JOIN articles a ON a.id = v.articleId
        ORDER BY v.createdAt DESC
        """
    )
    fun getAllVocabularyWithSource(): Flow<List<VocabularyWithSource>>

    @Query("SELECT * FROM vocabulary WHERE articleId = :articleId ORDER BY createdAt DESC")
    fun getVocabularyByArticle(articleId: Long): Flow<List<VocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVocabulary(vocabulary: VocabularyEntity): Long

    @Delete
    suspend fun deleteVocabulary(vocabulary: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE articleId = :articleId")
    suspend fun deleteVocabularyByArticle(articleId: Long)
}
