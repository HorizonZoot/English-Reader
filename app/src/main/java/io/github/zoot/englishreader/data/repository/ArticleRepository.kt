package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ReadingPositionEntity
import io.github.zoot.englishreader.data.importer.ImportBudgetValidator
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.di.ApplicationCoroutineScope
import io.github.zoot.englishreader.model.ArticleEditResult
import io.github.zoot.englishreader.model.ArticleEditSnapshot
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.AppliedTranslationLayoutCodec
import io.github.zoot.englishreader.model.ReadingArticleRecord
import io.github.zoot.englishreader.model.ReadingArticleState
import io.github.zoot.englishreader.model.ReadingPublication
import io.github.zoot.englishreader.model.TranslationFingerprint
import io.github.zoot.englishreader.util.ParagraphAligner
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文章数据仓库
 *
 * 封装 ArticleDao，提供统一的数据访问接口
 */
@Singleton
class ArticleRepository @Inject constructor(
    private val articleDao: ArticleDao,
    @ApplicationCoroutineScope private val applicationScope: CoroutineScope,
    private val layoutCodec: AppliedTranslationLayoutCodec
) {
    private val positionMutex = Mutex()
    private var positionTimestamp: Long? = null

    suspend fun getReadingPosition(articleId: Long): ReadingPosition? = positionMutex.withLock {
        articleDao.getReadingPosition(articleId)?.let { stored ->
            ReadingPosition(
                articleId = stored.articleId,
                anchor = ReadingAnchor(
                    paragraphIndex = stored.paragraphIndex.coerceAtLeast(0),
                    textKind = ReadingTextKind.fromName(stored.textKind),
                    characterOffset = stored.characterOffset.coerceAtLeast(0)
                )
            )
        }
    }

    suspend fun saveReadingPosition(
        position: ReadingPosition,
        expectedContent: String? = null,
        expectedPublication: ReadingPublication? = null
    ) {
        // 离开阅读页可以取消观察，但已经提交的本地进度写入必须完成。
        // UNDISPATCHED 先取得/排队同一把锁，紧接着重新打开时的读取会等到写入完成。
        applicationScope.async(start = CoroutineStart.UNDISPATCHED) {
            positionMutex.withLock {
                val entity = ReadingPositionEntity(
                    articleId = position.articleId,
                    paragraphIndex = position.anchor.paragraphIndex,
                    textKind = position.anchor.textKind.name,
                    characterOffset = position.anchor.characterOffset,
                    updatedAt = nextPositionTimestamp()
                )
                if (expectedContent == null) articleDao.saveReadingPosition(entity)
                else articleDao.saveReadingPositionIfContent(entity, expectedContent, expectedPublication)
            }
        }.await()
    }

    /** 保存已确认的编辑；只有正文变化才使译文、任务与旧位置失效。 */
    suspend fun saveEdit(original: ArticleEditSnapshot, title: String, content: String): ArticleEditResult {
        val editedContent = if (content == original.content) content else content.trim()
        if (editedContent != original.content) {
            try {
                ImportBudgetValidator.validate(editedContent)
            } catch (failure: ImportException) {
                return ArticleEditResult.InvalidContent(failure.failure)
            }
        }
        val editedTitle = ImportBudgetValidator.normalizeTitle(title, original.title)
        return applicationScope.async(start = CoroutineStart.UNDISPATCHED) {
            positionMutex.withLock {
                articleDao.saveEdit(original, editedTitle, editedContent, nextPositionTimestamp())
            }
        }.await()
    }

    private suspend fun nextPositionTimestamp(): Long {
        // 从持久记录续接顺序，系统时钟回拨也不会让新进度被当作旧写入丢弃。
        val previous = maxOf(positionTimestamp ?: 0, articleDao.latestReadingTimestamp() ?: 0)
        return maxOf(System.currentTimeMillis(), previous + 1).also { positionTimestamp = it }
    }

    /**
     * 获取所有文章（响应式）
     */
    fun getAllArticles(): Flow<List<ArticleEntity>> {
        return articleDao.getAllArticles()
    }

    /**
     * 获取单篇文章（不含书籍章节，响应式）。
     *
     * 书架用这个而不是 [getAllArticles]：章节也是 [ArticleEntity]，一本 500 章的书
     * 会把列表冲掉。见 [ArticleDao.getStandaloneArticles]。
     */
    fun getStandaloneArticles(): Flow<List<ArticleEntity>> {
        return articleDao.getStandaloneArticles()
    }

    /**
     * 根据 ID 获取文章
     */
    suspend fun getArticleById(id: Long): ArticleEntity? {
        return articleDao.getArticleById(id)
    }

    fun observeArticle(id: Long): Flow<ArticleEntity?> = articleDao.observeArticle(id)

    suspend fun getReadingArticle(id: Long): ReadingArticleState? = articleDao.getReadingArticle(id)?.toReadingState()

    fun observeReadingArticle(id: Long): Flow<ReadingArticleState?> =
        articleDao.observeReadingArticle(id).map { it?.toReadingState() }

    private fun ReadingArticleRecord.toReadingState(): ReadingArticleState {
        val paragraphs = ParagraphAligner.splitParagraphs(article.content)
        val translation = article.translation
        val decoded = layoutCodec.decode(appliedPlan, paragraphs)
        val layout = decoded?.takeIf {
            translation != null && it.articleFingerprint == TranslationFingerprint.forArticle(article.content) &&
                it.articleFingerprint == appliedSourceFingerprint &&
                TranslationFingerprint.forTranslation(translation) == appliedTranslationFingerprint &&
                it.matchesText(paragraphs, ParagraphAligner.splitParagraphs(translation))
        }
        if (appliedPlan != null && layout == null) Log.w("ArticleRepository", "stage=reading_layout category=invalid_layout")
        return ReadingArticleState(article, ReadingPublication(translation, appliedPlan), layout)
    }

    /**
     * 插入文章
     * @return 插入后的文章 ID
     */
    suspend fun insertArticle(article: ArticleEntity): Long {
        return articleDao.insertArticle(article)
    }

    /**
     * 更新文章
     */
    suspend fun updateArticle(article: ArticleEntity) {
        articleDao.updateArticle(article)
    }

    /**
     * 删除文章
     */
    suspend fun deleteArticle(article: ArticleEntity) {
        articleDao.deleteArticle(article)
    }

    /**
     * 更新文章最后阅读时间
     */
    suspend fun updateLastReadTime(articleId: Long) {
        articleDao.updateLastReadTime(articleId, System.currentTimeMillis())
    }

    /**
     * 原子刷新内置样本文章（保留用户导入的文章及其生词）。
     * @param sampleSources 视为"样本"的 source 白名单，仅这些文章会被删除重建
     * @param samples 新的样本文章列表
     */
    suspend fun refreshSampleArticles(sampleSources: List<String>, samples: List<ArticleEntity>) {
        articleDao.refreshSampleArticles(sampleSources, samples)
    }
}
