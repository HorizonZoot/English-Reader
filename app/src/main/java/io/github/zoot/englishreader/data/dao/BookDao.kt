package io.github.zoot.englishreader.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.BookEntity
import io.github.zoot.englishreader.data.entity.BookReadingProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书籍 DAO。
 *
 * 两个事务方法承载了本模块最容易出错的两处顺序依赖：
 * - [insertBookWithChapters]：整本原子入库，中途失败不留半本书。
 * - [deleteBookCascade]：删书前必须先解绑生词，否则 CASCADE 会静默吞掉用户生词。
 */
@Dao
interface BookDao {

    // ---- 查询 ----

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): BookEntity?

    /** 去重首选：dc:identifier 命中即视为同一本书 */
    @Query("SELECT * FROM books WHERE identifier = :identifier LIMIT 1")
    suspend fun findByIdentifier(identifier: String): BookEntity?

    /** identifier 缺失时的去重回落 */
    @Query("SELECT * FROM books WHERE contentFingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): BookEntity?

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun getChapters(bookId: Long): Flow<List<BookChapterEntity>>

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersOnce(bookId: Long): List<BookChapterEntity>

    @Query("SELECT articleId FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChapterArticleIds(bookId: Long): List<Long>

    /** 判断某篇 article 是否属于某本书。书架需要排除已属于书的 article。 */
    @Query("SELECT * FROM book_chapters WHERE articleId = :articleId LIMIT 1")
    suspend fun findChapterByArticleId(articleId: Long): BookChapterEntity?

    @Query("SELECT * FROM book_reading_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: Long): BookReadingProgressEntity?

    // ---- 写入原语 ----
    // 均为 internal 语义：外部只应通过下面两个 @Transaction 方法操作，
    // 单独调用会破坏「书 + 章节 + 进度」的一致性。

    /**
     * 插入书籍行。
     *
     * 用 ABORT 而非 REPLACE：REPLACE 会静默删掉同 id 的既有书并级联清空其章节与进度。
     * 重复导入必须由上层的去重查询显式拒绝，而不是靠冲突策略吞掉。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBookRow(book: BookEntity): Long

    /**
     * 插入章节正文 article。
     *
     * 同样用 ABORT：整本导入不得覆盖任何既有 article。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapterArticle(article: ArticleEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapterRelation(relation: BookChapterEntity): Long

    @Upsert
    suspend fun upsertProgress(progress: BookReadingProgressEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookRow(bookId: Long)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateBookLastReadTime(bookId: Long, timestamp: Long)

    // ---- 事务 ----

    /**
     * 原子写入一本书：书籍行 → 各章节 article + 关系行 → 初始阅读位置。
     *
     * 任一步失败整体回滚，不会留下「有书没章节」或「章节数与 chapterCount 不符」的状态。
     *
     * [chapters] 的顺序**就是**阅读顺序；chapterIndex 在此处按序号重新赋值，
     * 调用方传入的 chapterIndex 会被忽略。这样做是因为空正文项在解析阶段已被过滤，
     * 原始 spine 下标必然有空洞，让 DAO 统一重编号比让每个调用方自己维护更可靠。
     *
     * @param chapters 章节正文与关系元数据配对，顺序即阅读顺序，不可为空
     * @return 新书 id
     */
    @Transaction
    suspend fun insertBookWithChapters(
        book: BookEntity,
        chapters: List<Pair<ArticleEntity, BookChapterEntity>>
    ): Long {
        require(chapters.isNotEmpty()) { "chapters must not be empty" }

        val bookId = insertBookRow(book)
        var firstArticleId = 0L

        chapters.forEachIndexed { ordinal, (article, relation) ->
            val articleId = insertChapterArticle(article)
            if (ordinal == 0) firstArticleId = articleId
            insertChapterRelation(
                relation.copy(
                    id = 0,
                    bookId = bookId,
                    articleId = articleId,
                    chapterIndex = ordinal
                )
            )
        }

        upsertProgress(
            BookReadingProgressEntity(
                bookId = bookId,
                chapterArticleId = firstArticleId,
                paragraphIndex = 0,
                paragraphOffset = 0
            )
        )

        return bookId
    }

    /**
     * 删除一本书及其章节正文，**保留用户生词**。
     *
     * 顺序不可调整：
     * 1. 取章节 article id（此时关系行仍存在）
     * 2. 去重生词（一本书多章命中同一单词时，解绑后会撞 UNIQUE(word, NULL)）
     * 3. 解绑生词（articleId 置 NULL）
     * 4. 删章节 article（触发 book_chapters 关系行 CASCADE）
     * 5. 删 books 行（触发 book_reading_progress CASCADE）
     *
     * 把第 4 步提前会让 vocabulary 被 CASCADE 连带删除；把第 1 步放到第 5 步之后
     * 则取不到 id。
     *
     * **必须分批**：[ArticleDao.deleteRedundantVocabularyForArticles] 的 SQL 里
     * `:articleIds` 出现两次，Room 会绑定 2N 个参数。API 24–30 的 SQLite < 3.32，
     * `SQLITE_MAX_VARIABLE_NUMBER` 是 999，满 500 章时 2N = 1000 直接抛
     * "too many SQL variables"，整个事务回滚——那本书就再也删不掉了。
     * 故按 [VOCABULARY_BATCH] 分批，2N 恒 ≤ 400。
     *
     * 分批不破坏去重语义：每批内部按「去重 → 解绑 → 删除」顺序执行，第 k 批解绑后
     * 那些行的 articleId 已是 NULL，第 k+1 批的去重 SQL 通过 `w.articleId IS NULL`
     * 分支仍能看到它们，因此跨批重复词依然只留一行。若改成「先全部去重、再全部解绑」
     * 这个性质就没了。
     */
    @Transaction
    suspend fun deleteBookCascade(bookId: Long, articleDao: ArticleDao) {
        val articleIds = getChapterArticleIds(bookId)
        for (batch in articleIds.chunked(VOCABULARY_BATCH)) {
            articleDao.deleteRedundantVocabularyForArticles(batch)
            articleDao.unbindVocabularyFromArticles(batch)
            articleDao.deleteArticlesByIds(batch)
        }
        deleteBookRow(bookId)
    }

    companion object {
        /**
         * 每批处理的章节 article 数。
         *
         * 上界由最坏情况的参数展开决定：去重 SQL 绑定 2N 个参数，必须
         * 2N ≤ 999（API 24–30 的 SQLite 上限），即 N ≤ 499。取 200 留足余量，
         * 也避免未来给该 SQL 再加一处 `:articleIds` 时立刻越界。
         */
        const val VOCABULARY_BATCH: Int = 200
    }
}
