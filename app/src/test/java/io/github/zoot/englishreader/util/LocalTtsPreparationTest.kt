package io.github.zoot.englishreader.util

import android.app.Application
import io.github.zoot.englishreader.data.tts.TtsModelCatalog
import io.github.zoot.englishreader.data.tts.TtsModelEntry
import io.github.zoot.englishreader.data.tts.TtsModelException
import io.github.zoot.englishreader.data.tts.TtsModelFailure
import io.github.zoot.englishreader.data.tts.TtsModelRepository
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises managed ownership with a fake native factory, not sherpa/AudioTrack or device latency. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalTtsPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val backends = mutableListOf<LocalModelTtsBackend>()
    private val managedModels = CopyOnWriteArrayList<FakeModel>()

    @After fun close() {
        backends.forEach { it.shutdown() }
        managedModels.forEach { assertTrue("Native cleanup must finish before fixture cleanup", it.releaseFinished.await(5, TimeUnit.SECONDS)) }
    }

    @Test fun prepare_sameInstalledModel_reusesNativeInstanceWithoutSynthesizing() {
        val fixture = fixture()
        val models = CopyOnWriteArrayList<FakeModel>()
        val backend = backend(fixture.repository) { entry, directory ->
            assertTrue(File(directory, entry.modelFile).isFile)
            FakeModel(entry).also { models += it }
        }
        awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
        awaitPreparation(backend.prepare(TtsModelCatalog.libritts.voiceId(0)))
        assertEquals(1, models.size)
        assertEquals(0, models.single().generations.get())
        backend.shutdown()
        assertSame(models.single().createdOn, models.single().releases.poll(5, TimeUnit.SECONDS))
    }

    @Test fun prepare_sameTargetWhileLoading_returnsSamePreparationJob() {
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val creates = AtomicInteger()
        val backend = backend(fixture.repository) { entry, _ ->
            creates.incrementAndGet()
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            FakeModel(entry)
        }
        try {
            val first = backend.prepare(TtsModelCatalog.defaultVoiceId)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(first, backend.prepare(TtsModelCatalog.libritts.voiceId(12)))
            finish.countDown()
            awaitPreparation(first)
            assertEquals(1, creates.get())
        } finally {
            finish.countDown()
        }
    }

    @Test fun speak_matchingPreparation_keepsFirstExtractionAndReusesLoadedInstance() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val assetReads = AtomicInteger()
        val fixture = fixture {
            assetReads.incrementAndGet()
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        }
        val creates = AtomicInteger()
        val playbackEntered = CountDownLatch(1)
        val backend = backend(fixture.repository) { entry, _ ->
            creates.incrementAndGet()
            // The property throws before AudioTrack; preparation must never read it.
            FakeModel(entry, playbackEntered)
        }
        try {
            val preparation = backend.prepare(TtsModelCatalog.defaultVoiceId)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            backend.speak("Original sentence.", TtsModelCatalog.defaultVoiceId, 1f) { }
            finish.countDown()
            awaitPreparation(preparation)
            assertTrue(playbackEntered.await(5, TimeUnit.SECONDS))
            assertFalse(requireNotNull(preparation).isCancelled)
            assertEquals(1, assetReads.get())
            assertEquals(1, creates.get())
        } finally {
            finish.countDown()
            backend.stop()
        }
    }

    @Test fun prepare_replacedTargets_doesNotLoadObsoleteQueuedModel() {
        val fixture = fixture()
        runBlocking { fixture.repository.install(TtsModelCatalog.kokoro.id) }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val createdIds = CopyOnWriteArrayList<String>()
        val backend = backend(fixture.repository) { entry, _ ->
            createdIds += entry.id
            if (createdIds.size == 1) {
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
            }
            FakeModel(entry)
        }
        try {
            val first = backend.prepare(TtsModelCatalog.defaultVoiceId)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val obsolete = backend.prepare(TtsModelCatalog.kokoro.voiceId(1))
            val latest = backend.prepare(TtsModelCatalog.defaultVoiceId)
            finish.countDown()
            awaitPreparation(latest)
            assertTrue(requireNotNull(first).isCancelled)
            assertTrue(requireNotNull(obsolete).isCancelled)
            assertEquals(listOf(TtsModelCatalog.libritts.id), createdIds.toList())
        } finally {
            finish.countDown()
        }
    }

    @Test fun shutdown_duringNativeConstruction_releasesOnOriginalExecutorWithoutPlayback() {
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val models = LinkedBlockingQueue<FakeModel>()
        val backend = backend(fixture.repository) { entry, _ ->
            val model = FakeModel(entry)
            models.put(model)
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            model
        }
        try {
            val job = backend.prepare(TtsModelCatalog.defaultVoiceId)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val model = requireNotNull(models.poll(5, TimeUnit.SECONDS))
            backend.shutdown()
            finish.countDown()
            awaitPreparation(job)
            assertTrue(requireNotNull(job).isCancelled)
            assertSame(model.createdOn, model.releases.poll(5, TimeUnit.SECONDS))
            assertEquals(0, model.generations.get())
            assertEquals(1, model.releaseCount.get())
        } finally {
            finish.countDown()
        }
    }

    @Test fun prepare_repairedInstallation_releasesPreviousNativeRevision() {
        val fixture = fixture()
        val models = CopyOnWriteArrayList<FakeModel>()
        val backend = backend(fixture.repository) { entry, _ -> FakeModel(entry).also { models += it } }
        awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
        assertTrue(File(fixture.root, "${TtsModelCatalog.libritts.id}/tokens.txt").delete())
        awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
        assertEquals(2, models.size)
        assertEquals(1, models.first().releaseCount.get())
        assertEquals(0, models.last().releaseCount.get())
        assertTrue(models.all { it.generations.get() == 0 })
    }

    @Test fun prepare_failedNativeLoad_canRetryWithoutSynthesizing() {
        val fixture = fixture()
        val attempts = AtomicInteger()
        val models = CopyOnWriteArrayList<FakeModel>()
        val backend = backend(fixture.repository) { entry, _ ->
            if (attempts.incrementAndGet() == 1) throw TtsModelException(TtsModelFailure.CORRUPT)
            FakeModel(entry).also { models += it }
        }
        awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
        awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
        assertEquals(2, attempts.get())
        assertEquals(1, models.size)
        assertEquals(0, models.single().generations.get())
    }

    @Test fun prepareReading_installedOptionalModelInColdRepository_loadsWithoutRefreshOrDownload() {
        val fixture = fixture()
        runBlocking { fixture.repository.install(TtsModelCatalog.kokoro.id) }
        val client = mockk<OkHttpClient>()
        val network = mockk<NetworkChecker>()
        val reopened = TtsModelRepository(
            fixture.root,
            { error("An installed optional model must not reopen an asset") },
            client,
            Dispatchers.IO,
            fixture.entries.map { if (it.id == TtsModelCatalog.kokoro.id) it.copy(assetPath = null) else it }
        )
        val loaded = CountDownLatch(1)
        val ids = CopyOnWriteArrayList<String>()
        val backend = backend(reopened) { entry, _ ->
            ids += entry.id
            FakeModel(entry).also { loaded.countDown() }
        }
        val voiceId = TtsModelCatalog.kokoro.voiceId(1)
        assertTrue(backend.voices().none { it.id == voiceId })
        val player = TtsPlayer(network, backend) { error("Preparation must not start the system engine") }
        player.prepareReading(TtsReadingSettings(voiceId), false)
        assertTrue(loaded.await(5, TimeUnit.SECONDS))
        awaitPreparation(backend.prepare(voiceId))

        assertEquals(listOf(TtsModelCatalog.kokoro.id), ids.toList())
        assertTrue(managedModels.all { it.generations.get() == 0 })
        verify(exactly = 0) { client.newCall(any()) }
        verify(exactly = 0) { network.isOnline() }
        player.shutdown()
    }

    @Test fun stop_duringNativeConstruction_sameModelSpeechReusesFinishedInstance() {
        val assetReads = AtomicInteger()
        val fixture = fixture { assetReads.incrementAndGet() }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val playbackEntered = CountDownLatch(1)
        val creates = AtomicInteger()
        val backend = backend(fixture.repository) { entry, _ ->
            creates.incrementAndGet()
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            FakeModel(entry, playbackEntered)
        }
        try {
            val preparation = backend.prepare(TtsModelCatalog.defaultVoiceId)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            backend.stop()
            backend.speak("New sentence.", TtsModelCatalog.defaultVoiceId, 1f) { }
            finish.countDown()
            awaitPreparation(preparation)
            assertTrue(requireNotNull(preparation).isCancelled)
            assertTrue(playbackEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, creates.get())
            assertEquals(1, assetReads.get())
        } finally {
            finish.countDown()
            backend.stop()
        }
    }

    @Test fun shutdown_newModelWaitsForPreviousSessionRelease() {
        val secondStorageEntered = CountDownLatch(1)
        val fixture = fixture { path ->
            if (path == "${TtsModelCatalog.kokoro.id}.zip") secondStorageEntered.countDown()
        }
        val releaseEntered = CountDownLatch(1)
        val releaseAllowed = CountDownLatch(1)
        val secondModelCreated = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val backend = backend(fixture.repository) { entry, _ ->
            events += "create:${entry.id}"
            if (entry.id == TtsModelCatalog.kokoro.id) secondModelCreated.countDown()
            FakeModel(entry, beforeRelease = {
                if (entry.id == TtsModelCatalog.libritts.id) {
                    releaseEntered.countDown()
                    check(releaseAllowed.await(5, TimeUnit.SECONDS))
                    events += "release:first"
                }
            })
        }
        try {
            awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
            backend.shutdown()
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
            val next = backend.prepare(TtsModelCatalog.kokoro.voiceId(1))
            assertTrue(secondStorageEntered.await(5, TimeUnit.SECONDS))
            assertFalse(secondModelCreated.await(200, TimeUnit.MILLISECONDS))
            assertTrue(requireNotNull(next).isActive)
            releaseAllowed.countDown()
            awaitPreparation(next)
            assertEquals(listOf("create:${TtsModelCatalog.libritts.id}", "release:first", "create:${TtsModelCatalog.kokoro.id}"), events.toList())
        } finally {
            releaseAllowed.countDown()
        }
    }

    @Test fun shutdown_repeatedWhileReleasePending_preservesPredecessorWaitChain() {
        val intermediateStorageEntered = CountDownLatch(1)
        val fixture = fixture { path ->
            if (path == "${TtsModelCatalog.kokoro.id}.zip") intermediateStorageEntered.countDown()
        }
        val releaseEntered = CountDownLatch(1)
        val releaseAllowed = CountDownLatch(1)
        val nextCreated = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val creates = AtomicInteger()
        val backend = backend(fixture.repository) { entry, _ ->
            val index = creates.incrementAndGet()
            events += "create:$index"
            if (index > 1) nextCreated.countDown()
            FakeModel(entry, beforeRelease = {
                if (index == 1) {
                    releaseEntered.countDown()
                    check(releaseAllowed.await(5, TimeUnit.SECONDS))
                    events += "release:first"
                }
            })
        }
        try {
            awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
            backend.shutdown()
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
            val intermediate = backend.prepare(TtsModelCatalog.kokoro.voiceId(1))
            assertTrue(intermediateStorageEntered.await(5, TimeUnit.SECONDS))
            backend.shutdown()
            val latest = backend.prepare(TtsModelCatalog.kokoro.voiceId(1))
            assertFalse(nextCreated.await(200, TimeUnit.MILLISECONDS))
            assertTrue(requireNotNull(latest).isActive)
            releaseAllowed.countDown()
            awaitPreparation(intermediate)
            awaitPreparation(latest)
            assertTrue(requireNotNull(intermediate).isCancelled)
            assertEquals(listOf("create:1", "release:first", "create:2"), events.toList())
        } finally {
            releaseAllowed.countDown()
        }
    }

    @Test fun shutdown_duringNativeConstruction_failedReleaseBlocksReopenedSession() {
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val creates = AtomicInteger()
        val models = LinkedBlockingQueue<FakeModel>()
        val backend = backend(fixture.repository) { entry, _ ->
            val index = creates.incrementAndGet()
            val model = FakeModel(entry, beforeRelease = {
                if (index == 1) error("Controlled native release failure")
            })
            models.put(model)
            if (index == 1) {
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
            }
            model
        }
        try {
            val first = backend.prepare(TtsModelCatalog.defaultVoiceId)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val model = requireNotNull(models.poll(5, TimeUnit.SECONDS))
            backend.shutdown()
            val next = backend.prepare(TtsModelCatalog.defaultVoiceId)
            finish.countDown()
            awaitPreparation(first)
            awaitPreparation(next)

            assertTrue(requireNotNull(first).isCancelled)
            assertTrue(model.releaseFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, model.releaseCount.get())
            assertEquals(1, creates.get())
        } finally {
            finish.countDown()
        }
    }

    @Test fun prepare_failedModelReleaseBlocksRetriesAndReopenedSession() {
        val fixture = fixture()
        runBlocking { fixture.repository.install(TtsModelCatalog.kokoro.id) }
        val models = CopyOnWriteArrayList<FakeModel>()
        val backend = backend(fixture.repository) { entry, _ ->
            FakeModel(entry, beforeRelease = {
                if (entry.id == TtsModelCatalog.libritts.id) error("Controlled native release failure")
            }).also { models += it }
        }
        awaitPreparation(backend.prepare(TtsModelCatalog.defaultVoiceId))
        awaitPreparation(backend.prepare(TtsModelCatalog.kokoro.voiceId(1)))
        awaitPreparation(backend.prepare(TtsModelCatalog.kokoro.voiceId(1)))
        assertEquals(1, models.size)
        assertEquals(1, models.single().releaseCount.get())

        backend.shutdown()
        awaitPreparation(backend.prepare(TtsModelCatalog.kokoro.voiceId(1)))
        assertEquals(1, models.size)
        assertEquals(1, models.single().releaseCount.get())
    }

    private fun awaitPreparation(job: Job?) = runBlocking {
        withTimeout(5_000) { requireNotNull(job).join() }
    }

    private fun backend(
        repository: TtsModelRepository,
        factory: (TtsModelEntry, File) -> FakeModel
    ) = LocalModelTtsBackend(repository) { entry, directory ->
        factory(entry, directory).also { managedModels += it }
    }.also { backends += it }

    private class FakeModel(
        entry: TtsModelEntry,
        private val playbackEntered: CountDownLatch? = null,
        private val beforeRelease: () -> Unit = {}
    ) : LocalTtsModel {
        private val rate = entry.sampleRate
        val createdOn: Thread = Thread.currentThread()
        val generations = AtomicInteger()
        val releaseCount = AtomicInteger()
        val releases = LinkedBlockingQueue<Thread>()
        val releaseFinished = CountDownLatch(1)
        override val sampleRate: Int get() {
            playbackEntered?.let {
                it.countDown()
                throw IllegalStateException("Controlled audio boundary")
            }
            return rate
        }
        override fun generate(text: String, speaker: Int, rate: Float, callback: (FloatArray) -> Int) {
            generations.incrementAndGet()
            error("Silent preparation must not synthesize")
        }
        override fun release() {
            releaseCount.incrementAndGet()
            try {
                beforeRelease()
                releases.put(Thread.currentThread())
            } finally {
                releaseFinished.countDown()
            }
        }
    }

    private data class Fixture(val root: File, val repository: TtsModelRepository, val entries: List<TtsModelEntry>)

    private fun fixture(onAssetOpen: (String) -> Unit = {}): Fixture {
        val root = temporary.newFolder()
        val archives = mutableMapOf<String, ByteArray>()
        val entries = TtsModelCatalog.entries.map { original ->
            val files = linkedMapOf(
                original.modelFile to "model bytes", "tokens.txt" to "tokens",
                "espeak-ng-data/phondata" to "phonemes", "voices.bin" to "voices"
            )
            val bytes = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    files.forEach { (path, text) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            val assetPath = "${original.id}.zip"
            archives[assetPath] = bytes
            original.copy(
                archiveBytes = bytes.size.toLong(),
                archiveSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                unpackedBytes = files.values.sumOf { it.toByteArray().size.toLong() },
                fileCount = files.size,
                assetPath = assetPath,
                archiveRoot = ""
            )
        }
        val repository = TtsModelRepository(
            root,
            { path -> onAssetOpen(path); ByteArrayInputStream(archives.getValue(path)) },
            OkHttpClient(),
            Dispatchers.IO,
            entries
        )
        return Fixture(root, repository, entries)
    }
}
