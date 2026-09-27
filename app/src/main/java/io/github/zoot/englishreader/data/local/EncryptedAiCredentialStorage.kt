package io.github.zoot.englishreader.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private val errorLogger: (message: String) -> Unit = {}
) : AiCredentialStorage {

    constructor(context: Context) : this(
        preferencesFactory = AndroidCredentialPreferencesFactory(context.applicationContext),
        errorLogger = { message -> Log.e(TAG, message) }
    )

    private val stateLock = Any()
    private var state: StorageState = StorageState.Uninitialized

    override suspend fun read(address: AiCredentialAddress): CredentialReadResult = withContext(Dispatchers.IO) {
        val credentialKey = credentialKey(address)
        synchronized(stateLock) {
            val preferences = readyPreferencesLocked()
                ?: return@synchronized CredentialReadResult.StorageUnavailable
            try {
                preferences.getString(credentialKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(CredentialReadResult::Available)
                    ?: CredentialReadResult.Missing
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failStorageLocked("Failed to read an AI credential")
                CredentialReadResult.StorageUnavailable
            }
        }
    }

    override suspend fun write(address: AiCredentialAddress, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        val trimmedApiKey = apiKey.trim()
        if (trimmedApiKey.isEmpty()) return@withContext false
        val credentialKey = credentialKey(address)
        synchronized(stateLock) {
            val preferences = readyPreferencesLocked() ?: return@synchronized false
            try {
                preferences.putString(credentialKey, trimmedApiKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failStorageLocked("Failed to write an AI credential")
                false
            }
        }
    }

    override suspend fun delete(address: AiCredentialAddress): Boolean = withContext(Dispatchers.IO) {
        val credentialKey = credentialKey(address)
        synchronized(stateLock) {
            val preferences = readyPreferencesLocked() ?: return@synchronized false
            try {
                preferences.remove(credentialKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failStorageLocked("Failed to delete an AI credential")
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failStorageLocked("Failed to clear AI credentials")
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
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        failStorageLocked("Failed to initialize encrypted credential storage")
        null
    }

    private fun disposeLegacySlot(preferences: CredentialPreferences) {
        try {
            if (!preferences.remove(LEGACY_API_KEY)) {
                errorLogger("Failed to remove the legacy AI credential slot")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            errorLogger("Failed to remove the legacy AI credential slot")
        }
    }

    private fun failStorageLocked(message: String) {
        state = StorageState.Failed
        errorLogger(message)
    }

    private fun credentialKey(address: AiCredentialAddress): String {
        val id = address.profileId
        return if (address.slot == AiCredentialSlot.LEGACY) "$PROFILE_API_KEY_PREFIX${id.trim()}"
        else "$PROFILE_SLOT_PREFIX${id.length}:$id:${address.slot.token}"
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

    internal class SharedPreferencesCredentialPreferences(
        private val preferences: SharedPreferences
    ) : CredentialPreferences {
        override fun getString(key: String): String? = preferences.getString(key, null)

        override fun putString(key: String, value: String): Boolean =
            commit { putString(key, value) }

        override fun remove(key: String): Boolean = commit { remove(key) }

        override fun clear(): Boolean = commit { clear() }

        private fun commit(change: SharedPreferences.Editor.() -> Unit): Boolean {
            // API 24 may return true for a no-op retry without flushing a prior failed write.
            val marker = if (preferences.getString(COMMIT_MARKER, null) == "0") "1" else "0"
            return preferences.edit().apply(change).putString(COMMIT_MARKER, marker).commit()
        }

        private companion object {
            const val COMMIT_MARKER = "credential_commit_marker_v1"
        }
    }

    private companion object {
        const val TAG = "AiCredentialStorage"
        const val PREFS_FILE_NAME = "secure_api_keys"
        const val LEGACY_API_KEY = "ai_api_key"
        const val PROFILE_API_KEY_PREFIX = "api_key:"
        const val PROFILE_SLOT_PREFIX = "api_key_slot_v1:"
    }
}
