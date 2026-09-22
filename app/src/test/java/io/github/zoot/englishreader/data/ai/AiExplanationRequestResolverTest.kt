package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExplanationRequestResolverTest {
    private val profileRepository: AiProfileRepository = mockk()
    private val resolver = AiExplanationRequestResolver(
        profileRepository,
        AiErrorMapper(isOnline = { true })
    )

    @Test
    fun resolve_blankInput_rejectsBeforeAnyProfileResolution() = runTest {
        val explicit = resolver.resolve("profile-a", AiExplanationInput.Sentence(" \r\n "))
        val active = resolver.resolveActive(AiExplanationInput.Article(" \t "))

        assertEquals(
            AiExplanationResolutionResult.Rejected(AiError.NoContent),
            explicit
        )
        assertEquals(
            AiExplanationResolutionResult.Rejected(AiError.NoContent),
            active
        )
        coVerify(exactly = 0) { profileRepository.resolveValidatedProfile(any(), any()) }
        coVerify(exactly = 0) { profileRepository.resolveValidatedActiveProfile(any()) }
    }

    @Test
    fun resolveActive_overLimitArticle_rejectsBeforeProfileResolution() = runTest {
        val result = resolver.resolveActive(
            AiExplanationInput.Article(
                "x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1)
            )
        )

        assertEquals(
            AiExplanationResolutionResult.Rejected(
                AiError.InputTooLong(
                    ImportBudget.MAX_FULL_EXPLANATION_CHARS + 1,
                    ImportBudget.MAX_FULL_EXPLANATION_CHARS
                )
            ),
            result
        )
        coVerify(exactly = 0) { profileRepository.resolveValidatedActiveProfile(any()) }
    }

    @Test
    fun explicitAndActive_sameProfileAndInput_produceSameRequestAndKey() = runTest {
        val profile = resolvedProfile()
        coEvery { profileRepository.resolveValidatedProfile("profile-a", any()) } returns
            ProfileResolutionResult.Available(profile)
        coEvery { profileRepository.resolveValidatedActiveProfile(any()) } returns
            ProfileResolutionResult.Available(profile)

        val explicit = resolver.resolve(
            "profile-a",
            AiExplanationInput.Sentence("  Hello\r\nworld  ")
        ).requireReady()
        val active = resolver.resolveActive(
            AiExplanationInput.Sentence("  Hello\r\nworld  ")
        ).requireReady()

        assertEquals(
            explicit.operation.request.profile,
            active.operation.request.profile
        )
        assertEquals(
            explicit.operation.request.normalizedModelId,
            active.operation.request.normalizedModelId
        )
        assertEquals(
            explicit.operation.request.normalizedInput,
            active.operation.request.normalizedInput
        )
        assertEquals(
            explicit.operation.request.promptVersion,
            active.operation.request.promptVersion
        )
        assertEquals(
            explicit.operation.request.preparedMessages.map { it.role to it.content },
            active.operation.request.preparedMessages.map { it.role to it.content }
        )
        assertEquals(explicit.operation.semanticCacheKey, active.operation.semanticCacheKey)
        assertEquals(
            ExplanationCacheIdentity.from(explicit.operation.request).hash(),
            explicit.operation.semanticCacheKey
        )
        assertEquals("Hello\nworld", explicit.operation.request.normalizedInput)
        assertEquals(
            explicit.operation.request.normalizedInput,
            explicit.operation.request.preparedMessages[1].content
        )
    }

    @Test
    fun resolveActive_atArticleLimit_buildsOneImmutableArticleBundle() = runTest {
        coEvery { profileRepository.resolveValidatedActiveProfile(any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        val content = "x".repeat(ImportBudget.MAX_FULL_EXPLANATION_CHARS)

        val ready = resolver.resolveActive(AiExplanationInput.Article(content)).requireReady()

        assertEquals(ExplanationType.ARTICLE_EXPLANATION, ready.operation.request.explanationType)
        assertEquals("article-context-v2", ready.operation.request.promptVersion)
        assertEquals(content, ready.operation.request.preparedMessages[1].content)
        coVerify(exactly = 1) { profileRepository.resolveValidatedActiveProfile(any()) }
    }

    @Test
    fun resolveActive_profileFailures_preserveTypedErrors() = runTest {
        val cases = listOf(
            ProfileResolutionResult.NoActiveProfile to AiError.NoActiveProfile,
            ProfileResolutionResult.ProfileNotFound to AiError.ProfileNotFound,
            ProfileResolutionResult.InvalidEndpoint to AiError.InvalidEndpoint,
            ProfileResolutionResult.Missing to AiError.CredentialMissing,
            ProfileResolutionResult.StorageUnavailable to AiError.CredentialStorageUnavailable
        )

        cases.forEach { (profileResult, expected) ->
            coEvery { profileRepository.resolveValidatedActiveProfile(any()) } returns profileResult
            assertEquals(
                AiExplanationResolutionResult.Rejected(expected),
                resolver.resolveActive(AiExplanationInput.Sentence("Hello"))
            )
        }
    }

    @Test
    fun resolveActive_unexpectedException_mapsToUnknownWithoutDetail() = runTest {
        coEvery { profileRepository.resolveValidatedActiveProfile(any()) } throws
            IllegalStateException("secret endpoint")

        val result = resolver.resolveActive(AiExplanationInput.Sentence("private input"))

        assertEquals(AiExplanationResolutionResult.Rejected(AiError.Unknown), result)
        assertFalse(result.toString().contains("secret endpoint"))
        assertFalse(result.toString().contains("private input"))
    }

    @Test
    fun resolveActive_cancellation_propagates() = runTest {
        coEvery { profileRepository.resolveValidatedActiveProfile(any()) } throws
            CancellationException("cancelled")

        val thrown = runCatching {
            resolver.resolveActive(AiExplanationInput.Sentence("Hello"))
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun operationAndResolution_toString_redactRequestAndSemanticKey() = runTest {
        coEvery { profileRepository.resolveValidatedActiveProfile(any()) } returns
            ProfileResolutionResult.Available(resolvedProfile())
        val result = resolver.resolveActive(
            AiExplanationInput.Sentence("private sentence")
        ).requireReady()

        val rendered = result.toString() + result.operation.toString()
        assertFalse(rendered.contains("private sentence"))
        assertFalse(rendered.contains(result.operation.semanticCacheKey))
        assertFalse(rendered.contains("sk-secret"))
    }

    private fun AiExplanationResolutionResult.requireReady():
        AiExplanationResolutionResult.Ready {
        assertTrue(this is AiExplanationResolutionResult.Ready)
        return this as AiExplanationResolutionResult.Ready
    }

    private fun resolvedProfile() = ResolvedAiProfile(
        profileId = "profile-a",
        providerTemplate = AiProviderTemplate.DEEPSEEK,
        baseUrl = "https://api.deepseek.com/v1",
        modelId = " deepseek-chat ",
        authStrategy = AiAuthStrategy.API_KEY,
        temperature = 0.2,
        apiKey = "sk-secret"
    )
}
