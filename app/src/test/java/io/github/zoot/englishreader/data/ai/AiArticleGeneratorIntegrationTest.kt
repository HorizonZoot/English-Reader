package io.github.zoot.englishreader.data.ai

import com.squareup.moshi.Moshi
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiTimeoutEventListenerFactory
import io.github.zoot.englishreader.data.remote.ai.TlsMockWebServerFixture
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.github.zoot.englishreader.di.AiNetworkModule
import io.github.zoot.englishreader.di.AiNetworkPolicy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiArticleGeneratorIntegrationTest {
    private val fixture = TlsMockWebServerFixture()
    private val profiles: AiProfileRepository = mockk()
    private val json = Moshi.Builder().build().adapter(Map::class.java)
    private lateinit var generator: AiArticleGenerator
    private val parameters = AiArticleGenerationParameters(topic = "A quiet journey")

    @Before
    fun setup() {
        fixture.start()
        val client = AiNetworkPolicy.applyTo(fixture.client.newBuilder())
            .eventListenerFactory(AiTimeoutEventListenerFactory())
            .build()
        val retrofit = AiNetworkModule.provideAiRetrofit(client, AiNetworkModule.provideAiMoshi())
        val transport = AiNetworkModule.provideAiChatTransport(AiNetworkModule.provideAiChatCompletionApi(retrofit))
        generator = AiArticleGenerator(profiles, transport, AiErrorMapper(isOnline = { true }))
        coEvery { profiles.resolveValidatedProfile("chosen", any()) } returns ProfileResolutionResult.Available(
            ResolvedAiProfile(
                profileId = "chosen",
                providerTemplate = AiProviderTemplate.OPENAI_COMPATIBLE,
                baseUrl = fixture.server.url("/proxy/v1").toString(),
                modelId = "test-model",
                authStrategy = AiAuthStrategy.API_KEY,
                temperature = 0.7,
                apiKey = "test-key"
            )
        )
    }

    @After
    fun teardown() {
        fixture.shutdown()
    }

    @Test
    fun generate_sameInputTwice_sendsTwoBoundedAuthenticatedRequestsAndParsesResults() = runTest {
        listOf("First", "Second").forEach { title ->
            fixture.server.enqueue(response(json.toJson(mapOf("title" to title, "content" to "A quiet day."))))
            val result = generator.generate("chosen", parameters) as AiArticleGenerationResult.Success
            assertEquals(title, result.article.title)
            assertEquals("A quiet day.", result.article.content)
            assertEquals(3, result.article.wordCount)
            assertTrue(result.article.isOutsideWordTarget)
            val request = requireNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/proxy/v1/chat/completions", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = requireNotNull(json.fromJson(request.body.readUtf8()))
            assertEquals("test-model", body["model"])
            assertEquals(3_072.0, body["max_tokens"])
            assertEquals(false, body["stream"])
            assertFalse(body.containsKey("response_format"))
            assertFalse(body.containsKey("thinking"))
            val messages = body["messages"] as List<*>
            val prepared = (AiArticlePromptPolicy.prepare(parameters) as AiArticlePromptPreparationResult.Ready).prompt
            assertEquals(prepared.messages.map { it.role }, messages.map { (it as Map<*, *>)["role"] })
            assertEquals(prepared.messages.map { it.content }, messages.map { (it as Map<*, *>)["content"] })
        }
        assertEquals(2, fixture.server.requestCount)
    }

    @Test
    fun generate_invalidOrTruncatedOutput_returnsTypedRejectionWithoutRepairRequest() = runTest {
        listOf(
            response("{\"title\":\"Partial\",\"content\":\"A day.\"}", "length") to
                AiGeneratedArticleRejection.TRUNCATED_RESPONSE,
            MockResponse().setBody("{\"choices\":[]}") to AiGeneratedArticleRejection.NO_CONTENT,
            response("not a JSON article") to AiGeneratedArticleRejection.MALFORMED_RESPONSE
        ).forEachIndexed { index, (response, reason) ->
            fixture.server.enqueue(response)
            assertEquals(
                AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Output(reason)),
                generator.generate("chosen", parameters)
            )
            assertEquals(index + 1, fixture.server.requestCount)
        }
    }

    @Test
    fun generate_httpFailures_preservesCategoriesWithoutRetryOrRedirect() = runTest {
        listOf(
            401 to AiError.HttpAuth(401),
            429 to AiError.RateLimited(30),
            500 to AiError.Server(500),
            302 to AiError.UnexpectedHttp(302)
        ).forEachIndexed { index, (code, expected) ->
            fixture.server.enqueue(
                MockResponse().setResponseCode(code)
                    .setHeader("Retry-After", "30")
                    .setHeader("Location", fixture.server.url("/must-not-follow"))
                    .setBody("private provider error")
            )
            val result = generator.generate("chosen", parameters)
            assertEquals(AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(expected)), result)
            assertFalse(result.toString().contains("private provider error"))
            assertEquals(index + 1, fixture.server.requestCount)
        }
    }

    @Test
    fun generate_malformedTransportResponse_doesNotExposeBodyOrRetry() = runTest {
        fixture.server.enqueue(MockResponse().setBody("<html>private response</html>"))
        val result = generator.generate("chosen", parameters)
        assertEquals(
            AiArticleGenerationResult.Failure(AiArticleGenerationFailure.Request(AiError.MalformedResponse)),
            result
        )
        assertFalse(result.toString().contains("private response"))
        assertEquals(1, fixture.server.requestCount)
    }

    private fun response(content: String, finishReason: String = "stop") = MockResponse().setBody(
        json.toJson(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf("role" to "assistant", "content" to content),
                        "finish_reason" to finishReason
                    )
                )
            )
        )
    )
}
