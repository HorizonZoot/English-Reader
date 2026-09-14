package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.dao.VocabularyDao
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VocabularyInsertResult {
    data class Inserted(val id: Long) : VocabularyInsertResult

    data object AlreadyExists : VocabularyInsertResult
}

/**
 * 生词数据仓库
 *
 * 封装 VocabularyDao，提供统一的数据访问接口
 */
@Singleton
class VocabularyRepository @Inject constructor(
    private val vocabularyDao: VocabularyDao
) {

    /**
     * 获取所有生词（响应式）
     */
    fun getAllVocabulary(): Flow<List<VocabularyEntity>> {
        return vocabularyDao.getAllVocabulary()
    }

    /**
     * 获取所有生词，并附带来源文章标题（响应式）。
     *
     * 生词本界面用这个入口：展示「来自《标题》」所需的标题由 SQL 一次 JOIN 取出，
     * 不必逐条回查文章。
     */
    fun getAllVocabularyWithSource(): Flow<List<VocabularyWithSource>> {
        return vocabularyDao.getAllVocabularyWithSource()
    }

    /**
     * 获取指定文章的生词（响应式）
     */
    fun getVocabularyByArticle(articleId: Long): Flow<List<VocabularyEntity>> {
        return vocabularyDao.getVocabularyByArticle(articleId)
    }

    /**
     * 插入生词，并保留 Room 的冲突结果语义。
     *
     * `IGNORE` 冲突时 Room 返回 -1；该 sentinel 只在此边界转换为领域结果，
     * 避免 UI 和其他调用方依赖数据库实现细节。
     */
    suspend fun insertVocabulary(vocabulary: VocabularyEntity): VocabularyInsertResult {
        val id = vocabularyDao.insertVocabulary(vocabulary)
        return if (id == -1L) {
            VocabularyInsertResult.AlreadyExists
        } else {
            VocabularyInsertResult.Inserted(id)
        }
    }

    /**
     * 删除生词
     */
    suspend fun deleteVocabulary(vocabulary: VocabularyEntity) {
        vocabularyDao.deleteVocabulary(vocabulary)
    }

    /**
     * 删除指定文章的所有生词
     */
    suspend fun deleteVocabularyByArticle(articleId: Long) {
        vocabularyDao.deleteVocabularyByArticle(articleId)
    }
}
