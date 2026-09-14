package io.github.zoot.englishreader.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedAiCredentialStorageAndroidTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        assertTrue(EncryptedAiCredentialStorage(context).clearAllCredentials())
    }

    @After
    fun tearDown() = runBlocking {
        assertTrue(EncryptedAiCredentialStorage(context).clearAllCredentials())
    }

    @Test
    fun writeReadDelete_twoProfilesRemainIsolated() = runBlocking {
        val storage = EncryptedAiCredentialStorage(context)

        assertTrue(storage.write("profile-a", "credential-a"))
        assertTrue(storage.write("profile-b", "credential-b"))
        assertEquals(
            CredentialReadResult.Available("credential-a"),
            storage.read("profile-a")
        )
        assertEquals(
            CredentialReadResult.Available("credential-b"),
            storage.read("profile-b")
        )

        assertTrue(storage.delete("profile-a"))
        assertEquals(CredentialReadResult.Missing, storage.read("profile-a"))
        assertEquals(
            CredentialReadResult.Available("credential-b"),
            storage.read("profile-b")
        )
    }

    @Test
    fun newInstance_readsExistingCredentialAndClearsFromColdStart() = runBlocking {
        val firstInstance = EncryptedAiCredentialStorage(context)
        assertTrue(firstInstance.write("profile-a", "credential-a"))

        val secondInstance = EncryptedAiCredentialStorage(context)
        assertEquals(
            CredentialReadResult.Available("credential-a"),
            secondInstance.read("profile-a")
        )

        val coldStartClear = EncryptedAiCredentialStorage(context)
        assertTrue(coldStartClear.clearAllCredentials())
        assertEquals(
            CredentialReadResult.Missing,
            EncryptedAiCredentialStorage(context).read("profile-a")
        )
    }

    @Test
    fun firstInitialization_removesLegacyGlobalSlot() = runBlocking {
        val preferences = encryptedPreferences()
        assertTrue(preferences.edit().putString(LEGACY_API_KEY, "legacy-credential").commit())

        assertEquals(
            CredentialReadResult.Missing,
            EncryptedAiCredentialStorage(context).read("profile-a")
        )

        assertNull(preferences.getString(LEGACY_API_KEY, null))
    }

    private fun encryptedPreferences() = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private companion object {
        const val PREFS_FILE_NAME = "secure_api_keys"
        const val LEGACY_API_KEY = "ai_api_key"
    }
}
