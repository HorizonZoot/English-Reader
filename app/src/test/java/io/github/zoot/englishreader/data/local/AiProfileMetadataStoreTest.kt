package io.github.zoot.englishreader.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiProfileMetadataStoreTest {

    @Test
    fun saveProfiles_roundTripsMetadataWithoutCredentials() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = AiProfileMetadataStore(dataStore, testMoshi())
        val profiles = listOf(
            profile("00000000-0000-0000-0000-000000000001", "DeepSeek")
        )

        store.saveProfiles(profiles)

        assertEquals(profiles, store.profiles.first())
        assertFalse(dataStore.snapshot().asMap().values.any { it.toString().contains("apiKey") })
        assertFalse(dataStore.snapshot().toString().contains("secret-key"))
    }

    @Test
    fun activeProfileId_isStoredSeparatelyAndCanBeCleared() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = AiProfileMetadataStore(dataStore, testMoshi())

        store.setActiveProfileId("00000000-0000-0000-0000-000000000001")

        assertEquals(
            "00000000-0000-0000-0000-000000000001",
            store.activeProfileId.first()
        )
        assertTrue(dataStore.snapshot().asMap().keys.any { it.name == "active_profile_id" })

        store.setActiveProfileId(null)

        assertNull(store.activeProfileId.first())
    }

    @Test
    fun profiles_andActiveProfileId_useDedicatedKeys() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = AiProfileMetadataStore(dataStore, testMoshi())
        val profile = profile("00000000-0000-0000-0000-000000000002", "Kimi")

        store.saveProfiles(listOf(profile))
        store.setActiveProfileId(profile.profileId)

        val keys = dataStore.snapshot().asMap().keys.map { it.name }.toSet()
        assertEquals(setOf("ai_profiles_json", "active_profile_id"), keys)
    }

    @Test
    fun dataStoreReadFailure_emitsSafeEmptyMetadata() = runTest {
        val store = AiProfileMetadataStore(
            FailingPreferencesDataStore(IOException("disk unavailable")),
            testMoshi()
        )

        assertEquals(emptyList<AiProviderProfile>(), store.profiles.first())
        assertNull(store.activeProfileId.first())
        assertEquals(AiProfileMetadataReadResult.Unavailable, store.readMetadata())
    }

    @Test
    fun corruptedProfilesJson_emitsEmptyProfileList() = runTest {
        val brokenPreferences = mutablePreferencesOf(
            stringPreferencesKey("ai_profiles_json") to "{broken-json"
        )
        val store = AiProfileMetadataStore(
            InMemoryPreferencesDataStore(brokenPreferences),
            testMoshi()
        )

        assertEquals(emptyList<AiProviderProfile>(), store.profiles.first())
        assertEquals(AiProfileMetadataReadResult.Unavailable, store.readMetadata())
    }

    private fun testMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

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

    private class InMemoryPreferencesDataStore(
        initialPreferences: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initialPreferences)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                state.value = transform(state.value)
                state.value
            }

        fun snapshot(): Preferences = state.value
    }

    private class FailingPreferencesDataStore(
        private val failure: IOException
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            throw failure
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw failure
        }
    }
}
