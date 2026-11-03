package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiCredentialStorage
import io.github.zoot.englishreader.data.local.AiProfileMetadataReadResult
import io.github.zoot.englishreader.data.local.AiProfileMetadataSnapshot
import io.github.zoot.englishreader.data.local.AiProfileMetadataStore
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiThinkingMode
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.remote.ai.AiHttpStatusException
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@OptIn(ExperimentalCoroutinesApi::class)
class AiArticleGeneratorTest {
    private val profiles: AiProfileRepository = mockk()
    private val transport: AiChatTransport = mockk()
    private val generator = AiArticleGenerator(profiles, transport, AiErrorMapper(isOnline = { true }))
    private val parameters = AiArticleGenerationParameters(topic = "private topic")

    /**
     * 打开生成页只构造本类；网络栈要等真正发请求时才取。被拒绝的输入和缺失的配置
     * 都不应触发 transport 构建。
     */
    @Test
    fun generate_transportResolvedOnlyWhenRequestIsSent() = runTest {
        var resolved = 0
        val lazyGenerator = AiArticleGenerator(
            profiles, { resolved++; transport }, AiErrorMapper(isOnline = { true })
        )
        assertEquals(0, resolved)

        lazyGenerator.generate("chosen", parameters.copy(topic = "x".repeat(501)))
        lazyGenerator.generate(null, parameters)
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns ProfileResolutionResult.ProfileNotFound
        lazyGenerator.generate("chosen", parameters)
        assertEquals(0, resolved)

        resolveProfile()
        coEvery { transport.complete(any(), any()) } returns content()
        assertTrue(lazyGenerator.generate("chosen", parameters) is AiArticleGenerationResult.Success)
        assertEquals(1, resolved)
    }

    @Test
    fun generate_eachLength_sendsPreparedMessagesAndBoundedSnapshot() = runTest {
        val profile = profile()
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
            ProfileResolutionResult.Available(profile)
        val configs = mutableListOf<AiChatRequestConfig>()
        val messages = mutableListOf<List<AiChatMessage>>()
        coEvery { transport.complete(capture(configs), capture(messages)) } returns content()

        AiArticleLength.entries.forEach { length ->
            val input = parameters.copy(length = length, topic = "  private topic\r\nexample  ")
            val result = generator.generate("chosen", input) as AiArticleGenerationResult.Success
            val prepared = (AiArticlePromptPolicy.prepare(input) as AiArticlePromptPreparationResult.Ready).prompt
            assertEquals(prepared.parameters, result.article.parameters)
            assertEquals(
                prepared.messages.map { it.role to it.content },
                messages.last().map { it.role to it.content }
            )
        }

        assertEquals(listOf(2_048, 3_072, 4_096), configs.map { it.maxTokens })
        configs.forEach { config ->
            assertEquals(profile.baseUrl, config.baseUrl)
            assertEquals("test-model", config.modelId)
            assertEquals(profile.apiKey, config.apiKey)
            assertEquals(profile.providerTemplate, config.providerTemplate)
            assertEquals(profile.authStrategy, config.authStrategy)
            assertEquals(profile.temperature, config.temperature, 0.0)
            assertNull(config.thinkingMode)
            listOf("private-key", "test-model", "provider.invalid").forEach {
                assertFalse(config.toString().contains(it))
            }
        }
        coVerify(exactly = 3) { profiles.resolveValidatedProfile("chosen", any()) }
        coVerify(exactly = 0) { profiles.resolveValidatedActiveProfile(any()) }
        coVerify(exactly = 0) { transport.listModels(any()) }
    }

    @Test
    fun generate_confirmedModelsAtOfficialEndpoints_disablesThinking() = runTest {
        val configs = mutableListOf<AiChatRequestConfig>()
        coEvery { transport.complete(capture(configs), any()) } returns content()
        for (provider in listOf(AiProviderTemplate.DEEPSEEK, AiProviderTemplate.ZHIPU)) {
            val model = if (provider == AiProviderTemplate.DEEPSEEK) "deepseek-v4-flash" else "glm-5.2"
            coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
                ProfileResolutionResult.Available(profile().copy(
                    providerTemplate = provider, baseUrl = requireNotNull(provider.defaultBaseUrl), modelId = model
                ))

            assertTrue(generator.generate("chosen", parameters) is AiArticleGenerationResult.Success)
            assertEquals(AiThinkingMode.DISABLED, configs.last().thinkingMode)
        }
        coVerify(exactly = 2) { transport.complete(any(), any()) }
    }

