package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.core.ExcerptRange
import io.github.zoot.englishreader.data.dao.ExcerptDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.util.ParagraphAligner
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class ExcerptSaveResult { SAVED, ALREADY_EXISTS, SOURCE_CHANGED }

@Singleton
class ExcerptRepository @Inject constructor(private val dao: ExcerptDao) {
    fun observeAll() = dao.observeAll()
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun delete(id: Long): Boolean = dao.delete(id) > 0
    suspend fun restore(excerpt: ExcerptEntity) = dao.restore(excerpt)
    suspend fun hasSource(excerpt: ExcerptEntity): Boolean {
        val articleId = excerpt.articleId ?: return false
        return dao.articleExists(articleId)
    }

    suspend fun save(article: ArticleEntity, paragraphIndex: Int, range: ExcerptRange): ExcerptSaveResult {
        val paragraph = ParagraphAligner.splitParagraphs(article.content).getOrNull(paragraphIndex)
            ?: return ExcerptSaveResult.SOURCE_CHANGED
        if (range.endOffset > paragraph.length) return ExcerptSaveResult.SOURCE_CHANGED
        val text = paragraph.substring(range.startOffset, range.endOffset)
        if (text.isBlank()) return ExcerptSaveResult.SOURCE_CHANGED
        val excerpt = ExcerptEntity(
            articleId = article.id,
            originArticleId = article.id,
            sourceTitle = article.title,
            contentFingerprint = fingerprint(article.content),
            paragraphIndex = paragraphIndex,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            text = text
        )
        return when (dao.insertForContent(excerpt, article.content)) {
            null -> ExcerptSaveResult.SOURCE_CHANGED
            -1L -> ExcerptSaveResult.ALREADY_EXISTS
            else -> ExcerptSaveResult.SAVED
        }
    }

    companion object {
        fun sourceAnchor(excerpt: ExcerptEntity, articleId: Long, content: String): ReadingAnchor? {
            if (excerpt.articleId != articleId || excerpt.contentFingerprint != fingerprint(content)) return null
            val paragraph = ParagraphAligner.splitParagraphs(content).getOrNull(excerpt.paragraphIndex) ?: return null
            if (excerpt.startOffset < 0 || excerpt.endOffset <= excerpt.startOffset ||
                excerpt.endOffset > paragraph.length ||
                paragraph.substring(excerpt.startOffset, excerpt.endOffset) != excerpt.text
            ) return null
            return ReadingAnchor(excerpt.paragraphIndex, ReadingTextKind.ORIGINAL, excerpt.startOffset)
        }

        internal fun fingerprint(content: String): String = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
