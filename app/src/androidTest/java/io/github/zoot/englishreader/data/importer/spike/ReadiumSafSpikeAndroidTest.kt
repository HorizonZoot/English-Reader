package io.github.zoot.englishreader.data.importer.spike

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.XmlBytesDecoder
import io.github.zoot.englishreader.data.importer.XhtmlTextExtractor
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try

/**
 * SPIKE-ONLY device proof for the real SAF boundary and Readium lifecycle.
 *
 * Uses the platform MediaStore provider for a genuine cross-process `content://` source. Metrics
 * contain only safe scalar values; no URI, filesystem path, publication metadata, or body text is
 * logged.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReadiumSafSpikeAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun contentUri_shortMediumLong_opensReadsAndCleansUp() = runBlocking {
        val adapter = BoundedReadiumSourceAdapter(context)
        val initialTempCount = tempFileCount()
        val samples = listOf(
            Sample("short") { ReadiumEpubFixtures.epub3WithNav() },
            Sample("medium") { ReadiumEpubFixtures.largeEpub(chapters = 40, sentencesPerChapter = 40) },
            Sample("long") { ReadiumEpubFixtures.largeEpub(chapters = 120, sentencesPerChapter = 80) },
        )

        for (sample in samples) {
            val bytes = sample.createBytes()
            val uri = insertIntoMediaStore(bytes)
            try {
                measurePublication(uri, bytes.size, adapter) // Warm-up.
                assertEquals(initialTempCount, tempFileCount())

                val measurements = List(MEASURED_RUNS) {
                    measurePublication(uri, bytes.size, adapter).also {
                        assertEquals(initialTempCount, tempFileCount())
                    }
                }
                val reference = measurements.first()
                assertTrue(measurements.all { it.itemCount == reference.itemCount })
                assertTrue(measurements.all { it.tocCount == reference.tocCount })
                assertTrue(measurements.all { it.extractedChars == reference.extractedChars })

                val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                println(
                    "READIUM-DEVICE sample=${sample.name} runs=$MEASURED_RUNS " +
                        "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} archiveBytes=${bytes.size} " +
                        measurements.summary("copyMs") { it.copyMs } + " " +
                        measurements.summary("openMs") { it.openMs } + " " +
                        measurements.summary("enumerateMs") { it.enumerateMs } + " " +
                        measurements.summary("firstChapterMs") { it.firstChapterMs } + " " +
                        measurements.summary("allChaptersMs") { it.allChaptersMs } + " " +
                        "items=${reference.itemCount} tocEntries=${reference.tocCount} " +
                        "extractedChars=${reference.extractedChars} " +
                        "sampledJavaHeapPeakBytes=${measurements.maxOf { it.sampledHeapPeakBytes }} " +
                        "totalPssKb=${memoryInfo.totalPss}",
                )
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    @Test
    fun contentUri_overBudget_returnsTypedFailureAndDeletesTemp() = runBlocking {
        val uri = insertIntoMediaStore(ByteArray(2048) { 0x42 })
        val adapter = BoundedReadiumSourceAdapter(context, maxBytes = 1024)
        val before = tempFileCount()
        try {
            val outcome = adapter.copyToTemp(uri)
            assertEquals(
                BoundedReadiumSourceAdapter.Outcome.Failed(ImportFailure.SourceTooLarge(1024)),
                outcome,
            )
            assertEquals(before, tempFileCount())
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /**
     * Cancels the enclosing job while a source read is blocked.
     *
     * Cancellation precedes release of the read, so the next `ensureActive` checkpoint must
     * propagate the job's cause. The finite stream also bounds the work if that checkpoint
     * regresses; the stream itself never throws [CancellationException].
     */
    @Test
    fun cancellation_abortsMidCopyAndDeletesTempOnDevice() = runBlocking {
        val started = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val adapter = BoundedReadiumSourceAdapter(
            context = context,
            maxBytes = Int.MAX_VALUE,
            openInputStream = {
                object : ByteArrayInputStream(ByteArray(4096) { 0x41 }) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        started.countDown()
                        check(releaseRead.await(15, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
            },
        )
        val before = tempFileCount()

        // Marker instance: proves the adapter rethrows the job's own cancellation cause unchanged.
        val marker = CancellationException("spike-device-cancel-marker")
        var propagated: CancellationException? = null
        val job = launch(Dispatchers.IO) {
            try {
                adapter.copyToTemp(Uri.EMPTY)
            } catch (error: CancellationException) {
                propagated = error
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
        } finally {
            job.cancel(marker)
            releaseRead.countDown()
            job.join()
        }

        // Cancellation must surface as the same CancellationException, never as a typed
        // ImportFailure and never as a fabricated replacement.
        assertSame(marker, propagated)
        assertEquals(before, tempFileCount())
    }

    private suspend fun measurePublication(
        uri: Uri,
        archiveBytes: Int,
        adapter: BoundedReadiumSourceAdapter,
    ): Measurement {
        var copiedFile: File? = null
        var publication: Publication? = null
        try {
            val copyStarted = SystemClock.elapsedRealtime()
            val copied = adapter.copyToTemp(uri)
            val copyMs = SystemClock.elapsedRealtime() - copyStarted
            assertTrue(copied is BoundedReadiumSourceAdapter.Outcome.Copied)
            copiedFile = (copied as BoundedReadiumSourceAdapter.Outcome.Copied).file
            assertEquals(archiveBytes.toLong(), copiedFile.length())

            var sampledHeapPeak = usedJavaHeapBytes()
            val probe = ReadiumApiCompileProbe(context)

            val openStarted = SystemClock.elapsedRealtime()
            val retrieved = probe.retrieve(copiedFile)
            assertTrue(retrieved is Try.Success)
            val opened = probe.open((retrieved as Try.Success).value)
            assertTrue(opened is Try.Success)
            publication = (opened as Try.Success).value
            val openMs = SystemClock.elapsedRealtime() - openStarted
            sampledHeapPeak = max(sampledHeapPeak, usedJavaHeapBytes())

            val enumerateStarted = SystemClock.elapsedRealtime()
            val readingOrder = probe.readingOrder(publication)
            val tocCount = ReadiumSnapshotProjection.flattenToc(publication).size
            val enumerateMs = SystemClock.elapsedRealtime() - enumerateStarted
            assertTrue(readingOrder.isNotEmpty())
            sampledHeapPeak = max(sampledHeapPeak, usedJavaHeapBytes())

            val firstStarted = SystemClock.elapsedRealtime()
            val firstResource = requireNotNull(probe.resource(publication, readingOrder.first()))
            val firstRead = probe.read(firstResource)
            assertTrue(firstRead is Try.Success)
            val firstText = XhtmlTextExtractor.extract(
                XmlBytesDecoder.decode((firstRead as Try.Success).value),
            )
            val firstChapterMs = SystemClock.elapsedRealtime() - firstStarted
            assertTrue(firstText.isNotBlank())
            sampledHeapPeak = max(sampledHeapPeak, usedJavaHeapBytes())

            val allStarted = SystemClock.elapsedRealtime()
            var readableItems = 0
            var extractedChars = 0
            for (link in readingOrder) {
                val resource = probe.resource(publication, link) ?: continue
                val read = probe.read(resource)
                if (read is Try.Success) {
                    val text = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(read.value))
                    if (text.isNotBlank()) readableItems++
                    extractedChars += text.length
                }
                sampledHeapPeak = max(sampledHeapPeak, usedJavaHeapBytes())
            }
            val allChaptersMs = SystemClock.elapsedRealtime() - allStarted
            assertEquals(readingOrder.size, readableItems)
            assertTrue(extractedChars > 0)

            return Measurement(
                copyMs = copyMs,
                openMs = openMs,
                enumerateMs = enumerateMs,
                firstChapterMs = firstChapterMs,
                allChaptersMs = allChaptersMs,
                itemCount = readingOrder.size,
                tocCount = tocCount,
                extractedChars = extractedChars,
                sampledHeapPeakBytes = sampledHeapPeak,
            )
        } finally {
            publication?.close()
            copiedFile?.let(adapter::deleteTempOrLog)
        }
    }

    private fun insertIntoMediaStore(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "readium-spike-${System.nanoTime()}.epub")
            put(MediaStore.Downloads.MIME_TYPE, "application/epub+zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EnglishReaderSpike")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        )
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                output.write(bytes)
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun tempFileCount(): Int =
        context.cacheDir.listFiles { file -> file.name.startsWith("readium_spike_") }?.size ?: 0

    private fun usedJavaHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun List<Measurement>.summary(
        label: String,
        selector: (Measurement) -> Long,
    ): String {
        val values = map(selector).sorted()
        return "${label}Min=${values.first()} ${label}Median=${values[values.size / 2]} " +
            "${label}Max=${values.last()}"
    }

    private data class Sample(
        val name: String,
        val createBytes: () -> ByteArray,
    )

    private data class Measurement(
        val copyMs: Long,
        val openMs: Long,
        val enumerateMs: Long,
        val firstChapterMs: Long,
        val allChaptersMs: Long,
        val itemCount: Int,
        val tocCount: Int,
        val extractedChars: Int,
        val sampledHeapPeakBytes: Long,
    )

    private companion object {
        const val MEASURED_RUNS = 5
    }
}
