package io.github.zoot.englishreader.data.local

enum class AiCredentialSlot(val token: String) {
    LEGACY("legacy"), A("a"), B("b");

    companion object {
        fun fromToken(token: String?): AiCredentialSlot? = entries.firstOrNull { it.token == token }
    }
}

data class AiCredentialAddress(val profileId: String, val slot: AiCredentialSlot) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }

    override fun toString(): String = "AiCredentialAddress(profileId=[REDACTED], slot=$slot)"
}

sealed interface CredentialReadResult {
    data class Available(val apiKey: String) : CredentialReadResult {
        override fun toString(): String = "Available(apiKey=[REDACTED])"
    }

    data object Missing : CredentialReadResult

    data object StorageUnavailable : CredentialReadResult
}

interface AiCredentialStorage {
    suspend fun read(address: AiCredentialAddress): CredentialReadResult

    suspend fun write(address: AiCredentialAddress, apiKey: String): Boolean

    suspend fun delete(address: AiCredentialAddress): Boolean

    suspend fun read(profileId: String): CredentialReadResult = read(AiCredentialAddress(profileId, AiCredentialSlot.LEGACY))

    suspend fun write(profileId: String, apiKey: String): Boolean =
        write(AiCredentialAddress(profileId, AiCredentialSlot.LEGACY), apiKey)

    suspend fun delete(profileId: String): Boolean = delete(AiCredentialAddress(profileId, AiCredentialSlot.LEGACY))

    suspend fun reinitializeStorage()

    suspend fun clearAllCredentials(): Boolean
}
