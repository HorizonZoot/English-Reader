package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.data.dao.ContextCardDao
import io.github.zoot.englishreader.data.dao.VocabularyDao
import io.github.zoot.englishreader.data.entity.ContextCardEntity
import io.github.zoot.englishreader.model.ContextCardSaveResult
import io.github.zoot.englishreader.model.ContextReviewClock
import io.github.zoot.englishreader.model.ContextWordSelection
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.util.ParagraphAligner
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
    private val vocabularyDao: VocabularyDao,
    private val contextCardDao: ContextCardDao,
    private val reviewClock: ContextReviewClock
) {
    fun observeContextCards() = contextCardDao.observeCards()
    fun observeContextReview() = contextCardDao.observeRound()
    fun currentReviewDay(): Long = reviewClock.now().toLocalDate().toEpochDay()
    suspend fun getContextCard(id: Long) = contextCardDao.card(id)
    suspend fun deleteContextCard(id: Long): Boolean = contextCardDao.deleteCard(id) == 1

    suspend fun findContextCard(selection: ContextWordSelection): ContextCardEntity? {
        if (!isValidSelection(selection)) return null
        return contextCardDao.cardAtOccurrence(
            selection.article.id, ExcerptRepository.fingerprint(selection.article.content),
            selection.paragraphIndex, selection.sentence.startOffset, selection.sentence.endOffset,
            selection.wordStartOffset - selection.sentence.startOffset,
            selection.wordEndOffset - selection.sentence.startOffset
        )
    }

    suspend fun saveContextCard(
        selection: ContextWordSelection,
        dictionaryWord: String,
        meaning: String
    ): ContextCardSaveResult {
        val confirmedMeaning = meaning.trim()
        if (confirmedMeaning.isEmpty()) return ContextCardSaveResult.INVALID_MEANING
        if (!isValidSelection(selection)) return ContextCardSaveResult.SOURCE_CHANGED
        val now = reviewClock.now()
        val sentence = selection.sentence
        val start = selection.wordStartOffset - sentence.startOffset
        val end = selection.wordEndOffset - sentence.startOffset
        val card = ContextCardEntity(
            articleId = selection.article.id,
            originArticleId = selection.article.id,
            sourceTitle = selection.article.title,
            contentFingerprint = ExcerptRepository.fingerprint(selection.article.content),
            paragraphIndex = selection.paragraphIndex,
            sentenceStartOffset = sentence.startOffset,
            sentenceEndOffset = sentence.endOffset,
            sentence = sentence.text,
            targetStartOffset = start,
            targetEndOffset = end,
            word = sentence.text.substring(start, end),
            dictionaryWord = dictionaryWord,
            meaning = confirmedMeaning,
            createdAt = now.toInstant().toEpochMilli(),
            updatedAt = now.toInstant().toEpochMilli(),
            dueDay = now.toLocalDate().plusDays(1).toEpochDay()
        )
        return when (contextCardDao.insertForContent(card, selection.article.content)) {
            null -> ContextCardSaveResult.SOURCE_CHANGED
            -1L -> ContextCardSaveResult.ALREADY_EXISTS
            else -> ContextCardSaveResult.SAVED
        }
    }

    suspend fun editContextMeaning(card: ContextCardEntity, meaning: String): Boolean {
        val now = reviewClock.now()
        return contextCardDao.editMeaning(
            card.id, card.answerRevision, meaning.trim(), now.toLocalDate().toEpochDay(), now.toInstant().toEpochMilli()
        )
    }

    suspend fun startOrResumeContextReview() = reviewClock.now().let { now ->
        contextCardDao.startOrResume(now.toLocalDate().toEpochDay(), now.toInstant().toEpochMilli())
    }

    suspend fun startNextContextReview() = reviewClock.now().let { now ->
        contextCardDao.startOrResume(now.toLocalDate().toEpochDay(), now.toInstant().toEpochMilli(), startNext = true)
    }

    suspend fun revealContextAnswer(itemToken: String): Boolean =
        contextCardDao.reveal(itemToken, currentReviewDay())

    suspend fun gradeContextAnswer(itemToken: String, grade: ContextGrade): Boolean = reviewClock.now().let { now ->
        contextCardDao.rate(itemToken, grade, now.toLocalDate().toEpochDay(), now.toInstant().toEpochMilli())
    }

    suspend fun hasContextSource(card: ContextCardEntity): Boolean {
        val id = card.articleId ?: return false
        val content = contextCardDao.articleContent(id) ?: return false
        return contextSourceAnchor(card, id, content) != null
    }

    companion object {
        fun isValidSelection(selection: ContextWordSelection): Boolean {
            val paragraph = ParagraphAligner.splitParagraphs(selection.article.content)
                .getOrNull(selection.paragraphIndex) ?: return false
            val sentence = selection.sentence
            if (sentence.startOffset < 0 || sentence.endOffset > paragraph.length ||
                sentence.startOffset >= sentence.endOffset ||
                paragraph.substring(sentence.startOffset, sentence.endOffset) != sentence.text
            ) return false
            return selection.wordStartOffset >= sentence.startOffset &&
                selection.wordEndOffset <= sentence.endOffset &&
                selection.wordStartOffset < selection.wordEndOffset &&
                paragraph.substring(selection.wordStartOffset, selection.wordEndOffset).any(Char::isLetter)
        }

        fun contextSourceAnchor(card: ContextCardEntity, articleId: Long, content: String): ReadingAnchor? {
            if (card.articleId != articleId || card.contentFingerprint != ExcerptRepository.fingerprint(content)) return null
            val paragraph = ParagraphAligner.splitParagraphs(content).getOrNull(card.paragraphIndex) ?: return null
            if (card.sentenceStartOffset < 0 || card.sentenceEndOffset > paragraph.length ||
                card.sentenceStartOffset >= card.sentenceEndOffset ||
                paragraph.substring(card.sentenceStartOffset, card.sentenceEndOffset) != card.sentence ||
                card.targetStartOffset < 0 || card.targetEndOffset > card.sentence.length ||
                card.targetStartOffset >= card.targetEndOffset ||
                card.sentence.substring(card.targetStartOffset, card.targetEndOffset) != card.word
            ) return null
            return ReadingAnchor(card.paragraphIndex, ReadingTextKind.ORIGINAL, card.sentenceStartOffset + card.targetStartOffset)
        }
    }

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
        val id = vocabularyDao.insertVocabularyIfAbsent(vocabulary)
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
