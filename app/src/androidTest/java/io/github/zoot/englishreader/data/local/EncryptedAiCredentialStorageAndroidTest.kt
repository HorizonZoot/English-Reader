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

    @Test
    fun versionedSlots_paddedIdsAndSeparateSlots_stayIsolatedAfterReopenAndDelete() = runBlocking {
        val first = EncryptedAiCredentialStorage(context)
        val addresses = listOf("profile-a", " profile-a ", "profile-a:b").flatMap { id ->
            listOf(AiCredentialSlot.A, AiCredentialSlot.B).map { AiCredentialAddress(id, it) }
        }
        addresses.forEachIndexed { index, address -> assertTrue(first.write(address, "credential-$index")) }
        assertTrue(first.write("profile-a", "legacy-credential"))
        val reopened = EncryptedAiCredentialStorage(context)
        addresses.forEachIndexed { index, address ->
            assertEquals(CredentialReadResult.Available("credential-$index"), reopened.read(address))
        }

        assertTrue(reopened.delete(AiCredentialAddress("profile-a", AiCredentialSlot.A)))
        assertTrue(reopened.delete("profile-a"))
        val afterDelete = EncryptedAiCredentialStorage(context)
        assertEquals(CredentialReadResult.Missing, afterDelete.read("profile-a"))
        addresses.forEachIndexed { index, address ->
            val expected = if (index == 0) CredentialReadResult.Missing else CredentialReadResult.Available("credential-$index")
            assertEquals(expected, afterDelete.read(address))
        }
        assertTrue(EncryptedAiCredentialStorage(context).clearAllCredentials())
        val cleared = EncryptedAiCredentialStorage(context)
        addresses.forEach { assertEquals(CredentialReadResult.Missing, cleared.read(it)) }
    }

    @Test
    fun legacyCopyThenRotation_reopenPreservesSlotsAndDeletesAllKnownAddresses() = runBlocking {
        assertTrue(encryptedPreferences().edit().putString("api_key:profile-a", "legacy-credential").commit())
        val storage = EncryptedAiCredentialStorage(context)
        val a = AiCredentialAddress("profile-a", AiCredentialSlot.A)
        val b = AiCredentialAddress("profile-a", AiCredentialSlot.B)
        val other = AiCredentialAddress("profile-b", AiCredentialSlot.A)
        val legacy = storage.read("profile-a") as CredentialReadResult.Available
        assertTrue(storage.write(a, legacy.apiKey))
        assertTrue(storage.delete("profile-a"))
        assertTrue(storage.write(b, "rotated-credential"))
        assertTrue(storage.write(other, "other-credential"))

        val reopened = EncryptedAiCredentialStorage(context)
        assertEquals(CredentialReadResult.Missing, reopened.read("profile-a"))
        assertEquals(CredentialReadResult.Available("legacy-credential"), reopened.read(a))
        assertEquals(CredentialReadResult.Available("rotated-credential"), reopened.read(b))
        for (slot in AiCredentialSlot.entries) {
            assertTrue(reopened.delete(AiCredentialAddress("profile-a", slot)))
        }
        val afterDelete = EncryptedAiCredentialStorage(context)
        for (slot in AiCredentialSlot.entries) {
            assertEquals(CredentialReadResult.Missing, afterDelete.read(AiCredentialAddress("profile-a", slot)))
        }
        assertEquals(CredentialReadResult.Available("other-credential"), afterDelete.read(other))
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
