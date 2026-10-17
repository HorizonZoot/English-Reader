package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import io.github.zoot.englishreader.data.ai.AiArticleDifficulty
import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerationParameters
import io.github.zoot.englishreader.data.ai.AiArticleGenerationResult
import io.github.zoot.englishreader.data.ai.AiArticleGenerator
import io.github.zoot.englishreader.data.ai.AiArticleLength
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticle
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.AiArticleGenerationRepository
import io.github.zoot.englishreader.data.repository.AiArticleSaveStatus
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiArticleGenerationViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())
    private val generator = mockk<AiArticleGenerator>()
    private val articles = mockk<ArticleRepository>()
    private val profiles = mockk<AiProfileRepository>()
    private val available = MutableStateFlow(listOf(profile("first"), profile("second")))
    private val active = MutableStateFlow<String?>("first")
    private val stores = mutableListOf<ViewModelStore>()

    init {
        every { profiles.profiles } returns available
        every { profiles.activeProfileId } returns active
        coEvery { generator.generate(any(), any()) } answers {
            AiArticleGenerationResult.Success(
                AiGeneratedArticle("A walk", "A short walk in the park.", secondArg(), 7)
            )
        }
        coEvery { articles.insertArticle(any()) } returns 42L
    }

    @After
    fun tearDown() { stores.forEach { it.clear() } }

    @Test
    fun initialize_defaultProfileAndParameters_doesNotGenerate() = runTest {
        val vm = viewModel(AiArticleGenerationRepository(generator, backgroundScope, articles))
        assertFalse(vm.uiState.value.canGenerate)
        runCurrent()
        assertEquals("first", vm.uiState.value.selectedProfile?.profileId)
        assertEquals(AiArticleGenerationParameters(), vm.uiState.value.session.draft.parameters)
        assertTrue(vm.uiState.value.canGenerate)
        coVerify(exactly = 0) { generator.generate(any(), any()) }
    }

    @Test
    fun selectProfile_localChoiceAndInput_surviveSettingsAndViewModelRecreation() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.selectProfile("second")
        vm.updateTopic("A trip 出行")
        vm.updateDifficulty(AiArticleDifficulty.ADVANCED)
        vm.updateLength(AiArticleLength.LONG)
        vm.setScreenActive(false)
        stores.first().clear()
        active.value = "second"
        runCurrent()
        active.value = "first"
        val restored = viewModel(repository)
        runCurrent()
        assertEquals("second", restored.uiState.value.selectedProfile?.profileId)
        assertEquals(
            AiArticleGenerationParameters(AiArticleDifficulty.ADVANCED, AiArticleLength.LONG, "A trip 出行"),
            restored.uiState.value.session.draft.parameters
        )
        verify { profiles.profiles; profiles.activeProfileId }
        confirmVerified(profiles)
    }

    @Test
    fun profiles_deletedSelection_requiresExplicitReselectionWithoutFallback() = runTest {
        val vm = viewModel(AiArticleGenerationRepository(generator, backgroundScope, articles))
        runCurrent()
        available.value = listOf(profile("second"))
        active.value = "second"
        runCurrent()
        assertNull(vm.uiState.value.selectedProfile)
        assertEquals("first", vm.uiState.value.session.draft.profileId)
        vm.generate()
        coVerify(exactly = 0) { generator.generate(any(), any()) }
        vm.selectProfile("unknown")
        vm.selectProfile("second")
        runCurrent()
        assertTrue(vm.uiState.value.canGenerate)
        vm.generate()
        runCurrent()
        coVerify(exactly = 1) { generator.generate("second", AiArticleGenerationParameters()) }
    }

    @Test
    fun profiles_emptyThenConfigured_keepsTopicAndInitializesSelection() = runTest {
        available.value = emptyList()
        active.value = null
        val vm = viewModel(AiArticleGenerationRepository(generator, backgroundScope, articles))
        runCurrent()
        vm.updateTopic("My topic")
        vm.generate()
        runCurrent()
        assertFalse(vm.uiState.value.canGenerate)
        coVerify(exactly = 0) { generator.generate(any(), any()) }
        available.value = listOf(profile("new"))
        active.value = "new"
        runCurrent()
        assertEquals("My topic", vm.uiState.value.session.draft.parameters.topic)
        assertEquals("new", vm.uiState.value.selectedProfile?.profileId)
        assertTrue(vm.uiState.value.canGenerate)
    }

    @Test
    fun profiles_readFailure_canRetryWithoutLosingDraft() = runTest {
        every { profiles.profiles } returns flow { throw IOException("private data") }
        val vm = viewModel(AiArticleGenerationRepository(generator, backgroundScope, articles))
        vm.updateTopic("Retained")
        runCurrent()
        assertTrue(vm.uiState.value.profilesLoadFailed)
        assertFalse(vm.uiState.value.canGenerate)
        every { profiles.profiles } returns available
        vm.reloadProfiles()
        runCurrent()
        assertFalse(vm.uiState.value.profilesLoadFailed)
        assertTrue(vm.uiState.value.canGenerate)
        assertEquals("Retained", vm.uiState.value.session.draft.parameters.topic)
        assertFalse(vm.uiState.value.toString().contains("Retained"))
        assertFalse(vm.uiState.value.toString().contains("private data"))
    }

    @Test
    fun profiles_cancelledRead_isNotPresentedAsStorageFailure() = runTest {
        every { profiles.profiles } returns flow { throw CancellationException("cancelled") }
        val vm = viewModel(AiArticleGenerationRepository(generator, backgroundScope, articles))
        runCurrent()
        assertFalse(vm.uiState.value.profilesLoadFailed)
        assertFalse(vm.uiState.value.profilesLoaded)
    }

    @Test
    fun generate_topicBudget_rejectsOverLimitWithoutTruncation() = runTest {
        val vm = viewModel(AiArticleGenerationRepository(generator, backgroundScope, articles))
        runCurrent()
        val overBudget = "😀".repeat(250) + "x"
        vm.updateTopic(overBudget)
        vm.generate()
        runCurrent()
        assertTrue(vm.uiState.value.topicTooLong)
        assertEquals(overBudget, vm.uiState.value.session.draft.parameters.topic)
        coVerify(exactly = 0) { generator.generate(any(), any()) }
        vm.updateTopic("😀".repeat(250))
        vm.generate()
        runCurrent()
        coVerify(exactly = 1) { generator.generate("first", match { it.topic.length == 500 }) }
    }

    @Test
    fun generate_singleFlight_locksDraftAndSurvivesViewModelRemoval() = runTest {
        val result = CompletableDeferred<AiArticleGenerationResult>()
        coEvery { generator.generate(any(), any()) } coAnswers { result.await() }
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.generate()
        vm.generate()
        vm.selectProfile("second")
        vm.updateTopic("Blocked")
        vm.updateLength(AiArticleLength.SHORT)
        vm.updateDifficulty(AiArticleDifficulty.ADVANCED)
        runCurrent()
        assertTrue(vm.uiState.value.isBusy)
        assertEquals(AiArticleGenerationParameters(), repository.state.value.draft.parameters)
        assertEquals("first", repository.state.value.draft.profileId)
        vm.setScreenActive(false)
        stores.first().clear()
        result.complete(AiArticleGenerationResult.Success(AiGeneratedArticle("Done", "Text", AiArticleGenerationParameters(), 1)))
        runCurrent()
        val restored = viewModel(repository)
        runCurrent()
        assertEquals("Done", restored.uiState.value.session.preview?.article?.title)
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun generate_retryFails_preservesOldPreviewWithoutAutomaticRetry() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.generate()
        runCurrent()
        val preview = repository.state.value.preview
        vm.updateTopic("Next topic")
        coEvery { generator.generate(any(), any()) } returns
            AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(AiError.NoContent))
        vm.generate()
        runCurrent()
        assertEquals(preview, vm.uiState.value.session.preview)
        assertEquals("Next topic", vm.uiState.value.session.draft.parameters.topic)
        coVerify(exactly = 2) { generator.generate(any(), any()) }
    }

    @Test
    fun saveAndRead_doubleClick_navigatesOnceAndReusesSavedIdAfterNavigationFailure() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.generate()
        runCurrent()
        val id = requireNotNull(repository.state.value.preview).resultId
        vm.setScreenActive(true)
        vm.events.test {
            vm.saveAndRead(id)
            vm.saveAndRead(id)
            runCurrent()
            val first = awaitItem()
            assertEquals(42L, vm.consumeReadRequest(first))
            assertNull(vm.consumeReadRequest(first))
            expectNoEvents()
            // 导航失败也保留 Saved；下一次明确点击仅打开同一个 ID。
            vm.saveAndRead(id)
            val second = awaitItem()
            assertEquals(42L, vm.consumeReadRequest(second))
            expectNoEvents()
        }
        coVerify(exactly = 1) { articles.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    @Test
    fun saveAndRead_leaveWhileSaving_completesWithoutReplayAfterReturn() = runTest {
        val saved = CompletableDeferred<Long>()
        coEvery { articles.insertArticle(any()) } coAnswers { saved.await() }
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.generate()
        runCurrent()
        val id = requireNotNull(repository.state.value.preview).resultId
        vm.setScreenActive(true)
        vm.saveAndRead(id)
        runCurrent()
        vm.setScreenActive(false)
        saved.complete(42L)
        runCurrent()
        vm.setScreenActive(true)
        vm.events.test { expectNoEvents() }
        val restored = viewModel(repository)
        restored.setScreenActive(true)
        runCurrent()
        assertEquals(AiArticleSaveStatus.Saved(42L), restored.uiState.value.session.preview?.saveStatus)
        restored.events.test {
            expectNoEvents()
            restored.saveAndRead(id)
            assertEquals(42L, restored.consumeReadRequest(awaitItem()))
        }
        coVerify(exactly = 1) { articles.insertArticle(any()) }
    }

    @Test
    fun saveAndRead_bufferedOldEvent_isRejectedAfterLeavingEvenForSameArticle() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.generate()
        runCurrent()
        val id = requireNotNull(repository.state.value.preview).resultId
        vm.setScreenActive(true)
        vm.saveAndRead(id)
        runCurrent()
        vm.events.test {
            val stale = awaitItem()
            vm.setScreenActive(false)
            vm.setScreenActive(true)
            vm.saveAndRead(id)
            val current = awaitItem()
            assertNull(vm.consumeReadRequest(stale))
            assertEquals(42L, vm.consumeReadRequest(current))
            expectNoEvents()
        }
    }

    @Test
    fun saveAndRead_localFailure_retriesOnlyStorageAndRejectsStaleResult() = runTest {
        val repository = AiArticleGenerationRepository(generator, backgroundScope, articles)
        val vm = viewModel(repository)
        runCurrent()
        vm.generate()
        runCurrent()
        val id = requireNotNull(repository.state.value.preview).resultId
        vm.setScreenActive(true)
        vm.saveAndRead("stale")
        coVerify(exactly = 0) { articles.insertArticle(any()) }
        coEvery { articles.insertArticle(any()) } throws IOException("private sql")
        vm.events.test {
            vm.saveAndRead(id)
            runCurrent()
            assertEquals(AiArticleSaveStatus.Failed, vm.uiState.value.session.preview?.saveStatus)
            expectNoEvents()
            coEvery { articles.insertArticle(any()) } returns 42L
            vm.saveAndRead(id)
            runCurrent()
            assertEquals(42L, vm.consumeReadRequest(awaitItem()))
        }
        coVerify(exactly = 2) { articles.insertArticle(any()) }
        coVerify(exactly = 1) { generator.generate(any(), any()) }
    }

    private fun viewModel(repository: AiArticleGenerationRepository): AiArticleGenerationViewModel =
        AiArticleGenerationViewModel(repository, profiles).also { vm ->
            stores += ViewModelStore().apply { put("generation", vm) }
        }

    private fun profile(id: String) = AiProviderProfile(
        profileId = id, displayName = "Profile $id", providerTemplate = AiProviderTemplate.OPENAI_COMPATIBLE,
        baseUrl = "https://example.com/v1", modelId = "model", authStrategy = AiAuthStrategy.API_KEY,
        temperature = 0.7
    )
}
