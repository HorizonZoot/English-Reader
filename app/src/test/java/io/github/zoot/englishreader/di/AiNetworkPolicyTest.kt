package io.github.zoot.englishreader.di

import io.github.zoot.englishreader.data.remote.ai.TlsMockWebServerFixture
import io.github.zoot.englishreader.data.remote.ai.AiPhaseTrackingCallFactory
import io.github.zoot.englishreader.data.remote.ai.AiTimeoutEventListenerFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiNetworkPolicyTest {
    @Test
    fun provideAiOkHttp_usesPaidRequestSafetyPolicy() {
        val client = AiNetworkModule.provideAiOkHttp()

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        assertEquals(90_000, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.eventListenerFactory is AiTimeoutEventListenerFactory)
        assertTrue(
            (client.interceptors + client.networkInterceptors).none {
                it.javaClass.name.contains("HttpLoggingInterceptor")
            }
        )
    }

    @Test
    fun provideAiRetrofit_usesPhaseTrackingCallFactory() {
        val retrofit = AiNetworkModule.provideAiRetrofit(
            AiNetworkModule.provideAiOkHttp(),
            AiNetworkModule.provideAiMoshi()
        )

        assertTrue(retrofit.callFactory() is AiPhaseTrackingCallFactory)
    }

    @Test
    fun paidRequestPolicy_doesNotFollowRedirect() {
        val fixture = TlsMockWebServerFixture()
        fixture.start()
        try {
            fixture.server.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .addHeader("Location", fixture.server.url("/redirected"))
            )
            val client = AiNetworkPolicy.applyTo(fixture.client.newBuilder()).build()

            client.newCall(
                Request.Builder().url(fixture.server.url("/initial")).build()
            ).execute().use { response -> assertEquals(301, response.code) }

            assertEquals(1, fixture.server.requestCount)
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun paidRequestPolicy_connectionFailureDoesNotRetryPost() {
        val fixture = TlsMockWebServerFixture()
        fixture.start()
        try {
            fixture.server.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            )
            val client = AiNetworkPolicy.applyTo(fixture.client.newBuilder()).build()
            val request = Request.Builder()
                .url(fixture.server.url("/paid"))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            runCatching { client.newCall(request).execute().use { } }

            assertEquals(1, fixture.server.requestCount)
        } finally {
            fixture.shutdown()
        }
    }
}
