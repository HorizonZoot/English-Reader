package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerator
import io.github.zoot.englishreader.data.ai.AiErrorMapper
import io.github.zoot.englishreader.data.ai.AiGeneratedArticleRejection
import io.github.zoot.englishreader.data.dao.ArticleDao
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.repository.AiArticleGenerationRepository
import io.github.zoot.englishreader.data.repository.AiArticleGenerationState
import io.github.zoot.englishreader.data.repository.AiArticleGenerationStatus
import io.github.zoot.englishreader.data.repository.AiArticleSaveStatus
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AiArticleGenerationFlowTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val profiles: AiProfileRepository = mockk()
    private val transport: AiChatTransport = mockk()
    private val dao: ArticleDao = mockk()
    private val generator = AiArticleGenerator(profiles, transport, AiErrorMapper(isOnline = { true }))
    private val stores = mutableListOf<ViewModelStore>()
    private val rows = mutableMapOf<Long, ArticleEntity>()

    @Before
    fun setUp() {
        every { profiles.profiles } returns MutableStateFlow(
            listOf(
                AiProviderProfile(
                    "chosen", "Profile", AiProviderTemplate.OPENAI_COMPATIBLE,
                    "https://example.test/v1", "model", AiAuthStrategy.API_KEY, 0.7
                )
            )
        )
        every { profiles.activeProfileId } returns MutableStateFlow("chosen")
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
            ProfileResolutionResult.Available(
                ResolvedAiProfile(
                    "chosen", AiProviderTemplate.OPENAI_COMPATIBLE, "https://example.test/v1",
                    "model", AiAuthStrategy.API_KEY, temperature = 0.7, apiKey = "test-key"
                )
            )
        coEvery { transport.complete(any(), any()) } returns articleResponse()
        coEvery { dao.insertArticle(any()) } coAnswers {
            rows[42L] = firstArg<ArticleEntity>().copy(id = 42L)
            42L
        }
        coEvery { dao.getArticleById(any()) } coAnswers { rows[firstArg<Long>()] }
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
    }

    @Test
    fun generate_andSaveAcrossViewModelRecreation_keepsWorkAndRequiresExplicitOpen() = runTest {
        val response = CompletableDeferred<AiChatTransportResult>()
        val savedId = CompletableDeferred<Long>()
        coEvery { transport.complete(any(), any()) } coAnswers { response.await() }
        coEvery { dao.insertArticle(any()) } coAnswers {
            val article = firstArg<ArticleEntity>()
            val id = savedId.await()
            rows[id] = article.copy(id = id)
            id
        }
        val articles = articles()
        val session = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val first = viewModel(session)
        runCurrent()
        first.setScreenActive(true)
        first.generate()
        first.generate()
        runCurrent()
        assertTrue(session.state.value.isGenerating)
        first.setScreenActive(false)
        stores.last().clear()

        val saving = viewModel(session)
        runCurrent()
        saving.events.test {
            response.complete(articleResponse())
            runCurrent()
            assertFalse(session.state.value.isGenerating)
            expectNoEvents()
            coVerify(exactly = 0) { dao.insertArticle(any()) }
            val id = requireNotNull(session.state.value.preview).resultId
            saving.setScreenActive(true)
            saving.saveAndRead(id)
            runCurrent()
            assertTrue(session.state.value.isSaving)
            saving.setScreenActive(false)
            stores.last().clear()
            savedId.complete(42L)
            runCurrent()
            expectNoEvents()
        }

        val reopened = viewModel(session)
        reopened.setScreenActive(true)
        runCurrent()
        val preview = requireNotNull(session.state.value.preview)
        reopened.events.test {
            assertEquals(AiArticleSaveStatus.Saved(42L), preview.saveStatus)
            expectNoEvents()
            reopened.saveAndRead(preview.resultId)
            runCurrent()
            val event = awaitItem()
            assertEquals(42L, reopened.consumeReadRequest(event))
            assertNull(reopened.consumeReadRequest(event))
            expectNoEvents()
        }
        val stored = requireNotNull(articles.getArticleById(42L))
        assertEquals(preview.article.title, stored.title)
        assertEquals(preview.article.content, stored.content)
        assertEquals(1, rows.size)
        coVerify(exactly = 1) { transport.complete(any(), any()) }
        coVerify(exactly = 1) { dao.insertArticle(any()) }
    }

    @Test
    fun regenerate_truncatedOutputAndSaveFailure_preservesOriginalAndRetriesOnlyStorage() = runTest {
        coEvery { transport.complete(any(), any()) } returnsMany listOf(
            articleResponse(), AiChatTransportResult.Truncated("partial replacement")
        )
        val articles = articles()
        val session = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(session)
        runCurrent()
        vm.updateTopic("First topic")
        vm.generate()
        runCurrent()
        val original = requireNotNull(session.state.value.preview)
        vm.updateTopic("Replacement topic")
        vm.generate()
        runCurrent()

        assertEquals(original, session.state.value.preview)
        assertEquals(
            AiArticleGenerationFailure.Output(AiGeneratedArticleRejection.TRUNCATED_RESPONSE),
            (session.state.value.status as AiArticleGenerationStatus.Failed).failure
        )
        coVerify(exactly = 0) { dao.insertArticle(any()) }
        coEvery { dao.insertArticle(any()) } throws IOException("local write failed")
        vm.setScreenActive(true)
        vm.events.test {
            vm.saveAndRead(original.resultId)
            runCurrent()
            assertEquals(AiArticleSaveStatus.Failed, session.state.value.preview?.saveStatus)
            expectNoEvents()
            coEvery { dao.insertArticle(any()) } coAnswers {
                rows[42L] = firstArg<ArticleEntity>().copy(id = 42L)
                42L
            }
            vm.saveAndRead(original.resultId)
            runCurrent()
            assertEquals(42L, vm.consumeReadRequest(awaitItem()))
            vm.saveAndRead(original.resultId)
            runCurrent()
            assertEquals(42L, vm.consumeReadRequest(awaitItem()))
            expectNoEvents()
        }
        val stored = requireNotNull(articles.getArticleById(42L))
        assertEquals(original.article.title, stored.title)
        assertEquals(original.article.content, stored.content)
        coVerify(exactly = 2) { dao.insertArticle(any()) }
        coVerify(exactly = 2) { transport.complete(any(), any()) }
    }

    @Test
    fun newSession_afterSavedArticle_keepsOnlyExplicitlyStoredArticle() = runTest {
        val articles = articles()
        val session = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(session)
        runCurrent()
        vm.generate()
        runCurrent()
        val preview = requireNotNull(session.state.value.preview)
        assertTrue(session.savePreview(preview.resultId))
        runCurrent()
        stores.last().clear()

        val fresh = AiArticleGenerationRepository(generator, backgroundScope, articles)
        runCurrent()

        assertEquals(AiArticleGenerationState(), fresh.state.value)
        assertEquals(preview.article.content, articles.getArticleById(42L)?.content)
        coVerify(exactly = 1) { transport.complete(any(), any()) }
        coVerify(exactly = 1) { dao.insertArticle(any()) }
    }

    private fun TestScope.articles() = ArticleRepository(dao, backgroundScope, mockk())

    private fun viewModel(session: AiArticleGenerationRepository): AiArticleGenerationViewModel =
        AiArticleGenerationViewModel(session, profiles).also { vm ->
            stores += ViewModelStore().apply { put("generation", vm) }
        }

    private fun articleResponse() = AiChatTransportResult.Content(
        """{"title":"A quiet day","content":"One short paragraph.\n\nAnother quiet morning."}"""
    )
}
