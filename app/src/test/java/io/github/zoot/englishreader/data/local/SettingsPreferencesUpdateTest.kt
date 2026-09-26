package io.github.zoot.englishreader.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import io.mockk.every
import io.mockk.mockk
import io.github.zoot.englishreader.data.update.PendingUpdateDownload
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsPreferencesUpdateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun checkTime_defaultAndWrite_survivesReopen() = runTest {
        val file = temporary.newFolder().resolve("update.preferences_pb")
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + job), produceFile = { file }
        )
        try {
            val preferences = SettingsPreferences(store)
            assertEquals(0L, preferences.lastUpdateCheckAt.first())
            preferences.setLastUpdateCheckAt(1_800_000_000_000L)
            assertEquals(1_800_000_000_000L, store.data.first()[longPreferencesKey("last_update_check_at")])
        } finally {
            job.cancelAndJoin()
        }
        val reopened = SettingsPreferences(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        )
        assertEquals(1_800_000_000_000L, reopened.lastUpdateCheckAt.first())
    }

    @Test
    fun checkTime_write_preservesOtherPreferences() = runTest {
        val data = kotlinx.coroutines.flow.MutableStateFlow(
            androidx.datastore.preferences.core.preferencesOf(
                androidx.datastore.preferences.core.stringPreferencesKey("theme_option") to ThemeOption.DARK.name
            )
        )
        val store = object : DataStore<Preferences> {
            override val data = data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val preferences = SettingsPreferences(store)
        preferences.setLastUpdateCheckAt(1234L)
        assertEquals(1234L, preferences.lastUpdateCheckAt.first())
        assertEquals(ThemeOption.DARK, preferences.themeOption.first())
    }

    @Test
    fun checkTime_readFailure_defaultsButDoesNotSwallowCancellation() = runTest {
        val store = mockk<DataStore<Preferences>>()
        every { store.data } returns flow { throw IOException() }
        assertEquals(0L, SettingsPreferences(store).lastUpdateCheckAt.first())
        val cancellation = CancellationException("cancel check")
        every { store.data } returns flow { throw cancellation }
        try {
            SettingsPreferences(store).lastUpdateCheckAt.first()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun pendingDownload_survivesReopen() = runTest {
        val file = temporary.newFolder().resolve("download.preferences_pb")
        val job = Job()
        val first = SettingsPreferences(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + job), produceFile = { file }
        ))
        val record = PendingUpdateDownload(42, "v0.1.4-beta")
        try {
            assertNull(first.pendingUpdateDownload.first())
            first.setPendingUpdateDownload(record)
            assertEquals(record, first.pendingUpdateDownload.first())
        } finally {
            job.cancelAndJoin()
        }
        val reopened = SettingsPreferences(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        assertEquals(record, reopened.pendingUpdateDownload.first())
    }

    @Test
    fun pendingDownload_clearRemovesBothFieldsAndPreservesOtherSettings() = runTest {
        val values = kotlinx.coroutines.flow.MutableStateFlow(
            androidx.datastore.preferences.core.preferencesOf(
                androidx.datastore.preferences.core.stringPreferencesKey("theme_option") to ThemeOption.DARK.name,
                longPreferencesKey("update_download_id") to 42L,
                androidx.datastore.preferences.core.stringPreferencesKey("update_download_tag") to "v0.1.4-beta"
            )
        )
        val store = object : DataStore<Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(values.value).also { values.value = it }
        }
        val preferences = SettingsPreferences(store)
        assertEquals(PendingUpdateDownload(42, "v0.1.4-beta"), preferences.pendingUpdateDownload.first())
        preferences.setPendingUpdateDownload(null)
        assertNull(preferences.pendingUpdateDownload.first())
        assertEquals(ThemeOption.DARK, preferences.themeOption.first())
        assertEquals(setOf("theme_option"), values.value.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun pendingDownload_invalidRecordIsRejectedAndReadErrorsPropagate() = runTest {
        val store = mockk<DataStore<Preferences>>()
        val failure = IOException("unavailable")
        every { store.data } returns flow { throw failure }
        val preferences = SettingsPreferences(store)
        for (record in listOf(PendingUpdateDownload(0, "v1.0"), PendingUpdateDownload(1, "../other"))) {
            val rejected = runCatching { preferences.setPendingUpdateDownload(record) }.exceptionOrNull()
            assertTrue(rejected is IllegalArgumentException)
        }
        try {
            preferences.pendingUpdateDownload.first()
            fail("A read failure must not pretend there is no download")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
    }
}
