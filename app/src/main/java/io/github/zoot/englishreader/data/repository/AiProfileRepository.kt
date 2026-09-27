package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiCredentialAddress
import io.github.zoot.englishreader.data.local.AiCredentialSlot
import io.github.zoot.englishreader.data.local.AiCredentialStorage
import io.github.zoot.englishreader.data.local.AiProfileMetadataReadResult
import io.github.zoot.englishreader.data.local.AiProfileMetadataSnapshot
import io.github.zoot.englishreader.data.local.AiProfileMetadataStore
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.CredentialReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class ResolvedAiProfile(
    val profileId: String,
    val providerTemplate: AiProviderTemplate,
    val baseUrl: String,
    val modelId: String,
    val authStrategy: AiAuthStrategy,
    val temperature: Double,
    val apiKey: String
) {
    override fun toString(): String =
        "ResolvedAiProfile(" +
            "profileId=[REDACTED], " +
            "providerTemplate=$providerTemplate, " +
            "baseUrl=[REDACTED], " +
            "modelId=[REDACTED], " +
            "authStrategy=$authStrategy, " +
            "temperature=$temperature, " +
            "apiKey=[REDACTED])"
}

sealed interface ProfileResolutionResult {
    data class Available(val profile: ResolvedAiProfile) : ProfileResolutionResult

    data object Missing : ProfileResolutionResult

    data object StorageUnavailable : ProfileResolutionResult

    data object NoActiveProfile : ProfileResolutionResult

    data object ProfileNotFound : ProfileResolutionResult

    /** endpoint 元数据在读取加密凭据之前就未通过校验。 */
    data object InvalidEndpoint : ProfileResolutionResult
}

sealed interface ProfileMutationResult {
    data object Success : ProfileMutationResult

    data object DuplicateProfile : ProfileMutationResult

    data object ProfileNotFound : ProfileMutationResult

    data object CredentialWriteFailed : ProfileMutationResult

    data object CredentialDeleteFailed : ProfileMutationResult

    data object MetadataUnavailable : ProfileMutationResult

    data object InvalidProfile : ProfileMutationResult

    data object ReplacementCredentialRequired : ProfileMutationResult

    data object CredentialStorageUnavailable : ProfileMutationResult
}

