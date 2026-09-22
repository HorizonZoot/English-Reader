package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.remote.ai.AiThinkingMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [RemoteAiExecutor] 单元测试。
 *
 * 承接 6.3 `DefaultAiClientTest` 里 transport 映射与结果转换的覆盖。
 */
class RemoteAiExecutorTest {

    private val transport: AiChatTransport = mockk()
    private val executor = RemoteAiExecutor(transport)

    @Test
    fun transportContent_yieldsSuccess() = runTest {
        coEvery { transport.complete(any(), any()) } returns
            AiChatTransportResult.Content("This sentence means...")

        val result = executor.execute(request(), AiOperationCompletionGate())

        assertEquals(AiClientResult.Success("This sentence means..."), result)
    }

    @Test
    fun execute_truncatedResponse_neverAcceptsPartialTextAsSuccess() = runTest {
        for (text in listOf("Partial explanation", "   ", null)) {
            coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Truncated(text)

            val result = executor.execute(request(), AiOperationCompletionGate())

            assertEquals(AiClientResult.Failure(AiError.ResponseTruncated), result)
        }
    }

    @Test
    fun testConnection_truncatedProbe_acceptsOnlyNonBlankContent() = runTest {
        val cases = listOf(
            "OK" to AiClientResult.Success("OK"),
            "   " to AiClientResult.Failure(AiError.NoContent),
            null to AiClientResult.Failure(AiError.NoContent)
        )
        for ((text, expected) in cases) {
            coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Truncated(text)

            assertEquals(expected, executor.testConnection(request().request.profile))
        }
    }

    @Test
    fun transportException_yieldsFailureWithoutExceptionDetail() = runTest {
        coEvery { transport.complete(any(), any()) } throws
            IOException("timeout for https://private-proxy.internal/v1?token=sk-secret")

        val result = executor.execute(request(), AiOperationCompletionGate())

        // 断言完整相等：异常消息里的 URL / 令牌都不得进入 reason
        assertEquals(AiClientResult.Failure(AiError.Unknown), result)
    }

    @Test
    fun transportCancellation_propagates() = runTest {
        coEvery { transport.complete(any(), any()) } throws CancellationException("cancelled")

        val thrown = runCatching {
            executor.execute(request(), AiOperationCompletionGate())
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun mapsProfileFieldsToRequestConfig() = runTest {
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Content("ok")

        executor.execute(request(), AiOperationCompletionGate())

        val configSlot = slot<AiChatRequestConfig>()
        coVerify { transport.complete(capture(configSlot), any()) }

        val config = configSlot.captured
        assertEquals("https://api.deepseek.com", config.baseUrl)
        assertEquals("deepseek-v4-flash", config.modelId)
        assertEquals(AiProviderTemplate.DEEPSEEK, config.providerTemplate)
        assertEquals(AiAuthStrategy.API_KEY, config.authStrategy)
        assertEquals("sk-test-key", config.apiKey)
        assertEquals(0.2, config.temperature, 0.0)
    }

    /** 用快照里已规范化的 model ID，不在 remote 侧重新规范化。 */
    @Test
    fun sendsNormalizedModelIdFromSnapshot() = runTest {
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Content("ok")

        // profile 里带首尾空白，快照里是规范化后的值——remote 必须发送后者
        executor.execute(
            request(rawModelId = "  deepseek-v4-flash  ", normalizedModelId = "deepseek-v4-flash"),
            AiOperationCompletionGate()
        )

        val configSlot = slot<AiChatRequestConfig>()
        coVerify { transport.complete(capture(configSlot), any()) }
        assertEquals("deepseek-v4-flash", configSlot.captured.modelId)
    }

    @Test
    fun sendsPreparedSystemAndNormalizedUserMessagesUnchanged() = runTest {
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Content("ok")

        val request = request(input = "Explain this sentence.")
        executor.execute(request, AiOperationCompletionGate())

        val messagesSlot = slot<List<AiChatMessage>>()
        coVerify { transport.complete(any(), capture(messagesSlot)) }

        val messages = messagesSlot.captured
        assertEquals(request.request.preparedMessages.size, messages.size)
        request.request.preparedMessages.zip(messages).forEach { (expected, actual) ->
            assertEquals(expected.role, actual.role)
            assertEquals(expected.content, actual.content)
        }
    }

    @Test
    fun testConnection_deepSeek_disablesThinkingAndUsesFourTokenAcknowledgement() = runTest {
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Content("OK")

        val result = executor.testConnection(request().request.profile)

        assertEquals(AiClientResult.Success("OK"), result)
        val configSlot = slot<AiChatRequestConfig>()
        val messagesSlot = slot<List<AiChatMessage>>()
        coVerify(exactly = 1) { transport.complete(capture(configSlot), capture(messagesSlot)) }
        assertEquals(4, configSlot.captured.maxTokens)
        assertEquals(AiThinkingMode.DISABLED, configSlot.captured.thinkingMode)
        assertEquals("Reply with OK only.", messagesSlot.captured.single().content)
    }

    @Test
    fun execute_regularExplanation_doesNotSetProbeControls() = runTest {
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.NoContent

        val result = executor.execute(request(), AiOperationCompletionGate())

        assertEquals(AiClientResult.Failure(AiError.NoContent), result)
        val configSlot = slot<AiChatRequestConfig>()
        coVerify { transport.complete(capture(configSlot), any()) }
        assertEquals(null, configSlot.captured.maxTokens)
        assertEquals(null, configSlot.captured.thinkingMode)
    }

    @Test
    fun testConnection_nonDeepSeekProfile_keepsOneTokenProbeWithoutThinkingControl() = runTest {
        coEvery { transport.complete(any(), any()) } returns AiChatTransportResult.Content("OK")

        val profile = request().request.profile.copy(
            providerTemplate = AiProviderTemplate.ZHIPU,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
            modelId = "glm-5.2"
        )
        val result = executor.testConnection(profile)

        assertEquals(AiClientResult.Success("OK"), result)
        val configSlot = slot<AiChatRequestConfig>()
        val messagesSlot = slot<List<AiChatMessage>>()
        coVerify(exactly = 1) { transport.complete(capture(configSlot), capture(messagesSlot)) }
        assertEquals(1, configSlot.captured.maxTokens)
        assertEquals(null, configSlot.captured.thinkingMode)
        assertEquals("Connection test", messagesSlot.captured.single().content)
    }

    // ---- 辅助方法 ----

    private fun request(
        rawModelId: String = "deepseek-v4-flash",
        normalizedModelId: String = "deepseek-v4-flash",
        input: String = "Hello world."
    ): ResolvedAiExplanationOperation {
        val request = ResolvedAiExplanationRequest(
        // rawModelId 是本类的测试变量：多条用例靠它验证「归一化前的原始值不得直接发出」，
        // 因此显式传入，不依赖装置默认值。
        profile = resolvedAiProfile(modelId = rawModelId),
        normalizedModelId = normalizedModelId,
        normalizedInput = input,
        outputLanguageTag = "zh-CN",
        explanationType = ExplanationType.SENTENCE_EXPLANATION,
        promptVersion = "sentence-context-v1",
        preparedMessages = (
            AiPromptPolicy.prepare(AiExplanationInput.Sentence(input))
                as AiPromptPreparationResult.Ready
            ).prompt.messages
        )
        return ResolvedAiExplanationOperation(
            request = request,
            semanticCacheKey = ExplanationCacheIdentity.from(request).hash()
        )
    }
}
