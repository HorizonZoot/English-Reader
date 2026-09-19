package io.github.zoot.englishreader.data.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsModelRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files = linkedMapOf(
        "model.onnx" to "model bytes", "tokens.txt" to "tokens",
        "espeak-ng-data/phondata" to "phonemes", "espeak-ng-data/voices/!v/test" to "voice"
    )

    @Test fun install_bundledArchive_survivesReopenWithoutNetwork() = runBlocking {
        val bytes = zip(files)
        val entry = entry(bytes)
        val root = temporary.newFolder()
        repository(root, entry, bytes).install(entry.id)
        val reopened = repository(root, entry, bytes)
        reopened.refresh()
        assertEquals(TtsModelState.Installed, reopened.states.value[entry.id])
        files.forEach { (name, value) -> assertEquals(value, File(root, "${entry.id}/$name").readText()) }
        assertEquals(listOf(entry.id), root.list()!!.toList())
    }

    @Test fun install_badChecksum_leavesNoInstalledOrTemporaryFiles() = runBlocking {
        val bytes = zip(files)
        val entry = entry(bytes).copy(archiveSha256 = "0".repeat(64))
        val root = temporary.newFolder()
        val repo = repository(root, entry, bytes)
        assertFailure(TtsModelFailure.CORRUPT) { repo.install(entry.id) }
        assertEquals(TtsModelState.Failed(TtsModelFailure.CORRUPT), repo.states.value[entry.id])
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun install_unsafeZipPaths_rejectsBeforeWritingOutsideModel() = runBlocking {
        for (path in listOf("../escape", "/absolute", "C:/drive", "x\\escape", ".complete", "a/../../escape")) {
            val bytes = zip(files + (path to "bad"))
            val entry = entry(bytes)
            val root = temporary.newFolder()
            assertFailure(TtsModelFailure.CORRUPT) { repository(root, entry, bytes).install(entry.id) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun install_missingPhonemes_rejectsOtherwiseValidArchive() = runBlocking {
        val bytes = zip(files - "espeak-ng-data/phondata")
        val entry = entry(bytes)
        val root = temporary.newFolder()
        assertFailure(TtsModelFailure.CORRUPT) { repository(root, entry, bytes).install(entry.id) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun install_excessUnpackedBytes_rejectsBoundedArchive() = runBlocking {
        val bytes = zip(files)
        val entry = entry(bytes).copy(unpackedBytes = 1)
        val root = temporary.newFolder()
        assertFailure(TtsModelFailure.CORRUPT) { repository(root, entry, bytes).install(entry.id) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun install_storageUnavailable_reportsStorageFailure() = runBlocking {
        val bytes = zip(files)
        val entry = entry(bytes)
        val root = temporary.newFile()
        assertFailure(TtsModelFailure.STORAGE) { repository(root, entry, bytes).install(entry.id) }
    }

    @Test fun install_damagedBundledModel_repairsFromAsset() = runBlocking {
        val bytes = zip(files)
        val entry = entry(bytes)
        val root = temporary.newFolder()
        val repo = repository(root, entry, bytes)
        repo.install(entry.id)
        File(root, "${entry.id}/model.onnx").writeText("broken data")
        repo.refresh()
        assertEquals(TtsModelState.NotInstalled, repo.states.value[entry.id])
        repo.withModel(entry.id) { assertEquals("model bytes", File(it, "model.onnx").readText()) }
        assertEquals(TtsModelState.Installed, repo.states.value[entry.id])
    }

    @Test fun install_downloadedTarAndActiveLease_removalWaitsAndClearsPreference() = runBlocking {
        MockWebServer().use { server ->
            val bytes = tar(files)
            val entry = entry(bytes).copy(assetPath = null, archiveRoot = "fixture", downloadUrl = server.url("/model").toString())
            val root = temporary.newFolder()
            val repo = repository(root, entry, bytes, Dispatchers.Unconfined)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            repo.install(entry.id)
            assertEquals(TtsModelState.Installed, repo.states.value[entry.id])
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader = launch { repo.withModel(entry.id) { acquired.complete(Unit); release.await() } }
            acquired.await()
            var cleared = false
            val deletion = async(start = CoroutineStart.UNDISPATCHED) { repo.remove(entry.id) { cleared = true } }
            assertFalse(deletion.isCompleted)
            assertTrue(File(root, entry.id).isDirectory)
            release.complete(Unit)
            reader.join()
            deletion.await()
            assertTrue(cleared)
            assertEquals(TtsModelState.NotInstalled, repo.states.value[entry.id])
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun install_cancelWaitingForHttp_closesCallAndRemovesTemporaryFiles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val bytes = tar(files)
            val entry = entry(bytes).copy(assetPath = null, archiveRoot = "fixture", downloadUrl = server.url("/model").toString())
            val root = temporary.newFolder()
            val repo = repository(root, entry, bytes)
            val job = launch(Dispatchers.Default) { repo.install(entry.id) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(TtsModelState.NotInstalled, repo.states.value[entry.id])
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun withModel_optionalNotInstalled_neverDownloadsAutomatically() = runBlocking {
        val bytes = tar(files)
        val entry = entry(bytes).copy(assetPath = null, downloadUrl = "https://example.invalid/model", archiveRoot = "fixture")
        val root = temporary.newFolder()
        val repo = repository(root, entry, bytes)
        assertFailure(TtsModelFailure.UNAVAILABLE) { repo.withModel(entry.id) { fail("No model should be handed out") } }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    private fun repository(root: File, entry: TtsModelEntry, bytes: ByteArray, dispatcher: CoroutineDispatcher = Dispatchers.IO) =
        TtsModelRepository(root, { ByteArrayInputStream(bytes) }, OkHttpClient(), dispatcher, listOf(entry))

    private fun entry(bytes: ByteArray) = TtsModelCatalog.libritts.copy(
        id = "fixture", modelFile = "model.onnx", assetPath = "fixture.zip",
        archiveBytes = bytes.size.toLong(), archiveSha256 = sha(bytes),
        unpackedBytes = files.values.sumOf { it.toByteArray().size.toLong() }, fileCount = files.size
    )

    private fun zip(contents: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> contents.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }

    private fun tar(contents: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar -> contents.forEach { (name, value) ->
            val data = value.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("fixture/$name").apply { size = data.size.toLong() })
            tar.write(data)
            tar.closeArchiveEntry()
        } }
        return bytes.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun assertFailure(reason: TtsModelFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (failure: TtsModelException) { assertEquals(reason, failure.reason) }
    }
}
