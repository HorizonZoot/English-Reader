package io.github.zoot.englishreader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiCredentialStorage
import io.github.zoot.englishreader.data.local.AiProfileMetadataReadResult
import io.github.zoot.englishreader.data.local.AiProfileMetadataSnapshot
import io.github.zoot.englishreader.data.local.AiProfileMetadataStore
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.CredentialReadResult
import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiProfileRepositoryTest {

    private val metadata by lazy { metadataStore() }
    private val credentials by lazy { FakeCredentialStorage() }
    private val repository by lazy { AiProfileRepository(metadata, credentials) }

    @Test
    fun createProfile_firstProfileBecomesActiveAndStoresCredential() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")

        assertSame(
            ProfileMutationResult.Success,
            repository.createProfile(profile, "test-key")
        )

        assertEquals(listOf(profile), metadata.profiles.first())
        assertEquals(profile.profileId, metadata.activeProfileId.first())
        assertEquals("test-key", credentials.values[profile.profileId])
    }

    @Test
    fun createProfile_secondProfileDoesNotReplaceExplicitActiveProfile() = runTest {
        val firstProfile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        val secondProfile = profile("00000000-0000-0000-0000-000000000002", "Kimi")

        repository.createProfile(firstProfile, "first-key")
        repository.createProfile(secondProfile, "second-key")

        assertEquals(firstProfile.profileId, metadata.activeProfileId.first())
        assertEquals(setOf(firstProfile, secondProfile), metadata.profiles.first().toSet())
    }

    @Test
    fun selectActiveProfile_existingProfileSwitchesSelectionAndUnknownIsRejected() = runTest {
        val metadata = metadataStore()
        val repository = AiProfileRepository(metadata, FakeCredentialStorage())
        val firstProfile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        val secondProfile = profile("00000000-0000-0000-0000-000000000002", "Kimi")
        repository.createProfile(firstProfile, "first-key")
        repository.createProfile(secondProfile, "second-key")

        assertSame(
            ProfileMutationResult.Success,
            repository.selectActiveProfile(secondProfile.profileId)
        )
        assertEquals(secondProfile.profileId, metadata.activeProfileId.first())

        assertSame(
            ProfileMutationResult.ProfileNotFound,
            repository.selectActiveProfile("00000000-0000-0000-0000-000000000099")
        )
        assertEquals(secondProfile.profileId, metadata.activeProfileId.first())
    }

    @Test
    fun rotateApiKey_existingProfileUpdatesCredentialAndUnknownIsRejected() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "old-key")

        assertSame(
            ProfileMutationResult.Success,
            repository.rotateApiKey(profile.profileId, "new-key")
        )
        assertEquals("new-key", credentials.values[profile.profileId])

        assertSame(
            ProfileMutationResult.ProfileNotFound,
            repository.rotateApiKey("00000000-0000-0000-0000-000000000099", "unused-key")
        )
        assertFalse(credentials.values.containsValue("unused-key"))
    }

    @Test
    fun createProfile_duplicateIdDoesNotOverwriteExistingCredential() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "original-key")

        assertSame(
            ProfileMutationResult.DuplicateProfile,
            repository.createProfile(profile.copy(displayName = "Duplicate"), "replacement-key")
        )

        assertEquals("original-key", credentials.values[profile.profileId])
        assertEquals(listOf(profile), metadata.profiles.first())
    }

    @Test
    fun resolveProfile_returnsImmutableSnapshotForRequestedProfile() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(
            values = mutableMapOf("00000000-0000-0000-0000-000000000001" to "test-key")
        )
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        val result = repository.resolveProfile(profile.profileId)

        assertEquals(
            ProfileResolutionResult.Available(
                ResolvedAiProfile(
                    profileId = profile.profileId,
                    providerTemplate = profile.providerTemplate,
                    baseUrl = profile.baseUrl,
                    modelId = profile.modelId,
                    authStrategy = profile.authStrategy,
                    temperature = profile.temperature,
                    apiKey = "test-key"
                )
            ),
            result
        )
        assertFalse(result.toString().contains("test-key"))
    }

    @Test
    fun deleteProfile_credentialFailurePreservesProfileAndActiveSelection() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(deleteResult = false)
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(
            ProfileMutationResult.CredentialDeleteFailed,
            repository.deleteProfile(profile.profileId)
        )

        assertEquals(listOf(profile), metadata.profiles.first())
        assertEquals(profile.profileId, metadata.activeProfileId.first())
    }

    @Test
    fun deleteProfile_activeProfileClearsActiveIdAfterCredentialDeletion() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(ProfileMutationResult.Success, repository.deleteProfile(profile.profileId))

        assertTrue(metadata.profiles.first().isEmpty())
        assertEquals(null, metadata.activeProfileId.first())
        assertFalse(credentials.values.containsKey(profile.profileId))
    }

    @Test
    fun resolveActiveProfile_danglingIdReturnsProfileNotFound() = runTest {
        metadata.setActiveProfileId("00000000-0000-0000-0000-000000000099")

        assertSame(
            ProfileResolutionResult.ProfileNotFound,
            repository.resolveActiveProfile()
        )
    }

    @Test
    fun resolveProfile_missingCredentialRemainsDistinctFromStorageUnavailable() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")
        credentials.values.clear()

        val result = repository.resolveProfile(profile.profileId)

        assertSame(ProfileResolutionResult.Missing, result)
    }

    @Test
    fun resolveProfile_storageUnavailableRemainsDistinctFromMissing() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(
            readResult = CredentialReadResult.StorageUnavailable
        )
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(
            ProfileResolutionResult.StorageUnavailable,
            repository.resolveProfile(profile.profileId)
        )
    }

    @Test
    fun resolveValidatedProfile_cleartextEndpoint_rejectsBeforeCredentialRead() = runTest {
        val profile = profile(
            "00000000-0000-0000-0000-000000000001",
            "Cleartext"
        ).copy(baseUrl = "http://example.com/v1")
        repository.createProfile(profile, "test-key")

        val result = repository.resolveValidatedProfile(
            profile.profileId,
            AiEndpointResolver::chatCompletionsUrl
        )

        assertSame(ProfileResolutionResult.InvalidEndpoint, result)
        assertEquals(0, credentials.readCount)
    }

    @Test
    fun resolveValidatedProfile_credentialReadCancellation_propagates() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(
            beforeRead = { throw CancellationException("cancelled") }
        )
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        val thrown = runCatching {
            repository.resolveValidatedProfile(
                profile.profileId,
                AiEndpointResolver::chatCompletionsUrl
            )
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(1, credentials.readCount)
    }

    @Test
    fun resolveValidatedActiveProfile_noActiveId_rejectsWithoutCredentialRead() = runTest {
        val credentials = FakeCredentialStorage()
        val repository = AiProfileRepository(metadataStore(), credentials)

        val result = repository.resolveValidatedActiveProfile(
            AiEndpointResolver::chatCompletionsUrl
        )

        assertSame(ProfileResolutionResult.NoActiveProfile, result)
        assertEquals(0, credentials.readCount)
    }

    @Test
    fun resolveValidatedActiveProfile_danglingId_rejectsWithoutCredentialRead() = runTest {
        val metadata = metadataStore()
        metadata.setActiveProfileId("missing-profile")
        val credentials = FakeCredentialStorage()
        val repository = AiProfileRepository(metadata, credentials)

        val result = repository.resolveValidatedActiveProfile(
            AiEndpointResolver::chatCompletionsUrl
        )

        assertSame(ProfileResolutionResult.ProfileNotFound, result)
        assertEquals(0, credentials.readCount)
    }

    @Test
    fun resolveValidatedActiveProfile_invalidEndpoint_rejectsBeforeCredentialRead() = runTest {
        repository.createProfile(
            profile("profile-a", "Invalid").copy(baseUrl = "http://example.com/v1"),
            "secret-key"
        )

        val result = repository.resolveValidatedActiveProfile(
            AiEndpointResolver::chatCompletionsUrl
        )

        assertSame(ProfileResolutionResult.InvalidEndpoint, result)
        assertEquals(0, credentials.readCount)
    }

    @Test
    fun resolveValidatedActiveProfile_validProfile_readsCredentialExactlyOnce() = runTest {
        val active = profile("profile-a", "Active")
        repository.createProfile(active, "secret-key")

        val result = repository.resolveValidatedActiveProfile(
            AiEndpointResolver::chatCompletionsUrl
        )

        assertTrue(result is ProfileResolutionResult.Available)
        assertEquals(1, credentials.readCount)
    }

    @Test
    fun createProfile_metadataUnavailableDoesNotWriteCredential() = runTest {
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Unavailable
        val credentials = FakeCredentialStorage()
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")

        assertSame(
            ProfileMutationResult.MetadataUnavailable,
            repository.createProfile(profile, "test-key")
        )
        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun createProfile_metadataWriteFailureRemovesNewCredential() = runTest {
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(emptyList(), null)
        )
        coEvery { metadata.saveMetadata(any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage()
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")

        assertSame(
            ProfileMutationResult.MetadataUnavailable,
            repository.createProfile(profile, "test-key")
        )
        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun deleteProfile_metadataWriteFailureLeavesProfileWithMissingCredential() = runTest {
        // 凭据已删、元数据写失败时**不回滚**。终态是「profile 仍在、凭据 Missing」，
        // 用户经「替换 API Key」即可恢复。回滚曾被实现过，但那是不完整且不可观察的伪事务：
        // 回写结果无从上报、协程取消与进程崩溃都会绕过它。
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(profile), profile.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage(
            values = mutableMapOf(profile.profileId to "test-key")
        )
        val repository = AiProfileRepository(metadata, credentials)

        assertSame(
            ProfileMutationResult.MetadataUnavailable,
            repository.deleteProfile(profile.profileId)
        )
        // profile 元数据仍存在（saveMetadata 抛异常，未落盘）
        assertEquals(
            AiProfileMetadataReadResult.Available(
                AiProfileMetadataSnapshot(listOf(profile), profile.profileId)
            ),
            metadata.readMetadata()
        )
        // 凭据已被删除且不再恢复
        assertSame(CredentialReadResult.Missing, credentials.read(profile.profileId))
    }

    @Test
    fun deleteProfile_availableOrUnreadableCredential_succeedsWithoutReadingKey() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(profile), profile.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any()) } returns Unit

        listOf(null, CredentialReadResult.StorageUnavailable).forEach { readResult ->
            val credentials = FakeCredentialStorage(
                values = mutableMapOf(profile.profileId to "test-key"),
                readResult = readResult
            )
            val repository = AiProfileRepository(metadata, credentials)

            assertSame("readResult=$readResult", ProfileMutationResult.Success, repository.deleteProfile(profile.profileId))
            assertEquals("deletion must not decrypt credentials: $readResult", 0, credentials.readCount)
        }
    }

    @Test
    fun updateProfileSettings_changesOnlyNonIdentityFields() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        repository.createProfile(original, "test-key")

        assertSame(
            ProfileMutationResult.Success,
            repository.updateProfileSettings(original.profileId, "Renamed", 0.7)
        )

        assertEquals(
            original.copy(displayName = "Renamed", temperature = 0.7),
            metadata.profiles.first().single()
        )
        assertEquals("test-key", credentials.values[original.profileId])
    }

    @Test
    fun updateProfileSettings_invalidValuesPreserveMetadata() = runTest {
        val metadata = metadataStore()
        val repository = AiProfileRepository(metadata, FakeCredentialStorage())
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        repository.createProfile(original, "test-key")

        assertSame(
            ProfileMutationResult.InvalidProfile,
            repository.updateProfileSettings(original.profileId, "   ", Double.NaN)
        )
        assertEquals(original, metadata.profiles.first().single())
    }

    @Test
    fun updateProfile_identityUnchangedWithoutKey_preservesCredential() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        repository.createProfile(original, "old-key")

        assertSame(
            ProfileMutationResult.Success,
            repository.updateProfile(
                original.copy(displayName = " Renamed ", temperature = 0.5),
                replacementApiKey = null
            )
        )

        assertEquals("Renamed", metadata.profiles.first().single().displayName)
        assertEquals(0.5, metadata.profiles.first().single().temperature, 0.0)
        assertEquals("old-key", credentials.values[original.profileId])
    }

    @Test
    fun updateProfile_identityChangedWithoutKey_rejectsMutation() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        repository.createProfile(original, "old-key")

        assertSame(
            ProfileMutationResult.ReplacementCredentialRequired,
            repository.updateProfile(original.copy(modelId = "other-model"), null)
        )

        assertEquals(original, metadata.profiles.first().single())
        assertEquals("old-key", credentials.values[original.profileId])
    }

    @Test
    fun updateProfile_identityChangedWithKey_replacesCompleteSnapshot() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        repository.createProfile(original, "old-key")
        val proposed = original.copy(
            displayName = "DeepSeek",
            providerTemplate = AiProviderTemplate.DEEPSEEK,
            baseUrl = "https://api.deepseek.com",
            modelId = "deepseek-chat"
        )

        assertSame(ProfileMutationResult.Success, repository.updateProfile(proposed, "new-key"))

        assertEquals(proposed, metadata.profiles.first().single())
        assertEquals("new-key", credentials.values[original.profileId])
    }

    @Test
    fun updateProfile_metadataFailure_restoresPreviousCredential() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(original), original.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage(
            values = mutableMapOf(original.profileId to "old-key")
        )
        val repository = AiProfileRepository(metadata, credentials)

        assertSame(
            ProfileMutationResult.MetadataUnavailable,
            repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
        )

        assertEquals("old-key", credentials.values[original.profileId])
    }

    @Test
    fun updateProfile_metadataFailureWithMissingOldCredential_removesReplacementCredential() =
        runTest {
            val original = profile("00000000-0000-0000-0000-000000000001", "Original")
            val metadata = mockMetadataStore()
            coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
                AiProfileMetadataSnapshot(listOf(original), original.profileId)
            )
            coEvery { metadata.saveMetadata(any(), any()) } throws IOException("disk unavailable")
            val credentials = FakeCredentialStorage()
            val repository = AiProfileRepository(metadata, credentials)

            assertSame(
                ProfileMutationResult.MetadataUnavailable,
                repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
            )

            assertFalse(credentials.values.containsKey(original.profileId))
        }

    @Test
    fun updateProfile_credentialWriteFailure_preservesMetadataAndOldCredential() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(original), original.profileId)
        )
        val credentials = FakeCredentialStorage(
            values = mutableMapOf(original.profileId to "old-key"),
            writeResult = false
        )
        val repository = AiProfileRepository(metadata, credentials)

        assertSame(
            ProfileMutationResult.CredentialWriteFailed,
            repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
        )

        assertEquals("old-key", credentials.values[original.profileId])
        coVerify(exactly = 0) { metadata.saveMetadata(any(), any()) }
    }

    @Test
    fun updateProfile_metadataCancellation_restoresPreviousCredentialAndPropagates() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(original), original.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any()) } throws CancellationException("cancelled")
        val credentials = FakeCredentialStorage(
            values = mutableMapOf(original.profileId to "old-key")
        )
        val repository = AiProfileRepository(metadata, credentials)

        val thrown = runCatching {
            repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals("old-key", credentials.values[original.profileId])
    }

    @Test
    fun deleteAllProfiles_clearsCredentialsMetadataAndSelection() = runTest {
        val first = profile("00000000-0000-0000-0000-000000000001", "First")
        val second = profile("00000000-0000-0000-0000-000000000002", "Second")
        repository.createProfile(first, "first-key")
        repository.createProfile(second, "second-key")

        assertSame(ProfileMutationResult.Success, repository.deleteAllProfiles())

        assertTrue(credentials.values.isEmpty())
        assertTrue(metadata.profiles.first().isEmpty())
        assertEquals(null, metadata.activeProfileId.first())
    }

    @Test
    fun deleteAllProfiles_credentialFailure_preservesMetadataAndCredentials() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(clearResult = false)
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "Primary")
        repository.createProfile(profile, "test-key")

        assertSame(
            ProfileMutationResult.CredentialDeleteFailed,
            repository.deleteAllProfiles()
        )

        assertEquals(listOf(profile), metadata.profiles.first())
        assertEquals(profile.profileId, metadata.activeProfileId.first())
        assertEquals("test-key", credentials.values[profile.profileId])
    }

    @Test
    fun deleteAllProfiles_metadataFailure_keepsCredentialsDeletedAndReportsFailure() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "Primary")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(profile), profile.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage(
            values = mutableMapOf(profile.profileId to "test-key")
        )
        val repository = AiProfileRepository(metadata, credentials)

        assertSame(ProfileMutationResult.MetadataUnavailable, repository.deleteAllProfiles())

        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun reinitializeCredentialStorage_isNonDestructive() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(ProfileMutationResult.Success, repository.reinitializeCredentialStorage())

        assertEquals("test-key", credentials.values[profile.profileId])
        assertEquals(1, credentials.reinitializeCount)
        assertEquals(listOf(profile), metadata.profiles.first())
    }

    @Test
    fun clearAllCredentials_preservesProfilesAndActiveSelection() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(ProfileMutationResult.Success, repository.clearAllCredentials())

        assertTrue(credentials.values.isEmpty())
        assertEquals(listOf(profile), metadata.profiles.first())
        assertEquals(profile.profileId, metadata.activeProfileId.first())
    }

    @Test
    fun clearAllCredentials_storageFailurePreservesCredentials() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(clearResult = false)
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(
            ProfileMutationResult.CredentialDeleteFailed,
            repository.clearAllCredentials()
        )
        assertEquals("test-key", credentials.values[profile.profileId])
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun concurrentResolveAndDelete_areSerializedByRepositoryMutex() = runTest {
        val metadata = metadataStore()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val credentials = FakeCredentialStorage(
            values = mutableMapOf("00000000-0000-0000-0000-000000000001" to "test-key"),
            beforeRead = {
                readStarted.complete(Unit)
                releaseRead.await()
            }
        )
        val repository = AiProfileRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        val resolve = async { repository.resolveProfile(profile.profileId) }
        try {
            readStarted.await()
            val delete = async { repository.deleteProfile(profile.profileId) }
            runCurrent()

            assertFalse(delete.isCompleted)
            releaseRead.complete(Unit)

            assertEquals(ProfileResolutionResult.Available::class, resolve.await()::class)
            assertSame(ProfileMutationResult.Success, delete.await())
        } finally {
            releaseRead.complete(Unit)
        }
    }

    private fun metadataStore(): AiProfileMetadataStore = AiProfileMetadataStore(
        InMemoryPreferencesDataStore(),
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    )

    private fun mockMetadataStore(): AiProfileMetadataStore = mockk {
        every { profiles } returns MutableStateFlow(emptyList())
        every { activeProfileId } returns MutableStateFlow(null)
    }

    private fun profile(profileId: String, displayName: String): AiProviderProfile =
        AiProviderProfile(
            profileId = profileId,
            displayName = displayName,
            providerTemplate = AiProviderTemplate.OPENAI_COMPATIBLE,
            baseUrl = "https://example.com/v1",
            modelId = "model-id",
            authStrategy = AiAuthStrategy.API_KEY,
            temperature = 0.2
        )

    private class FakeCredentialStorage(
        val values: MutableMap<String, String> = mutableMapOf(),
        private val writeResult: Boolean = true,
        private val deleteResult: Boolean = true,
        private val clearResult: Boolean = true,
        private val beforeRead: (suspend () -> Unit)? = null,
        private val readResult: CredentialReadResult? = null
    ) : AiCredentialStorage {
        var reinitializeCount: Int = 0
            private set

        /** 读取次数：用于证明删除路径不会为补偿事务而解密明文 Key。 */
        var readCount: Int = 0
            private set

        override suspend fun read(profileId: String): CredentialReadResult {
            readCount++
            beforeRead?.invoke()
            return readResult
                ?: values[profileId]?.let(CredentialReadResult::Available)
                ?: CredentialReadResult.Missing
        }

        override suspend fun write(profileId: String, apiKey: String): Boolean {
            if (!writeResult) return false
            values[profileId] = apiKey
            return true
        }

        override suspend fun delete(profileId: String): Boolean {
            if (!deleteResult) return false
            values.remove(profileId)
            return true
        }

        override suspend fun reinitializeStorage() {
            reinitializeCount++
        }

        override suspend fun clearAllCredentials(): Boolean {
            if (!clearResult) return false
            values.clear()
            return true
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                state.value = transform(state.value)
                state.value
            }
    }
}
