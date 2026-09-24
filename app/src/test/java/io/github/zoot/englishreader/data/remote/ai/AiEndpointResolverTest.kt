package io.github.zoot.englishreader.data.remote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiEndpointResolverTest {

    @Test
    fun chatCompletionsUrl_validEndpoint_normalizesHostAndPreservesPathAndPort() {
        listOf(
            "https://api.moonshot.ai/v1" to "https://api.moonshot.ai/v1/chat/completions",
            "https://api.moonshot.ai/v1/" to "https://api.moonshot.ai/v1/chat/completions",
            "  https://api.moonshot.ai/v1/  " to "https://api.moonshot.ai/v1/chat/completions",
            "HTTPS://API.DEEPSEEK.COM/v1/" to "https://api.deepseek.com/v1/chat/completions",
            "https://api.moonshot.ai:443/v1/" to "https://api.moonshot.ai/v1/chat/completions",
            "https://example.com:8443/v1" to "https://example.com:8443/v1/chat/completions",
            // 路径大小写敏感，不能随 scheme / host 一起折叠。
            "https://example.com/API" to "https://example.com/API/chat/completions",
            "https://example.com/api" to "https://example.com/api/chat/completions"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, AiEndpointResolver.chatCompletionsUrl(input))
        }
    }

    @Test
    fun chatCompletionsUrl_unsafeOrInvalidEndpoint_isRejected() {
        listOf(
            "cleartext HTTP" to "http://example.com:80/v1",
            "user info" to "https://user:password@example.com/v1",
            "query string" to "https://example.com/v1?token=secret",
            "fragment" to "https://example.com/v1#fragment",
            "non-URL" to "not-a-url"
        ).forEach { (case, input) ->
            assertThrows(case, IllegalArgumentException::class.java) {
                AiEndpointResolver.chatCompletionsUrl(input)
            }
        }
    }

    @Test
    fun modelsUrl_preservesPrefixAndRejectsUnsafeEndpoints() {
        assertEquals(
            "https://example.com:8443/proxy/v1/models",
            AiEndpointResolver.modelsUrl(" https://example.com:8443/proxy/v1/ ")
        )
        for (input in listOf(
            "http://example.com/v1", "https://user:secret@example.com",
            "https://example.com?key=secret", "https://example.com#fragment", "not a url"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                AiEndpointResolver.modelsUrl(input)
            }
        }
    }

    @Test
    fun `root path vs v1 path must differ`() {
        val root = AiEndpointResolver.chatCompletionsUrl("https://example.com/")
        val v1 = AiEndpointResolver.chatCompletionsUrl("https://example.com/v1/")
        assertEquals("https://example.com/chat/completions", root)
        assertEquals("https://example.com/v1/chat/completions", v1)
        assertNotEquals("Root path and /v1 must produce different endpoints", root, v1)
    }

    @Test
    fun `different proxy hosts must differ`() {
        val a = AiEndpointResolver.chatCompletionsUrl("https://proxy-a.example/v1")
        val b = AiEndpointResolver.chatCompletionsUrl("https://proxy-b.example/v1")
        assertNotEquals("Different proxy hosts must produce different endpoints", a, b)
    }

}
