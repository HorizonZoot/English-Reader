package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.data.ai.AiClient
import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiConnectionTestEvent
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiModelDiscoveryDraft
import io.github.zoot.englishreader.data.ai.AiModelDiscoveryResult
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.PronunciationCacheStats
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import io.github.zoot.englishreader.data.repository.ProfileMutationResult
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val explanationCacheRepository: ExplanationCacheRepository = mockk(relaxed = true)
    private val defaultAiClient: AiClient = mockk(relaxed = true) {
        every { inFlightProfileIds } returns MutableStateFlow(emptySet())
    }

    @Test
    fun createProfile_validInput_persistsProfileAndEmitsCreated() = runTest {
        val repository = mockProfileRepository()
        val profileSlot = slot<AiProviderProfile>()
        coEvery {
            repository.createProfile(capture(profileSlot), "secret-key")
        } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.createDeepSeekProfile(apiKey = "  secret-key  ")
        advanceUntilIdle()

        val event = viewModel.profileActionEvents.first()
        assertEquals(ProfileActionResult.CREATED, event.action)
        assertEquals(profileSlot.captured.profileId, event.profileId)
        coVerify(exactly = 1) {
            repository.createProfile(
                match {
                    it.displayName == "DeepSeek" &&
                        it.providerTemplate == AiProviderTemplate.DEEPSEEK &&
                        it.baseUrl == "https://api.deepseek.com/v1" &&
                        it.modelId == "deepseek-chat"
                },
                "secret-key"
            )
        }
    }

    @Test
    fun createProfile_blankApiKey_doesNotCallRepositoryAndEmitsFailed() = runTest {
        val repository = mockProfileRepository()
        val viewModel = createViewModel(repository)

        viewModel.createDeepSeekProfile(apiKey = "   ")
        advanceUntilIdle()

        assertEquals(ProfileActionResult.FAILED, viewModel.profileActionEvents.first().action)
        coVerify(exactly = 0) { repository.createProfile(any(), any()) }
    }

    @Test
    fun createProfile_duplicateWhileRunning_dispatchesOneMutationAndResetsLoading() = runTest {
        val releaseMutation = CompletableDeferred<Unit>()
        val repository = mockProfileRepository()
        coEvery { repository.createProfile(any(), any()) } coAnswers {
            releaseMutation.await()
            ProfileMutationResult.Success
        }
        val viewModel = createViewModel(repository)

        repeat(2) {
            viewModel.createDeepSeekProfile(apiKey = "secret-key")
        }
        runCurrent()

        assertTrue(viewModel.profileMutationInFlight.value)
        coVerify(exactly = 1) { repository.createProfile(any(), "secret-key") }

        releaseMutation.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.profileMutationInFlight.value)
        assertEquals(ProfileActionResult.CREATED, viewModel.profileActionEvents.first().action)
        coVerify(exactly = 1) { repository.createProfile(any(), "secret-key") }
    }

    @Test
    fun updateProfile_whileCreateRunning_isIgnoredBySharedMutationGate() = runTest {
        val releaseMutation = CompletableDeferred<Unit>()
        val repository = mockProfileRepository()
        coEvery { repository.createProfile(any(), any()) } coAnswers {
            releaseMutation.await()
            ProfileMutationResult.Success
        }
        val viewModel = createViewModel(repository)

        viewModel.createDeepSeekProfile(apiKey = "secret-key")
        viewModel.updateProfile(
            profileId = "profile-1",
            displayName = "Renamed",
            providerTemplate = AiProviderTemplate.DEEPSEEK,
            baseUrl = "https://api.deepseek.com",
            modelId = "deepseek-chat",
            replacementApiKey = "",
            temperatureInput = "0.2"
        )
        runCurrent()

        coVerify(exactly = 1) { repository.createProfile(any(), any()) }
        coVerify(exactly = 0) { repository.updateProfile(any(), any()) }

        releaseMutation.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.profileMutationInFlight.value)
    }

    @Test
    fun updateProfile_duplicateAndCreateWhileRunning_dispatchesOneMutation() = runTest {
        val releaseMutation = CompletableDeferred<Unit>()
        val repository = mockProfileRepository()
        coEvery { repository.updateProfile(any(), any()) } coAnswers {
            releaseMutation.await()
            ProfileMutationResult.Success
        }
        val viewModel = createViewModel(repository)

        repeat(2) {
            viewModel.updateProfile(
                profileId = "profile-1",
                displayName = "Renamed",
                providerTemplate = AiProviderTemplate.DEEPSEEK,
                baseUrl = "https://api.deepseek.com",
                modelId = "deepseek-chat",
                replacementApiKey = "",
                temperatureInput = "0.2"
            )
        }
        viewModel.createDeepSeekProfile(apiKey = "secret-key")
        runCurrent()

        assertTrue(viewModel.profileMutationInFlight.value)
        coVerify(exactly = 1) { repository.updateProfile(any(), any()) }
        coVerify(exactly = 0) { repository.createProfile(any(), any()) }

        releaseMutation.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.profileMutationInFlight.value)
        assertEquals(ProfileActionResult.UPDATED, viewModel.profileActionEvents.first().action)
    }

    @Test
    fun createProfile_typedFailure_resetsMutationLoading() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.createProfile(any(), any()) } returns
            ProfileMutationResult.CredentialStorageUnavailable
        val viewModel = createViewModel(repository)

        viewModel.createDeepSeekProfile(apiKey = "secret-key")
        advanceUntilIdle()

        assertFalse(viewModel.profileMutationInFlight.value)
        assertEquals(
            ProfileMutationResult.CredentialStorageUnavailable,
            viewModel.profileActionEvents.first().failure
        )
    }

    @Test
    fun createProfile_exceptionEmitsFailureAndResetsMutationLoading() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.createProfile(any(), any()) } throws IOException("storage failed")
        val viewModel = createViewModel(repository)

        viewModel.createDeepSeekProfile(apiKey = "secret-key")
        advanceUntilIdle()

        assertFalse(viewModel.profileMutationInFlight.value)
        assertEquals(ProfileActionResult.FAILED, viewModel.profileActionEvents.first().action)
    }

    @Test
    fun createProfile_cancellationResetsMutationLoadingAndEmitsNothing() = runTest {
        val repository = mockProfileRepository()
        val releaseMutation = CompletableDeferred<Unit>()
        coEvery { repository.createProfile(any(), any()) } coAnswers {
            releaseMutation.await()
            throw CancellationException("cancelled")
        }
        val viewModel = createViewModel(repository)

        viewModel.profileActionEvents.test {
            try {
                viewModel.createDeepSeekProfile(apiKey = "secret-key")
                runCurrent()
                assertTrue(viewModel.profileMutationInFlight.value)
                expectNoEvents()
            } finally {
                releaseMutation.complete(Unit)
            }
            advanceUntilIdle()

            assertFalse(viewModel.profileMutationInFlight.value)
            expectNoEvents()
        }
    }

    @Test
    fun rotateApiKey_successEmitsRotated_withoutExposingKeyInState() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.rotateApiKey("profile-1", "rotated-key") } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.rotateApiKey("profile-1", " rotated-key ")
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.ROTATED, "profile-1"),
            viewModel.profileActionEvents.first()
        )
        assertFalse(viewModel.toString().contains("rotated-key"))
        coVerify(exactly = 1) { repository.rotateApiKey("profile-1", "rotated-key") }
    }

    @Test
    fun deleteProfile_successEmitsDeleted() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.deleteProfile("profile-1") } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.deleteProfile("profile-1")
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.DELETED, "profile-1"),
            viewModel.profileActionEvents.first()
        )
    }

    @Test
    fun updateProfileSettings_validInputEmitsScopedUpdatedEvent() = runTest {
        val repository = mockProfileRepository()
        coEvery {
            repository.updateProfileSettings("profile-1", "Renamed", 0.6)
        } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.updateProfileSettings("profile-1", " Renamed ", "0.6")
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.UPDATED, "profile-1"),
            viewModel.profileActionEvents.first()
        )
    }

    @Test
    fun updateProfileSettings_invalidTemperatureDoesNotCallRepository() = runTest {
        val repository = mockProfileRepository()
        val viewModel = createViewModel(repository)

        viewModel.updateProfileSettings("profile-1", "Renamed", "NaN")
        advanceUntilIdle()

        assertEquals(ProfileActionResult.FAILED, viewModel.profileActionEvents.first().action)
        coVerify(exactly = 0) { repository.updateProfileSettings(any(), any(), any()) }
    }

    @Test
    fun updateProfile_completeInputNormalizesAndEmitsUpdated() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.updateProfile(any(), "new-key") } returns
            ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.updateProfile(
            profileId = "profile-1",
            displayName = " Renamed ",
            providerTemplate = AiProviderTemplate.DEEPSEEK,
            baseUrl = " https://api.deepseek.com ",
            modelId = " deepseek-chat ",
            replacementApiKey = " new-key ",
            temperatureInput = "0.3"
        )
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.UPDATED, "profile-1"),
            viewModel.profileActionEvents.first()
        )
        coVerify {
            repository.updateProfile(
                match {
                    it.profileId == "profile-1" && it.displayName == "Renamed" &&
                        it.baseUrl == "https://api.deepseek.com" &&
                        it.modelId == "deepseek-chat" && it.temperature == 0.3
                },
                "new-key"
            )
        }
    }

    @Test
    fun deleteAllProfiles_successEmitsConfigurationsDeleted() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.deleteAllProfiles() } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.deleteAllProfiles()
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.CONFIGURATIONS_DELETED),
            viewModel.profileActionEvents.first()
        )
    }

    @Test
    fun reinitializeCredentialStorage_successEmitsNonDestructiveAction() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.reinitializeCredentialStorage() } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.reinitializeCredentialStorage()
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.STORAGE_REINITIALIZED),
            viewModel.profileActionEvents.first()
        )
        coVerify(exactly = 0) { repository.clearAllCredentials() }
    }

    @Test
    fun clearAllCredentials_successEmitsDestructiveAction() = runTest {
        val repository = mockProfileRepository()
        coEvery { repository.clearAllCredentials() } returns ProfileMutationResult.Success
        val viewModel = createViewModel(repository)

        viewModel.clearAllCredentials()
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.CREDENTIALS_CLEARED),
            viewModel.profileActionEvents.first()
        )
    }

    /**
     * 清除发音缓存后必须**立刻刷新占用**，而且顺序必须是「先清除、后读取」。
     *
     * ## 为什么重写这条
     *
     * 原来用 `coEvery { cache.stats() } returnsMany listOf(12词, 0词)` —— 按**调用次数**返回
     * 固定值，与 `clear()` 是否执行过无关。外部审计指出：把生产代码改成「先读 stats、后
     * clear」，这些断言仍然全过，因为第二次调用总是返回 0。
     *
     * | 顺序 | mock 最终值 | clear 次数 | 旧断言 | 真实缓存该返回 |
     * |---|---:|---:|---|---:|
     * | clear → stats | 0 | 1 | 接受 | 0 |
     * | stats → clear | 0 | 1 | **也接受** | 12 |
     *
     * 所以那条测试守不住它声称的性质。这一版让 fake 的 `stats()` 从**真实内部状态**派生，
     * 顺序错了第二次读到的就是 12，断言会红。
     */
    @Test
    fun clearPronunciationCache_readsStatsAfterClearingNotBefore() = runTest {
        // 有状态的 fake：stats() 反映 clear() 是否真的执行过。
        var cachedStats = PronunciationCacheStats(12, 240_000L)
        val cache = mockk<PronunciationAudioCache>(relaxed = true)
        coEvery { cache.stats() } answers { cachedStats }
        coEvery { cache.clear() } answers { cachedStats = PronunciationCacheStats(0, 0L) }

        val viewModel = createViewModel(pronunciationCache = cache)

        viewModel.refreshPronunciationCacheStats()
        advanceUntilIdle()
        assertEquals("baseline should reflect a populated cache", 12, viewModel.pronunciationCacheStats.value.wordCount)

        viewModel.clearPronunciationCache()
        advanceUntilIdle()

        coVerify(exactly = 1) { cache.clear() }
        assertEquals(
            "stats must be read AFTER clearing; reading first leaves the row showing 12 words " +
                "that no longer exist, and the user taps clear again",
            0,
            viewModel.pronunciationCacheStats.value.wordCount
        )
        assertEquals(0L, viewModel.pronunciationCacheStats.value.totalBytes)
    }

    @Test
    fun clearAiExplanationCache_successEmitsCacheCleared() = runTest {
        coEvery { explanationCacheRepository.clearAllCache() } returns 2
        val viewModel = createViewModel()

        viewModel.clearAiExplanationCache()
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.CACHE_CLEARED),
            viewModel.profileActionEvents.first()
        )
        coVerify(exactly = 1) { explanationCacheRepository.clearAllCache() }
    }

    @Test
    fun clearAiExplanationCache_failureEmitsFailed() = runTest {
        coEvery { explanationCacheRepository.clearAllCache() } throws IOException("disk unavailable")
        val viewModel = createViewModel()

        viewModel.clearAiExplanationCache()
        advanceUntilIdle()

        assertEquals(
            ProfileActionEvent(ProfileActionResult.FAILED),
            viewModel.profileActionEvents.first()
        )
        coVerify(exactly = 1) { explanationCacheRepository.clearAllCache() }
    }

    @Test
    fun clearAiExplanationCache_cancellationEmitsNothing() = runTest {
        coEvery { explanationCacheRepository.clearAllCache() } throws
            CancellationException("cancelled")
        val viewModel = createViewModel()

        viewModel.profileActionEvents.test {
            viewModel.clearAiExplanationCache()
            advanceUntilIdle()

            expectNoEvents()
        }
        coVerify(exactly = 1) { explanationCacheRepository.clearAllCache() }
    }

    @Test
    fun testConnection_successEmitsTypedOneShotEvent() = runTest {
        val aiClient = mockk<AiClient>()
        every { aiClient.inFlightProfileIds } returns MutableStateFlow(emptySet())
        coEvery { aiClient.testConnection("profile-1") } returns AiClientResult.Success("ok")
        val viewModel = createViewModel(aiClient = aiClient)

        viewModel.testConnection("profile-1", requestId = "request-1")
        advanceUntilIdle()

        assertEquals(
            AiConnectionTestEvent(
                requestId = "request-1",
                profileId = "profile-1",
                result = AiClientResult.Success("ok")
            ),
            viewModel.connectionTestEvents.first()
        )
    }

    @Test
    fun testConnection_typedFailureIsPreserved() = runTest {
        val aiClient = mockk<AiClient>()
        every { aiClient.inFlightProfileIds } returns MutableStateFlow(setOf("profile-1"))
        coEvery { aiClient.testConnection("profile-1") } returns
            AiClientResult.Failure(AiError.RateLimited(12))
        val viewModel = createViewModel(aiClient = aiClient)

        assertEquals(setOf("profile-1"), viewModel.connectionTestInFlightProfileIds.value)
        viewModel.testConnection("profile-1", requestId = "request-2")
        advanceUntilIdle()

        assertEquals(
            AiConnectionTestEvent(
                requestId = "request-2",
                profileId = "profile-1",
                result = AiClientResult.Failure(AiError.RateLimited(12))
            ),
            viewModel.connectionTestEvents.first()
        )
    }

    @Test
    fun testConnection_cancellationEmitsNothing() = runTest {
        val aiClient = mockk<AiClient>()
        every { aiClient.inFlightProfileIds } returns MutableStateFlow(emptySet())
        coEvery { aiClient.testConnection("profile-1") } throws
            CancellationException("cancelled")
        val viewModel = createViewModel(aiClient = aiClient)

        viewModel.connectionTestEvents.test {
            viewModel.testConnection("profile-1", requestId = "request-3")
            advanceUntilIdle()
            coVerify(exactly = 1) { aiClient.testConnection("profile-1") }
            expectNoEvents()
        }
    }

    @Test
    fun testConnectionDraft_duplicateWhileRunning_dispatchesOneProbeAndResetsLoading() = runTest {
        val releaseProbe = CompletableDeferred<Unit>()
        val aiClient = mockk<AiClient>()
        every { aiClient.inFlightProfileIds } returns MutableStateFlow(emptySet())
        coEvery { aiClient.testConnectionDraft(any()) } coAnswers {
            releaseProbe.await()
            AiClientResult.Success("ok")
        }
        val viewModel = createViewModel(aiClient = aiClient)

        val firstAccepted = viewModel.testConnectionDraft(
            requestId = "request-0",
            providerTemplate = AiProviderTemplate.DEEPSEEK,
            baseUrl = "https://api.deepseek.com",
            modelId = "deepseek-chat",
            apiKey = "sk-draft",
            temperatureInput = "0.2"
        )
        val duplicateAccepted = viewModel.testConnectionDraft(
            requestId = "request-1",
            providerTemplate = AiProviderTemplate.DEEPSEEK,
            baseUrl = "https://api.deepseek.com",
            modelId = "deepseek-chat",
            apiKey = "sk-draft",
            temperatureInput = "0.2"
        )
        runCurrent()

        assertTrue(firstAccepted)
        assertFalse(duplicateAccepted)
        assertTrue(viewModel.draftConnectionTestInFlight.value)
        coVerify(exactly = 1) { aiClient.testConnectionDraft(any()) }

        releaseProbe.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.draftConnectionTestInFlight.value)
        assertEquals(
            AiConnectionTestEvent(
                requestId = "request-0",
                profileId = io.github.zoot.englishreader.data.ai.AI_DRAFT_CONNECTION_TEST_ID,
                result = AiClientResult.Success("ok")
            ),
            viewModel.connectionTestEvents.first()
        )
    }

    @Test
    fun discoverModels_duplicateWhileLoading_dispatchesOneDirectoryRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        val draft = slot<AiModelDiscoveryDraft>()
        coEvery { defaultAiClient.discoverModels(capture(draft)) } coAnswers {
            release.await()
            AiModelDiscoveryResult.Success(listOf("model-a"))
        }
        val viewModel = createViewModel()

        viewModel.discoverModels(null, " https://example.com ", " secret ")
        viewModel.discoverModels(null, "https://example.com", "secret")
        runCurrent()

        assertTrue(viewModel.modelDiscovery.value.loading)
        assertEquals("https://example.com", draft.captured.baseUrl)
        assertEquals("secret", draft.captured.apiKey)
        coVerify(exactly = 1) { defaultAiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.modelDiscovery.value.loading)
        assertEquals(
            listOf("model-a"),
            (viewModel.modelDiscovery.value.result as AiModelDiscoveryResult.Success).modelIds
        )
        coVerify(exactly = 0) { defaultAiClient.testConnectionDraft(any()) }
        coVerify(exactly = 0) { defaultAiClient.testConnection(any(), any()) }
    }

    @Test
    fun discoverModels_savedProfile_usesStoredCredentialPathAndPreservesTypedFailure() = runTest {
        coEvery { defaultAiClient.discoverModels("profile-1") } returns
            AiModelDiscoveryResult.Failure(AiError.HttpAuth(401))
        val viewModel = createViewModel()

        viewModel.discoverModels("profile-1", "https://example.com", "")
        advanceUntilIdle()

        assertFalse(viewModel.modelDiscovery.value.loading)
        assertEquals(AiModelDiscoveryResult.Failure(AiError.HttpAuth(401)), viewModel.modelDiscovery.value.result)
        coVerify(exactly = 1) { defaultAiClient.discoverModels("profile-1") }
        coVerify(exactly = 0) { defaultAiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
    }

    @Test
    fun discoverModels_invalidatedRequestCompletesLate_preservesNewLoadingAndResult() = runTest {
        val oldRequest = CompletableDeferred<Unit>()
        val newRequest = CompletableDeferred<Unit>()
        coEvery { defaultAiClient.discoverModels("old") } coAnswers {
            withContext(NonCancellable) { oldRequest.await() }
            AiModelDiscoveryResult.Success(listOf("old-model"))
        }
        coEvery { defaultAiClient.discoverModels("new") } coAnswers {
            newRequest.await()
            AiModelDiscoveryResult.Success(listOf("new-model"))
        }
        val viewModel = createViewModel()

        viewModel.discoverModels("old", "", "")
        runCurrent()
        viewModel.invalidateModelDiscovery()
        assertEquals(ModelDiscoveryState(), viewModel.modelDiscovery.value)
        viewModel.discoverModels("new", "", "")
        runCurrent()
        assertEquals(ModelDiscoveryState(loading = true), viewModel.modelDiscovery.value)
        oldRequest.complete(Unit)
        runCurrent()
        assertEquals(ModelDiscoveryState(loading = true), viewModel.modelDiscovery.value)

        newRequest.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.modelDiscovery.value.loading)
        assertEquals(
            listOf("new-model"),
            (viewModel.modelDiscovery.value.result as AiModelDiscoveryResult.Success).modelIds
        )
    }

    @Test
    fun discoverModels_cancelled_resetsLoadingWithoutFailureResult() = runTest {
        coEvery { defaultAiClient.discoverModels("profile-1") } throws CancellationException("cancelled")
        val viewModel = createViewModel()

        viewModel.discoverModels("profile-1", "", "")
        advanceUntilIdle()

        assertEquals(ModelDiscoveryState(), viewModel.modelDiscovery.value)
    }

    private fun createViewModel(
        repository: AiProfileRepository = mockProfileRepository(),
        aiClient: AiClient = defaultAiClient,
        pronunciationCache: PronunciationAudioCache = mockk(relaxed = true)
    ) = SettingsViewModel(
        mockSettingsPreferences(),
        repository,
        explanationCacheRepository,
        aiClient,
        mockk(relaxed = true),
        pronunciationCache,
        mockk(relaxed = true)
    )

    private fun mockProfileRepository(): AiProfileRepository = mockk {
        every { profiles } returns flowOf(emptyList())
        every { activeProfileId } returns flowOf(null)
    }

    private fun SettingsViewModel.createDeepSeekProfile(apiKey: String) {
        createProfile(
            displayName = "DeepSeek",
            providerTemplate = AiProviderTemplate.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            modelId = "deepseek-chat",
            apiKey = apiKey
        )
    }

    private fun mockSettingsPreferences(): SettingsPreferences = mockk {
        every { fontSizeOption } returns flowOf(FontSizeOption.DEFAULT)
        every { themeOption } returns flowOf(ThemeOption.DEFAULT)
        every { allowNetworkTts } returns flowOf(false)
    }
}
