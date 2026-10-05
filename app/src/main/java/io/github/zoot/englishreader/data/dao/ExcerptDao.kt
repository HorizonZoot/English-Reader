package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.zoot.englishreader.core.ExcerptOverlapChoice
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import kotlinx.coroutines.flow.Flow

enum class ExcerptWriteResult { SAVED, ALREADY_EXISTS, SOURCE_CHANGED, OVERLAP }

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

    @Query("SELECT * FROM excerpts WHERE originArticleId = :articleId AND paragraphIndex = :paragraphIndex AND contentFingerprint = :fingerprint ORDER BY startOffset, id")
    abstract suspend fun getParagraphExcerpts(articleId: Long, paragraphIndex: Int, fingerprint: String): List<ExcerptEntity>

    @Update
    abstract suspend fun update(excerpt: ExcerptEntity)

    /** Source validation, overlap resolution and replacement must be atomic. */
    @Transaction
    open suspend fun saveResolvingOverlap(
        excerpt: ExcerptEntity,
        expectedContent: String,
        paragraph: String,
        choice: ExcerptOverlapChoice?
    ): ExcerptWriteResult {
        val articleId = excerpt.articleId ?: return ExcerptWriteResult.SOURCE_CHANGED
        if (articleContent(articleId) != expectedContent) return ExcerptWriteResult.SOURCE_CHANGED
        val candidates = getParagraphExcerpts(excerpt.originArticleId, excerpt.paragraphIndex, excerpt.contentFingerprint)
        val overlaps = mutableListOf<ExcerptEntity>()
        var start = excerpt.startOffset
        var end = excerpt.endOffset
        do {
            val added = candidates.filter { candidate ->
                candidate !in overlaps && candidate.startOffset < end && candidate.endOffset > start
            }
            overlaps.addAll(added)
            added.forEach { start = minOf(start, it.startOffset); end = maxOf(end, it.endOffset) }
        } while (added.isNotEmpty())
        if (overlaps.isEmpty()) return if (insert(excerpt) == -1L) ExcerptWriteResult.ALREADY_EXISTS else ExcerptWriteResult.SAVED
        if (overlaps.size == 1 && overlaps.single().startOffset == excerpt.startOffset &&
            overlaps.single().endOffset == excerpt.endOffset && overlaps.single().text == excerpt.text
        ) return ExcerptWriteResult.ALREADY_EXISTS
        if (choice == null) return ExcerptWriteResult.OVERLAP
        if (overlaps.any { it.startOffset < 0 || it.endOffset > paragraph.length || it.startOffset >= it.endOffset ||
                paragraph.substring(it.startOffset, it.endOffset) != it.text }
        ) return ExcerptWriteResult.SOURCE_CHANGED
        val retained = when (choice) {
            ExcerptOverlapChoice.MERGE -> {
                val oldest = overlaps.minWith(compareBy<ExcerptEntity> { it.createdAt }.thenBy { it.id })
                oldest.copy(articleId = articleId, sourceTitle = excerpt.sourceTitle,
                    startOffset = start, endOffset = end, text = paragraph.substring(start, end))
            }
            ExcerptOverlapChoice.KEEP_LONGEST -> (overlaps + excerpt)
                .maxWith(compareBy<ExcerptEntity> { it.endOffset - it.startOffset }.thenBy { -it.createdAt }.thenBy { -it.id })
        }
        overlaps.filter { it.id != retained.id }.forEach { delete(it.id) }
        if (retained.id == 0L) check(insert(retained) != -1L) else update(retained)
        return ExcerptWriteResult.SAVED
    }

    @Transaction
    open suspend fun deleteForUndo(id: Long): ExcerptEntity? {
        val excerpt = getById(id) ?: return null
        return if (delete(id) > 0) excerpt else null
    }

    @Transaction
    open suspend fun restore(excerpt: ExcerptEntity) {
        val source = excerpt.articleId?.takeIf { articleExists(it) }
        insert(excerpt.copy(articleId = source))
    }
}
