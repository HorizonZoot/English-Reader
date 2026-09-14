package io.github.zoot.englishreader.data.importer.spike

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportFailure
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * SPIKE-ONLY bounded SAF -> temp file adapter.
 *
 * The single source boundary shared by the JVM (`testDebug`) and device (`androidTest`) spike
 * tests, so both verify the same copy/cancellation/cleanup behaviour. It mirrors the production
 * [io.github.zoot.englishreader.data.importer.EpubTextExtractor] contract:
 *
 * - hard byte ceiling ([ImportBudget.MAX_EPUB_ARCHIVE_BYTES]) enforced during the copy;
 * - `null` `openInputStream` maps to [ImportFailure.SourceUnreadable];
 * - the copy loop has a real cancellation checkpoint, so cancelling the enclosing job aborts
 *   mid-stream instead of running to completion;
 * - [CancellationException] propagates unchanged;
 * - the temp file is deleted on every failure and on cancellation, and a failed delete is
 *   surfaced rather than silently ignored;
 * - no URI, path, or byte content is logged.
 */
internal class BoundedReadiumSourceAdapter(
    private val context: Context,
    private val maxBytes: Int = ImportBudget.MAX_EPUB_ARCHIVE_BYTES,
    private val openInputStream: (Uri) -> InputStream? = context.contentResolver::openInputStream,
) {

    /** Result of a bounded copy: either a temp [File] or a typed [ImportFailure]. */
    sealed interface Outcome {
        data class Copied(val file: File) : Outcome
        data class Failed(val failure: ImportFailure) : Outcome
    }

    /**
     * Copies [uri] into a cache-dir temp file under the archive byte ceiling.
     *
     * `suspend` on purpose: the copy loop calls [ensureActive] on every buffer, which is what makes
     * job cancellation abort the transfer. A blocking version could only observe cancellation if
     * the stream itself happened to throw, which is not a cancellation guarantee.
     */
    suspend fun copyToTemp(uri: Uri): Outcome {
        val tmp = File.createTempFile("readium_spike_", ".epub", context.cacheDir)
        var copied = false
        try {
            val input = openInputStream(uri)
                ?: return Outcome.Failed(ImportFailure.SourceUnreadable)
            input.use { source ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                tmp.outputStream().use { sink ->
                    while (true) {
                        // Real cancellation checkpoint: throws CancellationException as soon as the
                        // enclosing job is cancelled, before the next chunk is read or written.
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            return Outcome.Failed(ImportFailure.SourceTooLarge(maxBytes))
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            copied = true
            return Outcome.Copied(tmp)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never surface the raw exception: no URI, path, or provider detail leaves this layer.
            return Outcome.Failed(ImportFailure.SourceUnreadable)
        } finally {
            // Both use scopes must close before deletion; Windows cannot unlink an open file.
            if (!copied) deleteTempOrLog(tmp)
        }
    }

    /**
     * Deletes the temp copy, leaving a trace when it fails.
     *
     * Mirrors production [io.github.zoot.englishreader.data.importer.EpubTextExtractor]: a silent
     * `delete()` would leave a full copy of the user's book in `cacheDir` with nothing to explain
     * why. The message carries no path or URI.
     */
    fun deleteTempOrLog(file: File) {
        if (!file.delete() && file.exists()) {
            Log.w(TAG, "Failed to delete spike temp EPUB")
            file.deleteOnExit()
        }
    }

    private companion object {
        const val TAG = "ReadiumSpike"
    }
}
