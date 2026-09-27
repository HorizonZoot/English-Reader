package io.github.zoot.englishreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Buffer
import java.io.IOException

private val Context.aiProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_profile_metadata"
)

data class AiProfileMetadataSnapshot(
    val profiles: List<AiProviderProfile>,
    val activeProfileId: String?,
    val credentialSlots: Map<String, AiCredentialSlot> = profiles.associate { it.profileId to AiCredentialSlot.LEGACY }
) {
    override fun toString(): String =
        "AiProfileMetadataSnapshot(profileCount=${profiles.size}, activeProfileId=[REDACTED], credentialSlots=[REDACTED])"
}

sealed interface AiProfileMetadataReadResult {
    data class Available(val snapshot: AiProfileMetadataSnapshot) : AiProfileMetadataReadResult

    data object Unavailable : AiProfileMetadataReadResult
}

class AiProfileMetadataStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    moshi: Moshi
) {

    constructor(context: Context, moshi: Moshi) : this(context.aiProfileDataStore, moshi)

    private val profilesAdapter: JsonAdapter<List<AiProviderProfile?>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, AiProviderProfile::class.java)
    )

    private val slotsAdapter: JsonAdapter<Map<String, String>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    val profiles: Flow<List<AiProviderProfile>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[PROFILES_JSON_KEY]?.let(::decodeProfiles) ?: emptyList()
        }

    val activeProfileId: Flow<String?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[ACTIVE_PROFILE_ID_KEY] }

    suspend fun readMetadata(): AiProfileMetadataReadResult {
        return try {
            decodeMetadata(dataStore.data.first())?.let(AiProfileMetadataReadResult::Available)
                ?: AiProfileMetadataReadResult.Unavailable
        } catch (_: IOException) {
            AiProfileMetadataReadResult.Unavailable
        }
    }

    /**
     * 只写 profiles 列表，**不同步** `activeProfileId`。
     *
     * ⚠️ 生产代码请用 [saveMetadata]。若用本方法删掉了当前激活的 profile，
     * `activeProfileId` 会残留指向一个已不存在的 id，读取侧就得额外处理这个悬空引用。
     * 目前仅测试使用它来验证「元数据序列化不含凭据」这一往返性质。
     */
    suspend fun saveProfiles(profiles: List<AiProviderProfile>) {
        dataStore.edit { preferences ->
            val current = requireMetadata(preferences)
            writeMetadata(preferences, profiles, current.activeProfileId, referencesFor(profiles, current))
        }
    }

    suspend fun saveMetadata(
        profiles: List<AiProviderProfile>,
        activeProfileId: String?,
        credentialSlots: Map<String, AiCredentialSlot>? = null
    ) {
        dataStore.edit { preferences ->
            val current = requireMetadata(preferences)
            writeMetadata(preferences, profiles, activeProfileId, credentialSlots ?: referencesFor(profiles, current))
        }
    }

    suspend fun setActiveProfileId(profileId: String?) {
        dataStore.edit { preferences ->
            val current = requireMetadata(preferences)
            writeMetadata(preferences, current.profiles, profileId, current.credentialSlots)
        }
    }

    private fun referencesFor(
        profiles: List<AiProviderProfile>,
        current: AiProfileMetadataSnapshot
    ): Map<String, AiCredentialSlot> = profiles.associate { profile ->
        profile.profileId to (current.credentialSlots[profile.profileId] ?: AiCredentialSlot.LEGACY)
    }

    private fun requireMetadata(preferences: Preferences): AiProfileMetadataSnapshot =
        decodeMetadata(preferences) ?: throw IOException("AI profile metadata is unavailable")

    private fun writeMetadata(
        preferences: MutablePreferences,
        profiles: List<AiProviderProfile>,
        activeProfileId: String?,
        slots: Map<String, AiCredentialSlot>
    ) {
        val ids = profiles.map { it.profileId }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size && slots.keys == ids.toSet()) {
            "Credential references must match the profile set"
        }
        preferences[PROFILES_JSON_KEY] = profilesAdapter.toJson(profiles)
        preferences[CREDENTIAL_FORMAT_KEY] = CREDENTIAL_FORMAT_VERSION
        preferences[CREDENTIAL_SLOTS_KEY] = slotsAdapter.toJson(slots.mapValues { it.value.token })
        if (activeProfileId == null) preferences.remove(ACTIVE_PROFILE_ID_KEY)
        else preferences[ACTIVE_PROFILE_ID_KEY] = activeProfileId
    }

    private fun decodeMetadata(preferences: Preferences): AiProfileMetadataSnapshot? {
        return try {
            val profiles = preferences[PROFILES_JSON_KEY]?.let { decodeProfilesOrNull(it) ?: return null }.orEmpty()
            val ids = profiles.map { it.profileId }
            if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) return null
            val version = preferences[CREDENTIAL_FORMAT_KEY]
            val encodedSlots = preferences[CREDENTIAL_SLOTS_KEY]
            val slots = if (version == null && encodedSlots == null) {
                ids.associateWith { AiCredentialSlot.LEGACY }
            } else {
                if (version != CREDENTIAL_FORMAT_VERSION || encodedSlots == null) return null
                val decoded = decodeSlots(encodedSlots) ?: return null
                if (decoded.keys != ids.toSet()) return null
                decoded
            }
            AiProfileMetadataSnapshot(profiles, preferences[ACTIVE_PROFILE_ID_KEY], slots)
        } catch (_: JsonDataException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    private fun decodeProfiles(json: String): List<AiProviderProfile> =
        decodeProfilesOrNull(json).orEmpty()

    private fun decodeProfilesOrNull(json: String): List<AiProviderProfile>? = try {
        profilesAdapter.fromJson(json)?.let { profiles ->
            if (profiles.any { it == null }) null else profiles.filterNotNull()
        }
    } catch (_: JsonDataException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun decodeSlots(json: String): Map<String, AiCredentialSlot>? =
        JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
            val slots = mutableMapOf<String, AiCredentialSlot>()
            reader.beginObject()
            while (reader.hasNext()) {
                val profileId = reader.nextName()
                if (profileId in slots || reader.peek() != JsonReader.Token.STRING) return@use null
                slots[profileId] = AiCredentialSlot.fromToken(reader.nextString()) ?: return@use null
            }
            reader.endObject()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) null else slots
        }

    private companion object {
        val PROFILES_JSON_KEY = stringPreferencesKey("ai_profiles_json")
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val CREDENTIAL_FORMAT_KEY = intPreferencesKey("ai_credential_format_version")
        val CREDENTIAL_SLOTS_KEY = stringPreferencesKey("ai_credential_slots_json")
        const val CREDENTIAL_FORMAT_VERSION = 1
    }
}
