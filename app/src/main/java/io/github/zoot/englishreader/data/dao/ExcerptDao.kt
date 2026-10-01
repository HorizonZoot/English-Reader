package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ExcerptDao {
    @Query("SELECT * FROM excerpts ORDER BY createdAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<ExcerptEntity>>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    abstract suspend fun getById(id: Long): ExcerptEntity?

    @Query("SELECT content FROM articles WHERE id = :id")
    abstract suspend fun articleContent(id: Long): String?

    @Query("SELECT EXISTS(SELECT 1 FROM articles WHERE id = :id)")
    abstract suspend fun articleExists(id: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(excerpt: ExcerptEntity): Long

    @Query("DELETE FROM excerpts WHERE id = :id")
    abstract suspend fun delete(id: Long): Int

    /** Validation and insertion share a transaction with article edit/delete. */
    @Transaction
    open suspend fun insertForContent(excerpt: ExcerptEntity, expectedContent: String): Long? {
        val articleId = excerpt.articleId ?: return null
        if (articleContent(articleId) != expectedContent) return null
        return insert(excerpt)
    }

    @Transaction
    open suspend fun restore(excerpt: ExcerptEntity) {
        val source = excerpt.articleId?.takeIf { articleExists(it) }
        insert(excerpt.copy(id = 0, articleId = source))
    }
}
