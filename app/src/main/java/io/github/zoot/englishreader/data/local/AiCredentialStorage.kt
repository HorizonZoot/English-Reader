package io.github.zoot.englishreader.data.local

sealed interface CredentialReadResult {
    data class Available(val apiKey: String) : CredentialReadResult {
        override fun toString(): String = "Available(apiKey=[REDACTED])"
    }

    data object Missing : CredentialReadResult

    data object StorageUnavailable : CredentialReadResult
}

interface AiCredentialStorage {
    suspend fun read(profileId: String): CredentialReadResult

    suspend fun write(profileId: String, apiKey: String): Boolean

    suspend fun delete(profileId: String): Boolean

    suspend fun reinitializeStorage()

    suspend fun clearAllCredentials(): Boolean
}