    /**
     * 白名单按精确模型 ID 匹配，与模板默认模型各自维护。默认模型一旦升级而白名单未跟进，
     * 新用户的默认配置会静默恢复思考输出，可能计费后截断。此用例让这种漂移直接变红，
     * 由改默认模型的人确认新模型是否支持关闭思考，再同步白名单与 spec。
     */
    @Test
    fun generate_thinkingTemplateDefaultModels_stayOnDisableList() = runTest {
        val configs = mutableListOf<AiChatRequestConfig>()
        coEvery { transport.complete(capture(configs), any()) } returns content()
        for (provider in listOf(AiProviderTemplate.DEEPSEEK, AiProviderTemplate.ZHIPU)) {
            coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
                ProfileResolutionResult.Available(profile().copy(
                    providerTemplate = provider,
                    baseUrl = requireNotNull(provider.defaultBaseUrl),
                    modelId = requireNotNull(provider.defaultModelId)
                ))

            assertTrue(generator.generate("chosen", parameters) is AiArticleGenerationResult.Success)
            assertEquals("$provider default model", AiThinkingMode.DISABLED, configs.last().thinkingMode)
        }
    }

    @Test
    fun generate_unknownForcedThinkingOrCustomEndpoint_omitsThinkingOverride() = runTest {
        val cases = listOf(
            Triple(AiProviderTemplate.DEEPSEEK, "deepseek-reasoner", AiProviderTemplate.DEEPSEEK.defaultBaseUrl),
            Triple(AiProviderTemplate.DEEPSEEK, "future-model", AiProviderTemplate.DEEPSEEK.defaultBaseUrl),
            Triple(AiProviderTemplate.ZHIPU, "glm-4.5v", AiProviderTemplate.ZHIPU.defaultBaseUrl),
            Triple(AiProviderTemplate.ZHIPU, "future-model", AiProviderTemplate.ZHIPU.defaultBaseUrl),
            Triple(AiProviderTemplate.DEEPSEEK, "deepseek-v4-flash", "https://custom.example/v1"),
            Triple(AiProviderTemplate.ZHIPU, "glm-5.2", "https://custom.example/v1"),
            Triple(AiProviderTemplate.OPENAI_COMPATIBLE, "glm-5.2", AiProviderTemplate.ZHIPU.defaultBaseUrl)
        )
        val configs = mutableListOf<AiChatRequestConfig>()
        coEvery { transport.complete(capture(configs), any()) } returns content()
        for ((provider, model, endpoint) in cases) {
            coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
                ProfileResolutionResult.Available(profile().copy(
                    providerTemplate = provider, baseUrl = requireNotNull(endpoint), modelId = model
                ))

            assertTrue(generator.generate("chosen", parameters) is AiArticleGenerationResult.Success)
            assertNull("$provider / $model", configs.last().thinkingMode)
        }
        coVerify(exactly = cases.size) { transport.complete(any(), any()) }
    }

    @Test
    fun generate_endpointVariants_appliesOnlyExactOfficialRoutes() = runTest {
        val cases = listOf(
            Triple(AiProviderTemplate.DEEPSEEK, "https://API.DEEPSEEK.COM:443/", true),
            Triple(AiProviderTemplate.DEEPSEEK, "https://api.deepseek.com/v1/", true),
            Triple(AiProviderTemplate.DEEPSEEK, "https://api.deepseek.com/chat/completions", false),
            Triple(AiProviderTemplate.DEEPSEEK, "https://api.deepseek.com/other", false),
            Triple(AiProviderTemplate.DEEPSEEK, "https://api.deepseek.com:8443", false),
            Triple(AiProviderTemplate.DEEPSEEK, "https://api.deepseek.com.evil.example", false),
            Triple(AiProviderTemplate.ZHIPU, "https://open.bigmodel.cn/api/paas/v4/", true),
            Triple(AiProviderTemplate.ZHIPU, "https://open.bigmodel.cn/api/paas/v4/chat/completions", false),
            Triple(AiProviderTemplate.ZHIPU, "https://open.bigmodel.cn/other", false),
            Triple(AiProviderTemplate.ZHIPU, "https://open.bigmodel.cn:8443/api/paas/v4", false)
        )
        val configs = mutableListOf<AiChatRequestConfig>()
        coEvery { transport.complete(capture(configs), any()) } returns content()
        for ((provider, endpoint, disabled) in cases) {
            val model = if (provider == AiProviderTemplate.DEEPSEEK) "deepseek-v4-flash" else "glm-5.2"
            coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
                ProfileResolutionResult.Available(profile().copy(
                    providerTemplate = provider, baseUrl = endpoint, modelId = "  $model  "
                ))
            assertTrue(generator.generate("chosen", parameters) is AiArticleGenerationResult.Success)
            assertEquals(model, configs.last().modelId)
            assertEquals(AiThinkingMode.DISABLED.takeIf { disabled }, configs.last().thinkingMode)
        }
    }

