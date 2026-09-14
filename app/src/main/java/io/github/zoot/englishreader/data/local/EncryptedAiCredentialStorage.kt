package io.github.zoot.englishreader.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface CredentialPreferencesFactory {
    fun create(): CredentialPreferences
}

internal interface CredentialPreferences {
    fun getString(key: String): String?

    fun putString(key: String, value: String): Boolean

    fun remove(key: String): Boolean

    fun clear(): Boolean
}

class EncryptedAiCredentialStorage internal constructor(
    private val preferencesFactory: CredentialPreferencesFactory,
    private val errorLogger: (message: String, error: Throwable?) -> Unit = { _, _ -> }
) : AiCredentialStorage {

    constructor(context: Context) : this(
        preferencesFactory = AndroidCredentialPreferencesFactory(context.applicationContext),
        errorLogger = { message, error ->
            if (error == null) {
                Log.e(TAG, message)
            } else {
                Log.e(TAG, message, error)
            }
        }
    )

    private val stateLock = Any()
    private var state: StorageState = StorageState.Uninitialized

    override suspend fun read(profileId: String): CredentialReadResult = withContext(Dispatchers.IO) {
        val credentialKey = credentialKey(profileId)
        synchronized(stateLock) {
            val preferences = readyPreferencesLocked()
                ?: return@synchronized CredentialReadResult.StorageUnavailable
            try {
                preferences.getString(credentialKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(CredentialReadResult::Available)
                    ?: CredentialReadResult.Missing
            } catch (error: Exception) {
                failStorageLocked("Failed to read an AI credential", error)
                CredentialReadResult.StorageUnavailable
            }
        }
    }

    override suspend fun write(profileId: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val trimmedApiKey = apiKey.trim()
        if (trimmedApiKey.isEmpty()) return@withContext false
        val credentialKey = credentialKey(profileId)
        synchronized(stateLock) {
            val preferences = readyPreferencesLocked() ?: return@synchronized false
            try {
                preferences.putString(credentialKey, trimmedApiKey)
            } catch (error: Exception) {
                failStorageLocked("Failed to write an AI credential", error)
                false
            }
        }
    }

    override suspend fun delete(profileId: String): Boolean = withContext(Dispatchers.IO) {
        val credentialKey = credentialKey(profileId)
        synchronized(stateLock) {
            val preferences = readyPreferencesLocked() ?: return@synchronized false
            try {
                preferences.remove(credentialKey)
            } catch (error: Exception) {
                failStorageLocked("Failed to delete an AI credential", error)
                false
            }
        }
    }

    override suspend fun reinitializeStorage() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            state = StorageState.Uninitialized
        }
    }

    override suspend fun clearAllCredentials(): Boolean = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            if (state === StorageState.Failed) return@synchronized false
            val preferences = readyPreferencesLocked() ?: return@synchronized false
            try {
                preferences.clear()
            } catch (error: Exception) {
                failStorageLocked("Failed to clear AI credentials", error)
                false
            }
        }
    }

    private fun readyPreferencesLocked(): CredentialPreferences? = when (val currentState = state) {
        StorageState.Uninitialized -> initializeLocked()
        is StorageState.Ready -> currentState.preferences
        StorageState.Failed -> null
    }

    private fun initializeLocked(): CredentialPreferences? = try {
        preferencesFactory.create().also { preferences ->
            disposeLegacySlot(preferences)
            state = StorageState.Ready(preferences)
        }
    } catch (error: Exception) {
        failStorageLocked("Failed to initialize encrypted credential storage", error)
        null
    }

    private fun disposeLegacySlot(preferences: CredentialPreferences) {
        try {
            if (!preferences.remove(LEGACY_API_KEY)) {
                errorLogger("Failed to remove the legacy AI credential slot", null)
            }
        } catch (error: Exception) {
            errorLogger("Failed to remove the legacy AI credential slot", error)
        }
    }

    private fun failStorageLocked(message: String, error: Exception) {
        state = StorageState.Failed
        errorLogger(message, error)
    }

    private fun credentialKey(profileId: String): String {
        val normalizedProfileId = profileId.trim()
        require(normalizedProfileId.isNotEmpty()) { "profileId must not be blank" }
        return "$PROFILE_API_KEY_PREFIX$normalizedProfileId"
    }

    private sealed interface StorageState {
        data object Uninitialized : StorageState

        data class Ready(val preferences: CredentialPreferences) : StorageState

        data object Failed : StorageState
    }

    private class AndroidCredentialPreferencesFactory(
        private val context: Context
    ) : CredentialPreferencesFactory {
        override fun create(): CredentialPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val preferences = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return SharedPreferencesCredentialPreferences(preferences)
        }
    }

    private class SharedPreferencesCredentialPreferences(
        private val preferences: SharedPreferences
    ) : CredentialPreferences {
        override fun getString(key: String): String? = preferences.getString(key, null)

        override fun putString(key: String, value: String): Boolean =
            preferences.edit().putString(key, value).commit()

        override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

        override fun clear(): Boolean = preferences.edit().clear().commit()
    }

    private companion object {
        const val TAG = "AiCredentialStorage"
        const val PREFS_FILE_NAME = "secure_api_keys"
        const val LEGACY_API_KEY = "ai_api_key"
        const val PROFILE_API_KEY_PREFIX = "api_key:"
    }
}
