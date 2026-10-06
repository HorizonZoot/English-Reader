package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.ai.AiExecutor
import io.github.zoot.englishreader.data.ai.AiExplanationRequestResolver
import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.dao.WholeTranslationDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.model.WholeTranslationPreviewResult
import io.github.zoot.englishreader.model.WholeTranslationScope
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class WholeTranslationPreviewDispatchTest {
    private val articleDao = mockk<ArticleDao>()
    private val dao = mockk<WholeTranslationDao>()
    private val resolver = mockk<AiExplanationRequestResolver>()
    private val executor = mockk<AiExecutor>()
    private val article = ArticleEntity(7, "Title", "First sentence. Second sentence.", translation = "旧译文")

    @Test
    fun preview_defaultDispatcher_plansOffCallerThreadWithoutPaidDependencies() = runTest {
        val caller = Thread.currentThread()
        val repository = repository {
            assertNotSame("Planning must not run on the caller thread", caller, Thread.currentThread())
            wholeSentence(it)
        }

        val result = repository.preview(WholeTranslationScope.CurrentArticle(article.id))

        assertTrue(result is WholeTranslationPreviewResult.Ready)
        val preview = (result as WholeTranslationPreviewResult.Ready).preview
        assertEquals(article.content, preview.plans.single().blocks.joinToString("") { it.text })
        verify { listOf(resolver, executor) wasNot Called }
    }

    @Test
    fun preview_injectedDispatcher_queuesWorkAndPreservesResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var splits = 0
        val repository = repository(dispatcher) {
            splits++
            wholeSentence(it)
        }
        coEvery { articleDao.getArticleById(article.id) } coAnswers {
            assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
            article
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            repository.preview(WholeTranslationScope.CurrentArticle(article.id))
        }

        assertFalse(result.isCompleted)
        assertEquals(0, splits)
        coVerify(exactly = 0) { articleDao.getArticleById(any()) }
        runCurrent()

        assertTrue(result.await() is WholeTranslationPreviewResult.Ready)
        assertEquals(1, splits)
        verify { listOf(resolver, executor) wasNot Called }
    }

    @Test
    fun preview_cancelledBeforeDispatch_doesNotReadOrPlan() = runTest {
        var splits = 0
        val repository = repository(StandardTestDispatcher(testScheduler)) {
            splits++
            wholeSentence(it)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            repository.preview(WholeTranslationScope.CurrentArticle(article.id))
        }

        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(0, splits)
        coVerify(exactly = 0) { articleDao.getArticleById(any()) }
        verify { listOf(resolver, executor) wasNot Called }
    }

    @Test
    fun preview_plannerFails_propagatesFailureWithoutPaidDependencies() = runTest {
        val failure = IllegalStateException("synthetic planner failure")
        val repository = repository { throw failure }

        val result = runCatching { repository.preview(WholeTranslationScope.CurrentArticle(article.id)) }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        verify { listOf(resolver, executor) wasNot Called }
    }

    private fun TestScope.repository(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        splitter: (String) -> List<SentenceRange>
    ): WholeTranslationRepository {
        coEvery { articleDao.getArticleById(article.id) } returns article
        coEvery { dao.getArticleTranslationState(article.id) } returns null
        return WholeTranslationRepository(
            dao = dao,
            articleDao = articleDao,
            requestResolver = resolver,
            executor = executor,
            applicationScope = backgroundScope,
            clock = { 0L },
            materializationPolicy = mockk(),
            planningDispatcher = dispatcher,
            sentenceSplitter = splitter
        )
    }

    private fun wholeSentence(text: String) = listOf(SentenceRange(0, text, 0, text.length))
}
