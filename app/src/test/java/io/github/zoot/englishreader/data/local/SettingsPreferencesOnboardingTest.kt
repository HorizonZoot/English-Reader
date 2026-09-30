package io.github.zoot.englishreader.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsPreferencesOnboardingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun onboardingCompleted_newAndExistingSettingsWithoutFlag_remainIncompleteWithoutWriting() = runTest {
        for (initial in listOf(emptyPreferences(), preferencesOf(
            stringPreferencesKey("theme_option") to ThemeOption.DARK.name,
            booleanPreferencesKey("allow_network_tts") to true
        ))) {
            var writes = 0
            val values = MutableStateFlow(initial)
            val store = object : DataStore<Preferences> {
                override val data = values
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    writes++
                    return transform(values.value).also { values.value = it }
                }
            }
            assertFalse(SettingsPreferences(store).onboardingCompleted.first())
            assertEquals(initial, values.value)
            assertEquals(0, writes)
        }
    }

    @Test
    fun completeOnboarding_firstWrite_survivesStoreReopen() = runTest {
        val file = temporary.newFolder().resolve("onboarding.preferences_pb")
        val job = Job(backgroundScope.coroutineContext[Job])
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + job), produceFile = { file }
        )
        try {
            val preferences = SettingsPreferences(store)
            // DataStore 1.0.0 cannot replace an existing file on Windows; test its first write here.
            preferences.completeOnboarding()
            assertEquals(true, store.data.first()[booleanPreferencesKey("onboarding_completed")])
        } finally {
            job.cancelAndJoin()
        }
        val reopened = SettingsPreferences(PreferenceDataStoreFactory.create(
            scope = backgroundScope, produceFile = { file }
        ))
        assertTrue(reopened.onboardingCompleted.first())
    }

    @Test
    fun completeOnboarding_existingPreferences_preservesOtherSettings() = runTest {
        val initial = preferencesOf(
            stringPreferencesKey("theme_option") to ThemeOption.DARK.name,
            booleanPreferencesKey("allow_network_tts") to true
        )
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(initial)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val preferences = SettingsPreferences(store)
        preferences.completeOnboarding()
        assertTrue(preferences.onboardingCompleted.first())
        assertEquals(
            initial.asMap(),
            store.data.value.asMap().filterKeys { it != booleanPreferencesKey("onboarding_completed") }
        )
    }

    @Test
    fun onboardingCompleted_readFailureAndCancellation_propagateWithoutInventingCompletion() = runTest {
        for (failure in listOf(IOException("unavailable"), CancellationException("cancelled"))) {
            val store = mockk<DataStore<Preferences>> {
                every { data } returns flow { throw failure }
            }
            try {
                SettingsPreferences(store).onboardingCompleted.first()
                fail("Read failure must reach the startup boundary")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
        }
    }

    @Test
    fun completeOnboarding_writeFailureAndCancellation_propagateWithoutChangingFlag() = runTest {
        for (failure in listOf(IOException("unavailable"), CancellationException("cancelled"))) {
            val store = mockk<DataStore<Preferences>> {
                every { data } returns MutableStateFlow(emptyPreferences())
                coEvery { updateData(any()) } throws failure
            }
            val preferences = SettingsPreferences(store)
            try {
                preferences.completeOnboarding()
                fail("Write failure must reach the startup boundary")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertFalse(preferences.onboardingCompleted.first())
        }
    }
}
