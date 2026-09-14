package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class AiErrorMapperTest {
    private val mapper = AiErrorMapper(isOnline = { true })

    @Test
    fun mapProfileResolution_allFailureKinds_preservesTypedMeaning() {
        assertEquals(AiError.NoActiveProfile,
            mapper.mapProfileResolution(ProfileResolutionResult.NoActiveProfile))
        assertEquals(AiError.ProfileNotFound,
            mapper.mapProfileResolution(ProfileResolutionResult.ProfileNotFound))
        assertEquals(AiError.CredentialMissing,
            mapper.mapProfileResolution(ProfileResolutionResult.Missing))
        assertEquals(AiError.CredentialStorageUnavailable,
            mapper.mapProfileResolution(ProfileResolutionResult.StorageUnavailable))
        assertEquals(AiError.InvalidEndpoint,
            mapper.mapProfileResolution(ProfileResolutionResult.InvalidEndpoint))
        assertNull(mapper.mapProfileResolution(mockAvailable()))
    }

    @Test
    fun map_httpStatuses_returnsDocumentedCategories() {
        assertEquals(AiError.HttpAuth(401), mapper.map(httpException(401)))
        assertEquals(AiError.HttpAuth(403), mapper.map(httpException(403)))
        assertEquals(AiError.HttpNotFound(), mapper.map(httpException(404)))
        assertEquals(AiError.RequestTimeout(), mapper.map(httpException(408)))
        assertEquals(AiError.PayloadTooLarge(), mapper.map(httpException(413)))
        assertEquals(AiError.RateLimited(120), mapper.map(httpException(429, "120")))
        assertEquals(AiError.Server(500), mapper.map(httpException(500)))
        assertEquals(AiError.Server(503), mapper.map(httpException(503)))
        assertEquals(AiError.Server(599), mapper.map(httpException(599)))
        assertEquals(AiError.UnexpectedHttp(301), mapper.map(httpException(301)))
        assertEquals(AiError.UnexpectedHttp(400), mapper.map(httpException(400)))
    }

    @Test
    fun map_networkAndPayloadFailures_returnsSafeCategories() {
        assertEquals(AiError.DnsFailure, mapper.map(UnknownHostException("private.example")))
        assertEquals(
            AiError.Offline,
            AiErrorMapper(isOnline = { false }).map(UnknownHostException("private.example"))
        )
        assertEquals(AiError.TlsFailure, mapper.map(SSLHandshakeException("secret host")))
        assertEquals(AiError.MalformedResponse, mapper.map(JsonDataException("raw payload")))
        assertEquals(AiError.MalformedResponse, mapper.map(JsonEncodingException("html body")))
        assertEquals(AiError.MalformedResponse, mapper.map(EOFException("truncated body")))
        assertEquals(AiError.Timeout(TimeoutPhase.READ), mapper.map(SocketTimeoutException()))
        assertEquals(
            AiError.Timeout(TimeoutPhase.CALL),
            mapper.map(InterruptedIOException("call timeout"))
        )
        assertEquals(AiError.Unknown, mapper.map(IOException("socket closed")))
        assertEquals(
            AiError.Offline,
            AiErrorMapper(isOnline = { false }).map(IOException("socket closed"))
        )
    }

    @Test
    fun map_phaseMarkers_preservesConnectReadAndCall() {
        TimeoutPhase.entries.forEach { phase ->
            assertEquals(
                AiError.Timeout(phase),
                mapper.map(AiTimeoutException(phase))
            )
        }
    }

    @Test
    fun map_cancellation_rethrows() {
        val thrown = runCatching { mapper.map(CancellationException("cancelled")) }
            .exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    private fun httpException(status: Int, retryAfter: String? = null): HttpException {
        val mediaType = "application/json".toMediaType()
        val errorBody = "{}".toResponseBody(mediaType)
        val rawBuilder = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.test/v1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body("{}".toResponseBody(mediaType))
        if (retryAfter != null) rawBuilder.header("Retry-After", retryAfter)
        return HttpException(retrofit2.Response.error<Any>(errorBody, rawBuilder.build()))
    }

    private fun mockAvailable(): ProfileResolutionResult.Available =
        ProfileResolutionResult.Available(
            io.github.zoot.englishreader.data.repository.ResolvedAiProfile(
                profileId = "profile-secret",
                providerTemplate = io.github.zoot.englishreader.data.local.AiProviderTemplate.DEEPSEEK,
                baseUrl = "https://private.example/v1",
                modelId = "model-secret",
                authStrategy = io.github.zoot.englishreader.data.local.AiAuthStrategy.API_KEY,
                temperature = 0.2,
                apiKey = "key-secret"
            )
        )
}
