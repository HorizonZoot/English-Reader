package io.github.zoot.englishreader.data.importer.spike

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.importer.ImportFailure
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * SPIKE-ONLY verification of the single SAF -> temp file boundary shared by the JVM and device
 * spike tests.
 *
 * The device test exercises the same [BoundedReadiumSourceAdapter] from `src/debug`; these JVM
 * cases pin the parts Robolectric can prove without a provider: the byte ceiling, the
 * `openInputStream == null` mapping, real job cancellation, and temp-file cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoundedReadiumSourceAdapterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.cacheDir.listFiles { file -> file.name.startsWith("readium_spike_") }
            ?.forEach { it.delete() }
    }

    @Test
    fun copyToTemp_withinBudget_copiesAllBytesAndKeepsFile() = runBlocking {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val uri = registerStream("within-budget", payload)
        val adapter = BoundedReadiumSourceAdapter(context)

        val outcome = adapter.copyToTemp(uri)

        assertTrue(outcome is BoundedReadiumSourceAdapter.Outcome.Copied)
        val file = (outcome as BoundedReadiumSourceAdapter.Outcome.Copied).file
        try {
            assertTrue(file.isFile)
            assertArrayEqualsBytes(payload, file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun copyToTemp_overBudget_failsTypedAndDeletesTempFile() = runBlocking {
        val payload = ByteArray(2048) { 0x42 }
        val uri = registerStream("over-budget", payload)
        val adapter = BoundedReadiumSourceAdapter(context, maxBytes = 1024)

        val before = tempFileCount()
        val outcome = adapter.copyToTemp(uri)

        assertEquals(
            BoundedReadiumSourceAdapter.Outcome.Failed(ImportFailure.SourceTooLarge(1024)),
            outcome,
        )
        // The partially written file must not survive a budget rejection.
        assertEquals(before, tempFileCount())
    }

    @Test
    fun copyToTemp_overBudget_closesSourceBeforeDeletingTempFile() = runBlocking {
        val before = tempFileCount()
        var sourceClosed = false
        var tempPresentDuringClose = false
        val source = object : ByteArrayInputStream(ByteArray(2048)) {
            override fun close() {
                tempPresentDuringClose = tempFileCount() == before + 1
                super.close()
                sourceClosed = true
            }
        }
        val adapter = BoundedReadiumSourceAdapter(context, maxBytes = 1024, openInputStream = { source })

        val outcome = adapter.copyToTemp(Uri.EMPTY)

        assertEquals(
            BoundedReadiumSourceAdapter.Outcome.Failed(ImportFailure.SourceTooLarge(1024)),
            outcome,
        )
        assertTrue("source must be closed before returning a rejection", sourceClosed)
        assertTrue("temporary copy must remain until both streams close", tempPresentDuringClose)
        assertEquals("rejected copy must be removed before returning", before, tempFileCount())
    }

    @Test
    fun copyToTemp_unreadableSource_mapsToSourceUnreadableWithoutTempFile() = runBlocking {
        // No stream registered for this URI, so openInputStream returns null.
        val adapter = BoundedReadiumSourceAdapter(context)

        val before = tempFileCount()
        val outcome = adapter.copyToTemp(Uri.parse("content://com.example.absent/missing"))

        assertEquals(
            BoundedReadiumSourceAdapter.Outcome.Failed(ImportFailure.SourceUnreadable),
            outcome,
        )
        assertEquals(before, tempFileCount())
    }

    @Test
    fun copyToTemp_permissionDenied_mapsToSourceUnreadableWithoutTempFile() = runBlocking {
        val adapter = BoundedReadiumSourceAdapter(
            context = context,
            openInputStream = { throw SecurityException("denied content://private/book.epub") },
        )

        val before = tempFileCount()
        val outcome = adapter.copyToTemp(Uri.EMPTY)

        assertEquals(
            BoundedReadiumSourceAdapter.Outcome.Failed(ImportFailure.SourceUnreadable),
            outcome,
        )
        assertFalse(outcome.toString().contains("content://"))
        assertEquals(before, tempFileCount())
    }

    @Test
    fun copyToTemp_readFailure_mapsToSourceUnreadableWithoutLeakingDetail() = runBlocking {
        val uri = Uri.parse("content://com.example.spike/throwing")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : InputStream() {
                override fun read(): Int = throw java.io.IOException("provider path /secret/a.epub")
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    throw java.io.IOException("provider path /secret/a.epub")
            },
        )
        val adapter = BoundedReadiumSourceAdapter(context)

        val before = tempFileCount()
        val outcome = adapter.copyToTemp(uri)

        // The IOException message carries a path; the typed failure must not.
        assertEquals(
            BoundedReadiumSourceAdapter.Outcome.Failed(ImportFailure.SourceUnreadable),
            outcome,
        )
        assertFalse(outcome.toString().contains("/secret/"))
        assertEquals(before, tempFileCount())
    }

    /**
     * Cancels the enclosing job while the copy is mid-stream.
     *
     * This is the real cancellation contract, not a stream that throws
     * [CancellationException] on its own: the adapter's `ensureActive` checkpoint must abort the
     * transfer, rethrow unchanged, and still delete the partial temp file.
     */
    @Test
    fun copyToTemp_jobCancelledMidStream_propagatesAndStillDeletesTempFile() = runBlocking {
        val started = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val uri = Uri.parse("content://com.example.spike/slow")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : ByteArrayInputStream(ByteArray(4096) { 0x41 }) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.countDown()
                    check(releaseRead.await(15, TimeUnit.SECONDS))
                    return super.read(b, off, len)
                }
            },
        )
        val adapter = BoundedReadiumSourceAdapter(context, maxBytes = Int.MAX_VALUE)

        val before = tempFileCount()
        // Cancel with a marker instance so the assertion can prove the adapter rethrows the job's
        // own cancellation cause unchanged, rather than fabricating some CancellationException.
        val marker = CancellationException("spike-cancel-marker")
        var propagated: CancellationException? = null
        val job = launch(Dispatchers.IO) {
            try {
                adapter.copyToTemp(uri)
            } catch (error: CancellationException) {
                propagated = error
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
        } finally {
            job.cancel(marker)
            releaseRead.countDown()
            job.join()
        }

        // Cancellation must never be swallowed into a typed ImportFailure, and the partial copy
        // must not survive.
        assertSame(marker, propagated)
        assertEquals(before, tempFileCount())
    }


    private fun registerStream(name: String, payload: ByteArray): Uri {
        val uri = Uri.parse("content://com.example.spike/$name")
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, payload.inputStream())
        return uri
    }

    private fun tempFileCount(): Int =
        context.cacheDir.listFiles { f: File -> f.name.startsWith("readium_spike_") }?.size ?: 0

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue(expected.contentEquals(actual))
    }
}
