package io.github.zoot.englishreader.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EncryptedAiCredentialStorageTest {

    @Test
    fun read_firstAccess_initializesOnceAndReturnsMissing() = runTest {
        val preferences = FakeCredentialPreferences()
        val factory = QueueCredentialPreferencesFactory(preferences)
        val storage = EncryptedAiCredentialStorage(factory)

        assertSame(CredentialReadResult.Missing, storage.read("profile-1"))
        assertSame(CredentialReadResult.Missing, storage.read("profile-2"))

        assertEquals(1, factory.createCount)
        assertEquals(listOf(LEGACY_KEY), preferences.removeCalls)
    }

    @Test
    fun read_existingCredential_returnsAvailableWithRedactedToString() = runTest {
        val preferences = FakeCredentialPreferences(
            values = mutableMapOf(credentialKey("profile-1") to "secret-key")
        )
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))

        val result = storage.read("profile-1")

        assertEquals(CredentialReadResult.Available("secret-key"), result)
        assertFalse(result.toString().contains("secret-key"))
    }

    @Test
    fun read_initializationFails_reportsUnavailableUntilReinitialized() = runTest {
        val preferences = FakeCredentialPreferences()
        val factory = QueueCredentialPreferencesFactory(
            IllegalStateException("keystore unavailable"),
            preferences
        )
        val storage = EncryptedAiCredentialStorage(factory)

        assertSame(CredentialReadResult.StorageUnavailable, storage.read("profile-1"))
        assertSame(CredentialReadResult.StorageUnavailable, storage.read("profile-1"))
        assertEquals(1, factory.createCount)

        storage.reinitializeStorage()

        assertSame(CredentialReadResult.Missing, storage.read("profile-1"))
        assertEquals(2, factory.createCount)
    }

    @Test
    fun read_existingEntryThrows_reportsUnavailableNotMissing() = runTest {
        val preferences = FakeCredentialPreferences(
            values = mutableMapOf(credentialKey("profile-1") to "secret-key"),
            getFailure = IllegalStateException("decrypt failed")
        )
        val storage = EncryptedAiCredentialStorage(
            QueueCredentialPreferencesFactory(preferences, preferences)
        )

        assertSame(CredentialReadResult.StorageUnavailable, storage.read("profile-1"))
        preferences.getFailure = null
        assertSame(CredentialReadResult.StorageUnavailable, storage.read("profile-1"))

        storage.reinitializeStorage()

        assertEquals(CredentialReadResult.Available("secret-key"), storage.read("profile-1"))
    }

    @Test
    fun write_multipleProfiles_keepsCredentialsIsolatedAndTrimsValues() = runTest {
        val preferences = FakeCredentialPreferences()
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))

        assertTrue(storage.write("profile-1", "  key-one  "))
        assertTrue(storage.write("profile-2", "key-two"))

        assertEquals(CredentialReadResult.Available("key-one"), storage.read("profile-1"))
        assertEquals(CredentialReadResult.Available("key-two"), storage.read("profile-2"))
        assertEquals("key-one", preferences.values[credentialKey("profile-1")])
        assertEquals("key-two", preferences.values[credentialKey("profile-2")])
    }

    @Test
    fun write_blankCredential_rejectsWithoutInitializingStorage() = runTest {
        val factory = QueueCredentialPreferencesFactory(FakeCredentialPreferences())
        val storage = EncryptedAiCredentialStorage(factory)

        assertFalse(storage.write("profile-1", "   "))

        assertEquals(0, factory.createCount)
    }

    @Test
    fun delete_commitReturnsFalse_preservesCredential() = runTest {
        val key = credentialKey("profile-1")
        val preferences = FakeCredentialPreferences(
            values = mutableMapOf(key to "secret-key"),
            removeResults = mutableMapOf(key to false)
        )
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))

        assertFalse(storage.delete("profile-1"))

        assertEquals("secret-key", preferences.values[key])
    }

    @Test
    fun clearAllCredentials_fromUninitialized_initializesAndClearsEverything() = runTest {
        val preferences = FakeCredentialPreferences(
            values = mutableMapOf(
                credentialKey("profile-1") to "key-one",
                credentialKey("profile-2") to "key-two",
                LEGACY_KEY to "legacy-key"
            )
        )
        val factory = QueueCredentialPreferencesFactory(preferences)
        val storage = EncryptedAiCredentialStorage(factory)

        assertTrue(storage.clearAllCredentials())

        assertTrue(preferences.values.isEmpty())
        assertEquals(1, preferences.clearCalls)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun clearAllCredentials_fromFailed_refusesWithoutRetryingInitialization() = runTest {
        val factory = QueueCredentialPreferencesFactory(
            IllegalStateException("keystore unavailable"),
            FakeCredentialPreferences()
        )
        val storage = EncryptedAiCredentialStorage(factory)

        assertSame(CredentialReadResult.StorageUnavailable, storage.read("profile-1"))
        assertFalse(storage.clearAllCredentials())

        assertEquals(1, factory.createCount)
    }

    @Test
    fun reinitializeStorage_fromReady_recreatesWithoutDestroyingCredentials() = runTest {
        val values = mutableMapOf(credentialKey("profile-1") to "secret-key")
        val first = FakeCredentialPreferences(values)
        val second = FakeCredentialPreferences(values)
        val factory = QueueCredentialPreferencesFactory(first, second)
        val storage = EncryptedAiCredentialStorage(factory)

        assertEquals(CredentialReadResult.Available("secret-key"), storage.read("profile-1"))

        storage.reinitializeStorage()

        assertEquals(CredentialReadResult.Available("secret-key"), storage.read("profile-1"))
        assertEquals(2, factory.createCount)
        assertEquals("secret-key", values[credentialKey("profile-1")])
    }

    @Test
    fun legacyRemoval_failureDoesNotBlockAndRetriesAfterReinitialize() = runTest {
        val values = mutableMapOf(
            credentialKey("profile-1") to "secret-key",
            LEGACY_KEY to "legacy-key"
        )
        val first = FakeCredentialPreferences(
            values = values,
            removeResults = mutableMapOf(LEGACY_KEY to false)
        )
        val second = FakeCredentialPreferences(values)
        val storage = EncryptedAiCredentialStorage(
            QueueCredentialPreferencesFactory(first, second)
        )

        assertEquals(CredentialReadResult.Available("secret-key"), storage.read("profile-1"))
        assertEquals("legacy-key", values[LEGACY_KEY])

        storage.reinitializeStorage()

        assertEquals(CredentialReadResult.Available("secret-key"), storage.read("profile-1"))
        assertFalse(values.containsKey(LEGACY_KEY))
    }

    @Test
    fun concurrentFirstReads_initializeOnlyOnce() = runTest {
        val preferences = FakeCredentialPreferences()
        val createCount = AtomicInteger()
        val initializationStarted = CountDownLatch(1)
        val releaseInitialization = CountDownLatch(1)
        val readsStarted = CountDownLatch(20)
        val storage = EncryptedAiCredentialStorage(
            CredentialPreferencesFactory {
                createCount.incrementAndGet()
                initializationStarted.countDown()
                check(releaseInitialization.await(15, TimeUnit.SECONDS))
                preferences
            }
        )

        val reads = List(20) { index ->
            async(Dispatchers.IO) {
                readsStarted.countDown()
                storage.read("profile-$index")
            }
        }
        try {
            assertTrue(initializationStarted.await(5, TimeUnit.SECONDS))
            assertTrue(readsStarted.await(5, TimeUnit.SECONDS))
            assertEquals(1, createCount.get())
        } finally {
            releaseInitialization.countDown()
        }

        val results = reads.awaitAll()
        assertTrue(results.all { it === CredentialReadResult.Missing })
        assertEquals(1, createCount.get())
    }

    private class QueueCredentialPreferencesFactory(
        vararg results: Any
    ) : CredentialPreferencesFactory {
        private val queue = ArrayDeque(results.toList())
        var createCount: Int = 0
            private set

        override fun create(): CredentialPreferences {
            createCount += 1
            return when (val result = queue.removeFirst()) {
                is Throwable -> throw result
                is CredentialPreferences -> result
                else -> error("Unsupported factory result: $result")
            }
        }
    }

    private class FakeCredentialPreferences(
        val values: MutableMap<String, String> = mutableMapOf(),
        var getFailure: RuntimeException? = null,
        private val removeResults: MutableMap<String, Boolean> = mutableMapOf(),
        private var putResult: Boolean = true,
        private var clearResult: Boolean = true
    ) : CredentialPreferences {
        val removeCalls = mutableListOf<String>()
        var clearCalls: Int = 0
            private set

        override fun getString(key: String): String? {
            getFailure?.let { throw it }
            return values[key]
        }

        override fun putString(key: String, value: String): Boolean {
            if (putResult) values[key] = value
            return putResult
        }

        override fun remove(key: String): Boolean {
            removeCalls += key
            val result = removeResults[key] ?: true
            if (result) values.remove(key)
            return result
        }

        override fun clear(): Boolean {
            clearCalls += 1
            if (clearResult) values.clear()
            return clearResult
        }
    }

    companion object {
        private const val LEGACY_KEY = "ai_api_key"

        private fun credentialKey(profileId: String): String = "api_key:$profileId"
    }
}
