package io.github.zoot.englishreader.data.tts

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlin.coroutines.CoroutineContext

internal object TtsModelArchive {
    fun extract(archive: File, target: File, entry: TtsModelEntry, context: CoroutineContext): Stamp {
        val files = linkedMapOf<String, Pair<Long, String>>()
        var total = 0L
        var entries = 0
        fun copy(name: String, directory: Boolean, input: InputStream) {
            context.ensureActive()
            if (++entries > 10_000) corrupt()
            val relative = if (entry.archiveRoot.isEmpty()) name else {
                if (name.trimEnd('/') == entry.archiveRoot && directory) return
                if (!name.startsWith(entry.archiveRoot + "/")) corrupt()
                name.removePrefix(entry.archiveRoot + "/")
            }
            val path = relative.trimEnd('/')
            if (!safePath(path)) corrupt()
            val file = File(target, path)
            if (!file.canonicalPath.startsWith(target.canonicalPath + File.separator)) corrupt()
            if (directory) {
                if (!file.isDirectory && !file.mkdirs()) storage()
                return
            }
            if (files.size >= entry.fileCount || files.containsKey(path) || file.exists()) corrupt()
            if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) storage()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val output = try { file.outputStream() } catch (_: java.io.IOException) { storage() }
            output.use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    bytes += count
                    if (total > entry.unpackedBytes) corrupt()
                    try { out.write(buffer, 0, count) } catch (_: java.io.IOException) { storage() }
                    digest.update(buffer, 0, count)
                }
            }
            files[path] = bytes to digest.digest().hex()
        }
        try {
            if (entry.bundled) {
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        copy(item.name, item.isDirectory, zip)
                        zip.closeEntry()
                    }
                }
            } else {
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                    while (true) {
                        val item = tar.nextEntry ?: break
                        if ((!item.isFile && !item.isDirectory) || item.isSparse || !tar.canReadEntryData(item)) corrupt()
                        copy(item.name, item.isDirectory, tar)
                    }
                }
            }
        } catch (failure: TtsModelException) {
            throw failure
        } catch (_: java.io.IOException) {
            corrupt()
        }
        if (files.size != entry.fileCount || total != entry.unpackedBytes ||
            entry.requiredFiles.any { (files[it]?.first ?: 0) <= 0 }) corrupt()
        File(target, ".files").writeText(files.entries.joinToString("\n") { (path, value) ->
            "${value.second}\t${value.first}\t$path"
        })
        File(target, ".complete").writeText(entry.archiveSha256)
        // Every payload was hashed while extracting the already verified archive. Capture those
        // same files instead of reading the whole model again before its first use.
        return Stamp(
            target.canonicalPath,
            entry.archiveSha256,
            (files.keys + listOf(".files", ".complete")).associateWith { path ->
                context.ensureActive()
                FileStamp.read(File(target, path)) ?: storage()
            }
        )
    }

    /**
     * Only a full validation or successful extraction may create a trusted stamp. Metadata is
     * an in-process reuse guard for this app-private, repository-owned directory; a restart or
     * explicit recheck still hashes every payload, including same-size/same-timestamp changes.
     */
    data class Stamp internal constructor(
        val directoryPath: String,
        val archiveSha256: String,
        private val files: Map<String, FileStamp>
    ) {
        fun matches(directory: File, entry: TtsModelEntry, context: CoroutineContext): Boolean {
            if (!directory.isDirectory || directory.canonicalPath != directoryPath ||
                archiveSha256 != entry.archiveSha256 ||
                File(directory, ".complete").readTextOrNull() != archiveSha256) return false
            return files.all { (path, metadata) ->
                context.ensureActive()
                metadata == FileStamp.read(File(directory, path))
            }
        }

        fun relocated(directory: File): Stamp = copy(directoryPath = directory.canonicalPath)
    }

    internal data class FileStamp(val size: Long, val modified: Long) {
        companion object {
            fun read(file: File): FileStamp? = if (file.isFile) {
                FileStamp(file.length(), file.lastModified())
            } else null
        }
    }

    fun verify(directory: File, entry: TtsModelEntry, context: CoroutineContext): Stamp? {
        val complete = File(directory, ".complete")
        if (!directory.isDirectory || complete.readTextOrNull() != entry.archiveSha256) return null
        val manifest = File(directory, ".files")
        val manifestMetadata = FileStamp.read(manifest) ?: return null
        val completeMetadata = FileStamp.read(complete) ?: return null
        if (manifestMetadata.size > 2_000_000) return null
        val lines = manifest.readLines()
        if (lines.size != entry.fileCount) return null
        val files = linkedMapOf<String, FileStamp>()
        var total = 0L
        for (line in lines) {
            context.ensureActive()
            val fields = line.split('\t')
            if (fields.size != 3 || !safePath(fields[2]) || fields[2] in files) return null
            val size = fields[1].toLongOrNull() ?: return null
            val file = File(directory, fields[2])
            val before = FileStamp.read(file) ?: return null
            if (before.size != size || size < 0) return null
            total += size
            if (sha256(file, context) != fields[0] || FileStamp.read(file) != before) return null
            files[fields[2]] = before
        }
        if (total != entry.unpackedBytes ||
            entry.requiredFiles.any { (files[it]?.size ?: 0) <= 0 } ||
            FileStamp.read(manifest) != manifestMetadata || FileStamp.read(complete) != completeMetadata ||
            complete.readTextOrNull() != entry.archiveSha256) return null
        files[".files"] = manifestMetadata
        files[".complete"] = completeMetadata
        return Stamp(directory.canonicalPath, entry.archiveSha256, files)
    }

    fun sha256(file: File, context: CoroutineContext): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun File.readTextOrNull(): String? = if (isFile && length() < 100) readText() else null
    private fun safePath(path: String) = path.isNotEmpty() && '\\' !in path && ':' !in path &&
        '\t' !in path && '\n' !in path && '\r' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        path != ".files" && path != ".complete"
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun corrupt(): Nothing = throw TtsModelException(TtsModelFailure.CORRUPT)
    private fun storage(): Nothing = throw TtsModelException(TtsModelFailure.STORAGE)
}
