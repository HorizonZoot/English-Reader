package io.github.zoot.englishreader.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import io.github.zoot.englishreader.model.TtsReadingSettings
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsPreferencesTtsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun inMemoryDataStore(initial: Preferences = emptyPreferences()): DataStore<Preferences> {
        val stored = MutableStateFlow(initial)
        return object : DataStore<Preferences> {
            override val data = stored
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(stored.value).also { stored.value = it }
        }
    }

    @Test
    fun allowNetworkTts_defaultAndFirstWrite_persistExplicitConsent() = runTest {
        val file = temporary.newFolder().resolve("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val preferences = SettingsPreferences(store)
        assertFalse(preferences.allowNetworkTts.first())

        preferences.setAllowNetworkTts(true)
        assertTrue(SettingsPreferences(store).allowNetworkTts.first())
        assertEquals(ThemeOption.DEFAULT, preferences.themeOption.first())
    }

    @Test
    fun setAllowNetworkTts_disabled_updatesConsentAndPreservesOtherSettings() = runTest {
        val store = inMemoryDataStore(preferencesOf(
            booleanPreferencesKey("allow_network_tts") to true,
            stringPreferencesKey("theme_option") to ThemeOption.DARK.name
        ))
        val preferences = SettingsPreferences(store)
        preferences.setAllowNetworkTts(false)
        assertFalse(preferences.allowNetworkTts.first())
        assertEquals(ThemeOption.DARK, preferences.themeOption.first())
    }

    @Test
    fun readingVoice_defaultsNormalizationAndReset_preserveConsentAndAppearance() = runTest {
        val store = inMemoryDataStore()
        val preferences = SettingsPreferences(store)
        assertEquals(TtsReadingSettings(), preferences.ttsReadingSettings.first())
        preferences.setAllowNetworkTts(true)
        preferences.setThemeOption(ThemeOption.DARK)
        preferences.setTtsVoiceId("engine/voice")
        preferences.setTtsSpeechRate(1.26f)
        assertEquals(TtsReadingSettings("engine/voice", 1.3f), SettingsPreferences(store).ttsReadingSettings.first())
        preferences.clearTtsVoiceIf("old/voice")
        assertEquals("engine/voice", preferences.ttsReadingSettings.first().voiceId)
        preferences.clearTtsVoiceIf("engine/voice")
        assertEquals(null, preferences.ttsReadingSettings.first().voiceId)
        preferences.resetReadingVoiceSettings()
        assertEquals(TtsReadingSettings(), preferences.ttsReadingSettings.first())
        assertTrue(preferences.allowNetworkTts.first())
        assertEquals(ThemeOption.DARK, preferences.themeOption.first())
    }

    @Test
    fun readingVoice_corruptedRateAndReadErrors_normalizeWithoutSwallowingCancellation() = runTest {
        for ((value, expected) in listOf(-1f to 0.5f, 3f to 2f, Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f, 1.21f to 1.2f)) {
            val store = mockk<DataStore<Preferences>> {
                every { data } returns MutableStateFlow(preferencesOf(floatPreferencesKey("reading_tts_rate") to value))
            }
            assertEquals(expected, SettingsPreferences(store).ttsReadingSettings.first().speechRate)
        }
        val store = mockk<DataStore<Preferences>> { every { data } returns flow { throw IOException() } }
        assertEquals(TtsReadingSettings(), SettingsPreferences(store).ttsReadingSettings.first())
        val cancellation = CancellationException()
        every { store.data } returns flow { throw cancellation }
        try {
            SettingsPreferences(store).ttsReadingSettings.first()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun readingVoice_rateFirstWrite_persistsToDataStore() = runTest {
        val file = temporary.newFolder().resolve("voice.preferences_pb")
        val storeJob = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + storeJob)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            SettingsPreferences(store).setTtsSpeechRate(1.8f)
        } finally {
            storeJob.cancelAndJoin()
        }
        val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        assertEquals(1.8f, SettingsPreferences(reopened).ttsReadingSettings.first().speechRate)
    }

    @Test
    fun allowNetworkTts_readIoFailure_defaultsToNoConsent() = runTest {
        val store = mockk<DataStore<Preferences>> {
            every { data } returns flow { throw IOException() }
        }
        assertFalse(SettingsPreferences(store).allowNetworkTts.first())
    }

    @Test
    fun allowNetworkTts_cancelledRead_propagatesCancellation() = runTest {
        val cancellation = CancellationException()
        val store = mockk<DataStore<Preferences>> {
            every { data } returns flow { throw cancellation }
        }
        try {
            SettingsPreferences(store).allowNetworkTts.first()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
