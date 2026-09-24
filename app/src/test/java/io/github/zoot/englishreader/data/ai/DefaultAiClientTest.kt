package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.remote.ai.AiThinkingMode
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefaultAiClient] 单元测试。
 *
 * 覆盖解析恰好一次、规范化、委托及连接探测的请求与生命周期边界。
 * transport 映射与结果转换在 `RemoteAiExecutorTest`，缓存行为在 `CachedAiExecutorTest`。
 */
class DefaultAiClientTest {

    private val profileRepository: AiProfileRepository = mockk()
    private val executor: AiExecutor = mockk()
    private val connectionExecutor: RemoteAiExecutor = mockk()
    private val client = DefaultAiClient(
        profileRepository,
        executor,
        connectionExecutor,
        AiConnectionTestRegistry(),
        AiErrorMapper(isOnline = { true })
    )

    // ---- profile 解析失败分支：不得触达 executor ----

    @Test
    fun profile_unavailable_returnsTypedFailureWithoutCallingExecutor() = runTest {
        listOf(
            ProfileResolutionResult.Missing to AiError.CredentialMissing,
            ProfileResolutionResult.StorageUnavailable to AiError.CredentialStorageUnavailable,
            ProfileResolutionResult.ProfileNotFound to AiError.ProfileNotFound
        ).forEach { (resolution, error) ->
            coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns resolution

            val result = client.explain("test-profile", "Hello world")

            assertEquals("resolution=$resolution", AiClientResult.Failure(error), result)
            coVerify(exactly = 0) { executor.execute(any(), any()) }
        }
    }

