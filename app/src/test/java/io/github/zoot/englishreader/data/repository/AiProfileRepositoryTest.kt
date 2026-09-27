package io.github.zoot.englishreader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiCredentialAddress
import io.github.zoot.englishreader.data.local.AiCredentialSlot
import io.github.zoot.englishreader.data.local.AiCredentialStorage
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.data.local.AiProfileMetadataReadResult
import io.github.zoot.englishreader.data.local.AiProfileMetadataSnapshot
import io.github.zoot.englishreader.data.local.AiProfileMetadataStore
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.CredentialReadResult
import io.github.zoot.englishreader.data.local.CredentialPreferences
import io.github.zoot.englishreader.data.local.CredentialPreferencesFactory
import io.github.zoot.englishreader.data.local.EncryptedAiCredentialStorage
import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.File
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AiProfileRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val ownerScope by lazy { CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher) }

    @After
    fun tearDown() {
        ownerScope.cancel()
    }

    private val metadata by lazy { metadataStore() }
    private val credentials by lazy { FakeCredentialStorage() }
    private val repository by lazy { createRepository(metadata, credentials) }

    @Test
    fun createProfile_firstProfileBecomesActiveAndStoresCredential() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")

        assertSame(
            ProfileMutationResult.Success,
            repository.createProfile(profile, "test-key")
        )

        assertEquals(listOf(profile), metadata.profiles.first())
        assertEquals(profile.profileId, metadata.activeProfileId.first())
        assertEquals("test-key", boundKey(metadata, credentials, profile.profileId))
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
        val repository = createRepository(metadata, FakeCredentialStorage())
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
        assertEquals("new-key", boundKey(metadata, credentials, profile.profileId))

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

        assertEquals("original-key", boundKey(metadata, credentials, profile.profileId))
        assertEquals(listOf(profile), metadata.profiles.first())
    }

    @Test
    fun resolveProfile_returnsImmutableSnapshotForRequestedProfile() = runTest {
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf("00000000-0000-0000-0000-000000000001" to "test-key")
        )
        val repository = createRepository(metadata, credentials)
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
        val repository = createRepository(metadata, credentials)
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
        assertFalse(credentials.values.keys.any { it.profileId == profile.profileId })
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
        val repository = createRepository(metadata, credentials)
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
        val repository = createRepository(metadata, credentials)
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
        val repository = createRepository(metadataStore(), credentials)

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
        val repository = createRepository(metadata, credentials)

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
        val repository = createRepository(metadata, credentials)
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
        coEvery { metadata.saveMetadata(any(), any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage()
        val repository = createRepository(metadata, credentials)
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
        coEvery { metadata.saveMetadata(any(), any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf(profile.profileId to "test-key")
        )
        val repository = createRepository(metadata, credentials)

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
        coEvery { metadata.saveMetadata(any(), any(), any()) } returns Unit

        listOf(null, CredentialReadResult.StorageUnavailable).forEach { readResult ->
            val credentials = FakeCredentialStorage(
                legacyValues = mutableMapOf(profile.profileId to "test-key"),
                readResult = readResult
            )
            val repository = createRepository(metadata, credentials)

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
        assertEquals("test-key", boundKey(metadata, credentials, original.profileId))
    }

    @Test
    fun updateProfileSettings_invalidValuesPreserveMetadata() = runTest {
        val metadata = metadataStore()
        val repository = createRepository(metadata, FakeCredentialStorage())
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
        assertEquals("old-key", boundKey(metadata, credentials, original.profileId))
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
        assertEquals("old-key", boundKey(metadata, credentials, original.profileId))
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
        assertEquals("new-key", boundKey(metadata, credentials, original.profileId))
    }

    @Test
    fun updateProfile_migrationMetadataFailure_preservesLegacyBinding() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(original), original.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf(original.profileId to "old-key")
        )
        val repository = createRepository(metadata, credentials)

        assertSame(
            ProfileMutationResult.MetadataUnavailable,
            repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
        )

        assertEquals("old-key", boundKey(metadata, credentials, original.profileId))
    }

    @Test
    fun updateProfile_metadataFailureWithMissingLegacy_doesNotPublishReplacement() =
        runTest {
            val original = profile("00000000-0000-0000-0000-000000000001", "Original")
            val metadata = mockMetadataStore()
            coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
                AiProfileMetadataSnapshot(listOf(original), original.profileId)
            )
            coEvery { metadata.saveMetadata(any(), any(), any()) } throws IOException("disk unavailable")
            val credentials = FakeCredentialStorage()
            val repository = createRepository(metadata, credentials)

            assertSame(
                ProfileMutationResult.MetadataUnavailable,
                repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
            )

            assertFalse(credentials.values.containsKey(AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)))
        }

    @Test
    fun updateProfile_credentialWriteFailure_preservesMetadataAndOldCredential() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(original), original.profileId)
        )
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf(original.profileId to "old-key"),
            writeResult = false
        )
        val repository = createRepository(metadata, credentials)

        assertSame(
            ProfileMutationResult.CredentialWriteFailed,
            repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
        )

        assertEquals("old-key", boundKey(metadata, credentials, original.profileId))
        coVerify(exactly = 0) { metadata.saveMetadata(any(), any(), any()) }
    }

    @Test
    fun updateProfile_migrationMetadataCancellation_preservesLegacyAndPropagates() = runTest {
        val original = profile("00000000-0000-0000-0000-000000000001", "Original")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(original), original.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any(), any()) } throws CancellationException("cancelled")
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf(original.profileId to "old-key")
        )
        val repository = createRepository(metadata, credentials)

        val thrown = runCatching {
            repository.updateProfile(original.copy(modelId = "other-model"), "new-key")
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals("old-key", boundKey(metadata, credentials, original.profileId))
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
        val repository = createRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "Primary")
        repository.createProfile(profile, "test-key")

        assertSame(
            ProfileMutationResult.CredentialDeleteFailed,
            repository.deleteAllProfiles()
        )

        assertEquals(listOf(profile), metadata.profiles.first())
        assertEquals(profile.profileId, metadata.activeProfileId.first())
        assertEquals("test-key", boundKey(metadata, credentials, profile.profileId))
    }

    @Test
    fun deleteAllProfiles_metadataFailure_keepsCredentialsDeletedAndReportsFailure() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "Primary")
        val metadata = mockMetadataStore()
        coEvery { metadata.readMetadata() } returns AiProfileMetadataReadResult.Available(
            AiProfileMetadataSnapshot(listOf(profile), profile.profileId)
        )
        coEvery { metadata.saveMetadata(any(), any(), any()) } throws IOException("disk unavailable")
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf(profile.profileId to "test-key")
        )
        val repository = createRepository(metadata, credentials)

        assertSame(ProfileMutationResult.MetadataUnavailable, repository.deleteAllProfiles())

        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun reinitializeCredentialStorage_isNonDestructive() = runTest {
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(ProfileMutationResult.Success, repository.reinitializeCredentialStorage())

        assertEquals("test-key", boundKey(metadata, credentials, profile.profileId))
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
        val repository = createRepository(metadata, credentials)
        val profile = profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        repository.createProfile(profile, "test-key")

        assertSame(
            ProfileMutationResult.CredentialDeleteFailed,
            repository.clearAllCredentials()
        )
        assertEquals("test-key", boundKey(metadata, credentials, profile.profileId))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun concurrentResolveAndDelete_areSerializedByRepositoryMutex() = runTest {
        val metadata = metadataStore()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val credentials = FakeCredentialStorage(
            legacyValues = mutableMapOf("00000000-0000-0000-0000-000000000001" to "test-key"),
            beforeRead = {
                readStarted.complete(Unit)
                releaseRead.await()
            }
        )
        val repository = createRepository(metadata, credentials)
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

    @Test
    fun updateProfile_twoIdentityChanges_rotateSlotsAndKeepStableSelection() = runTest {
        val original = profile("profile-a", "Original")
        val other = profile("profile-b", "Other")
        repository.createProfile(original, "key-a")
        repository.createProfile(other, "other-key")
        repository.selectActiveProfile(other.profileId)
        val oldSnapshot = repository.resolveProfile(original.profileId)
        val next = original.copy(baseUrl = "https://next.example/v1", modelId = "next-model")

        assertSame(ProfileMutationResult.Success, repository.updateProfile(next, "key-b"))
        assertEquals(AiCredentialSlot.B, snapshot(metadata).credentialSlots[original.profileId])
        assertEquals("key-a", credentials.values[AiCredentialAddress(original.profileId, AiCredentialSlot.A)])
        assertBinding(repository, next, "key-b")

        val last = next.copy(modelId = "last-model")
        assertSame(ProfileMutationResult.Success, repository.updateProfile(last, "key-c"))
        assertEquals(AiCredentialSlot.A, snapshot(metadata).credentialSlots[original.profileId])
        assertBinding(repository, last, "key-c")
        assertBinding(repository, other, "other-key")
        assertEquals(other.profileId, snapshot(metadata).activeProfileId)
        assertEquals("key-a", (oldSnapshot as ProfileResolutionResult.Available).profile.apiKey)
    }

    @Test
    fun createProfile_distinctPaddedIdWithFailedMetadata_doesNotOverwriteOriginalBinding() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val metadata = metadataStore(dataStore)
        val values = mutableMapOf<String, String>()
        val credentials = EncryptedAiCredentialStorage(CredentialPreferencesFactory {
            object : CredentialPreferences {
                override fun getString(key: String): String? = values[key]
                override fun putString(key: String, value: String): Boolean {
                    values[key] = value
                    return true
                }
                override fun remove(key: String): Boolean {
                    values.remove(key)
                    return true
                }
                override fun clear(): Boolean {
                    values.clear()
                    return true
                }
            }
        })
        val repository = createRepository(metadata, credentials)
        val original = profile("profile-a", "Original")
        val padded = profile(" profile-a ", "Distinct").copy(modelId = "other-model")
        assertSame(ProfileMutationResult.Success, repository.createProfile(original, "original-key"))
        dataStore.failWriteAt = dataStore.writeCount + 1

        assertSame(ProfileMutationResult.MetadataUnavailable, repository.createProfile(padded, "other-key"))
        assertBinding(repository, original, "original-key")

        assertSame(ProfileMutationResult.Success, repository.createProfile(padded, "other-key"))
        assertBinding(repository, original, "original-key")
        assertBinding(repository, padded, "other-key")
    }

    @Test
    fun resolveProfile_selectedSlotMissing_doesNotReadOtherSlots() = runTest {
        val original = profile("profile-a", "Original")
        repository.createProfile(original, "current-key")
        credentials.values.remove(AiCredentialAddress(original.profileId, AiCredentialSlot.A))
        credentials.values[AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)] = "legacy-key"
        credentials.values[AiCredentialAddress(original.profileId, AiCredentialSlot.B)] = "stale-key"

        assertSame(ProfileResolutionResult.Missing, repository.resolveProfile(original.profileId))
        assertEquals(listOf(AiCredentialAddress(original.profileId, AiCredentialSlot.A)), credentials.reads)
    }

    @Test
    fun updateProfile_inactiveWriteFalseAfterMemoryMutation_preservesBindingAfterReopen() = runTest {
        val original = profile("profile-a", "Original")
        repository.createProfile(original, "old-key")
        credentials.writeResult = false
        credentials.mutateMemoryOnFailure = true

        assertSame(
            ProfileMutationResult.CredentialWriteFailed,
            repository.updateProfile(original.copy(modelId = "new-model"), "new-key")
        )

        assertEquals("new-key", credentials.values[AiCredentialAddress(original.profileId, AiCredentialSlot.B)])
        assertBinding(repository, original, "old-key")
        assertBinding(createRepository(metadata, credentials.reopened()), original, "old-key")
    }

    @Test
    fun updateProfile_versionedMetadataWriteFailure_doesNotOverwriteCurrentSlot() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val metadata = metadataStore(dataStore)
        val credentials = FakeCredentialStorage()
        val repository = createRepository(metadata, credentials)
        val original = profile("profile-a", "Original")
        repository.createProfile(original, "old-key")
        dataStore.failWriteAt = dataStore.writeCount + 1

        assertSame(
            ProfileMutationResult.MetadataUnavailable,
            repository.updateProfile(original.copy(modelId = "new-model"), "new-key")
        )

        assertBinding(repository, original, "old-key")
        assertBinding(createRepository(metadata, credentials.reopened()), original, "old-key")
        assertEquals("new-key", credentials.persistedValues[AiCredentialAddress(original.profileId, AiCredentialSlot.B)])
        assertEquals(listOf(AiCredentialSlot.A, AiCredentialSlot.B), credentials.writes.map { it.slot })
    }

    @Test
    fun updateProfile_legacyMigration_commitsOldBindingBeforeDeletingLegacy() = runTest {
        val original = profile("profile-a", "Original")
        val other = profile("profile-b", "Other")
        val dataStore = InMemoryPreferencesDataStore()
        val metadata = metadataStore(dataStore)
        metadata.saveProfiles(listOf(original, other))
        val credentials = FakeCredentialStorage(legacyValues = mapOf(
            original.profileId to "old-key",
            other.profileId to "other-key"
        ))
        val repository = createRepository(metadata, credentials)
        val published = mutableListOf<AiProfileMetadataSnapshot>()
        dataStore.afterCommit = { published += snapshot(metadata) }
        credentials.beforeDelete = {
            assertEquals(AiCredentialSlot.A, snapshot(metadata).credentialSlots[original.profileId])
            assertEquals(original, snapshot(metadata).profiles.first())
            assertEquals("old-key", credentials.persistedValues[AiCredentialAddress(original.profileId, AiCredentialSlot.A)])
        }
        val next = original.copy(modelId = "new-model")

        assertSame(ProfileMutationResult.Success, repository.updateProfile(next, "new-key"))

        assertEquals(listOf(original, next), published.map { it.profiles.first() })
        assertEquals(listOf(AiCredentialSlot.A, AiCredentialSlot.B), published.map { it.credentialSlots[original.profileId] })
        assertEquals(AiCredentialSlot.LEGACY, snapshot(metadata).credentialSlots[other.profileId])
        assertFalse(credentials.persistedValues.containsKey(AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)))
        assertBinding(createRepository(metadata, credentials.reopened()), next, "new-key")
        assertBinding(repository, other, "other-key")
    }

    @Test
    fun updateProfile_legacyCopyFalseWithChangedMemory_leavesLegacyReference() = runTest {
        val original = profile("profile-a", "Original")
        val metadata = metadataStore()
        metadata.saveProfiles(listOf(original))
        val credentials = FakeCredentialStorage(legacyValues = mapOf(original.profileId to "old-key"), writeResult = false)
        credentials.mutateMemoryOnFailure = true
        val repository = createRepository(metadata, credentials)

        assertSame(ProfileMutationResult.CredentialWriteFailed, repository.updateProfile(original.copy(modelId = "next"), "new-key"))

        assertEquals(AiCredentialSlot.LEGACY, snapshot(metadata).credentialSlots[original.profileId])
        assertTrue(credentials.deletes.isEmpty())
        assertBinding(createRepository(metadata, credentials.reopened()), original, "old-key")
    }

    @Test
    fun updateProfile_legacyRemovalFalseThenMissing_retriesDurableDeletionBeforeReplacement() = runTest {
        val original = profile("profile-a", "Original")
        val metadata = metadataStore()
        metadata.saveProfiles(listOf(original))
        val credentials = FakeCredentialStorage(legacyValues = mapOf(original.profileId to "old-key"), deleteResult = false)
        credentials.mutateMemoryOnFailure = true
        val repository = createRepository(metadata, credentials)
        val next = original.copy(modelId = "new-model")
        val legacy = AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)

        assertSame(ProfileMutationResult.CredentialDeleteFailed, repository.updateProfile(next, "new-key"))
        assertEquals(AiCredentialSlot.A, snapshot(metadata).credentialSlots[original.profileId])
        assertSame(CredentialReadResult.Missing, credentials.read(legacy))
        assertEquals("old-key", credentials.persistedValues[legacy])
        assertBinding(repository, original, "old-key")

        credentials.deleteResult = true
        assertSame(ProfileMutationResult.Success, repository.updateProfile(next, "new-key"))

        assertEquals(listOf(legacy, legacy), credentials.deletes)
        assertFalse(credentials.reopened().values.containsKey(legacy))
        assertBinding(createRepository(metadata, credentials.reopened()), next, "new-key")
    }

    @Test
    fun updateProfile_legacyTargetWriteFailure_keepsMigratedOldBinding() = runTest {
        val original = profile("profile-a", "Original")
        val metadata = metadataStore()
        metadata.saveProfiles(listOf(original))
        val credentials = FakeCredentialStorage(legacyValues = mapOf(original.profileId to "old-key"))
        credentials.writeOutcomes.addAll(listOf(true, false))
        credentials.mutateMemoryOnFailure = true
        val repository = createRepository(metadata, credentials)

        assertSame(ProfileMutationResult.CredentialWriteFailed, repository.updateProfile(original.copy(modelId = "next"), "new-key"))

        assertEquals(AiCredentialSlot.A, snapshot(metadata).credentialSlots[original.profileId])
        assertBinding(createRepository(metadata, credentials.reopened()), original, "old-key")
        assertFalse(credentials.persistedValues.containsKey(AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)))
    }

    @Test
    fun updateProfile_legacyTargetMetadataFailure_keepsMigratedOldBinding() = runTest {
        val original = profile("profile-a", "Original")
        val dataStore = InMemoryPreferencesDataStore()
        val metadata = metadataStore(dataStore)
        metadata.saveProfiles(listOf(original))
        dataStore.failWriteAt = dataStore.writeCount + 2
        val credentials = FakeCredentialStorage(legacyValues = mapOf(original.profileId to "old-key"))
        val repository = createRepository(metadata, credentials)

        assertSame(ProfileMutationResult.MetadataUnavailable, repository.updateProfile(original.copy(modelId = "next"), "new-key"))

        assertEquals(AiCredentialSlot.A, snapshot(metadata).credentialSlots[original.profileId])
        assertBinding(createRepository(metadata, credentials.reopened()), original, "old-key")
        assertEquals("new-key", credentials.persistedValues[AiCredentialAddress(original.profileId, AiCredentialSlot.B)])
    }

    @Test
    fun updateProfile_missingLegacy_commitsReplacementDirectlyToA() = runTest {
        val original = profile("profile-a", "Original")
        val metadata = metadataStore()
        metadata.saveProfiles(listOf(original))
        val credentials = FakeCredentialStorage()
        val repository = createRepository(metadata, credentials)
        val next = original.copy(modelId = "next")

        assertSame(ProfileMutationResult.Success, repository.updateProfile(next, "new-key"))

        assertEquals(listOf(AiCredentialAddress(original.profileId, AiCredentialSlot.A)), credentials.writes)
        assertEquals(listOf(AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)), credentials.deletes)
        assertBinding(createRepository(metadata, credentials.reopened()), next, "new-key")
    }

    @Test
    fun updateProfile_equalReplacementKey_stillMigratesAndPublishesNewIdentity() = runTest {
        val original = profile("profile-a", "Original")
        val metadata = metadataStore()
        metadata.saveProfiles(listOf(original))
        val credentials = FakeCredentialStorage(legacyValues = mapOf(original.profileId to "same-key"))
        val repository = createRepository(metadata, credentials)
        val next = original.copy(modelId = "next")

        assertSame(ProfileMutationResult.Success, repository.updateProfile(next, "same-key"))

        assertEquals(AiCredentialSlot.B, snapshot(metadata).credentialSlots[original.profileId])
        assertFalse(credentials.persistedValues.containsKey(AiCredentialAddress(original.profileId, AiCredentialSlot.LEGACY)))
        assertBinding(repository, next, "same-key")
    }

    @Test
    fun updateProfile_metadataOnlyAndHelpers_preserveReferencesWithoutDecrypting() = runTest {
        val original = profile("profile-a", "Original")
        val metadata = metadataStore()
        val credentials = FakeCredentialStorage(readResult = CredentialReadResult.StorageUnavailable)
        val repository = createRepository(metadata, credentials)
        repository.createProfile(original, "old-key")
        val references = snapshot(metadata).credentialSlots

        assertSame(ProfileMutationResult.Success, repository.updateProfile(original.copy(displayName = "Renamed"), null))
        assertSame(ProfileMutationResult.Success, repository.updateProfileSettings(original.profileId, "Name", 0.8))
        assertSame(ProfileMutationResult.Success, repository.selectActiveProfile(original.profileId))
        assertSame(ProfileMutationResult.Success, repository.reinitializeCredentialStorage())
        assertEquals(references, snapshot(metadata).credentialSlots)
        assertEquals(0, credentials.readCount)
        assertSame(ProfileMutationResult.Success, repository.clearAllCredentials())
        assertEquals(references, snapshot(metadata).credentialSlots)
    }

    @Test
    fun updateProfile_callerCancelledAfterWriterCommit_queuesNextSlotReuseUntilAcknowledged() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)
        val durable = ActorPreferencesDataStore(dataStoreScope)
        val acknowledgements = AcknowledgementDataStore(durable)
        val metadata = metadataStore(acknowledgements)
        val credentials = FakeCredentialStorage()
        val repository = createRepository(metadata, credentials)
        val original = profile("profile-a", "Original")
        val committed = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        try {
            assertSame(ProfileMutationResult.Success, repository.createProfile(original, "key-a"))
            acknowledgements.afterNextCommit = {
                committed.complete(Unit)
                releaseAcknowledgement.await()
            }
            val next = original.copy(modelId = "model-b")
            val caller = launch { repository.updateProfile(next, "key-b") }
            committed.await()
            caller.cancelAndJoin()
            assertEquals(next, snapshot(metadata).profiles.single())
            assertEquals(AiCredentialSlot.B, snapshot(metadata).credentialSlots[original.profileId])

            val last = original.copy(modelId = "model-c")
            val following = async { repository.updateProfile(last, "key-c") }
            runCurrent()
            assertFalse(following.isCompleted)
            assertEquals("key-a", credentials.values[AiCredentialAddress(original.profileId, AiCredentialSlot.A)])
            assertEquals(listOf(AiCredentialSlot.A, AiCredentialSlot.B), credentials.writes.map { it.slot })

            releaseAcknowledgement.complete(Unit)
            assertSame(ProfileMutationResult.Success, following.await())
            assertBinding(repository, last, "key-c")
            assertEquals(AiCredentialSlot.A, snapshot(metadata).credentialSlots[original.profileId])
            assertBinding(createRepository(metadata, credentials.reopened()), last, "key-c")
        } finally {
            releaseAcknowledgement.complete(Unit)
            dataStoreScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun createProfile_callerCancelledAfterRealDataStoreCommit_preservesBindingOnDiskReopen() = runTest {
        // DataStore 1.0.0 uses File.renameTo; Windows cannot replace an existing target.
        // This real-file case tests its first commit. Repeated writes use the actor fixture above.
        val file = File(temporaryFolder.root, "profiles.preferences_pb")
        val dataStoreJob = SupervisorJob()
        val reopenedJob = SupervisorJob()
        val durable = PreferenceDataStoreFactory.create(scope = CoroutineScope(dataStoreJob + Dispatchers.IO)) { file }
        val acknowledgements = AcknowledgementDataStore(durable)
        val metadata = metadataStore(acknowledgements)
        val credentials = FakeCredentialStorage()
        val repository = createRepository(metadata, credentials)
        val original = profile("profile-a", "Original")
        val committed = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        acknowledgements.afterNextCommit = {
            committed.complete(Unit)
            releaseAcknowledgement.await()
        }
        try {
            val caller = launch {
                assertSame(ProfileMutationResult.Success, repository.createProfile(original, "durable-key"))
            }
            committed.await()
            caller.cancelAndJoin()
            assertEquals(original, snapshot(metadata).profiles.single())
            assertTrue(file.isFile)
            val resolve = async { repository.resolveProfile(original.profileId) }
            runCurrent()
            assertFalse("Resolve must wait for the accepted mutation's acknowledgement", resolve.isCompleted)

            releaseAcknowledgement.complete(Unit)
            assertTrue(resolve.await() is ProfileResolutionResult.Available)
            dataStoreJob.cancelAndJoin()
            val reopened = PreferenceDataStoreFactory.create(scope = CoroutineScope(reopenedJob + Dispatchers.IO)) { file }
            val reopenedMetadata = metadataStore(reopened)
            assertEquals(AiCredentialSlot.A, snapshot(reopenedMetadata).credentialSlots[original.profileId])
            assertBinding(createRepository(reopenedMetadata, credentials.reopened()), original, "durable-key")
            assertFalse(file.readText().contains("durable-key"))
        } finally {
            releaseAcknowledgement.complete(Unit)
            dataStoreJob.cancelAndJoin()
            reopenedJob.cancelAndJoin()
        }
    }

    @Test
    fun updateProfile_internalCancellationAfterCommit_quarantinesMutationsWithoutCompensation() = runTest {
        val acknowledgements = AcknowledgementDataStore(InMemoryPreferencesDataStore())
        val metadata = metadataStore(acknowledgements)
        val credentials = FakeCredentialStorage()
        val repository = createRepository(metadata, credentials)
        val original = profile("profile-a", "Original")
        repository.createProfile(original, "key-a")
        val next = original.copy(modelId = "model-b")
        acknowledgements.afterNextCommit = { throw CancellationException("owner interrupted before acknowledgement") }

        assertTrue(runCatching { repository.updateProfile(next, "key-b") }.exceptionOrNull() is CancellationException)
        assertBinding(repository, next, "key-b")
        val writes = credentials.writes.toList()
        val deletes = credentials.deletes.toList()
        assertSame(ProfileMutationResult.MetadataUnavailable, repository.rotateApiKey(original.profileId, "key-c"))
        assertSame(ProfileMutationResult.MetadataUnavailable, repository.clearAllCredentials())
        assertSame(ProfileMutationResult.MetadataUnavailable, repository.selectActiveProfile(original.profileId))
        assertEquals(writes, credentials.writes)
        assertEquals(deletes, credentials.deletes)
        assertBinding(createRepository(metadata, credentials.reopened()), next, "key-b")
    }

    private suspend fun snapshot(metadata: AiProfileMetadataStore): AiProfileMetadataSnapshot =
        (metadata.readMetadata() as AiProfileMetadataReadResult.Available).snapshot

    private suspend fun assertBinding(
        repository: AiProfileRepository,
        expected: AiProviderProfile,
        key: String
    ) {
        val result = repository.resolveProfile(expected.profileId) as ProfileResolutionResult.Available
        assertEquals(expected.profileId, result.profile.profileId)
        assertEquals(expected.providerTemplate, result.profile.providerTemplate)
        assertEquals(expected.baseUrl, result.profile.baseUrl)
        assertEquals(expected.modelId, result.profile.modelId)
        assertEquals(expected.authStrategy, result.profile.authStrategy)
        assertEquals(expected.temperature, result.profile.temperature, 0.0)
        assertEquals(key, result.profile.apiKey)
    }

    private fun createRepository(
        metadata: AiProfileMetadataStore,
        credentials: AiCredentialStorage
    ): AiProfileRepository = AiProfileRepository(metadata, credentials, ownerScope)

    private suspend fun boundKey(
        metadata: AiProfileMetadataStore,
        credentials: FakeCredentialStorage,
        profileId: String
    ): String? {
        val snapshot = (metadata.readMetadata() as AiProfileMetadataReadResult.Available).snapshot
        val slot = snapshot.credentialSlots[profileId] ?: return null
        return credentials.values[AiCredentialAddress(profileId, slot)]
    }

    private fun metadataStore(dataStore: DataStore<Preferences> = InMemoryPreferencesDataStore()): AiProfileMetadataStore = AiProfileMetadataStore(
        dataStore,
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
        legacyValues: Map<String, String> = emptyMap(),
        var writeResult: Boolean = true,
        var deleteResult: Boolean = true,
        private val clearResult: Boolean = true,
        private val beforeRead: (suspend () -> Unit)? = null,
        private val readResult: CredentialReadResult? = null
    ) : AiCredentialStorage {
        val values: MutableMap<AiCredentialAddress, String> = legacyValues.mapKeys {
            AiCredentialAddress(it.key, AiCredentialSlot.LEGACY)
        }.toMutableMap()
        val persistedValues = values.toMutableMap()
        var mutateMemoryOnFailure = false
        val writeOutcomes = ArrayDeque<Boolean>()
        val writes = mutableListOf<AiCredentialAddress>()
        val deletes = mutableListOf<AiCredentialAddress>()
        val reads = mutableListOf<AiCredentialAddress>()
        var beforeDelete: (suspend () -> Unit)? = null
        var reinitializeCount: Int = 0
            private set

        /** 读取次数：用于证明删除路径不会为补偿事务而解密明文 Key。 */
        var readCount: Int = 0
            private set

        override suspend fun read(address: AiCredentialAddress): CredentialReadResult {
            readCount++
            reads += address
            beforeRead?.invoke()
            return readResult
                ?: values[address]?.let(CredentialReadResult::Available)
                ?: CredentialReadResult.Missing
        }

        override suspend fun write(address: AiCredentialAddress, apiKey: String): Boolean {
            writes += address
            val committed = writeOutcomes.removeFirstOrNull() ?: writeResult
            if (committed || mutateMemoryOnFailure) values[address] = apiKey
            if (committed) persistedValues[address] = apiKey
            return committed
        }

        override suspend fun delete(address: AiCredentialAddress): Boolean {
            deletes += address
            beforeDelete?.invoke()
            if (deleteResult || mutateMemoryOnFailure) values.remove(address)
            if (deleteResult) persistedValues.remove(address)
            return deleteResult
        }

        override suspend fun reinitializeStorage() {
            reinitializeCount++
        }

        override suspend fun clearAllCredentials(): Boolean {
            if (!clearResult) return false
            values.clear()
            persistedValues.clear()
            return true
        }

        fun reopened(): FakeCredentialStorage = FakeCredentialStorage().also {
            it.values.putAll(persistedValues)
            it.persistedValues.putAll(persistedValues)
        }
    }

    private class AcknowledgementDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data
        var afterNextCommit: (suspend () -> Unit)? = null

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val result = delegate.updateData(transform)
            val acknowledgement = afterNextCommit
            afterNextCommit = null
            acknowledgement?.invoke()
            return result
        }
    }

    /** Separate writer/ack ownership; no claim that this fixture implements filesystem durability. */
    private class ActorPreferencesDataStore(scope: CoroutineScope) : DataStore<Preferences> {
        private data class Update(
            val transform: suspend (Preferences) -> Preferences,
            val callerContext: CoroutineContext,
            val acknowledgement: CompletableDeferred<Preferences>
        )

        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val updates = Channel<Update>(Channel.UNLIMITED)
        override val data: Flow<Preferences> = state

        init {
            scope.launch {
                for (update in updates) {
                    update.acknowledgement.completeWith(runCatching {
                        val next = withContext(update.callerContext) { update.transform(state.value) }
                        state.value = next
                        next
                    })
                }
            }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val acknowledgement = CompletableDeferred<Preferences>()
            updates.send(Update(transform, currentCoroutineContext(), acknowledgement))
            return acknowledgement.await()
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var writeCount = 0
            private set
        var failWriteAt: Int? = null
        var afterCommit: (suspend () -> Unit)? = null

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val transformed = transform(state.value)
                writeCount++
                if (writeCount == failWriteAt) throw IOException("simulated metadata write failure")
                state.value = transformed
                afterCommit?.invoke()
                state.value
            }
    }
}
