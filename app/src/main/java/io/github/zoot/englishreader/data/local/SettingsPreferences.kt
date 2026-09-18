package io.github.zoot.englishreader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zoot.englishreader.model.TtsReadingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * DataStore 扩展属性（顶层定义，确保单例）
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * 阅读正文字号档位（三档预设）。
 *
 * 只作用于阅读页正文（[io.github.zoot.englishreader.ui.component.InteractiveText]），
 * 不改系统级组件字号。
 */
enum class FontSizeOption(val sizeSp: Int) {
    SMALL(16),
    MEDIUM(18),
    LARGE(22);

    companion object {
        val DEFAULT = MEDIUM

        fun fromName(name: String?): FontSizeOption =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 主题模式：跟随系统 / 固定亮色 / 固定暗色。
 */
enum class ThemeOption {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        val DEFAULT = SYSTEM

        fun fromName(name: String?): ThemeOption =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

enum class ReadingMode {
    SCROLL, PAGED;

    companion object {
        val DEFAULT = SCROLL

        fun fromName(name: String?): ReadingMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 应用设置存储（非敏感数据）。
 *
 * 使用 DataStore 存储字号档位与主题模式。API Key 属敏感数据，
 * 单独存于 [AiCredentialStorage]（EncryptedSharedPreferences），不放这里。
 */
class SettingsPreferences internal constructor(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.settingsDataStore)

    companion object {
        private val FONT_SIZE_KEY = stringPreferencesKey("font_size_option")
        private val THEME_KEY = stringPreferencesKey("theme_option")
        private val READING_MODE_KEY = stringPreferencesKey("reading_mode")
        private val ALLOW_NETWORK_TTS_KEY = booleanPreferencesKey("allow_network_tts")
        private val TTS_VOICE_KEY = stringPreferencesKey("reading_tts_voice")
        private val TTS_RATE_KEY = floatPreferencesKey("reading_tts_rate")
        private val LAST_UPDATE_CHECK_KEY = longPreferencesKey("last_update_check_at")
    }

    // DataStore 读盘失败会抛 IOException（官方约定），.catch 回退默认值而非让异常
    // 传播到 stateIn/顶层 collect 导致崩溃；非 IO 异常仍上抛以免掩盖真实 bug。
    val fontSizeOption: Flow<FontSizeOption> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            FontSizeOption.fromName(preferences[FONT_SIZE_KEY])
        }

    val themeOption: Flow<ThemeOption> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            ThemeOption.fromName(preferences[THEME_KEY])
        }

    val readingMode: Flow<ReadingMode> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences -> ReadingMode.fromName(preferences[READING_MODE_KEY]) }

    val allowNetworkTts: Flow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences -> preferences[ALLOW_NETWORK_TTS_KEY] ?: false }

    val ttsReadingSettings: Flow<TtsReadingSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            TtsReadingSettings(
                voiceId = preferences[TTS_VOICE_KEY],
                speechRate = preferences[TTS_RATE_KEY] ?: TtsReadingSettings.DEFAULT_RATE
            ).normalized()
        }

    suspend fun setTtsVoiceId(voiceId: String?) {
        dataStore.edit { preferences ->
            if (voiceId.isNullOrBlank()) preferences.remove(TTS_VOICE_KEY)
            else preferences[TTS_VOICE_KEY] = voiceId
        }
    }

    suspend fun clearTtsVoiceIf(expectedId: String) {
        dataStore.edit { preferences ->
            if (preferences[TTS_VOICE_KEY] == expectedId) preferences.remove(TTS_VOICE_KEY)
        }
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { preferences -> preferences[TTS_RATE_KEY] = TtsReadingSettings.normalizeRate(rate) }
    }

    suspend fun resetReadingVoiceSettings() {
        dataStore.edit { preferences ->
            preferences.remove(TTS_VOICE_KEY)
            preferences.remove(TTS_RATE_KEY)
        }
    }

    suspend fun setAllowNetworkTts(allowed: Boolean) {
        dataStore.edit { preferences -> preferences[ALLOW_NETWORK_TTS_KEY] = allowed }
    }

    /**
     * 上次**成功**完成更新检查的时刻（epoch millis）；`0` 表示从未检查过。
     *
     * 只负责存取，24h 的判断在 `UpdateRepository`——这里不该知道节流窗口有多长。
     */
    val lastUpdateCheckAt: Flow<Long> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences -> preferences[LAST_UPDATE_CHECK_KEY] ?: 0L }

    suspend fun setLastUpdateCheckAt(epochMillis: Long) {
        dataStore.edit { preferences -> preferences[LAST_UPDATE_CHECK_KEY] = epochMillis }
    }

    suspend fun setReadingMode(mode: ReadingMode) {
        dataStore.edit { preferences -> preferences[READING_MODE_KEY] = mode.name }
    }

    suspend fun setFontSizeOption(option: FontSizeOption) {
        dataStore.edit { preferences ->
            preferences[FONT_SIZE_KEY] = option.name
        }
    }

    suspend fun setThemeOption(option: ThemeOption) {
        dataStore.edit { preferences ->
            preferences[THEME_KEY] = option.name
        }
    }
}
