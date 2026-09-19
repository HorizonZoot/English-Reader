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
    fun extract(archive: File, target: File, entry: TtsModelEntry, context: CoroutineContext) {
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
    }

    fun valid(directory: File, entry: TtsModelEntry, context: CoroutineContext): Boolean {
        if (!directory.isDirectory || File(directory, ".complete").readTextOrNull() != entry.archiveSha256) return false
        val manifest = File(directory, ".files")
        if (!manifest.isFile || manifest.length() > 2_000_000) return false
        val lines = manifest.readLines()
        if (lines.size != entry.fileCount) return false
        val seen = mutableSetOf<String>()
        var total = 0L
        for (line in lines) {
            context.ensureActive()
            val fields = line.split('\t')
            if (fields.size != 3 || !safePath(fields[2]) || !seen.add(fields[2])) return false
            val size = fields[1].toLongOrNull() ?: return false
            val file = File(directory, fields[2])
            if (!file.isFile || file.length() != size || size < 0) return false
            total += size
            if (sha256(file, context) != fields[0]) return false
        }
        return total == entry.unpackedBytes && entry.requiredFiles.all { it in seen && File(directory, it).length() > 0 }
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
