package io.github.zoot.englishreader.data.ai

import com.squareup.moshi.JsonDataException
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiModelCatalogConfig
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelDiscoveryTest {
    private val repository = mockk<AiProfileRepository>()
    private val transport = mockk<AiChatTransport>()
    private val explanationExecutor = mockk<AiExecutor>()
    private val registry = AiConnectionTestRegistry()
    private val client = DefaultAiClient(
        repository,
        explanationExecutor,
        RemoteAiExecutor(transport),
        registry,
        AiErrorMapper(isOnline = { true })
    )

    @Test
    fun discoverModels_draft_normalizesCatalogWithoutProfileCacheOrGeneration() = runTest {
        val config = slot<AiModelCatalogConfig>()
        coEvery { transport.listModels(capture(config)) } returns
            listOf(" z-model ", "", "a-model", "a-model", " ")

        val result = client.discoverModels(draft()) as AiModelDiscoveryResult.Success

        assertEquals(listOf("a-model", "z-model"), result.modelIds)
        assertEquals("https://example.com/v1", config.captured.baseUrl)
        assertEquals("secret-key", config.captured.apiKey)
        coVerify(exactly = 1) { transport.listModels(any()) }
        coVerify(exactly = 0) { repository.resolveValidatedProfile(any(), any()) }
        coVerify(exactly = 0) { transport.complete(any(), any()) }
        coVerify(exactly = 0) { explanationExecutor.execute(any(), any()) }
        assertTrue(registry.inFlightProfileIds.value.isEmpty())
    }

    @Test
    fun discoverModels_savedProfile_resolvesCredentialOnceWithoutGeneration() = runTest {
        val profile = resolvedAiProfile().copy(apiKey = "stored-secret")
        coEvery { repository.resolveValidatedProfile("profile-1", any()) } returns
            ProfileResolutionResult.Available(profile)
        val config = slot<AiModelCatalogConfig>()
        coEvery { transport.listModels(capture(config)) } returns listOf("another-model")

        val result = client.discoverModels("profile-1") as AiModelDiscoveryResult.Success

        assertEquals(listOf("another-model"), result.modelIds)
        assertEquals(profile.baseUrl, config.captured.baseUrl)
        assertEquals("stored-secret", config.captured.apiKey)
        coVerify(exactly = 1) { repository.resolveValidatedProfile("profile-1", any()) }
        coVerify(exactly = 1) { transport.listModels(any()) }
        coVerify(exactly = 0) { transport.complete(any(), any()) }
        coVerify(exactly = 0) { explanationExecutor.execute(any(), any()) }
    }

    @Test
    fun discoverModels_invalidEndpointOrMissingKey_failsBeforeAnyIo() = runTest {
        assertEquals(
            AiModelDiscoveryResult.Failure(AiError.InvalidEndpoint),
            client.discoverModels(draft(baseUrl = "http://example.com"))
        )
        assertEquals(
            AiModelDiscoveryResult.Failure(AiError.CredentialMissing),
            client.discoverModels(draft(apiKey = "  "))
        )
        assertEquals(
            AiModelDiscoveryResult.Failure(AiError.ProfileNotFound),
            client.discoverModels("")
        )
        coVerify(exactly = 0) { transport.listModels(any()) }
        coVerify(exactly = 0) { repository.resolveValidatedProfile(any(), any()) }
    }

    @Test
    fun discoverModels_savedCredentialUnavailable_returnsTypedFailureWithoutNetwork() = runTest {
        coEvery { repository.resolveValidatedProfile("profile-1", any()) } returns
            ProfileResolutionResult.StorageUnavailable

        assertEquals(
            AiModelDiscoveryResult.Failure(AiError.CredentialStorageUnavailable),
            client.discoverModels("profile-1")
        )
        coVerify(exactly = 0) { transport.listModels(any()) }
    }

    @Test
    fun discoverModels_emptyOrBlankCatalog_returnsNoContentWithoutGeneration() = runTest {
        for (models in listOf(emptyList(), listOf("", "  "))) {
            coEvery { transport.listModels(any()) } returns models
            assertEquals(
                AiModelDiscoveryResult.Failure(AiError.NoContent),
                client.discoverModels(draft())
            )
        }
        coVerify(exactly = 0) { transport.complete(any(), any()) }
    }

    @Test
    fun discoverModels_transportFailure_preservesErrorCategoriesWithoutGeneration() = runTest {
        for ((exception, expected) in listOf(
            UnknownHostException("private-host") to AiError.DnsFailure,
            JsonDataException("private-body") to AiError.MalformedResponse,
            AiTimeoutException(TimeoutPhase.CONNECT) to AiError.Timeout(TimeoutPhase.CONNECT),
            AiTimeoutException(TimeoutPhase.READ) to AiError.Timeout(TimeoutPhase.READ),
            AiTimeoutException(TimeoutPhase.CALL) to AiError.Timeout(TimeoutPhase.CALL)
        )) {
            coEvery { transport.listModels(any()) } throws exception
            assertEquals(AiModelDiscoveryResult.Failure(expected), client.discoverModels(draft()))
        }
        coVerify(exactly = 0) { transport.complete(any(), any()) }
    }

    @Test
    fun discoverModels_transportCancellation_propagatesOriginalCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { transport.listModels(any()) } throws cancellation

        assertSame(cancellation, runCatching { client.discoverModels(draft()) }.exceptionOrNull())
        coVerify(exactly = 0) { transport.complete(any(), any()) }
    }

    @Test
    fun discoverModels_credentialResolutionCancellation_propagatesWithoutNetwork() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { repository.resolveValidatedProfile("profile-1", any()) } throws cancellation

        assertSame(cancellation, runCatching { client.discoverModels("profile-1") }.exceptionOrNull())
        coVerify(exactly = 0) { transport.listModels(any()) }
    }

    @Test
    fun discoverySnapshots_toString_redactsEndpointCredentialsAndModelIds() {
        val config = AiModelCatalogConfig("https://private-endpoint.test", AiAuthStrategy.API_KEY, "private-key")
        val result = AiModelDiscoveryResult.Success(listOf("private-model"))
        val text = listOf(config, draft("https://private-endpoint.test", "private-key"), result).toString()

        for (secret in listOf("private-endpoint", "private-key", "private-model")) {
            assertFalse(text.contains(secret))
        }
        assertTrue(text.contains("modelCount=1"))
    }

    private fun draft(
        baseUrl: String = " https://example.com/v1 ",
        apiKey: String = " secret-key "
    ) = AiModelDiscoveryDraft(baseUrl, AiAuthStrategy.API_KEY, apiKey)
}
