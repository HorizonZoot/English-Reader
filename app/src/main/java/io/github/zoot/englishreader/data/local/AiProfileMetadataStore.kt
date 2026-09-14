package io.github.zoot.englishreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.aiProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_profile_metadata"
)

data class AiProfileMetadataSnapshot(
    val profiles: List<AiProviderProfile>,
    val activeProfileId: String?
)

sealed interface AiProfileMetadataReadResult {
    data class Available(val snapshot: AiProfileMetadataSnapshot) : AiProfileMetadataReadResult

    data object Unavailable : AiProfileMetadataReadResult
}

class AiProfileMetadataStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    moshi: Moshi
) {

    constructor(context: Context, moshi: Moshi) : this(context.aiProfileDataStore, moshi)

    private val profilesAdapter: JsonAdapter<List<AiProviderProfile>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, AiProviderProfile::class.java)
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
            val preferences = dataStore.data.first()
            val profilesJson = preferences[PROFILES_JSON_KEY]
            val decodedProfiles = if (profilesJson == null) {
                emptyList()
            } else {
                decodeProfilesOrNull(profilesJson)
                    ?: return AiProfileMetadataReadResult.Unavailable
            }
            AiProfileMetadataReadResult.Available(
                AiProfileMetadataSnapshot(
                    profiles = decodedProfiles,
                    activeProfileId = preferences[ACTIVE_PROFILE_ID_KEY]
                )
            )
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
            preferences[PROFILES_JSON_KEY] = profilesAdapter.toJson(profiles)
        }
    }

    suspend fun saveMetadata(
        profiles: List<AiProviderProfile>,
        activeProfileId: String?
    ) {
        dataStore.edit { preferences ->
            preferences[PROFILES_JSON_KEY] = profilesAdapter.toJson(profiles)
            if (activeProfileId == null) {
                preferences.remove(ACTIVE_PROFILE_ID_KEY)
            } else {
                preferences[ACTIVE_PROFILE_ID_KEY] = activeProfileId
            }
        }
    }

    suspend fun setActiveProfileId(profileId: String?) {
        dataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(ACTIVE_PROFILE_ID_KEY)
            } else {
                preferences[ACTIVE_PROFILE_ID_KEY] = profileId
            }
        }
    }

    private fun decodeProfiles(json: String): List<AiProviderProfile> =
        decodeProfilesOrNull(json).orEmpty()

    private fun decodeProfilesOrNull(json: String): List<AiProviderProfile>? = try {
        profilesAdapter.fromJson(json)
    } catch (_: JsonDataException) {
        null
    } catch (_: IOException) {
        null
    }

    private companion object {
        val PROFILES_JSON_KEY = stringPreferencesKey("ai_profiles_json")
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
    }
}
