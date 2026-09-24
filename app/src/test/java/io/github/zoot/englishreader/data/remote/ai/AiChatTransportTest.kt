package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiErrorMapper
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * transport 自证测试。
 *
 * 这里直接组装 OkHttp / Moshi / Retrofit，以便使用受信任的 loopback TLS fixture；
 * production Hilt graph 由 `AiNetworkPolicyTest` 与 instrumentation graph test 单独验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var fixture: TlsMockWebServerFixture
    private lateinit var transport: AiChatTransport

    @Before
    fun setup() {
        fixture = TlsMockWebServerFixture()
        fixture.start()
        server = fixture.server

        transport = transportFor(fixture.client)
    }

    @After
    fun teardown() {
        fixture.shutdown()
    }

    // ---- URL 拼接：本任务最高风险项 ----

    @Test
    fun complete_providerEndpoints_preserveFullPath() = runTest {
        val cases = listOf(
            Triple(AiProviderTemplate.DEEPSEEK, "", "/chat/completions"),
            Triple(AiProviderTemplate.KIMI, "/v1", "/v1/chat/completions"),
            Triple(AiProviderTemplate.ZHIPU, "/api/paas/v4/", "/api/paas/v4/chat/completions"),
            Triple(AiProviderTemplate.OPENAI_COMPATIBLE, "/proxy/openai/v1", "/proxy/openai/v1/chat/completions")
        )
        cases.forEach { (provider, prefix, expectedPath) ->
            enqueueContent("ok")
            transport.complete(config(provider, baseUrl = serverUrl(prefix)), messages())
            assertEquals("$provider: $prefix", expectedPath, server.awaitRequest().path)
        }
    }

    @Test
    fun unparseableBaseUrl_isRejected() = runTest {
        val thrown = runCatching {
            transport.complete(
                config(AiProviderTemplate.OPENAI_COMPATIBLE, baseUrl = "not a url"),
                messages()
            )
        }.exceptionOrNull()

        assertNotNull("无法解析的 baseUrl 必须被拒绝，而非拼成畸形请求", thrown)
        assertEquals(0, server.requestCount)
    }

    // ---- 认证与请求体 ----

    @Test
    fun authorizationHeader_isBearerToken() = runTest {
        enqueueContent("ok")
        transport.complete(
            config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl(""), apiKey = "sk-test-123"),
            messages()
        )
        assertEquals("Bearer sk-test-123", server.awaitRequest().getHeader("Authorization"))
    }

    @Test
    fun requestBody_deepSeekConnectionProbe_serializesFourTokensAndDisabledThinking() = runTest {
        enqueueContent("ok")
        transport.complete(
            config(
                AiProviderTemplate.DEEPSEEK,
                baseUrl = serverUrl(""),
                maxTokens = 4,
                thinkingMode = AiThinkingMode.DISABLED
            ),
            messages()
        )

        val body = bodyOf(server)
        assertEquals(4.0, body["max_tokens"])
        assertEquals("disabled", (body["thinking"] as Map<*, *>)["type"])
    }

    @Test
    fun requestBody_regularExplanation_disablesStreamingAndOmitsThinkingControl() = runTest {
        enqueueContent("ok")
        transport.complete(
            config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl("")),
            messages()
        )

        val body = bodyOf(server)
        assertEquals(false, body["stream"])
        assertFalse(body.containsKey("thinking"))
    }

    /**
     * 断言**键缺失**而非值为 null：`"temperature": null` 是另一种请求，provider 可能拒绝。
     */
    @Test
    fun requestBody_kimiK3_hasNoTemperatureKey() = runTest {
        enqueueContent("ok")
        transport.complete(
            config(AiProviderTemplate.KIMI, baseUrl = serverUrl("/v1"), modelId = "kimi-k3"),
            messages()
        )
        assertFalse(bodyOf(server).containsKey("temperature"))
    }

    @Test
    fun requestBody_supportedModels_includesTemperature() = runTest {
        listOf(
            config(AiProviderTemplate.KIMI, baseUrl = serverUrl("/v1"), modelId = "moonshot-v1-8k"),
            config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl(""))
        ).forEach { requestConfig ->
            enqueueContent("ok")
            transport.complete(requestConfig, messages())
            assertEquals(requestConfig.providerTemplate.name, 0.2, bodyOf(server)["temperature"] as Double, 0.0)
        }
    }

    // ---- 响应解析 ----

    @Test
    fun response_standardShape_yieldsContent() = runTest {
        enqueueContent("This sentence means ...")
        val result = transport.complete(
            config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl("")),
            messages()
        )
        assertEquals("This sentence means ...", (result as AiChatTransportResult.Content).text)
    }

    @Test
    fun response_normalFinishReason_yieldsContent() = runTest {
        for (reason in listOf("\"stop\"", "null")) {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"content":"Complete"},"finish_reason":$reason}]}"""
            ))

            val result = transport.complete(config(AiProviderTemplate.DEEPSEEK, serverUrl("")), messages())

            assertEquals("Complete", (result as AiChatTransportResult.Content).text)
        }
    }

    @Test
    fun response_firstChoiceTruncated_doesNotAcceptPartialTextOrAnotherChoice() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"Only the beginning"},"finish_reason":"length"},
                {"message":{"content":"Another choice"},"finish_reason":"stop"}]}"""
        ))

        val result = transport.complete(config(AiProviderTemplate.DEEPSEEK, serverUrl("")), messages())

        assertTrue(result is AiChatTransportResult.Truncated)
        assertEquals("Only the beginning", (result as AiChatTransportResult.Truncated).text)
        assertFalse(result.toString().contains("Only the beginning"))
    }

    @Test
    fun response_truncatedWithoutContent_preservesTruncationReason() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":null},"finish_reason":"length"}]}"""
        ))

        val result = transport.complete(config(AiProviderTemplate.DEEPSEEK, serverUrl("")), messages())

        assertTrue(result is AiChatTransportResult.Truncated)
        assertEquals(null, (result as AiChatTransportResult.Truncated).text)
    }

    @Test
    fun response_withoutUsableContent_yieldsNoContentResult() = runTest {
        val cases = listOf(
            "empty choices" to """{"choices":[]}""",
            "missing content" to """{"choices":[{"message":{"role":"assistant"}}]}""",
            "blank content" to """{"choices":[{"message":{"role":"assistant","content":"   "}}]}"""
        )
        cases.forEach { (case, body) ->
            server.enqueue(MockResponse().setBody(body))
            val result = transport.complete(
                config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl("")),
                messages()
            )
            assertEquals(case, AiChatTransportResult.NoContent, result)
        }
    }

    @Test
    fun response_htmlBody_mapsToMalformedResponseWithoutExposingBody() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html>secret reflected prompt</html>")
        )

        val thrown = runCatching {
            transport.complete(
                config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl("")),
                messages()
            )
        }.exceptionOrNull()

        assertTrue(thrown is Exception)
        assertEquals(
            AiError.MalformedResponse,
            AiErrorMapper(isOnline = { true }).map(thrown as Exception)
        )
    }

    @Test
    fun response_unexpectedSuccessStatus_mapsToUnexpectedHttp() = runTest {
        val cases = listOf(
            201 to MockResponse().setResponseCode(201)
                .setBody("""{"choices":[{"message":{"content":"unexpected"}}]}"""),
            204 to MockResponse().setResponseCode(204)
        )
        cases.forEach { (status, response) ->
            server.enqueue(response)
            val thrown = runCatching {
                transport.complete(
                    config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl("")),
                    messages()
                )
            }.exceptionOrNull()

            assertTrue("HTTP $status", thrown is Exception)
            assertEquals(
                "HTTP $status",
                AiError.UnexpectedHttp(status),
                AiErrorMapper(isOnline = { true }).map(thrown as Exception)
            )
        }
    }

    @Test
    fun suspendCancellation_cancelsUnderlyingOkHttpCall() = runTest {
        val callCancelled = CountDownLatch(1)
        val client = fixture.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    callCancelled.countDown()
                }
            })
            .build()
        val cancellableTransport = transportFor(client)
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        val requestJob = launch {
            cancellableTransport.complete(
                config(AiProviderTemplate.DEEPSEEK, baseUrl = serverUrl("")),
                messages()
            )
        }
        try {
            runCurrent()
            server.awaitRequest()
        } finally {
            requestJob.cancelAndJoin()
        }

        assertTrue(callCancelled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun listModels_providerEndpoints_preservePathAndSendBearerWithoutBody() = runTest {
        val cases = listOf(
            AiProviderTemplate.DEEPSEEK to "",
            AiProviderTemplate.KIMI to "/v1",
            AiProviderTemplate.ZHIPU to "/api/paas/v4/",
            AiProviderTemplate.OPENAI_COMPATIBLE to "/proxy/openai/v1"
        )
        for ((provider, prefix) in cases) {
            server.enqueue(MockResponse().setBody(
                """{"data":[{"id":" model-a "},{"id":"model-a"},{"id":""},{"id":"model-b"}]}"""
            ))

            val catalog = transport.listModels(config(provider, serverUrl(prefix), apiKey = "sk-models"))
            val request = server.awaitRequest()

            assertEquals(listOf("model-a", "model-b"), catalog)
            assertEquals("${prefix.trimEnd('/')}/models", request.path)
            assertEquals("GET", request.method)
            assertEquals("Bearer sk-models", request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test
    fun listModels_missingOrMalformedData_isNotASuccessfulCatalog() = runTest {
        for (body in listOf("{}", """{"data":null}""", "<html>secret</html>")) {
            server.enqueue(MockResponse().setBody(body))

            val thrown = runCatching {
                transport.listModels(config(AiProviderTemplate.DEEPSEEK, serverUrl("")))
            }.exceptionOrNull()

            assertTrue(thrown is Exception)
            assertEquals(AiError.MalformedResponse, AiErrorMapper(isOnline = { true }).map(thrown as Exception))
        }
    }

    @Test
    fun listModels_httpErrors_preserveSafeClassification() = runTest {
        for ((status, error) in listOf(
            401 to AiError.HttpAuth(401),
            404 to AiError.HttpNotFound(),
            201 to AiError.UnexpectedHttp(201)
        )) {
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("""{"data":[],"detail":"secret-response"}"""))

            val thrown = runCatching {
                transport.listModels(config(AiProviderTemplate.DEEPSEEK, serverUrl("")))
            }.exceptionOrNull()

            assertTrue(thrown is Exception)
            assertEquals(error, AiErrorMapper(isOnline = { true }).map(thrown as Exception))
            assertFalse(thrown.toString().contains("secret-response"))
        }
    }

    @Test
    fun listModels_cancellation_cancelsUnderlyingCall() = runTest {
        val cancelled = CountDownLatch(1)
        val client = fixture.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.countDown()
                }
            }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch {
            transportFor(client).listModels(config(AiProviderTemplate.DEEPSEEK, serverUrl("")))
        }
        try {
            runCurrent()
            server.awaitRequest()
        } finally {
            job.cancelAndJoin()
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    // ---- 脱敏 ----

    @Test
    fun toString_ofConfigAndDtos_redactsSecretsAndContent() {
        val customBaseUrl = "https://private-proxy.internal/v1"
        val rendered = config(
            AiProviderTemplate.OPENAI_COMPATIBLE,
            baseUrl = customBaseUrl,
            apiKey = "sk-super-secret"
        ).toString()

        assertFalse("apiKey 不得出现", rendered.contains("sk-super-secret"))
        assertFalse("baseUrl 不得出现（ADR-010 禁止记录完整 URL）", rendered.contains(customBaseUrl))
        assertFalse(rendered.contains("private-proxy.internal"))
        assertTrue("provider/model 应保留以便排查", rendered.contains("OPENAI_COMPATIBLE"))

        val sentence = "The quick brown fox jumps."
        assertFalse(AiChatMessage("user", sentence).toString().contains(sentence))
        assertFalse(AiChatRequestMessageDto("user", sentence).toString().contains(sentence))
        assertFalse(AiChatResponseMessageDto("assistant", sentence).toString().contains(sentence))
        assertFalse(AiChatTransportResult.Content(sentence).toString().contains(sentence))
        assertFalse(AiModelDto("private-model").toString().contains("private-model"))
        assertFalse(AiModelListResponse(listOf(AiModelDto("private-model")))
            .toString().contains("private-model"))
    }

    // ---- 辅助方法 ----

    private fun MockWebServer.awaitRequest(): RecordedRequest =
        checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "Expected an HTTP request within 5 seconds" }

    private fun serverUrl(path: String): String =
        server.url("/").toString().trimEnd('/') + path

    private fun enqueueContent(text: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""
            )
        )
    }

    private fun transportFor(client: OkHttpClient): AiChatTransport {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl("https://localhost/placeholder/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AiChatCompletionApi::class.java)
        return RetrofitAiChatTransport(api)
    }

    /**
     * 用 Moshi 而非 `org.json` 解析：JVM 单元测试拿到的是 Android stub，
     * `JSONObject.put()` 返回 null，会让 helper 自己 NPE。
     */
    private fun bodyOf(server: MockWebServer): Map<*, *> {
        val raw = server.awaitRequest().body.readUtf8()
        return requireNotNull(
            Moshi.Builder().build().adapter(Map::class.java).fromJson(raw)
        )
    }

    private fun messages(): List<AiChatMessage> =
        listOf(AiChatMessage(role = "user", content = "Explain this sentence."))

    private fun config(
        template: AiProviderTemplate,
        baseUrl: String,
        modelId: String = "test-model",
        apiKey: String = "test-key",
        maxTokens: Int? = null,
        thinkingMode: AiThinkingMode? = null
    ): AiChatRequestConfig = AiChatRequestConfig(
        baseUrl = baseUrl,
        modelId = modelId,
        providerTemplate = template,
        authStrategy = AiAuthStrategy.API_KEY,
        apiKey = apiKey,
        temperature = 0.2,
        maxTokens = maxTokens,
        thinkingMode = thinkingMode
    )
}
