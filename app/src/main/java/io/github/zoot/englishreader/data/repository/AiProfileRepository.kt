package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiCredentialStorage
import io.github.zoot.englishreader.data.local.AiProfileMetadataReadResult
import io.github.zoot.englishreader.data.local.AiProfileMetadataSnapshot
import io.github.zoot.englishreader.data.local.AiProfileMetadataStore
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.CredentialReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val operationMutex: Mutex = Mutex()
) {

    val profiles: Flow<List<AiProviderProfile>> = metadataStore.profiles
    val activeProfileId: Flow<String?> = metadataStore.activeProfileId

    suspend fun createProfile(
        profile: AiProviderProfile,
        apiKey: String
    ): ProfileMutationResult = operationMutex.withLock {
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileMutationResult.MetadataUnavailable
        val currentProfiles = metadata.profiles
        if (currentProfiles.any { it.profileId == profile.profileId }) {
            return@withLock ProfileMutationResult.DuplicateProfile
        }
        if (!credentialStorage.write(profile.profileId, apiKey)) {
            return@withLock ProfileMutationResult.CredentialWriteFailed
        }

        val nextActiveProfileId = if (currentProfiles.isEmpty()) {
            profile.profileId
        } else {
            metadata.activeProfileId
        }
        try {
            metadataStore.saveMetadata(currentProfiles + profile, nextActiveProfileId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            credentialStorage.delete(profile.profileId)
            return@withLock ProfileMutationResult.MetadataUnavailable
        }
        ProfileMutationResult.Success
    }

    suspend fun selectActiveProfile(profileId: String): ProfileMutationResult =
        operationMutex.withLock {
            val metadata = readMetadataLocked()
                ?: return@withLock ProfileMutationResult.MetadataUnavailable
            if (metadata.profiles.none { it.profileId == profileId }) {
                return@withLock ProfileMutationResult.ProfileNotFound
            }
            try {
                metadataStore.setActiveProfileId(profileId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@withLock ProfileMutationResult.MetadataUnavailable
            }
            ProfileMutationResult.Success
        }

    suspend fun rotateApiKey(
        profileId: String,
        apiKey: String
    ): ProfileMutationResult = operationMutex.withLock {
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileMutationResult.MetadataUnavailable
        if (metadata.profiles.none { it.profileId == profileId }) {
            return@withLock ProfileMutationResult.ProfileNotFound
        }
        if (!credentialStorage.write(profileId, apiKey)) {
            ProfileMutationResult.CredentialWriteFailed
        } else {
            ProfileMutationResult.Success
        }
    }

    suspend fun updateProfileSettings(
        profileId: String,
        displayName: String,
        temperature: Double
    ): ProfileMutationResult = operationMutex.withLock {
        val normalizedDisplayName = displayName.trim()
        if (normalizedDisplayName.isBlank() || !temperature.isFinite()) {
            return@withLock ProfileMutationResult.InvalidProfile
        }
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileMutationResult.MetadataUnavailable
        val profileIndex = metadata.profiles.indexOfFirst { it.profileId == profileId }
        if (profileIndex < 0) return@withLock ProfileMutationResult.ProfileNotFound

        val updatedProfiles = metadata.profiles.toMutableList().apply {
            this[profileIndex] = this[profileIndex].copy(
                displayName = normalizedDisplayName,
                temperature = temperature
            )
        }
        try {
            metadataStore.saveMetadata(updatedProfiles, metadata.activeProfileId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock ProfileMutationResult.MetadataUnavailable
        }
        ProfileMutationResult.Success
    }

    /**
     * 整体替换一份完整的 profile 快照，同时保持其稳定的 [AiProviderProfile.profileId] 不变。
     *
     * connection identity 一旦改变，就必须随带一份替换用的凭据。凭据先于元数据持久化被替换时，
     * 若元数据写入失败则回滚为此前的凭据。已存储的凭据明文绝不越出本 repository 边界。
     */
    suspend fun updateProfile(
        proposedProfile: AiProviderProfile,
        replacementApiKey: String?
    ): ProfileMutationResult = operationMutex.withLock {
        val normalizedProfile = proposedProfile.copy(
            displayName = proposedProfile.displayName.trim(),
            baseUrl = proposedProfile.baseUrl.trim(),
            modelId = proposedProfile.modelId.trim()
        )
        if (normalizedProfile.profileId.isBlank() || normalizedProfile.displayName.isBlank() ||
            normalizedProfile.baseUrl.isBlank() || normalizedProfile.modelId.isBlank() ||
            !normalizedProfile.temperature.isFinite()
        ) {
            return@withLock ProfileMutationResult.InvalidProfile
        }

        val metadata = readMetadataLocked()
            ?: return@withLock ProfileMutationResult.MetadataUnavailable
        val profileIndex = metadata.profiles.indexOfFirst {
            it.profileId == normalizedProfile.profileId
        }
        if (profileIndex < 0) return@withLock ProfileMutationResult.ProfileNotFound

        val existingProfile = metadata.profiles[profileIndex]
        val identityChanged = existingProfile.connectionIdentity() !=
            normalizedProfile.connectionIdentity()
        val normalizedReplacementKey = replacementApiKey?.trim().orEmpty()
        if (identityChanged && normalizedReplacementKey.isBlank()) {
            return@withLock ProfileMutationResult.ReplacementCredentialRequired
        }

        val previousCredential = if (normalizedReplacementKey.isNotBlank()) {
            when (val credential = credentialStorage.read(normalizedProfile.profileId)) {
                is CredentialReadResult.Available -> credential
                CredentialReadResult.Missing -> CredentialReadResult.Missing
                CredentialReadResult.StorageUnavailable ->
                    return@withLock ProfileMutationResult.CredentialStorageUnavailable
            }
        } else {
            null
        }
        if (previousCredential is CredentialReadResult.Available &&
            previousCredential.apiKey == normalizedReplacementKey
        ) {
            return@withLock saveUpdatedProfileMetadata(
                metadata = metadata,
                profileIndex = profileIndex,
                normalizedProfile = normalizedProfile
            )
        }

        if (normalizedReplacementKey.isNotBlank() &&
            !credentialStorage.write(normalizedProfile.profileId, normalizedReplacementKey)
        ) {
            return@withLock ProfileMutationResult.CredentialWriteFailed
        }

        val updatedProfiles = metadata.profiles.toMutableList().apply {
            this[profileIndex] = normalizedProfile
        }
        try {
            metadataStore.saveMetadata(updatedProfiles, metadata.activeProfileId)
        } catch (cancellation: CancellationException) {
            if (previousCredential != null) {
                withContext(NonCancellable) {
                    restoreCredential(normalizedProfile.profileId, previousCredential)
                }
            }
            throw cancellation
        } catch (_: Exception) {
            if (previousCredential != null) {
                restoreCredential(normalizedProfile.profileId, previousCredential)
            }
            return@withLock ProfileMutationResult.MetadataUnavailable
        }
        ProfileMutationResult.Success
    }

    suspend fun reinitializeCredentialStorage(): ProfileMutationResult = operationMutex.withLock {
        try {
            credentialStorage.reinitializeStorage()
            ProfileMutationResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProfileMutationResult.CredentialStorageUnavailable
        }
    }

    suspend fun clearAllCredentials(): ProfileMutationResult = operationMutex.withLock {
        try {
            if (credentialStorage.clearAllCredentials()) {
                ProfileMutationResult.Success
            } else {
                ProfileMutationResult.CredentialDeleteFailed
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProfileMutationResult.CredentialStorageUnavailable
        }
    }

    suspend fun deleteAllProfiles(): ProfileMutationResult = operationMutex.withLock {
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileMutationResult.MetadataUnavailable
        if (metadata.profiles.isEmpty() && metadata.activeProfileId == null) {
            return@withLock ProfileMutationResult.Success
        }
        val credentialsCleared = try {
            credentialStorage.clearAllCredentials()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock ProfileMutationResult.CredentialStorageUnavailable
        }
        if (!credentialsCleared) {
            return@withLock ProfileMutationResult.CredentialDeleteFailed
        }
        try {
            metadataStore.saveMetadata(emptyList(), null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock ProfileMutationResult.MetadataUnavailable
        }
        ProfileMutationResult.Success
    }

    suspend fun deleteProfile(profileId: String): ProfileMutationResult =
        operationMutex.withLock {
            val metadata = readMetadataLocked()
                ?: return@withLock ProfileMutationResult.MetadataUnavailable
            val currentProfiles = metadata.profiles
            if (currentProfiles.none { it.profileId == profileId }) {
                return@withLock ProfileMutationResult.ProfileNotFound
            }
            if (!credentialStorage.delete(profileId)) {
                return@withLock ProfileMutationResult.CredentialDeleteFailed
            }

            // 凭据已删但元数据写失败时**不做回滚**：留下一个凭据为 Missing 的 profile，
            // 用户经「替换 API Key」即可恢复。曾经的回滚（先读出明文 key、失败时写回）是个
            // 不完整且不可观察的伪事务：write 的结果无从上报、协程取消与进程崩溃都会绕过它，
            // 而为回滚所做的预读会让「凭据已损坏但仍想删掉该 profile」的用户彻底删不掉。
            val nextActiveProfileId = metadata.activeProfileId.takeUnless { it == profileId }
            try {
                metadataStore.saveMetadata(
                    profiles = currentProfiles.filterNot { it.profileId == profileId },
                    activeProfileId = nextActiveProfileId
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@withLock ProfileMutationResult.MetadataUnavailable
            }
            ProfileMutationResult.Success
        }

    suspend fun resolveProfile(profileId: String): ProfileResolutionResult =
        operationMutex.withLock { resolveProfileLocked(profileId) }

    /**
     * 先校验仅存在于元数据中的 endpoint 字段，再解析 profile。
     *
     * 校验器在 [AiCredentialStorage.read] 之前运行，因此 cleartext 或格式非法的 endpoint
     * 不会作为副作用触发一次凭据访问或网络请求。
     */
    suspend fun resolveValidatedProfile(
        profileId: String,
        endpointValidator: (String) -> Unit
    ): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileResolutionResult.StorageUnavailable
        val profile = metadata.profiles.firstOrNull { it.profileId == profileId }
            ?: return@withLock ProfileResolutionResult.ProfileNotFound
        try {
            endpointValidator(profile.baseUrl)
        } catch (invalidEndpoint: IllegalArgumentException) {
            return@withLock ProfileResolutionResult.InvalidEndpoint
        }
        resolveProfileLocked(profileId, metadata)
    }

    suspend fun resolveActiveProfile(): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileResolutionResult.StorageUnavailable
        val profileId = metadata.activeProfileId
            ?: return@withLock ProfileResolutionResult.NoActiveProfile
        resolveProfileLocked(profileId, metadata)
    }

    /**
     * 从同一份元数据快照中解析出活跃 profile，在解密凭据之前先校验其 endpoint，
     * 并返回一份不可变的启动时刻 profile 快照。
     */
    suspend fun resolveValidatedActiveProfile(
        endpointValidator: (String) -> Unit
    ): ProfileResolutionResult = operationMutex.withLock {
        val metadata = readMetadataLocked()
            ?: return@withLock ProfileResolutionResult.StorageUnavailable
        val profileId = metadata.activeProfileId
            ?: return@withLock ProfileResolutionResult.NoActiveProfile
        val profile = metadata.profiles.firstOrNull { it.profileId == profileId }
            ?: return@withLock ProfileResolutionResult.ProfileNotFound
        try {
            endpointValidator(profile.baseUrl)
        } catch (_: IllegalArgumentException) {
            return@withLock ProfileResolutionResult.InvalidEndpoint
        }
        resolveProfileLocked(profileId, metadata)
    }

    private suspend fun readMetadataLocked(): AiProfileMetadataSnapshot? =
        when (val metadata = metadataStore.readMetadata()) {
            is AiProfileMetadataReadResult.Available -> metadata.snapshot
            AiProfileMetadataReadResult.Unavailable -> null
        }

    private suspend fun resolveProfileLocked(profileId: String): ProfileResolutionResult {
        val metadata = readMetadataLocked()
            ?: return ProfileResolutionResult.StorageUnavailable
        return resolveProfileLocked(profileId, metadata)
    }

    private suspend fun resolveProfileLocked(
        profileId: String,
        metadata: AiProfileMetadataSnapshot
    ): ProfileResolutionResult {
        val profile = metadata.profiles
            .firstOrNull { it.profileId == profileId }
            ?: return ProfileResolutionResult.ProfileNotFound
        return when (val credential = credentialStorage.read(profileId)) {
            is CredentialReadResult.Available -> ProfileResolutionResult.Available(
                ResolvedAiProfile(
                    profileId = profile.profileId,
                    providerTemplate = profile.providerTemplate,
                    baseUrl = profile.baseUrl,
                    modelId = profile.modelId,
                    authStrategy = profile.authStrategy,
                    temperature = profile.temperature,
                    apiKey = credential.apiKey
                )
            )

            CredentialReadResult.Missing -> ProfileResolutionResult.Missing
            CredentialReadResult.StorageUnavailable -> ProfileResolutionResult.StorageUnavailable
        }
    }

    private suspend fun restoreCredential(
        profileId: String,
        previousCredential: CredentialReadResult
    ) {
        when (previousCredential) {
            is CredentialReadResult.Available ->
                credentialStorage.write(profileId, previousCredential.apiKey)
            CredentialReadResult.Missing -> credentialStorage.delete(profileId)
            CredentialReadResult.StorageUnavailable -> Unit
        }
    }

    private suspend fun saveUpdatedProfileMetadata(
        metadata: AiProfileMetadataSnapshot,
        profileIndex: Int,
        normalizedProfile: AiProviderProfile
    ): ProfileMutationResult {
        val updatedProfiles = metadata.profiles.toMutableList().apply {
            this[profileIndex] = normalizedProfile
        }
        return try {
            metadataStore.saveMetadata(updatedProfiles, metadata.activeProfileId)
            ProfileMutationResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProfileMutationResult.MetadataUnavailable
        }
    }

    private fun AiProviderProfile.connectionIdentity(): List<Any> = listOf(
        providerTemplate,
        baseUrl.trim(),
        modelId.trim(),
        authStrategy
    )
}
