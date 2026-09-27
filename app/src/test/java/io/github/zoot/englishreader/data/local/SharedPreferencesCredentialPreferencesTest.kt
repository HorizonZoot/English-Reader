package io.github.zoot.englishreader.data.local

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesCredentialPreferencesTest {
    @Test
    fun remove_failedWriteThenNoOpRetry_persistsDeletion() {
        val raw = Api24Preferences(mapOf("api_key:first" to "first-key", "api_key:second" to "second-key"))
        val adapter = EncryptedAiCredentialStorage.SharedPreferencesCredentialPreferences(raw)
        raw.failNextWrite = true

        assertFalse(adapter.remove("api_key:first"))
        assertNull(adapter.getString("api_key:first"))
        assertTrue(adapter.remove("api_key:first"))

        val reopened = raw.reopen()
        assertNull(reopened.getString("api_key:first", null))
        assertEquals("second-key", reopened.getString("api_key:second", null))
    }

    @Test
    fun clear_failedWriteThenNoOpRetry_doesNotRestoreAnyCredential() {
        val keys = listOf("api_key:profile", "api_key_slot_v1:7:profile:a", "api_key_slot_v1:7:profile:b")
        val raw = Api24Preferences(keys.associateWith { "stored-key" })
        val adapter = EncryptedAiCredentialStorage.SharedPreferencesCredentialPreferences(raw)
        raw.failNextWrite = true

        assertFalse(adapter.clear())
        assertTrue(adapter.clear())

        val reopened = raw.reopen()
        keys.forEach { assertNull("Credential must stay deleted after reopening: $it", reopened.getString(it, null)) }
    }

    @Test
    fun putString_failedWriteThenSameValueRetry_persistsNewValue() {
        val raw = Api24Preferences(mapOf("api_key:profile" to "old-key"))
        val adapter = EncryptedAiCredentialStorage.SharedPreferencesCredentialPreferences(raw)
        raw.failNextWrite = true

        assertFalse(adapter.putString("api_key:profile", "new-key"))
        assertEquals("new-key", adapter.getString("api_key:profile"))
        assertTrue(adapter.putString("api_key:profile", "new-key"))

        assertEquals("new-key", raw.reopen().getString("api_key:profile", null))
    }

    @Test
    fun remove_twoFailedWritesThenMissingRetry_persistsDeletion() {
        val raw = Api24Preferences(mapOf("api_key:profile" to "stored-key"))
        val adapter = EncryptedAiCredentialStorage.SharedPreferencesCredentialPreferences(raw)
        repeat(2) {
            raw.failNextWrite = true
            assertFalse(adapter.remove("api_key:profile"))
            assertEquals("stored-key", raw.reopen().getString("api_key:profile", null))
        }

        assertTrue(adapter.remove("api_key:profile"))
        assertNull(raw.reopen().getString("api_key:profile", null))
    }

    @Test
    fun clear_commitCancellation_propagatesAndRetryPersistsMissingKeys() {
        val raw = Api24Preferences(mapOf("api_key:profile" to "stored-key"))
        val adapter = EncryptedAiCredentialStorage.SharedPreferencesCredentialPreferences(raw)
        raw.throwNextWrite = CancellationException("interrupted commit")

        assertTrue(runCatching { adapter.clear() }.exceptionOrNull() is CancellationException)
        assertEquals("stored-key", raw.reopen().getString("api_key:profile", null))
        assertTrue(adapter.clear())
        assertNull(raw.reopen().getString("api_key:profile", null))
    }

    @Test
    fun remove_markerReadFailure_doesNotReportDeletionOrChangeDisk() {
        val raw = Api24Preferences(mapOf("api_key:profile" to "stored-key"))
        val adapter = EncryptedAiCredentialStorage.SharedPreferencesCredentialPreferences(raw)
        raw.markerReadFailure = IllegalStateException("marker unavailable")

        assertTrue(runCatching { adapter.remove("api_key:profile") }.exceptionOrNull() is IllegalStateException)
        assertEquals("stored-key", raw.reopen().getString("api_key:profile", null))
    }

    /** Models AOSP Android 7.0: memory changes precede disk I/O; a no-change commit skips disk. */
    private class Api24Preferences(initial: Map<String, Any>) : SharedPreferences {
        private val disk = initial.toMutableMap()
        private val memory = initial.toMutableMap()
        var failNextWrite = false
        var throwNextWrite: RuntimeException? = null
        var markerReadFailure: RuntimeException? = null

        fun reopen() = Api24Preferences(disk)

        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun getString(key: String, defValue: String?): String? {
            if (key == "credential_commit_marker_v1") markerReadFailure?.let { throw it }
            return memory[key] as? String ?: defValue
        }
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (memory[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = memory[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = memory[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = memory[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = memory[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = memory.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clearRequested = true }
            override fun apply() { commit() }

            override fun commit(): Boolean {
                var changed = false
                if (clearRequested && memory.isNotEmpty()) {
                    memory.clear()
                    changed = true
                }
                changes.forEach { (key, value) ->
                    if (value == null) {
                        if (memory.remove(key) != null) changed = true
                    } else if (memory[key] != value) {
                        memory[key] = value
                        changed = true
                    }
                }
                clearRequested = false
                changes.clear()
                if (!changed) return true
                throwNextWrite?.let {
                    throwNextWrite = null
                    throw it
                }
                if (failNextWrite) {
                    failNextWrite = false
                    return false
                }
                disk.clear()
                disk.putAll(memory)
                return true
            }
        }
    }
}
