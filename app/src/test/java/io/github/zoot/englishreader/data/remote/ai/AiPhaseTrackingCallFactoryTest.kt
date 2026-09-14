package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.ai.AiTimeoutException
import io.github.zoot.englishreader.data.ai.TimeoutPhase
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiPhaseTrackingCallFactoryTest {
    @Test
    fun execute_connectSocketTimeout_isWrappedWithConnectPhaseAndTagged() {
        val delegate = ThrowingCallFactory(SocketTimeoutException("connect")) { tracker ->
            tracker.markConnect()
        }
        val factory = AiPhaseTrackingCallFactory(delegate)

        val thrown = runCatching { factory.newCall(request()).execute() }.exceptionOrNull()

        assertNotNull(delegate.lastRequest.tag(AiCallPhaseTracker::class.java))
        assertTrue(thrown is AiTimeoutException)
        assertEquals(TimeoutPhase.CONNECT, (thrown as AiTimeoutException).phase)
    }

    @Test
    fun enqueue_totalCallTimeout_isWrappedWithCallPhase() {
        val factory = AiPhaseTrackingCallFactory(
            ThrowingCallFactory(InterruptedIOException("timeout"))
        )
        var callbackFailure: IOException? = null

        factory.newCall(request()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callbackFailure = e
            }

            override fun onResponse(call: Call, response: Response) = Unit
        })

        assertTrue(callbackFailure is AiTimeoutException)
        assertEquals(TimeoutPhase.CALL, (callbackFailure as AiTimeoutException).phase)
    }

    @Test
    fun execute_nonTimeoutIOException_isNotReclassified() {
        val failure = IOException("network failure")
        val factory = AiPhaseTrackingCallFactory(ThrowingCallFactory(failure))

        val thrown = runCatching { factory.newCall(request()).execute() }.exceptionOrNull()

        assertSame(failure, thrown)
    }

    @Test
    fun classifier_untaggedSocketTimeout_fallsBackToRead() {
        assertEquals(
            TimeoutPhase.READ,
            AiTimeoutClassifier.classify(SocketTimeoutException(), null)
        )
    }

    @Test
    fun execute_tlsResponseStall_isWrappedWithReadPhase() {
        withTlsFixture { fixture ->
            fixture.server.enqueue(
                MockResponse().setHeadersDelay(SERVER_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            )
            val factory = trackingFactory(
                fixture.client,
                readTimeoutMillis = SHORT_TIMEOUT_MILLIS,
                callTimeoutMillis = LONG_TIMEOUT_MILLIS
            )

            val thrown = runCatching {
                factory.newCall(Request.Builder().url(fixture.server.url("/read")).build())
                    .execute()
                    .use { }
            }.exceptionOrNull()

            assertTrue(thrown is AiTimeoutException)
            assertEquals(TimeoutPhase.READ, (thrown as AiTimeoutException).phase)
        }
    }

    @Test
    fun execute_totalCallDeadline_isWrappedWithCallPhase() {
        withTlsFixture { fixture ->
            fixture.server.enqueue(
                MockResponse().setHeadersDelay(SERVER_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            )
            val factory = trackingFactory(
                fixture.client,
                readTimeoutMillis = LONG_TIMEOUT_MILLIS,
                callTimeoutMillis = SHORT_TIMEOUT_MILLIS
            )

            val thrown = runCatching {
                factory.newCall(Request.Builder().url(fixture.server.url("/call")).build())
                    .execute()
                    .use { }
            }.exceptionOrNull()

            assertTrue(thrown is AiTimeoutException)
            assertEquals(TimeoutPhase.CALL, (thrown as AiTimeoutException).phase)
        }
    }

    @Test
    fun execute_stalledTlsHandshake_isWrappedWithConnectPhase() {
        withStalledTlsServer { url ->
            val factory = trackingFactory(
                OkHttpClient(),
                readTimeoutMillis = SHORT_TIMEOUT_MILLIS,
                callTimeoutMillis = LONG_TIMEOUT_MILLIS
            )

            val thrown = runCatching {
                factory.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { }
            }.exceptionOrNull()

            assertTrue(thrown is AiTimeoutException)
            assertEquals(TimeoutPhase.CONNECT, (thrown as AiTimeoutException).phase)
        }
    }

    private fun request(): Request = Request.Builder()
        .url("https://example.test/v1")
        .build()

    private fun trackingFactory(
        baseClient: OkHttpClient,
        readTimeoutMillis: Long,
        callTimeoutMillis: Long
    ): AiPhaseTrackingCallFactory {
        val client = baseClient.newBuilder()
            .eventListenerFactory(AiTimeoutEventListenerFactory())
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return AiPhaseTrackingCallFactory(client)
    }

    private fun withTlsFixture(block: (TlsMockWebServerFixture) -> Unit) {
        val fixture = TlsMockWebServerFixture()
        fixture.start()
        try {
            block(fixture)
        } finally {
            fixture.shutdown()
        }
    }

    private fun withStalledTlsServer(block: (String) -> Unit) {
        val serverSocket = ServerSocket(0)
        val releaseSocket = CountDownLatch(1)
        val acceptThread = thread(name = "stalled-tls-server", isDaemon = true) {
            try {
                serverSocket.accept().use {
                    releaseSocket.await(LONG_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                }
            } catch (_: IOException) {
                // 关闭 fixture 时走到这里属于预期的收尾路径。
            }
        }
        try {
            block("https://127.0.0.1:${serverSocket.localPort}/connect")
        } finally {
            releaseSocket.countDown()
            serverSocket.close()
            acceptThread.join(LONG_TIMEOUT_MILLIS)
        }
    }

    private class ThrowingCallFactory(
        private val failure: IOException,
        private val onCreate: (AiCallPhaseTracker) -> Unit = {}
    ) : Call.Factory {
        lateinit var lastRequest: Request

        override fun newCall(request: Request): Call {
            lastRequest = request
            request.tag(AiCallPhaseTracker::class.java)?.let(onCreate)
            return ThrowingCall(request, failure)
        }
    }

    private class ThrowingCall(
        private val request: Request,
        private val failure: IOException
    ) : Call {
        override fun request(): Request = request
        override fun execute(): Response = throw failure
        override fun enqueue(responseCallback: Callback) =
            responseCallback.onFailure(this, failure)
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ThrowingCall(request, failure)
    }

    private companion object {
        const val SHORT_TIMEOUT_MILLIS = 150L
        const val LONG_TIMEOUT_MILLIS = 5_000L
        const val SERVER_DELAY_MILLIS = 1_000L
    }
}
