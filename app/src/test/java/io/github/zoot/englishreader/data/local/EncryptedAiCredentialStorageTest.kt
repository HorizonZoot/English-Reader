package io.github.zoot.englishreader.data.local

import kotlinx.coroutines.CancellationException
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
    fun delete_commitReturnsFalse_reportsFailure() = runTest {
        val key = credentialKey("profile-1")
        val preferences = FakeCredentialPreferences(
            values = mutableMapOf(key to "secret-key"),
            removeResults = mutableMapOf(key to false)
        )
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))

        assertFalse(storage.delete("profile-1"))
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

        assertEquals(CredentialReadResult.Missing, storage.read("profile-1"))
        assertEquals(CredentialReadResult.Missing, storage.read("profile-2"))
        assertFalse(preferences.values.containsKey(LEGACY_KEY))
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
    fun writeReadDelete_allSlotsAndProfiles_remainIndependentAcrossInstances() = runTest {
        val preferences = FakeCredentialPreferences()
        val first = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val addresses = listOf("profile-1", "profile-2").flatMap { profileId ->
            AiCredentialSlot.entries.map { AiCredentialAddress(profileId, it) }
        }
        addresses.forEachIndexed { index, address -> assertTrue(first.write(address, "  key-$index  ")) }
        val reopenedPreferences = FakeCredentialPreferences(preferences.values.toMutableMap())
        val reopened = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(reopenedPreferences))
        addresses.forEachIndexed { index, address ->
            assertEquals(CredentialReadResult.Available("key-$index"), reopened.read(address))
        }

        val removed = AiCredentialAddress("profile-1", AiCredentialSlot.A)
        assertTrue(reopened.delete(removed))
        addresses.forEachIndexed { index, address ->
            val expected = if (address == removed) CredentialReadResult.Missing else CredentialReadResult.Available("key-$index")
            assertEquals(expected, reopened.read(address))
        }
        assertEquals(6, preferences.values.size)
    }

    @Test
    fun stringOverloads_targetOnlyLegacySlot() = runTest {
        val preferences = FakeCredentialPreferences()
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val a = AiCredentialAddress("profile-1", AiCredentialSlot.A)
        val b = AiCredentialAddress("profile-1", AiCredentialSlot.B)
        assertTrue(storage.write(a, "key-a"))
        assertTrue(storage.write(b, "key-b"))
        assertEquals(CredentialReadResult.Missing, storage.read("profile-1"))
        assertTrue(storage.write("profile-1", "legacy-key"))
        assertEquals(CredentialReadResult.Available("legacy-key"), storage.read(" profile-1 "))
        assertEquals(CredentialReadResult.Available("legacy-key"), storage.read(AiCredentialAddress("profile-1", AiCredentialSlot.LEGACY)))
        assertEquals("legacy-key", preferences.values[credentialKey("profile-1")])

        assertTrue(storage.delete("profile-1"))

        assertEquals(CredentialReadResult.Missing, storage.read("profile-1"))
        assertEquals(CredentialReadResult.Available("key-a"), storage.read(a))
        assertEquals(CredentialReadResult.Available("key-b"), storage.read(b))
    }

    @Test
    fun read_missingSlot_doesNotFallBackToAnotherSlotOrGlobalCredential() = runTest {
        val preferences = FakeCredentialPreferences(mutableMapOf(LEGACY_KEY to "global-secret"))
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val address = AiCredentialAddress("profile-1", AiCredentialSlot.A)
        assertEquals(CredentialReadResult.Missing, storage.read(address))
        assertTrue(storage.write("profile-1", "legacy-key"))
        assertTrue(storage.write(AiCredentialAddress("profile-1", AiCredentialSlot.B), "key-b"))

        assertEquals(CredentialReadResult.Missing, storage.read(address))
        assertFalse(preferences.values.containsKey(LEGACY_KEY))
        assertFalse(address.toString().contains("profile-1"))
    }

    @Test
    fun write_profileIdsContainingSeparatorsAndUnicode_doNotCollide() = runTest {
        val preferences = FakeCredentialPreferences()
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val ids = listOf("a", "a:a", "1:a", "api_key:a", "api_key_slot_v1:1:a:a", "配置:😀")
        val addresses = ids.flatMap { id -> AiCredentialSlot.entries.map { AiCredentialAddress(id, it) } }
        addresses.forEachIndexed { index, address -> assertTrue(storage.write(address, "key-$index")) }

        assertEquals(addresses.size, preferences.values.size)
        addresses.forEachIndexed { index, address ->
            assertEquals(CredentialReadResult.Available("key-$index"), storage.read(address))
        }
    }

    @Test
    fun write_versionedPaddedProfileIds_remainDistinctAfterReopen() = runTest {
        val preferences = FakeCredentialPreferences()
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val ids = listOf("p", " p ", "p ", " p", "1:p", "p:a")
        val addresses = ids.flatMap { id -> listOf(AiCredentialSlot.A, AiCredentialSlot.B).map { AiCredentialAddress(id, it) } }
        addresses.forEachIndexed { index, address -> assertTrue(storage.write(address, "key-$index")) }
        val reopened = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(
            FakeCredentialPreferences(preferences.values.toMutableMap())
        ))

        assertEquals(addresses.size, preferences.values.size)
        addresses.forEachIndexed { index, address ->
            assertEquals(CredentialReadResult.Available("key-$index"), reopened.read(address))
        }
        assertTrue(reopened.delete(AiCredentialAddress("p", AiCredentialSlot.A)))
        assertEquals(CredentialReadResult.Available("key-2"), reopened.read(AiCredentialAddress(" p ", AiCredentialSlot.A)))
    }

    @Test
    fun copyLegacyToSlot_deleteLegacyAndReopen_preservesCopiedCredential() = runTest {
        val preferences = FakeCredentialPreferences(mutableMapOf(credentialKey("profile-1") to "old-key"))
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val a = AiCredentialAddress("profile-1", AiCredentialSlot.A)
        val old = storage.read("profile-1") as CredentialReadResult.Available
        assertTrue(storage.write(a, old.apiKey))
        assertTrue(storage.delete("profile-1"))
        val reopened = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(
            FakeCredentialPreferences(preferences.values.toMutableMap())
        ))

        assertEquals(CredentialReadResult.Missing, reopened.read("profile-1"))
        assertEquals(CredentialReadResult.Available("old-key"), reopened.read(a))
        assertEquals(CredentialReadResult.Missing, reopened.read(AiCredentialAddress("profile-1", AiCredentialSlot.B)))
    }

    @Test
    fun clearAllCredentials_fromColdInstance_removesAllSlotsIncludingOrphans() = runTest {
        val preferences = FakeCredentialPreferences()
        val first = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))
        val addresses = listOf("profile-1", "orphan").flatMap { id ->
            AiCredentialSlot.entries.map { AiCredentialAddress(id, it) }
        }
        addresses.forEachIndexed { index, address -> assertTrue(first.write(address, "key-$index")) }
        val cold = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences))

        assertTrue(cold.clearAllCredentials())

        val reopened = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(
            FakeCredentialPreferences(preferences.values.toMutableMap())
        ))
        addresses.forEach { assertEquals(CredentialReadResult.Missing, reopened.read(it)) }
    }

    @Test
    fun operationFailures_logOnlySafeMessagesAndMarkStorageUnavailable() = runTest {
        val privateDetail = "secret-key https://private.example/tenant profile-private model-private"
        for (operation in listOf("read", "write", "delete", "clear")) {
            val preferences = FakeCredentialPreferences()
            val messages = mutableListOf<String>()
            val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences)) { messages += it }
            assertEquals(CredentialReadResult.Missing, storage.read("profile-1"))
            val failure = IllegalStateException(privateDetail)
            when (operation) {
                "read" -> preferences.getFailure = failure
                "write" -> preferences.putFailure = failure
                "delete" -> preferences.removeFailure = failure
                "clear" -> preferences.clearFailure = failure
            }
            val address = AiCredentialAddress("profile-1", AiCredentialSlot.B)
            when (operation) {
                "read" -> assertEquals(CredentialReadResult.StorageUnavailable, storage.read(address))
                "write" -> assertFalse(storage.write(address, "secret-key"))
                "delete" -> assertFalse(storage.delete(address))
                else -> assertFalse(storage.clearAllCredentials())
            }
            assertEquals(1, messages.size)
            listOf("secret-key", "private.example", "profile-private", "model-private", "profile-1").forEach { secret ->
                assertFalse(operation, messages.single().contains(secret))
            }
            assertEquals(CredentialReadResult.StorageUnavailable, storage.read(address))
        }
    }

    @Test
    fun initializationFailure_logsSafeMessageWithoutRawThrowable() = runTest {
        val messages = mutableListOf<String>()
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(
            IllegalStateException("secret-key https://private.example/profile-private")
        )) { messages += it }

        assertEquals(CredentialReadResult.StorageUnavailable, storage.read("profile-1"))

        assertEquals(1, messages.size)
        assertTrue(messages.single().isNotBlank())
        for (privateDetail in listOf("secret-key", "private.example", "profile-private")) {
            assertFalse(messages.single().contains(privateDetail))
        }
    }

    @Test
    fun legacyRemoval_exception_logsSafeMessageAndPreservesProfileAccess() = runTest {
        val preferences = FakeCredentialPreferences(mutableMapOf(credentialKey("profile-1") to "key-one"))
        preferences.removeFailure = IllegalStateException("secret-key https://private.example/profile-private")
        val messages = mutableListOf<String>()
        val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences)) { messages += it }

        assertEquals(CredentialReadResult.Available("key-one"), storage.read("profile-1"))

        assertEquals(1, messages.size)
        for (privateDetail in listOf("secret-key", "private.example", "profile-private", "key-one")) {
            assertFalse(messages.single().contains(privateDetail))
        }
    }

    @Test
    fun initializationCancellation_retriesWithoutLoggingOrMarkingStorageFailed() = runTest {
        for (stage in listOf("factory", "legacy removal")) {
            val cancellation = CancellationException("cancelled")
            val preferences = FakeCredentialPreferences()
            if (stage == "legacy removal") preferences.removeFailure = cancellation
            val factory = if (stage == "factory") {
                QueueCredentialPreferencesFactory(cancellation, preferences)
            } else {
                QueueCredentialPreferencesFactory(preferences, preferences)
            }
            val messages = mutableListOf<String>()
            val storage = EncryptedAiCredentialStorage(factory) { messages += it }
            val address = AiCredentialAddress("profile-1", AiCredentialSlot.A)

            assertTrue(stage, runCatching { storage.read(address) }.exceptionOrNull() is CancellationException)
            preferences.removeFailure = null

            assertEquals(CredentialReadResult.Missing, storage.read(address))
            assertEquals(2, factory.createCount)
            assertTrue(messages.isEmpty())
        }
    }

    @Test
    fun operationCancellation_propagatesWithoutLoggingOrPoisoningStorage() = runTest {
        for (operation in listOf("read", "write", "delete", "clear")) {
            val preferences = FakeCredentialPreferences()
            val messages = mutableListOf<String>()
            val storage = EncryptedAiCredentialStorage(QueueCredentialPreferencesFactory(preferences)) { messages += it }
            val address = AiCredentialAddress("profile-1", AiCredentialSlot.A)
            assertTrue(storage.write(address, "key-a"))
            val cancellation = CancellationException("cancelled")
            when (operation) {
                "read" -> preferences.getFailure = cancellation
                "write" -> preferences.putFailure = cancellation
                "delete" -> preferences.removeFailure = cancellation
                "clear" -> preferences.clearFailure = cancellation
            }

            val failure = runCatching {
                when (operation) {
                    "read" -> storage.read(address)
                    "write" -> storage.write(address, "new-key")
                    "delete" -> storage.delete(address)
                    else -> storage.clearAllCredentials()
                }
            }.exceptionOrNull()

            assertTrue(operation, failure is CancellationException)
            assertTrue(messages.isEmpty())
            preferences.getFailure = null
            preferences.putFailure = null
            preferences.removeFailure = null
            preferences.clearFailure = null
            assertEquals(CredentialReadResult.Available("key-a"), storage.read(address))
        }
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
        private val removeResults: MutableMap<String, Boolean> = mutableMapOf()
    ) : CredentialPreferences {
        var putFailure: RuntimeException? = null
        var removeFailure: RuntimeException? = null
        var clearFailure: RuntimeException? = null
        val removeCalls = mutableListOf<String>()
        var clearCalls: Int = 0
            private set

        override fun getString(key: String): String? {
            getFailure?.let { throw it }
            return values[key]
        }

        override fun putString(key: String, value: String): Boolean {
            putFailure?.let { throw it }
            values[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            removeFailure?.let { throw it }
            removeCalls += key
            val result = removeResults[key] ?: true
            if (result) values.remove(key)
            return result
        }

        override fun clear(): Boolean {
            clearFailure?.let { throw it }
            clearCalls += 1
            values.clear()
            return true
        }
    }

    companion object {
        private const val LEGACY_KEY = "ai_api_key"

        private fun credentialKey(profileId: String): String = "api_key:$profileId"
    }
}