    /**
     * repository 抛异常时也必须收成 `Failure`，不能逃给调用方。
     *
     * `AiProfileMetadataStore.readMetadata()` 只 catch `IOException`，DataStore 抛出的其他
     * 异常会穿过 `resolveProfile()` 上来。
     */
    @Test
    fun profileRepository_exception_yieldsFailure() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } throws
            IllegalStateException("DataStore corrupted at https://api.deepseek.com?key=sk-secret")

        val result = client.explain("test-profile", "Hello world")

        // 断言完整相等：异常消息里的 URL / 令牌都不得进入 reason。
        assertEquals(AiClientResult.Failure(AiError.Unknown), result)
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    // ---- 解析恰好一次 ----

    /**
     * 本任务的核心不变量：解析发生**一次**。
     *
     * 若缓存层与远端层各自解析一遍，两次之间用户改了 endpoint 或 model，就会把请求 B 的
     * 结果写进身份 A 的缓存键下——这正是 6.4 要消除的串味。
     */
    @Test
    fun resolvesProfileExactlyOnce() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(eq("p1"), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("explanation")

        client.explain("p1", "Hello world")

        coVerify(exactly = 1) { profileRepository.resolveValidatedProfile(eq("p1"), any()) }
        coVerify(exactly = 1) { executor.execute(any(), any()) }
    }

    @Test
    fun explain_eachDirectCallPassesFreshCompletionGateToExecutor() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("explanation")

        client.explain("p1", "Hello world")
        client.explain("p1", "Hello again")

        val gates = mutableListOf<AiOperationCompletionGate>()
        coVerify(exactly = 2) { executor.execute(any(), capture(gates)) }
        assertEquals(2, gates.size)
        assertNotSame(gates[0], gates[1])
        assertTrue(gates[0].tryCancel())
        assertTrue(gates[1].tryCancel())
    }

    @Test
    fun passesResolvedProfileAndFixedMetadataToExecutor() = runTest {
        val profile = resolvedProfile()
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(profile)
        coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("explanation")

        client.explain("p1", "Hello world")

        val requestSlot = slot<ResolvedAiExplanationOperation>()
        coVerify { executor.execute(capture(requestSlot), any()) }

        val request = requestSlot.captured.request
        // executor 必须拿到解析出的同一个快照实例，而不是重新构造的等价对象
        assertEquals(profile, request.profile)
        assertEquals("deepseek-v4-flash", request.normalizedModelId)
        assertEquals("Hello world", request.normalizedInput)
        assertEquals("zh-CN", request.outputLanguageTag)
        assertEquals(ExplanationType.SENTENCE_EXPLANATION, request.explanationType)
        assertEquals("sentence-context-v3", request.promptVersion)
        val currentKey = requestSlot.captured.semanticCacheKey
        assertEquals(ExplanationCacheIdentity.from(request).hash(), currentKey)
        assertNotEquals(
            "Concise explanations must not reuse the previous verbose prompt cache",
            ExplanationCacheIdentity.from(request.copy(promptVersion = "sentence-context-v2")).hash(),
            currentKey
        )
        assertEquals(2, request.preparedMessages.size)
        assertEquals("system", request.preparedMessages[0].role)
        assertEquals("user", request.preparedMessages[1].role)
        assertEquals("Hello world", request.preparedMessages[1].content)
    }

    /**
     * model ID 也必须在此规范化恰好一次，并写进快照。
     *
     * 若留给下游各自规范化，identity 与 remote 任一侧漏调就会漂移——Kimi 家族前缀判据
     * 会在两侧得出不同结论，导致「身份记为省略 temperature、请求却发送了它」。
     */
    @Test
    fun normalizesModelIdIntoSnapshot() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile(modelId = "  moonshot-v1-8k  "))
        coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("explanation")

        client.explain("p1", "Hello world")

        val requestSlot = slot<ResolvedAiExplanationOperation>()
        coVerify { executor.execute(capture(requestSlot), any()) }
        assertEquals("moonshot-v1-8k", requestSlot.captured.request.normalizedModelId)
    }

    // ---- 输入规范化只做一次 ----

    @Test
    fun normalizesInputBeforeHandingToExecutor() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("explanation")

        client.explain("p1", "  Line one\r\nLine two  ")

        val requestSlot = slot<ResolvedAiExplanationOperation>()
        coVerify { executor.execute(capture(requestSlot), any()) }

        // 去首尾空白、CRLF → LF，但保留内部换行
        assertEquals("Line one\nLine two", requestSlot.captured.request.normalizedInput)
        assertEquals("Line one\nLine two", requestSlot.captured.request.preparedMessages[1].content)
    }

    @Test
    fun article_overLimit_rejectsBeforeProfileResolution() = runTest {
        val result = client.explain(
            "p1",
            AiExplanationInput.Article("x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1))
        )

        assertEquals(
            AiClientResult.Failure(
                AiError.InputTooLong(
                    ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1,
                    ImportBudget.MAX_FULL_EXPLANATION_CHARS
                )
            ),
            result
        )
        coVerify(exactly = 0) { profileRepository.resolveValidatedProfile(any(), any()) }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test
    fun blankInput_rejectsBeforeProfileResolution() = runTest {
        val sentence = client.explain("p1", AiExplanationInput.Sentence(" \r\n "))
        val article = client.explain("p1", AiExplanationInput.Article(" \n\t "))

        assertEquals(AiClientResult.Failure(AiError.NoContent), sentence)
        assertEquals(AiClientResult.Failure(AiError.NoContent), article)
        coVerify(exactly = 0) { profileRepository.resolveValidatedProfile(any(), any()) }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test
    fun article_atLimit_buildsArticlePromptAndSnapshot() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        coEvery { executor.execute(any(), any()) } returns AiClientResult.Success("explanation")

        client.explain(
            "p1",
            AiExplanationInput.Article("x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS))
        )

        val requestSlot = slot<ResolvedAiExplanationOperation>()
        coVerify { executor.execute(capture(requestSlot), any()) }
        assertEquals(
            ExplanationType.ARTICLE_EXPLANATION,
            requestSlot.captured.request.explanationType
        )
        assertEquals("article-context-v2", requestSlot.captured.request.promptVersion)
        assertEquals(
            ImportBudget.MAX_FULL_EXPLANATION_CHARS,
            requestSlot.captured.request.preparedMessages[1].content.length
        )
    }

    // ---- 结果透传 ----

    @Test
    fun explain_executorResult_isPassedThrough() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        listOf(
            "success" to AiClientResult.Success("This means..."),
            "failure" to AiClientResult.Failure(AiError.NoContent)
        ).forEach { (case, expected) ->
            coEvery { executor.execute(any(), any()) } returns expected

            assertEquals(case, expected, client.explain("p1", "Hello world"))
        }
    }

    @Test
    fun executor_exception_yieldsFailure() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        coEvery { executor.execute(any(), any()) } throws
            RuntimeException("timeout for https://private-proxy.internal/v1?token=sk-secret")

        val result = client.explain("p1", "Hello world")

        assertEquals(AiClientResult.Failure(AiError.Unknown), result)
    }

    // ---- 取消传播 ----

    @Test
    fun executor_cancellation_propagates() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        coEvery { executor.execute(any(), any()) } throws CancellationException("cancelled")

        val thrown = runCatching { client.explain("p1", "Hello world") }.exceptionOrNull()

        assertTrue("CancellationException 必须上抛而非收成 Failure", thrown is CancellationException)
    }

    @Test
    fun profileRepository_cancellation_propagates() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } throws
            CancellationException("cancelled")

        val thrown = runCatching { client.explain("p1", "Hello world") }.exceptionOrNull()

        assertTrue("CancellationException 必须上抛而非收成 Failure", thrown is CancellationException)
    }

    @Test
    fun testConnectionDraft_validDraftUsesNormalizedEphemeralProfile() = runTest {
        val captured = slot<ResolvedAiProfile>()
        coEvery { connectionExecutor.testConnection(capture(captured)) } returns
            AiClientResult.Success("ok")

        val result = client.testConnectionDraft(
            AiConnectionDraft(
                providerTemplate = AiProviderTemplate.DEEPSEEK,
                baseUrl = " https://api.deepseek.com ",
                modelId = " deepseek-chat ",
                authStrategy = AiAuthStrategy.API_KEY,
                temperature = 0.2,
                apiKey = " sk-draft "
            )
        )

        assertEquals(AiClientResult.Success("ok"), result)
        assertEquals("https://api.deepseek.com", captured.captured.baseUrl)
        assertEquals("deepseek-chat", captured.captured.modelId)
        assertEquals("sk-draft", captured.captured.apiKey)
        coVerify(exactly = 0) { profileRepository.resolveValidatedProfile(any(), any()) }
    }

    @Test
    fun testConnectionDraft_invalidEndpointDoesNotReachRemote() = runTest {
        val result = client.testConnectionDraft(
            AiConnectionDraft(
                providerTemplate = AiProviderTemplate.OPENAI_COMPATIBLE,
                baseUrl = "http://unsafe.example.com",
                modelId = "model",
                authStrategy = AiAuthStrategy.API_KEY,
                temperature = 0.2,
                apiKey = "sk-draft"
            )
        )

        assertEquals(AiClientResult.Failure(AiError.InvalidEndpoint), result)
        coVerify(exactly = 0) { connectionExecutor.testConnection(any()) }
    }

    // ---- 连接探测：请求形状、并发去重与取消清理 ----

    @Test
    fun testConnection_deepSeekProfile_sendsNonThinkingFourTokenProbeAndBypassesExplanationExecutor() =
        runTest {
            val transport = mockk<AiChatTransport>()
            val remote = RemoteAiExecutor(transport)
            val client = DefaultAiClient(
                profileRepository,
                executor,
                remote,
                AiConnectionTestRegistry(this),
                AiErrorMapper(isOnline = { true })
            )
            coEvery { profileRepository.resolveValidatedProfile(eq("p1"), any()) } returns
                ProfileResolutionResult.Available(connectionProfile())
            coEvery { transport.listModels(any()) } returns listOf("deepseek-chat")
            coEvery { transport.complete(any(), any()) } returns
                AiChatTransportResult.Content("ok")

            val result = client.testConnection("p1")

            assertEquals(AiClientResult.Success("ok", listOf("deepseek-chat")), result)
            val config = slot<AiChatRequestConfig>()
            val messages = slot<List<AiChatMessage>>()
            coVerify(exactly = 1) { profileRepository.resolveValidatedProfile(eq("p1"), any()) }
            coVerify(exactly = 1) { transport.complete(capture(config), capture(messages)) }
            coVerify(exactly = 0) { executor.execute(any(), any()) }
            assertEquals(4, config.captured.maxTokens)
            assertEquals(AiThinkingMode.DISABLED, config.captured.thinkingMode)
            assertEquals(1, messages.captured.size)
            assertEquals("user", messages.captured.single().role)
            assertEquals("Reply with OK only.", messages.captured.single().content)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testConnection_concurrentSameProfile_resolvesAndPostsExactlyOnce() = runTest {
        val transport = mockk<AiChatTransport>()
        val resolutionGate = CompletableDeferred<Unit>()
        val client = DefaultAiClient(
            profileRepository,
            executor,
            RemoteAiExecutor(transport),
            AiConnectionTestRegistry(this),
            AiErrorMapper(isOnline = { true })
        )
        coEvery { profileRepository.resolveValidatedProfile(eq("p1"), any()) } coAnswers {
            resolutionGate.await()
            ProfileResolutionResult.Available(connectionProfile())
        }
        coEvery { transport.listModels(any()) } returns listOf("deepseek-chat")
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Content("ok")

        val first = async { client.testConnection("p1") }
        runCurrent()
        val second = async { client.testConnection("p1") }
        runCurrent()
        resolutionGate.complete(Unit)

        assertEquals(AiClientResult.Success("ok", listOf("deepseek-chat")), first.await())
        assertEquals(AiClientResult.Success("ok", listOf("deepseek-chat")), second.await())
        coVerify(exactly = 1) { transport.listModels(any()) }
        coVerify(exactly = 1) { profileRepository.resolveValidatedProfile(eq("p1"), any()) }
        coVerify(exactly = 1) { transport.complete(any(), any()) }
    }

    @Test
    fun testConnection_profileResolutionCancellation_rethrowsAndCleansOwnedState() = runTest {
        val transport = mockk<AiChatTransport>(relaxed = true)
        val registry = AiConnectionTestRegistry(this)
        val client = DefaultAiClient(
            profileRepository,
            executor,
            RemoteAiExecutor(transport),
            registry,
            AiErrorMapper(isOnline = { true })
        )
        coEvery { profileRepository.resolveValidatedProfile(eq("p1"), any()) } throws
            CancellationException("cancelled")

        val thrown = runCatching { client.testConnection("p1") }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(registry.inFlightProfileIds.value.isEmpty())
        coVerify(exactly = 0) { transport.complete(any(), any()) }
    }

    @Test
    fun testConnection_transportCancellation_rethrowsAndCleansOwnedState() = runTest {
        val transport = mockk<AiChatTransport>()
        val registry = AiConnectionTestRegistry(this)
        val client = DefaultAiClient(
            profileRepository,
            executor,
            RemoteAiExecutor(transport),
            registry,
            AiErrorMapper(isOnline = { true })
        )
        coEvery { profileRepository.resolveValidatedProfile(eq("p1"), any()) } returns
            ProfileResolutionResult.Available(connectionProfile())
        coEvery { transport.listModels(any()) } returns listOf("deepseek-chat")
        coEvery { transport.complete(any(), any()) } throws CancellationException("cancelled")

        val thrown = runCatching { client.testConnection("p1") }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(registry.inFlightProfileIds.value.isEmpty())
    }

    @Test
    fun testConnection_temperatureOverride_usesStoredCredentialWithDraftTemperature() = runTest {
        coEvery { profileRepository.resolveValidatedProfile(eq("p1"), any()) } returns
            ProfileResolutionResult.Available(connectionProfile())
        val captured = slot<ResolvedAiProfile>()
        coEvery { connectionExecutor.testConnection(capture(captured)) } returns
            AiClientResult.Success("OK", listOf("deepseek-chat"))

        client.testConnection("p1", temperature = 0.8)

        assertEquals(0.8, captured.captured.temperature, 0.0)
        assertEquals("secret", captured.captured.apiKey)
        assertEquals("deepseek-chat", captured.captured.modelId)
        coVerify(exactly = 1) { profileRepository.resolveValidatedProfile(eq("p1"), any()) }
    }

    private fun connectionProfile() = resolvedProfile(modelId = "deepseek-chat").copy(
        profileId = "p1",
        apiKey = "secret"
    )

    // ---- 辅助方法 ----

    // modelId 保留为本类自己的参数：`  moonshot-v1-8k  ` 这类带空白的取值是
    // Kimi 参数策略用例的测试输入，必须留在调用点可见。
    private fun resolvedProfile(
        modelId: String = "deepseek-v4-flash"
    ) = resolvedAiProfile(modelId = modelId)
}
