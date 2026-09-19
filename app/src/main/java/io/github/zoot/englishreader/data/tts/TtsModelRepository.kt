package io.github.zoot.englishreader.data.tts

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

@Singleton
class TtsModelRepository internal constructor(
    private val root: File,
    private val openAsset: (String) -> InputStream,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val entries: List<TtsModelEntry> = TtsModelCatalog.entries
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir, "tts-models"),
        { context.assets.open(it) },
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES).build(),
        Dispatchers.IO
    )

    private val locks = entries.associate { it.id to Mutex() }
    private val mutableStates = MutableStateFlow<Map<String, TtsModelState>>(
        entries.associate { it.id to TtsModelState.NotInstalled }
    )
    val states = mutableStates.asStateFlow()

    suspend fun refresh() = withContext(ioDispatcher) {
        for (entry in entries) {
            val lock = locks.getValue(entry.id)
            if (!lock.tryLock()) continue
            try {
                cleanup(File(root, entry.id + ".tmp"))
                cleanup(File(root, entry.id + ".archive"))
                publish(entry, if (TtsModelArchive.valid(File(root, entry.id), entry, coroutineContext)) {
                    TtsModelState.Installed
                } else TtsModelState.NotInstalled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                publish(entry, TtsModelState.Failed(TtsModelFailure.STORAGE))
            } catch (_: SecurityException) {
                publish(entry, TtsModelState.Failed(TtsModelFailure.STORAGE))
            } finally {
                lock.unlock()
            }
        }
    }

    suspend fun install(id: String) = withContext(ioDispatcher) {
        val entry = entries.first { it.id == id }
        val lock = locks.getValue(id)
        if (!lock.tryLock()) return@withContext
        try {
            ensureInstalled(entry, allowDownload = true)
        } finally {
            lock.unlock()
        }
    }

    // The lease prevents removal while native inference is reading the model files.
    suspend fun <T> withModel(id: String, action: suspend (File) -> T): T {
        val entry = entries.firstOrNull { it.id == id } ?: throw TtsModelException(TtsModelFailure.UNAVAILABLE)
        return locks.getValue(id).withLock {
            val directory = withContext(ioDispatcher) { ensureInstalled(entry, allowDownload = false) }
            action(directory)
        }
    }

    suspend fun remove(id: String, clearPreference: suspend () -> Unit) = withContext(ioDispatcher) {
        val entry = entries.first { it.id == id }
        require(!entry.bundled)
        locks.getValue(id).withLock {
            publish(entry, TtsModelState.Removing)
            try {
                clearPreference()
                val directory = File(root, id)
                if (directory.exists() && !directory.deleteRecursively()) throw IOException()
                publish(entry, TtsModelState.NotInstalled)
            } catch (cancelled: CancellationException) {
                publish(entry, TtsModelState.Failed(TtsModelFailure.STORAGE))
                throw cancelled
            } catch (_: Exception) {
                publish(entry, TtsModelState.Failed(TtsModelFailure.STORAGE))
                throw TtsModelException(TtsModelFailure.STORAGE)
            }
        }
    }

    private suspend fun ensureInstalled(entry: TtsModelEntry, allowDownload: Boolean): File {
        val directory = File(root, entry.id)
        val temporary = File(root, entry.id + ".tmp")
        val archive = File(root, entry.id + ".archive")
        try {
            if (TtsModelArchive.valid(directory, entry, coroutineContext)) {
                publish(entry, TtsModelState.Installed)
                return directory
            }
            if (!entry.bundled && !allowDownload) throw TtsModelException(TtsModelFailure.UNAVAILABLE)
            if (!root.isDirectory && !root.mkdirs()) throw IOException()
            cleanup(temporary)
            cleanup(archive)
            if (entry.bundled) {
                publish(entry, TtsModelState.Installing)
                openAsset(requireNotNull(entry.assetPath)).use { input -> copyArchive(input, archive, entry, false) }
            } else {
                publish(entry, TtsModelState.Downloading(0, entry.archiveBytes))
                download(archive, entry)
            }
            if (archive.length() != entry.archiveBytes ||
                TtsModelArchive.sha256(archive, coroutineContext) != entry.archiveSha256) {
                throw TtsModelException(TtsModelFailure.CORRUPT)
            }
            publish(entry, TtsModelState.Installing)
            if (!temporary.mkdir()) throw IOException()
            TtsModelArchive.extract(archive, temporary, entry, coroutineContext)
            coroutineContext.ensureActive()
            if (directory.exists() && !directory.deleteRecursively()) throw IOException()
            // Same-filesystem rename into an absent destination is atomic on Android and the JVM.
            if (!temporary.renameTo(directory)) throw IOException()
            publish(entry, TtsModelState.Installed)
            return directory
        } catch (cancelled: CancellationException) {
            publish(entry, TtsModelState.NotInstalled)
            throw cancelled
        } catch (failure: TtsModelException) {
            publish(entry, TtsModelState.Failed(failure.reason))
            throw failure
        } catch (_: IOException) {
            publish(entry, TtsModelState.Failed(TtsModelFailure.STORAGE))
            throw TtsModelException(TtsModelFailure.STORAGE)
        } catch (_: SecurityException) {
            publish(entry, TtsModelState.Failed(TtsModelFailure.STORAGE))
            throw TtsModelException(TtsModelFailure.STORAGE)
        } finally {
            cleanup(temporary)
            cleanup(archive)
        }
    }

    private suspend fun download(target: File, entry: TtsModelEntry) = coroutineScope {
        val call = client.newCall(Request.Builder().url(requireNotNull(entry.downloadUrl)).build())
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw TtsModelException(TtsModelFailure.NETWORK)
                val body = response.body ?: throw TtsModelException(TtsModelFailure.NETWORK)
                if (body.contentLength() > entry.archiveBytes) throw TtsModelException(TtsModelFailure.CORRUPT)
                body.byteStream().use { copyArchive(it, target, entry, true) }
            }
        } catch (failure: TtsModelException) {
            throw failure
        } catch (_: IOException) {
            coroutineContext.ensureActive()
            throw TtsModelException(TtsModelFailure.NETWORK)
        } finally {
            cancellation.cancel()
        }
    }

    private suspend fun copyArchive(input: InputStream, target: File, entry: TtsModelEntry, progress: Boolean) {
        val output = try { target.outputStream() } catch (_: IOException) { throw TtsModelException(TtsModelFailure.STORAGE) }
        output.use { out ->
            var total = 0L
            var reported = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > entry.archiveBytes || total > MAX_DOWNLOAD_BYTES) throw TtsModelException(TtsModelFailure.CORRUPT)
                try { out.write(buffer, 0, count) } catch (_: IOException) { throw TtsModelException(TtsModelFailure.STORAGE) }
                if (progress && total - reported >= 256 * 1024) {
                    reported = total
                    publish(entry, TtsModelState.Downloading(total, entry.archiveBytes))
                }
            }
        }
    }

    private fun publish(entry: TtsModelEntry, state: TtsModelState) {
        mutableStates.update { it + (entry.id to state) }
    }

    private fun cleanup(file: File) {
        try {
            if (file.exists() && !file.deleteRecursively()) Log.w("TtsModelRepository", "Temporary model cleanup failed")
        } catch (_: SecurityException) {
            Log.w("TtsModelRepository", "Temporary model cleanup denied")
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 400L * 1024 * 1024
    }
}