    @Test
    fun generate_sameParametersAgain_callsRemoteAgain() = runTest {
        resolveProfile()
        coEvery { transport.complete(any(), any()) } returnsMany listOf(content("First"), content("Second"))

        val first = generator.generate("chosen", parameters) as AiArticleGenerationResult.Success
        val second = generator.generate("chosen", parameters) as AiArticleGenerationResult.Success

        assertEquals("First", first.article.title)
        assertEquals("Second", second.article.title)
        coVerify(exactly = 2) { transport.complete(any(), any()) }
    }

    @Test
    fun generate_overLimitTopic_rejectsBeforeProfileOrNetworkEvenWithoutSelection() = runTest {
        val input = parameters.copy(topic = "x".repeat(501))
        listOf("chosen", null).forEach { id ->
            assertEquals(requestFailure(AiError.InputTooLong(501, 500)), generator.generate(id, input))
        }
        verify { profiles wasNot Called }
        verify { transport wasNot Called }
    }

    @Test
    fun generate_noSelection_doesNotFallBackToGlobalProfile() = runTest {
        assertEquals(requestFailure(AiError.NoActiveProfile), generator.generate(null, parameters))
        verify { profiles wasNot Called }
        verify { transport wasNot Called }
    }

    @Test
    fun generate_profileUnavailable_preservesCategoryWithoutRemoteCall() = runTest {
        listOf(
            ProfileResolutionResult.NoActiveProfile to AiError.NoActiveProfile,
            ProfileResolutionResult.ProfileNotFound to AiError.ProfileNotFound,
            ProfileResolutionResult.InvalidEndpoint to AiError.InvalidEndpoint,
            ProfileResolutionResult.Missing to AiError.CredentialMissing,
            ProfileResolutionResult.StorageUnavailable to AiError.CredentialStorageUnavailable
        ).forEach { (resolution, error) ->
            coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns resolution
            assertEquals(requestFailure(error), generator.generate("chosen", parameters))
        }
        verify { transport wasNot Called }
    }

