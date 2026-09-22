package io.github.zoot.englishreader.data.dao

import androidx.room.*
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookReadingProgressEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingArticleRecord
import io.github.zoot.englishreader.model.ReadingPublication
import kotlinx.coroutines.flow.Flow

private const val READING_ARTICLE_QUERY =
    "SELECT a.*, s.appliedPlan, s.appliedSourceFingerprint, s.appliedTranslationFingerprint " +
        "FROM articles a LEFT JOIN article_translation_state s ON s.articleId = a.id WHERE a.id = :id"

/**
 * 文章数据访问对象
 *
 * 使用 Flow 实现响应式查询
 */
@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY createdAt DESC")
    fun getAllArticles(): Flow<List<ArticleEntity>>

    /**
     * 只返回**单篇**文章，排除属于某本书的章节。
     *
     * 书架必须按「一本书一行」显示，而不是把 500 章铺成 500 行——那会让用户导入一本
     * 长篇后彻底找不到自己原来的文章。章节仍是 [ArticleEntity]（阅读页、词典、TTS、
     * AI 缓存都靠 articleId 工作），所以区分只能在查询层做：凡在 `book_chapters`
     * 里出现过的 articleId 都归书，不归列表。
     *
     * 用 `NOT IN` 而非 `LEFT JOIN ... IS NULL`：`book_chapters.articleId` 上有唯一
     * 索引，子查询走索引扫描；且语义更直白——这里要表达的就是「不属于任何一本书」。
     */
    @Query(
        "SELECT * FROM articles " +
            "WHERE id NOT IN (SELECT articleId FROM book_chapters) " +
            "ORDER BY createdAt DESC"
    )
    fun getStandaloneArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getArticleById(id: Long): ArticleEntity?

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observeArticle(id: Long): Flow<ArticleEntity?>

    @Query(READING_ARTICLE_QUERY)
    suspend fun getReadingArticle(id: Long): ReadingArticleRecord?

    @Query(READING_ARTICLE_QUERY)
    fun observeReadingArticle(id: Long): Flow<ReadingArticleRecord?>

    @Query("SELECT * FROM reading_positions WHERE articleId = :articleId")
    suspend fun getReadingPosition(articleId: Long): ReadingPositionEntity?

    @Query("SELECT MAX(updatedAt) FROM (SELECT updatedAt FROM reading_positions UNION ALL SELECT updatedAt FROM book_reading_progress)")
    suspend fun latestReadingTimestamp(): Long?

    @Upsert
    suspend fun upsertReadingPosition(position: ReadingPositionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM articles WHERE id = :articleId)")
    suspend fun articleExists(articleId: Long): Boolean

    @Query("SELECT bookId FROM book_chapters WHERE articleId = :articleId LIMIT 1")
    suspend fun readingBookId(articleId: Long): Long?

    @Query("SELECT updatedAt FROM book_reading_progress WHERE bookId = :bookId")
    suspend fun bookPositionUpdatedAt(bookId: Long): Long?

    @Upsert
    suspend fun upsertBookPosition(position: BookReadingProgressEntity)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateReadingBookTime(bookId: Long, timestamp: Long)

    /** 字符锚点和书架的当前章指针同事务提交，较旧的保存不能把书退回上一章。 */
    @Transaction
    suspend fun saveReadingPosition(position: ReadingPositionEntity) {
        if (!articleExists(position.articleId)) return
        val stored = getReadingPosition(position.articleId)
        if (stored != null && stored.updatedAt > position.updatedAt) return
        upsertReadingPosition(position)
        updateLastReadTime(position.articleId, position.updatedAt)
        val bookId = readingBookId(position.articleId) ?: return
        if ((bookPositionUpdatedAt(bookId) ?: 0) > position.updatedAt) return
        upsertBookPosition(
            BookReadingProgressEntity(
                bookId = bookId,
                chapterArticleId = position.articleId,
                paragraphIndex = position.paragraphIndex,
                paragraphOffset = 0,
                updatedAt = position.updatedAt
            )
        )
        updateReadingBookTime(bookId, position.updatedAt)
    }

    /** 正文检查与位置提交必须同事务，避免旧布局的迟到保存覆盖编辑后的起点。 */
    @Transaction
    suspend fun saveReadingPositionIfContent(
        position: ReadingPositionEntity,
        expectedContent: String,
        expectedPublication: ReadingPublication? = null
    ) {
        val current = getReadingArticle(position.articleId) ?: return
        if (current.article.content != expectedContent) return
        if (expectedPublication != null &&
            ReadingPublication(current.article.translation, current.appliedPlan) != expectedPublication
        ) return
        saveReadingPosition(position)
    }

    @Query("UPDATE articles SET title = :title WHERE id = :id")
    suspend fun updateEditedTitle(id: Long, title: String)

    @Query("UPDATE articles SET title = :title, content = :content, translation = NULL WHERE id = :id")
    suspend fun updateEditedContent(id: Long, title: String, content: String)

    @Query(
        "UPDATE whole_translation_tasks SET status = 'cancelled', failureReason = NULL, updatedAt = :now " +
            "WHERE status NOT IN ('completed', 'cancelled') AND taskId IN " +
            "(SELECT taskId FROM translation_task_articles WHERE articleId = :articleId)"
    )
    suspend fun cancelTranslationTasksForEdit(articleId: Long, now: Long)

    /**
     * 编辑正文后清除已发布的对照布局，保留用户的分块偏好。
     *
     * 布局里的 offset 是按编辑前的正文算的，编辑后它们指向的已经是别的字符。不清除的话，阅读层
     * 会拿旧坐标去裁新正文——轻则显示错位的对照，重则切出半个词。清除后回落到整段展示，直到下次
     * 成功发布。
     *
     * `preferredMode` 不动：那是用户对这篇文章的选择，与正文改了什么无关。
     */
    @Query(
        "UPDATE article_translation_state SET appliedPlan = NULL, " +
            "appliedSourceFingerprint = NULL, appliedTranslationFingerprint = NULL, " +
            "appliedTaskId = NULL, updatedAt = :now WHERE articleId = :articleId"
    )
    suspend fun clearAppliedLayoutForEdit(articleId: Long, now: Long)

    /** 编辑只更新指定字段，不通过删除重插触发任何文章关联的级联。 */
    @Transaction
    suspend fun saveEdit(
        original: ArticleEditSnapshot,
        title: String,
        content: String,
        positionTimestamp: Long
    ): ArticleEditResult {
        val current = getArticleById(original.articleId) ?: return ArticleEditResult.NotFound
        if (readingBookId(current.id) != null) return ArticleEditResult.NotStandalone
        if (current.title != original.title || current.content != original.content) {
            return ArticleEditResult.Conflict
        }
        if (content == current.content) {
            if (title != current.title) updateEditedTitle(current.id, title)
            return ArticleEditResult.Saved
        }

        cancelTranslationTasksForEdit(current.id, positionTimestamp)
        clearAppliedLayoutForEdit(current.id, positionTimestamp)
        updateEditedContent(current.id, title, content)
        upsertReadingPosition(
            ReadingPositionEntity(
                articleId = current.id,
                paragraphIndex = 0,
                textKind = ReadingTextKind.TITLE.name,
                characterOffset = 0,
                updatedAt = positionTimestamp
            )
        )
        return ArticleEditResult.Saved
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticle(article: ArticleEntity): Long

    @Update
    suspend fun updateArticle(article: ArticleEntity)

    /** 与删书一致：先去重、解绑生词，再删除文章，任一步失败均回滚。 */
    @Transaction
    suspend fun deleteArticle(article: ArticleEntity) {
        val articleIds = listOf(article.id)
        deleteRedundantVocabularyForArticles(articleIds)
        unbindVocabularyFromArticles(articleIds)
        deleteArticlesByIds(articleIds)
    }

    /**
     * 删除样本关联生词中的冗余行，为后续解绑（articleId 置 null）去重。
     *
     * 背景：vocabulary 有 UNIQUE(word, articleId)，但 SQLite 认为 NULL 彼此不相等，
     * 若同一 word 关联多篇样本文章，解绑后会得到多行 (word, NULL) 且约束不拦截，造成生词本重复。
     * 因此解绑前删除以下冗余样本关联行，保证每个 word 解绑后最多一行 (word, NULL)：
     *  - 该 word 在生词本中已存在 articleId IS NULL 的行（那行保留，样本关联行删掉）；
     *  - 或同一 word 有多个样本关联行时，仅保留 id 最小的一行，其余删掉。
     */
    @Query(
        "DELETE FROM vocabulary " +
            "WHERE articleId IN (SELECT id FROM articles WHERE source IN (:sampleSources)) " +
            "AND EXISTS (" +
            "  SELECT 1 FROM vocabulary w WHERE w.word = vocabulary.word AND (" +
            "    w.articleId IS NULL " +
            "    OR (w.articleId IN (SELECT id FROM articles WHERE source IN (:sampleSources)) AND w.id < vocabulary.id)" +
            "  )" +
            ")"
    )
    suspend fun deleteRedundantSampleVocabulary(sampleSources: List<String>)

    /**
     * 将样本文章关联的生词解绑（articleId 置 null），使其在删除样本时不被 CASCADE 连带删除。
     * 生词保留在生词本中，仅失去与文章的关联。
     * 调用前须先执行 [deleteRedundantSampleVocabulary] 去重，否则可能产生重复 (word, NULL)。
     */
    @Query(
        "UPDATE vocabulary SET articleId = NULL " +
            "WHERE articleId IN (SELECT id FROM articles WHERE source IN (:sampleSources))"
    )
    suspend fun unbindVocabularyFromSampleArticles(sampleSources: List<String>)

    /**
     * 删除样本文章（source 命中白名单）。用正向白名单而非"非用户来源"，
     * 避免误删 source 为 null 或其他未知来源的用户数据。
     */
    @Query("DELETE FROM articles WHERE source IN (:sampleSources)")
    suspend fun deleteSampleArticles(sampleSources: List<String>)

    /**
     * 原子刷新内置样本文章：去重样本生词 → 解绑（保留生词本）→ 删旧样本 → 插新样本。
     * 整体在一个事务内，中途失败全回滚，不会出现半清空状态。
     *
     * 注意：步骤顺序有严格依赖——去重与解绑都依赖旧样本文章行仍存在（用于定位关联生词），
     * 必须在 [deleteSampleArticles] 之前执行；插入置于最后。切勿调整顺序或将插入改为并发执行，
     * 否则会破坏事务上下文或导致生词被 CASCADE 误删。
     *
     * @param sampleSources 视为"样本"的 source 白名单（仅这些会被删除），不可为空
     * @param samples 新的样本文章列表，不可为空（空列表会清空样本却不补，造成样本丢失）
     */
    @Transaction
    suspend fun refreshSampleArticles(sampleSources: List<String>, samples: List<ArticleEntity>) {
        require(sampleSources.isNotEmpty()) { "sampleSources must not be empty" }
        require(samples.isNotEmpty()) { "samples must not be empty" }
        deleteRedundantSampleVocabulary(sampleSources)
        unbindVocabularyFromSampleArticles(sampleSources)
        deleteSampleArticles(sampleSources)
        samples.forEach { insertArticle(it) }
    }

    @Query("UPDATE articles SET lastReadAt = :timestamp WHERE id = :id")
    suspend fun updateLastReadTime(id: Long, timestamp: Long)

    // ---- 整本书章节清理 ----
    // 与样本刷新的三步同构，但以 articleId 集合而非 source 白名单定位。
    // 不复用样本那套：书籍章节的 source 是用户导入来源，用 source 删会误伤其他书。

    /**
     * 删除指定 article 关联生词中的冗余行，为后续解绑去重。
     *
     * 与 [deleteRedundantSampleVocabulary] 同样的原因：vocabulary 有
     * UNIQUE(word, articleId)，而 SQLite 认为 NULL 彼此不相等。若同一个 word
     * 关联了同一本书的多个章节，解绑后会得到多行 (word, NULL) 且约束不拦截，
     * 生词本出现重复项。一本书内多章出现同一单词是常态，所以这一步对书籍
     * 比对样本文章更关键。
     */
    @Query(
        "DELETE FROM vocabulary " +
            "WHERE articleId IN (:articleIds) " +
            "AND EXISTS (" +
            "  SELECT 1 FROM vocabulary w WHERE w.word = vocabulary.word AND (" +
            "    w.articleId IS NULL " +
            "    OR (w.articleId IN (:articleIds) AND w.id < vocabulary.id)" +
            "  )" +
            ")"
    )
    suspend fun deleteRedundantVocabularyForArticles(articleIds: List<Long>)

    /**
     * 将指定 article 关联的生词解绑（articleId 置 null）。
     *
     * 必须在删除这些 article **之前**调用：vocabulary 对 articles 是 CASCADE，
     * 顺序颠倒会静默丢掉用户生词。调用前須先执行
     * [deleteRedundantVocabularyForArticles] 去重。
     */
    @Query("UPDATE vocabulary SET articleId = NULL WHERE articleId IN (:articleIds)")
    suspend fun unbindVocabularyFromArticles(articleIds: List<Long>)

    /** 按 id 集合删除 article。供删书时清理章节正文。 */
    @Query("DELETE FROM articles WHERE id IN (:articleIds)")
    suspend fun deleteArticlesByIds(articleIds: List<Long>)
}
