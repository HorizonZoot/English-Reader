package io.github.zoot.englishreader.data.dictionary

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import io.github.zoot.englishreader.data.dao.DictionaryDao
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Tests mutation ownership against controlled transaction outcomes, not SQLite implementation. */
class DictionaryMutationCoordinationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val version = AtomicInteger(1)
    private val assetReads = AtomicInteger()
    private val database = TransactionDao()
    private val mutex = ObservedMutex()
    private val mutationLock = mockk<DictionaryMutationLock>().also { shared ->
        every { shared.mutex } returns mutex
    }
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val assets = mockk<AssetManager>()
        every { context.cacheDir } returns temporaryFolder.newFolder("cache")
        every { context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE) } returns prefs
        every { context.assets } returns assets
        every { prefs.getInt("dict_version", any()) } answers { version.get() }
        every { prefs.getBoolean(any(), any()) } returns true
        every { prefs.edit() } returns editor
        every { editor.putInt("dict_version", any()) } answers { version.set(secondArg()); editor }
        every { assets.open("dict_base.tsv") } answers {
            assetReads.incrementAndGet()
            ByteArrayInputStream("word\tphonetic\tchinese\tenglish\nhello\t\t你好\thello\nread\t\t阅读\tread\n".toByteArray())
        }
        database.seed(20)
    }

    @Test
    fun initialize_thenInstall_serializesBothCommitsAndKeepsExtendedInventory() = runBlocking {
        val assetWrite = CompletableDeferred<Unit>()
        val releaseAssets = CompletableDeferred<Unit>()
        val initializedDao = object : DictionaryDao by database {
            override suspend fun replaceAll(entries: List<DictionaryEntry>) {
                assetWrite.complete(Unit)
                releaseAssets.await()
                database.replaceAll(entries)
            }
        }
        val initialize = launch { repository(initializedDao).ensureInitialized() }
        val installer = installer()
        var installation: kotlinx.coroutines.Job? = null
        try {
            withTimeout(10_000) { assetWrite.await(); mutex.attempts.receive() }
            assertTrue("The shared lock must still cover the assets transaction", mutex.isLocked)
            installation = launch { installer.install() }
            withTimeout(10_000) { mutex.attempts.receive() }
            assertEquals(20, database.committedCount)
            releaseAssets.complete(Unit)
            initialize.join()
            installation.join()

            assertEquals(PACK_ENTRIES, database.committedCount)
            assertEquals(100, version.get())
            assertEquals(DictionaryPackState.Installed(PACK_ENTRIES), installer.state.value)
        } finally {
            releaseAssets.complete(Unit)
            initialize.cancelAndJoin()
            installation?.cancelAndJoin()
        }
    }

    @Test
    fun install_thenInitialize_waitsForCommitAndVersionBeforeReadingInventory() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val initRead = CompletableDeferred<Unit>()
        val installingDao = object : DictionaryDao by database {
            override suspend fun replaceAllStreaming(block: suspend (suspend (List<DictionaryEntry>) -> Unit) -> Unit) {
                database.replaceAllStreaming(block)
                committed.complete(Unit)
                releaseCommit.await()
            }
        }
        val initializingDao = object : DictionaryDao by database {
            override suspend fun getCount(): Int {
                initRead.complete(Unit)
                return database.getCount()
            }
        }
        val installer = installer(installingDao)
        val installation = launch { installer.install() }
        var initialize: kotlinx.coroutines.Job? = null
        try {
            withTimeout(10_000) { committed.await(); mutex.attempts.receive() }
            assertEquals(1, version.get())
            assertTrue(mutex.isLocked)
            initialize = launch(start = CoroutineStart.UNDISPATCHED) { repository(initializingDao).ensureInitialized() }
            withTimeout(10_000) { mutex.attempts.receive() }
            assertFalse(initRead.isCompleted)
            releaseCommit.complete(Unit)
            installation.join()
            initialize.join()

            assertTrue(initRead.isCompleted)
            assertEquals(0, assetReads.get())
            assertEquals(PACK_ENTRIES, database.committedCount)
            assertEquals(100, version.get())
        } finally {
            releaseCommit.complete(Unit)
            installation.cancelAndJoin()
            initialize?.cancelAndJoin()
        }
    }

    @Test
    fun install_cancelledAfterCommit_waitsForTransactionChildrenBeforeSettlingAndUnlocking() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupDone = AtomicBoolean(false)
        val initRead = CompletableDeferred<Unit>()
        val cancellationSeen = CompletableDeferred<Unit>()
        val installingDao = object : DictionaryDao by database {
            override suspend fun replaceAllStreaming(block: suspend (suspend (List<DictionaryEntry>) -> Unit) -> Unit): Unit = coroutineScope {
                val child = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                            cleanupDone.set(true)
                        }
                    }
                }
                try {
                    database.replaceAllStreaming(block)
                    committed.complete(Unit)
                    awaitCancellation()
                } finally {
                    child.cancel()
                }
            }

            override suspend fun getCount(): Int {
                if (committed.isCompleted) assertTrue("Settlement must wait for child cleanup", cleanupDone.get())
                return database.getCount()
            }
        }
        val initializingDao = object : DictionaryDao by database {
            override suspend fun getCount(): Int {
                assertTrue(cleanupDone.get())
                initRead.complete(Unit)
                return database.getCount()
            }
        }
        val installer = installer(installingDao)
        val installation = launch {
            try {
                installer.install()
            } catch (cancellation: CancellationException) {
                cancellationSeen.complete(Unit)
                throw cancellation
            }
        }
        var initialize: kotlinx.coroutines.Job? = null
        try {
            withTimeout(10_000) { committed.await(); mutex.attempts.receive() }
            installation.cancel()
            withTimeout(10_000) { cleanupEntered.await() }
            assertTrue(mutex.isLocked)
            initialize = launch(start = CoroutineStart.UNDISPATCHED) { repository(initializingDao).ensureInitialized() }
            withTimeout(10_000) { mutex.attempts.receive() }
            assertFalse(initRead.isCompleted)
            assertFalse(cancellationSeen.isCompleted)
            releaseCleanup.complete(Unit)
            installation.join()
            initialize.join()

            assertTrue(cancellationSeen.isCompleted)
            assertEquals(0, assetReads.get())
            assertEquals(100, version.get())
            assertEquals(DictionaryPackState.Installed(PACK_ENTRIES), installer.state.value)
        } finally {
            releaseCleanup.complete(Unit)
            installation.cancelAndJoin()
            initialize?.cancelAndJoin()
        }
    }

    @Test
    fun install_commitReturnsCancellation_preservesHigherVersionAndPublishesInventory() = runBlocking {
        version.set(250)
        val cancellation = CancellationException("cancelled after commit")
        val dao = object : DictionaryDao by database {
            override suspend fun replaceAllStreaming(block: suspend (suspend (List<DictionaryEntry>) -> Unit) -> Unit) {
                database.replaceAllStreaming(block)
                throw cancellation
            }
        }
        val installer = installer(dao)

        assertCancellation(cancellation, runCatching { installer.install() }.exceptionOrNull())

        assertEquals(250, version.get())
        assertEquals(PACK_ENTRIES, database.committedCount)
        assertEquals(DictionaryPackState.Installed(PACK_ENTRIES), installer.state.value)
    }

    @Test
    fun install_rollbackCancellation_keepsV1UntilAssetsAreActuallyImported() = runBlocking {
        val cancellation = CancellationException("rollback")
        val dao = object : DictionaryDao by database {
            override suspend fun replaceAllStreaming(block: suspend (suspend (List<DictionaryEntry>) -> Unit) -> Unit) {
                database.replaceAllStreaming { insert ->
                    block(insert)
                    throw cancellation
                }
            }
        }
        val installer = installer(dao)

        assertCancellation(cancellation, runCatching { installer.install() }.exceptionOrNull())

        assertEquals(20, database.committedCount)
        assertNotNull(database.lookup("old1"))
        assertEquals(1, version.get())
        assertEquals(DictionaryPackState.Failed(DictionaryPackFailure.CANCELLED), installer.state.value)
        repository().ensureInitialized()
        assertEquals(1, assetReads.get())
        assertEquals(2, database.committedCount)
        assertNotNull(database.lookup("hello"))
        assertEquals(BuiltInDictionary.VERSION, version.get())
    }

    @Test
    fun install_cancelSettlementReadFails_keepsVersionAndOriginalCancellation() = runBlocking {
        val cancellation = CancellationException("commit returned cancellation")
        val committed = AtomicBoolean(false)
        val dao = object : DictionaryDao by database {
            override suspend fun replaceAllStreaming(block: suspend (suspend (List<DictionaryEntry>) -> Unit) -> Unit) {
                database.replaceAllStreaming(block)
                committed.set(true)
                throw cancellation
            }

            override suspend fun getCount(): Int {
                if (committed.get()) throw IOException("inventory unavailable")
                return database.getCount()
            }
        }
        val installer = installer(dao)

        assertCancellation(cancellation, runCatching { installer.install() }.exceptionOrNull())

        assertEquals(1, version.get())
        assertEquals(DictionaryPackState.Failed(DictionaryPackFailure.CANCELLED), installer.state.value)
        assertEquals(PACK_ENTRIES, database.committedCount)
        repository().ensureInitialized()
        assertEquals(0, assetReads.get())
        assertEquals(PACK_ENTRIES, database.committedCount)
    }

    @Test
    fun install_cancelledWaitingForSharedLock_doesNotReadInventoryOrWaitForSettlement() = runBlocking {
        assertTrue(mutex.tryLock())
        val reads = AtomicInteger()
        val dao = object : DictionaryDao by database {
            override suspend fun getCount(): Int {
                reads.incrementAndGet()
                return database.getCount()
            }
        }
        val installer = installer(dao)
        val operation = launch { installer.install() }
        try {
            withTimeout(10_000) { mutex.attempts.receive() }
            operation.cancel()
            withTimeout(5_000) { operation.join() }
            assertTrue(mutex.isLocked)
            assertEquals(0, reads.get())
            assertEquals(20, database.committedCount)
            assertEquals(1, version.get())
            assertEquals(DictionaryPackState.Failed(DictionaryPackFailure.CANCELLED), installer.state.value)
        } finally {
            mutex.unlock()
            operation.cancelAndJoin()
        }
    }

    @Test
    fun remove_cancelledWaitingForSharedLock_keepsPackAndVersion() = runBlocking {
        database.seed(PACK_ENTRIES)
        version.set(250)
        val installer = installer()
        installer.refreshState()
        assertTrue(mutex.tryLock())
        val operation = launch { installer.remove() }
        try {
            withTimeout(10_000) { mutex.attempts.receive() }
            operation.cancel()
            withTimeout(5_000) { operation.join() }
            assertTrue(mutex.isLocked)
            assertEquals(PACK_ENTRIES, database.committedCount)
            assertEquals(250, version.get())
            assertEquals(DictionaryPackState.Installed(PACK_ENTRIES), installer.state.value)
        } finally {
            mutex.unlock()
            operation.cancelAndJoin()
        }
    }

    private fun repository(dao: DictionaryDao = database) = DictionaryRepository(context, dao, mockk(), mutationLock)

    private fun installer(dao: DictionaryDao = database): DictionaryPackInstaller {
        val csv = buildString {
            append("word,phonetic,definition,translation\n")
            repeat(PACK_ENTRIES) { index -> append("packed$index,,definition $index,释义 $index\n") }
        }
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = mockk(relaxed = true) {
                every { execute() } returns Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(csv.toResponseBody()).build()
            }
        }
        return DictionaryPackInstaller(context, dao, mutationLock, factory)
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue(actual is CancellationException)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    private class ObservedMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        val attempts = Channel<Unit>(Channel.UNLIMITED)
        override suspend fun lock(owner: Any?) {
            attempts.trySend(Unit)
            delegate.lock(owner)
        }
    }

    /** Models atomic replacement and rollback so tests can control the return/cleanup boundary. */
    private class TransactionDao : DictionaryDao {
        private val committed = AtomicReference<Map<String, DictionaryEntry>>(emptyMap())
        private var candidate: MutableMap<String, DictionaryEntry>? = null
        val committedCount: Int get() = committed.get().size

        fun seed(count: Int) {
            committed.set((1..count).associate { "old$it" to DictionaryEntry("old$it", null, "旧释义", null) })
        }

        override suspend fun lookup(word: String) = committed.get()[word]
        override suspend fun getCount() = candidate?.size ?: committedCount
        override suspend fun decodeLiteralEscapes() = 0
        override suspend fun deleteAll() { committed.set(emptyMap()) }
        override suspend fun insertAll(entries: List<DictionaryEntry>) {
            committed.set(committed.get() + entries.associateBy { it.word })
        }
        override suspend fun replaceAll(entries: List<DictionaryEntry>) {
            committed.set(entries.associateBy { it.word })
        }
        override suspend fun replaceAllStreaming(block: suspend (suspend (List<DictionaryEntry>) -> Unit) -> Unit) {
            val next = mutableMapOf<String, DictionaryEntry>()
            candidate = next
            try {
                block { batch -> batch.forEach { next[it.word] = it } }
                committed.set(next.toMap())
            } finally {
                candidate = null
            }
        }
    }

    private companion object {
        const val PACK_ENTRIES = 50_000
    }
}