    @Test
    fun generate_unsafeEndpoint_rejectsBeforeDecryptingCredentials() = runTest {
        val metadata: AiProfileMetadataStore = mockk()
        val credentials: AiCredentialStorage = mockk()
        every { metadata.profiles } returns flowOf(emptyList())
        every { metadata.activeProfileId } returns flowOf("unrelated-default")
        val realProfiles = AiProfileRepository(metadata, credentials, backgroundScope)
        val realGenerator = AiArticleGenerator(realProfiles, transport, AiErrorMapper(isOnline = { true }))
        listOf("http://localhost/v1", "https://user:pass@provider.invalid", "https://provider.invalid?secret=1")
            .forEach { endpoint ->
                val profile = AiProviderProfile(
                    "chosen", "Private profile", AiProviderTemplate.OPENAI_COMPATIBLE,
                    endpoint, "private-model", AiAuthStrategy.API_KEY, 0.7
                )
                coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
                    AiProfileMetadataSnapshot(listOf(profile), "unrelated-default")
                )
                assertEquals(requestFailure(AiError.InvalidEndpoint), realGenerator.generate("chosen", parameters))
            }
        verify { credentials wasNot Called }
        verify { transport wasNot Called }
        coVerify(exactly = 0) { metadata.saveMetadata(any(), any(), any()) }
    }

    @Test
    fun generate_profileChangesDuringRemote_keepsOneImmutableSnapshot() = runTest {
        var selected = profile()
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } coAnswers {
            ProfileResolutionResult.Available(selected)
        }
        val release = CompletableDeferred<Unit>()
        val configs = mutableListOf<AiChatRequestConfig>()
        coEvery { transport.complete(capture(configs), any()) } coAnswers {
            release.await()
            content()
        }
        val first = async { generator.generate("chosen", parameters) }
        runCurrent()
        selected = selected.copy(baseUrl = "https://new.invalid", modelId = "new-model", apiKey = "new-key")
        release.complete(Unit)
        assertTrue(first.await() is AiArticleGenerationResult.Success)
        assertTrue(generator.generate("chosen", parameters) is AiArticleGenerationResult.Success)
        assertEquals("https://provider.invalid/v1", configs[0].baseUrl)
        assertEquals("private-key", configs[0].apiKey)
        assertEquals("test-model", configs[0].modelId)
        assertEquals("https://new.invalid", configs[1].baseUrl)
        assertEquals("new-key", configs[1].apiKey)
        coVerify(exactly = 2) { profiles.resolveValidatedProfile("chosen", any()) }
    }

    @Test
    fun generate_invalidOutput_returnsTypedRejectionInsteadOfPartialArticle() = runTest {
        resolveProfile()
        listOf(
            AiChatTransportResult.NoContent to AiGeneratedArticleRejection.NO_CONTENT,
            AiChatTransportResult.Truncated("private partial article") to AiGeneratedArticleRejection.TRUNCATED_RESPONSE,
            AiChatTransportResult.Content("private malformed response") to AiGeneratedArticleRejection.MALFORMED_RESPONSE
        ).forEach { (response, reason) ->
            coEvery { transport.complete(any(), any()) } returns response
            val result = generator.generate("chosen", parameters)
            assertEquals(AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Output(reason)), result)
            assertFalse(result.toString().contains("private"))
        }
    }

    @Test
    fun generate_remoteFailure_preservesTypedCategoriesWithoutRetry() = runTest {
        resolveProfile()
        val cases = listOf(
            AiTimeoutException(TimeoutPhase.CONNECT) to AiError.Timeout(TimeoutPhase.CONNECT),
            AiTimeoutException(TimeoutPhase.READ) to AiError.Timeout(TimeoutPhase.READ),
            AiTimeoutException(TimeoutPhase.CALL) to AiError.Timeout(TimeoutPhase.CALL),
            UnknownHostException("private endpoint") to AiError.DnsFailure,
            SSLException("private endpoint") to AiError.TlsFailure,
            AiHttpStatusException(401, null) to AiError.HttpAuth(401),
            AiHttpStatusException(429, "42") to AiError.RateLimited(42),
            IOException("private body") to AiError.Unknown
        )
        cases.forEach { (exception, expected) ->
            coEvery { transport.complete(any(), any()) } throws exception
            val result = generator.generate("chosen", parameters)
            assertEquals(requestFailure(expected), result)
            assertFalse(result.toString().contains("private"))
        }
        coVerify(exactly = cases.size) { transport.complete(any(), any()) }
    }

    @Test
    fun generate_profileException_returnsOnlySafeCategory() = runTest {
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } throws IllegalStateException("private key")
        assertEquals(requestFailure(AiError.Unknown), generator.generate("chosen", parameters))
        verify { transport wasNot Called }
    }

    @Test
    fun generate_profileCancellation_propagatesWithoutRemoteCall() = runTest {
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } throws CancellationException("cancelled")
        val attempt = async { generator.generate("chosen", parameters) }
        runCurrent()
        assertTrue(attempt.isCancelled)
        verify { transport wasNot Called }
    }

    @Test
    fun generate_wrappedRemoteCancellation_propagatesInsteadOfFailure() = runTest {
        resolveProfile()
        coEvery { transport.complete(any(), any()) } throws IOException(CancellationException("cancelled"))
        val attempt = async { generator.generate("chosen", parameters) }
        runCurrent()
        assertTrue(attempt.isCancelled)
    }

    @Test
    fun generate_cancelledDuringProfileResolution_doesNotStartRemote() = runTest {
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } coAnswers {
            currentCoroutineContext().cancel()
            ProfileResolutionResult.Available(profile())
        }
        val attempt = async { generator.generate("chosen", parameters) }
        runCurrent()
        assertTrue(attempt.isCancelled)
        verify { transport wasNot Called }
    }

    @Test
    fun generate_nonCooperativeRemoteAfterCancellation_doesNotPublishSuccess() = runTest {
        resolveProfile()
        val release = CompletableDeferred<Unit>()
        coEvery { transport.complete(any(), any()) } coAnswers {
            withContext(NonCancellable) { release.await() }
            content()
        }
        var published = false
        val attempt = async {
            generator.generate("chosen", parameters)
            published = true
        }
        runCurrent()
        attempt.cancel()
        release.complete(Unit)
        runCurrent()
        assertTrue(attempt.isCancelled)
        assertFalse(published)
    }

    private fun resolveProfile() {
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns
            ProfileResolutionResult.Available(profile())
    }

    private fun profile() = ResolvedAiProfile(
        "chosen", AiProviderTemplate.OPENAI_COMPATIBLE, "https://provider.invalid/v1",
        " test-model ", AiAuthStrategy.API_KEY, 0.7, "private-key"
    )

    private fun content(title: String = "A morning") =
        AiChatTransportResult.Content("""{"title":"$title","content":"We enjoy a quiet morning."}""")

    private fun requestFailure(error: AiError) =
        AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(error))
}