class AiProfileRepository(
    private val metadataStore: AiProfileMetadataStore,
    private val credentialStorage: AiCredentialStorage,
    private val applicationScope: CoroutineScope,
    private val operationMutex: Mutex = Mutex()
) {
    val profiles: Flow<List<AiProviderProfile>> = metadataStore.profiles
    val activeProfileId: Flow<String?> = metadataStore.activeProfileId

    // Guarded by operationMutex. An unacknowledged metadata write must not reuse either slot.
    private var metadataWriteUncertain = false

    suspend fun createProfile(profile: AiProviderProfile, apiKey: String): ProfileMutationResult = mutate {
        val normalized = profile.normalized()
        if (!normalized.isValid()) return@mutate ProfileMutationResult.InvalidProfile
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        if (metadata.profiles.any { it.profileId == normalized.profileId }) {
            return@mutate ProfileMutationResult.DuplicateProfile
        }
        val address = AiCredentialAddress(normalized.profileId, AiCredentialSlot.A)
        if (!credentialStorage.write(address, apiKey)) return@mutate ProfileMutationResult.CredentialWriteFailed
        val result = saveMetadataLocked(metadata.copy(
            profiles = metadata.profiles + normalized,
            activeProfileId = if (metadata.profiles.isEmpty()) normalized.profileId else metadata.activeProfileId,
            credentialSlots = metadata.credentialSlots + (normalized.profileId to AiCredentialSlot.A)
        ))
        if (result != ProfileMutationResult.Success && !metadataWriteUncertain) {
            try {
                credentialStorage.delete(address)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // An unreferenced encrypted slot is also covered by explicit clear-all.
            }
        }
        result
    }

    suspend fun selectActiveProfile(profileId: String): ProfileMutationResult = mutate {
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        if (metadata.profiles.none { it.profileId == profileId }) return@mutate ProfileMutationResult.ProfileNotFound
        saveMetadataLocked(metadata.copy(activeProfileId = profileId))
    }

    suspend fun rotateApiKey(profileId: String, apiKey: String): ProfileMutationResult = mutate {
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        val index = metadata.profiles.indexOfFirst { it.profileId == profileId }
        if (index < 0) return@mutate ProfileMutationResult.ProfileNotFound
        if (apiKey.isBlank()) return@mutate ProfileMutationResult.CredentialWriteFailed
        replaceCredentialLocked(metadata, index, metadata.profiles[index], apiKey.trim())
    }

    suspend fun updateProfileSettings(
        profileId: String,
        displayName: String,
        temperature: Double
    ): ProfileMutationResult = mutate {
        if (displayName.isBlank() || !temperature.isFinite()) return@mutate ProfileMutationResult.InvalidProfile
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        val index = metadata.profiles.indexOfFirst { it.profileId == profileId }
        if (index < 0) return@mutate ProfileMutationResult.ProfileNotFound
        saveProfileLocked(metadata, index, metadata.profiles[index].copy(
            displayName = displayName.trim(),
            temperature = temperature
        ))
    }

    /** The metadata reference publishes a complete binding only after the inactive slot is durable. */
    suspend fun updateProfile(
        proposedProfile: AiProviderProfile,
        replacementApiKey: String?
    ): ProfileMutationResult = mutate {
        val proposed = proposedProfile.normalized()
        if (!proposed.isValid()) return@mutate ProfileMutationResult.InvalidProfile
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        val index = metadata.profiles.indexOfFirst { it.profileId == proposed.profileId }
        if (index < 0) return@mutate ProfileMutationResult.ProfileNotFound
        val replacement = replacementApiKey?.trim().orEmpty()
        if (metadata.profiles[index].connectionIdentity() != proposed.connectionIdentity() && replacement.isEmpty()) {
            return@mutate ProfileMutationResult.ReplacementCredentialRequired
        }
        if (replacement.isEmpty()) saveProfileLocked(metadata, index, proposed)
        else replaceCredentialLocked(metadata, index, proposed, replacement)
    }

    private suspend fun replaceCredentialLocked(
        metadata: AiProfileMetadataSnapshot,
        profileIndex: Int,
        proposed: AiProviderProfile,
        replacement: String
    ): ProfileMutationResult {
        val profileId = proposed.profileId
        var current = metadata
        if (current.credentialSlots.getValue(profileId) == AiCredentialSlot.LEGACY) {
            when (val old = credentialStorage.read(AiCredentialAddress(profileId, AiCredentialSlot.LEGACY))) {
                CredentialReadResult.StorageUnavailable -> return ProfileMutationResult.CredentialStorageUnavailable
                CredentialReadResult.Missing -> Unit
                is CredentialReadResult.Available -> {
                    if (!credentialStorage.write(AiCredentialAddress(profileId, AiCredentialSlot.A), old.apiKey)) {
                        return ProfileMutationResult.CredentialWriteFailed
                    }
                    val migrated = current.copy(
                        credentialSlots = current.credentialSlots + (profileId to AiCredentialSlot.A)
                    )
                    val result = saveMetadataLocked(migrated)
                    if (result != ProfileMutationResult.Success) return result
                    current = migrated
                }
            }
        }

        // Retry even after an in-memory Missing: the previous removal may not have reached disk.
        if (!credentialStorage.delete(AiCredentialAddress(profileId, AiCredentialSlot.LEGACY))) {
            return ProfileMutationResult.CredentialDeleteFailed
        }
        val nextSlot = when (current.credentialSlots.getValue(profileId)) {
            AiCredentialSlot.A -> AiCredentialSlot.B
            AiCredentialSlot.B, AiCredentialSlot.LEGACY -> AiCredentialSlot.A
        }
        if (!credentialStorage.write(AiCredentialAddress(profileId, nextSlot), replacement)) {
            return ProfileMutationResult.CredentialWriteFailed
        }
        return saveProfileLocked(current.copy(
            credentialSlots = current.credentialSlots + (profileId to nextSlot)
        ), profileIndex, proposed)
    }

    suspend fun reinitializeCredentialStorage(): ProfileMutationResult = mutate {
        try {
            credentialStorage.reinitializeStorage()
            ProfileMutationResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProfileMutationResult.CredentialStorageUnavailable
        }
    }

    suspend fun clearAllCredentials(): ProfileMutationResult = mutate {
        clearCredentialsLocked()
    }

    suspend fun deleteAllProfiles(): ProfileMutationResult = mutate {
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        val cleared = clearCredentialsLocked()
        if (cleared != ProfileMutationResult.Success) return@mutate cleared
        saveMetadataLocked(metadata.copy(profiles = emptyList(), activeProfileId = null, credentialSlots = emptyMap()))
    }

    suspend fun deleteProfile(profileId: String): ProfileMutationResult = mutate {
        val metadata = readMetadataLocked() ?: return@mutate ProfileMutationResult.MetadataUnavailable
        if (metadata.profiles.none { it.profileId == profileId }) return@mutate ProfileMutationResult.ProfileNotFound
        // No credential read is needed to delete all known addresses, including an unused slot.
        for (slot in AiCredentialSlot.entries) {
            if (!credentialStorage.delete(AiCredentialAddress(profileId, slot))) {
                return@mutate ProfileMutationResult.CredentialDeleteFailed
            }
        }
        saveMetadataLocked(metadata.copy(
            profiles = metadata.profiles.filterNot { it.profileId == profileId },
            activeProfileId = metadata.activeProfileId.takeUnless { it == profileId },
            credentialSlots = metadata.credentialSlots - profileId
        ))
    }

    private suspend fun clearCredentialsLocked(): ProfileMutationResult = try {
        if (credentialStorage.clearAllCredentials()) ProfileMutationResult.Success
        else ProfileMutationResult.CredentialDeleteFailed
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ProfileMutationResult.CredentialStorageUnavailable
    }

    suspend fun resolveProfile(profileId: String): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked() ?: return@withLock ProfileResolutionResult.StorageUnavailable
        resolveProfileLocked(profileId, metadata)
    }

    /** Endpoint validation precedes decryption, including the legacy compatibility path. */
    suspend fun resolveValidatedProfile(
        profileId: String,
        endpointValidator: (String) -> Unit
    ): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked() ?: return@withLock ProfileResolutionResult.StorageUnavailable
        val profile = metadata.profiles.firstOrNull { it.profileId == profileId }
            ?: return@withLock ProfileResolutionResult.ProfileNotFound
        try {
            endpointValidator(profile.baseUrl)
        } catch (_: IllegalArgumentException) {
            return@withLock ProfileResolutionResult.InvalidEndpoint
        }
        resolveProfileLocked(profileId, metadata)
    }

    suspend fun resolveActiveProfile(): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked() ?: return@withLock ProfileResolutionResult.StorageUnavailable
        val id = metadata.activeProfileId ?: return@withLock ProfileResolutionResult.NoActiveProfile
        resolveProfileLocked(id, metadata)
    }

    suspend fun resolveValidatedActiveProfile(
        endpointValidator: (String) -> Unit
    ): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked() ?: return@withLock ProfileResolutionResult.StorageUnavailable
        val id = metadata.activeProfileId ?: return@withLock ProfileResolutionResult.NoActiveProfile
        val profile = metadata.profiles.firstOrNull { it.profileId == id }
            ?: return@withLock ProfileResolutionResult.ProfileNotFound
        try {
            endpointValidator(profile.baseUrl)
        } catch (_: IllegalArgumentException) {
            return@withLock ProfileResolutionResult.InvalidEndpoint
        }
        resolveProfileLocked(id, metadata)
    }

    private suspend fun readMetadataLocked(): AiProfileMetadataSnapshot? = try {
        when (val result = metadataStore.readMetadata()) {
            is AiProfileMetadataReadResult.Available -> result.snapshot
            AiProfileMetadataReadResult.Unavailable -> null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveProfileLocked(
        profileId: String,
        metadata: AiProfileMetadataSnapshot
    ): ProfileResolutionResult {
        val profile = metadata.profiles.firstOrNull { it.profileId == profileId }
            ?: return ProfileResolutionResult.ProfileNotFound
        val slot = metadata.credentialSlots[profileId] ?: return ProfileResolutionResult.StorageUnavailable
        return when (val credential = credentialStorage.read(AiCredentialAddress(profileId, slot))) {
            is CredentialReadResult.Available -> ProfileResolutionResult.Available(ResolvedAiProfile(
                profileId = profile.profileId,
                providerTemplate = profile.providerTemplate,
                baseUrl = profile.baseUrl,
                modelId = profile.modelId,
                authStrategy = profile.authStrategy,
                temperature = profile.temperature,
                apiKey = credential.apiKey
            ))
            CredentialReadResult.Missing -> ProfileResolutionResult.Missing
            CredentialReadResult.StorageUnavailable -> ProfileResolutionResult.StorageUnavailable
        }
    }

    private suspend fun saveProfileLocked(
        metadata: AiProfileMetadataSnapshot,
        index: Int,
        profile: AiProviderProfile
    ): ProfileMutationResult = saveMetadataLocked(metadata.copy(
        profiles = metadata.profiles.toMutableList().apply { this[index] = profile }
    ))

    private suspend fun saveMetadataLocked(metadata: AiProfileMetadataSnapshot): ProfileMutationResult {
        metadataWriteUncertain = true
        return try {
            metadataStore.saveMetadata(metadata.profiles, metadata.activeProfileId, metadata.credentialSlots)
            metadataWriteUncertain = false
            ProfileMutationResult.Success
        } catch (cancellation: CancellationException) {
            // DataStore's actor may still commit. The inactive slot must not be reused.
            throw cancellation
        } catch (_: IOException) {
            metadataWriteUncertain = false
            ProfileMutationResult.MetadataUnavailable
        } catch (_: Exception) {
            ProfileMutationResult.MetadataUnavailable
        }
    }

    private suspend fun mutate(block: suspend () -> ProfileMutationResult): ProfileMutationResult {
        currentCoroutineContext().ensureActive()
        return applicationScope.async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().ensureActive()
            operationMutex.withLock {
                if (metadataWriteUncertain) ProfileMutationResult.MetadataUnavailable else block()
            }
        }.await()
    }

    private fun AiProviderProfile.normalized(): AiProviderProfile = copy(
        displayName = displayName.trim(),
        baseUrl = baseUrl.trim(),
        modelId = modelId.trim()
    )

    private fun AiProviderProfile.isValid(): Boolean =
        profileId.isNotBlank() && displayName.isNotBlank() && baseUrl.isNotBlank() &&
            modelId.isNotBlank() && temperature.isFinite()

    private fun AiProviderProfile.connectionIdentity(): List<Any> =
        listOf(providerTemplate, baseUrl.trim(), modelId.trim(), authStrategy)
}
