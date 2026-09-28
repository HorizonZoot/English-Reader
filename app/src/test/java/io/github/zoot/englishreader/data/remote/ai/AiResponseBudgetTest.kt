package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiErrorMapper
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiResponseBudgetTest {
    @Test
    fun read_declaredOversize_closesWithoutReading() {
        val body = CountingBody(AiResponseBudget.CHAT_BYTES + 1, Long.MAX_VALUE)
        val call = mockk<Call>(relaxed = true)

        assertThrows(AiResponseTooLargeException::class.java) {
            AiResponseBudget.read(response(body), call)
        }

        assertEquals(0, body.bytesRead)
        assertTrue(body.closed)
        verify(exactly = 1) { call.cancel() }
    }

    @Test
    fun read_unknownLength_stopsAfterBoundedPrefixAndCloses() {
        val body = CountingBody(-1, Long.MAX_VALUE)
        val call = mockk<Call>(relaxed = true)

        assertThrows(AiResponseTooLargeException::class.java) {
            AiResponseBudget.read(response(body), call)
        }

        assertTrue(body.bytesRead > AiResponseBudget.CHAT_BYTES)
        assertTrue(body.bytesRead <= AiResponseBudget.CHAT_BYTES + 8192)
        assertTrue(body.closed)
        verify(exactly = 1) { call.cancel() }
    }

    @Test
    fun read_non200_neverReadsBodyAndKeepsOnlyStatusMetadata() {
        val body = CountingBody(-1, Long.MAX_VALUE)
        val call = mockk<Call>(relaxed = true)

        val error = assertThrows(AiHttpStatusException::class.java) {
            AiResponseBudget.read(response(body, 429).newBuilder()
                .header("Retry-After", "17").build(), call)
        }

        assertEquals(AiError.RateLimited(17), AiErrorMapper(isOnline = { true }).map(error))
        assertEquals(0, body.bytesRead)
        assertTrue(body.closed)
        verify(exactly = 1) { call.cancel() }
    }

    @Test
    fun executeAndClone_applyBudgetAndCloseSuccessfulBody() {
        val fixture = TlsMockWebServerFixture()
        fixture.start()
        try {
            fixture.server.enqueue(MockResponse().setBody("ok"))
            fixture.server.enqueue(MockResponse().setChunkedBody(
                "x".repeat(AiResponseBudget.MODEL_CATALOG_BYTES.toInt() + 1), 4096))
            val client = fixture.client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
            val call = AiPhaseTrackingCallFactory(client).newCall(
                Request.Builder().url(fixture.server.url("/models")).build())
            call.execute().use { assertEquals("ok", it.body!!.string()) }
            assertThrows(AiResponseTooLargeException::class.java) { call.clone().execute() }
            assertEquals(2, fixture.server.requestCount)
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun map_wrappedBudgetFailure_isMalformedEvenOfflineAndCancellationWins() {
        val mapper = AiErrorMapper(isOnline = { false })
        val failure = IOException("wrapper", AiResponseTooLargeException())
        assertEquals(AiError.MalformedResponse, mapper.map(failure))
        assertFalse(failure.cause.toString().contains("http"))
        val cancelled = CancellationException("cancelled")
        failure.cause!!.initCause(cancelled)
        assertEquals(cancelled, assertThrows(CancellationException::class.java) { mapper.map(failure) })
    }

    private fun response(body: ResponseBody, status: Int = 200): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/chat/completions")
            .post(ByteArray(0).toRequestBody()).build())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .body(body)
        .build()

    private class CountingBody(private val declared: Long, private val available: Long) : ResponseBody() {
        var bytesRead = 0L
        var closed = false
        private val buffered = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (bytesRead == available) return -1
                val count = minOf(byteCount, available - bytesRead)
                sink.write(ByteArray(count.toInt()))
                bytesRead += count
                return count
            }
            override fun close() { closed = true }
            override fun timeout(): Timeout = Timeout.NONE
        }.buffer()
        override fun source(): BufferedSource = buffered
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = declared
    }
}
