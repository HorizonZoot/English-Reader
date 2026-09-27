package io.github.zoot.englishreader.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AiProfileMetadataStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        assertEquals(setOf("ai_profiles_json", "active_profile_id", "ai_credential_format_version", "ai_credential_slots_json"), keys)
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

    @Test
    fun readMetadata_bothFormatFieldsMissing_readsLegacyWithoutWriting() = runTest {
        val profiles = listOf(profile("profile-a", "A"), profile("profile-b", "B"))
        val initial = legacyPreferences(profiles)
        val dataStore = InMemoryPreferencesDataStore(initial)
        val store = AiProfileMetadataStore(dataStore, testMoshi())

        val result = store.readMetadata()

        assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(
            profiles, "profile-a", mapOf("profile-a" to AiCredentialSlot.LEGACY, "profile-b" to AiCredentialSlot.LEGACY)
        )), result)
        assertEquals(initial, dataStore.snapshot())
        assertFalse(dataStore.snapshot().asMap().keys.any { it.name == FORMAT_KEY.name || it.name == SLOTS_KEY.name })
    }

    @Test
    fun readMetadata_incompleteOrCorruptVersionedFormat_neverFallsBackToLegacy() = runTest {
        val base = versionedPreferences(listOf(profile("profile-a", "A")), """{"profile-a":"a"}""")
        fun modified(change: MutablePreferences.() -> Unit): Preferences = base.toMutablePreferences().apply(change)
        val cases = listOf(
            "version only" to modified { remove(SLOTS_KEY) },
            "slots only" to modified { remove(FORMAT_KEY) },
            "unknown version" to modified { this[FORMAT_KEY] = 2 },
            "unknown slot" to modified { this[SLOTS_KEY] = """{"profile-a":"future"}""" },
            "missing reference" to modified { this[SLOTS_KEY] = "{}" },
            "extra reference" to modified { this[SLOTS_KEY] = """{"profile-a":"a","other":"legacy"}""" },
            "malformed json" to modified { this[SLOTS_KEY] = "{broken" },
            "null map" to modified { this[SLOTS_KEY] = "null" },
            "array map" to modified { this[SLOTS_KEY] = "[]" },
            "null slot" to modified { this[SLOTS_KEY] = """{"profile-a":null}""" },
            "duplicate slot" to modified { this[SLOTS_KEY] = """{"profile-a":"a","profile-a":"b"}""" },
            "duplicate after null" to modified { this[SLOTS_KEY] = """{"profile-a":null,"profile-a":"a"}""" },
            "null profile element" to modified { this[PROFILES_KEY] = "[null]" },
            "wrong version type" to modified {
                remove(FORMAT_KEY)
                this[stringPreferencesKey(FORMAT_KEY.name)] = "1"
            },
            "wrong map type" to modified {
                remove(SLOTS_KEY)
                this[intPreferencesKey(SLOTS_KEY.name)] = 1
            },
            "duplicate profile" to versionedPreferences(
                listOf(profile("profile-a", "A"), profile("profile-a", "Duplicate")), """{"profile-a":"a"}"""
            ),
            "blank profile" to versionedPreferences(listOf(profile(" ", "A")), """{" ":"a"}""")
        )
        for ((label, preferences) in cases) {
            val dataStore = InMemoryPreferencesDataStore(preferences)
            val store = AiProfileMetadataStore(dataStore, testMoshi())
            assertEquals(label, AiProfileMetadataReadResult.Unavailable, store.readMetadata())
            assertEquals(label, preferences, dataStore.snapshot())
        }
    }

    @Test
    fun saveProfiles_versionedBindings_preservesEverySurvivingSlot() = runTest {
        val profiles = listOf(profile("profile-a", "A"), profile("profile-b", "B"), profile("legacy", "Legacy"))
        val dataStore = InMemoryPreferencesDataStore(versionedPreferences(profiles,
            """{"profile-a":"a","profile-b":"b","legacy":"legacy"}"""))
        val store = AiProfileMetadataStore(dataStore, testMoshi())
        val changed = profiles.reversed().map { it.copy(displayName = "Renamed ${it.displayName}") }

        store.saveProfiles(changed)

        assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(changed, "profile-a", mapOf(
            "profile-a" to AiCredentialSlot.A, "profile-b" to AiCredentialSlot.B, "legacy" to AiCredentialSlot.LEGACY
        ))), store.readMetadata())
        assertEquals(1, dataStore.snapshot()[FORMAT_KEY])
    }

    @Test
    fun setActiveProfileId_versionedBindings_preservesSlotsWhenSelectingAndClearing() = runTest {
        val profiles = listOf(profile("profile-a", "A"), profile("profile-b", "B"))
        val dataStore = InMemoryPreferencesDataStore(versionedPreferences(profiles,
            """{"profile-a":"b","profile-b":"a"}"""))
        val store = AiProfileMetadataStore(dataStore, testMoshi())
        val slots = mapOf("profile-a" to AiCredentialSlot.B, "profile-b" to AiCredentialSlot.A)

        for (active in listOf("profile-b", null)) {
            store.setActiveProfileId(active)
            assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(profiles, active, slots)), store.readMetadata())
        }
    }

    @Test
    fun writers_legacySnapshot_firstWriteSeedsCompleteExplicitReferences() = runTest {
        val profiles = listOf(profile("profile-a", "A"), profile("profile-b", "B"))
        for (writer in listOf("profiles", "metadata", "active")) {
            val dataStore = InMemoryPreferencesDataStore(legacyPreferences(profiles))
            val store = AiProfileMetadataStore(dataStore, testMoshi())
            when (writer) {
                "profiles" -> store.saveProfiles(profiles)
                "metadata" -> store.saveMetadata(profiles, "profile-a")
                "active" -> store.setActiveProfileId("profile-a")
                else -> error("Unknown writer")
            }
            assertEquals(writer, 1, dataStore.snapshot()[FORMAT_KEY])
            assertEquals(writer, AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(profiles, "profile-a")), store.readMetadata())
            assertTrue(writer, dataStore.snapshot()[SLOTS_KEY]?.contains("profile-b") == true)
        }
    }

    @Test
    fun saveMetadata_partialMigrationAndLaterEdits_preservesUntouchedBindings() = runTest {
        val original = listOf(profile("profile-a", "A"), profile("profile-b", "B"))
        val dataStore = InMemoryPreferencesDataStore(legacyPreferences(original))
        val store = AiProfileMetadataStore(dataStore, testMoshi())
        store.saveMetadata(original, "profile-b", mapOf("profile-a" to AiCredentialSlot.A, "profile-b" to AiCredentialSlot.LEGACY))
        val changed = original.map { it.copy(temperature = 0.7) }
        store.saveMetadata(changed, "profile-b")
        assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(changed, "profile-b",
            mapOf("profile-a" to AiCredentialSlot.A, "profile-b" to AiCredentialSlot.LEGACY))), store.readMetadata())

        val withNew = changed + profile("profile-c", "C")
        val slots = mapOf("profile-a" to AiCredentialSlot.B, "profile-b" to AiCredentialSlot.LEGACY, "profile-c" to AiCredentialSlot.A)
        store.saveMetadata(withNew, "profile-c", slots)
        val remaining = withNew.filterNot { it.profileId == "profile-a" }
        store.saveMetadata(remaining, "profile-c")
        assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(remaining, "profile-c", slots - "profile-a")), store.readMetadata())

        store.saveMetadata(emptyList(), null, emptyMap())
        assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(emptyList(), null, emptyMap())), store.readMetadata())
        assertEquals(1, dataStore.snapshot()[FORMAT_KEY])
        assertEquals("{}", dataStore.snapshot()[SLOTS_KEY])
    }

    @Test
    fun saveMetadata_invalidReferenceSet_rejectsBeforePublishingAnyField() = runTest {
        val original = profile("profile-a", "A")
        val dataStore = InMemoryPreferencesDataStore(versionedPreferences(listOf(original), """{"profile-a":"b"}"""))
        val store = AiProfileMetadataStore(dataStore, testMoshi())
        val before = dataStore.snapshot()
        for (slots in listOf(emptyMap(), mapOf("other" to AiCredentialSlot.A),
            mapOf("profile-a" to AiCredentialSlot.A, "other" to AiCredentialSlot.B))) {
            val error = runCatching { store.saveMetadata(listOf(original.copy(displayName = "Changed")), null, slots) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(before, dataStore.snapshot())
        }
    }

    @Test
    fun writers_corruptReferenceMap_refuseToOverwriteUnavailableMetadata() = runTest {
        val profiles = listOf(profile("profile-a", "A"))
        for (writer in listOf("profiles", "metadata", "active")) {
            val initial = versionedPreferences(profiles, "{}")
            val dataStore = InMemoryPreferencesDataStore(initial)
            val store = AiProfileMetadataStore(dataStore, testMoshi())
            val error = runCatching {
                when (writer) {
                    "profiles" -> store.saveProfiles(profiles)
                    "metadata" -> store.saveMetadata(profiles, null, mapOf("profile-a" to AiCredentialSlot.A))
                    "active" -> store.setActiveProfileId(null)
                    else -> error("Unknown writer")
                }
            }.exceptionOrNull()
            assertTrue(writer, error is IOException)
            assertEquals(writer, initial, dataStore.snapshot())
        }
    }

    @Test
    fun readAndWrite_cancelledDataStore_propagatesOriginalCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        val store = AiProfileMetadataStore(FailingPreferencesDataStore(cancellation), testMoshi())

        assertSame(cancellation, runCatching { store.readMetadata() }.exceptionOrNull())
        assertSame(cancellation, runCatching { store.profiles.first() }.exceptionOrNull())
        assertSame(cancellation, runCatching { store.activeProfileId.first() }.exceptionOrNull())
        assertSame(cancellation, runCatching { store.saveMetadata(emptyList(), null) }.exceptionOrNull())
        assertSame(cancellation, runCatching { store.saveProfiles(emptyList()) }.exceptionOrNull())
        assertSame(cancellation, runCatching { store.setActiveProfileId(null) }.exceptionOrNull())
    }

    @Test
    fun saveMetadata_fileReopened_preservesVersionedBindingsAndActiveProfile() = runTest {
        val file = File(temporaryFolder.root, "profiles.preferences_pb")
        val profiles = listOf(profile("profile-a", "A"), profile("profile-b", "B"), profile("legacy", "Legacy"))
        val slots = mapOf("profile-a" to AiCredentialSlot.A, "profile-b" to AiCredentialSlot.B, "legacy" to AiCredentialSlot.LEGACY)
        val firstJob = SupervisorJob()
        val first = AiProfileMetadataStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file }
        ), testMoshi())
        try {
            first.saveMetadata(profiles, "profile-b", slots)
        } finally {
            firstJob.cancelAndJoin()
        }

        val secondJob = SupervisorJob()
        val second = AiProfileMetadataStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file }
        ), testMoshi())
        try {
            val result = second.readMetadata()
            assertEquals(AiProfileMetadataReadResult.Available(AiProfileMetadataSnapshot(profiles, "profile-b", slots)), result)
            assertFalse(result.toString().contains("profile-b"))
            assertFalse(result.toString().contains("example.com"))
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    private fun legacyPreferences(profiles: List<AiProviderProfile>): MutablePreferences = mutablePreferencesOf(
        PROFILES_KEY to profiles.joinToString(prefix = "[", postfix = "]") {
            testMoshi().adapter(AiProviderProfile::class.java).toJson(it)
        },
        ACTIVE_KEY to "profile-a"
    )

    private fun versionedPreferences(profiles: List<AiProviderProfile>, slotsJson: String): MutablePreferences =
        legacyPreferences(profiles).apply {
            this[FORMAT_KEY] = 1
            this[SLOTS_KEY] = slotsJson
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
        private val failure: Throwable
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            throw failure
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw failure
        }
    }

    private companion object {
        val PROFILES_KEY = stringPreferencesKey("ai_profiles_json")
        val ACTIVE_KEY = stringPreferencesKey("active_profile_id")
        val FORMAT_KEY = intPreferencesKey("ai_credential_format_version")
        val SLOTS_KEY = stringPreferencesKey("ai_credential_slots_json")
    }
}
