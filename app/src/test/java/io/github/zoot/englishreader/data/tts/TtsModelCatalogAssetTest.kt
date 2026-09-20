package io.github.zoot.englishreader.data.tts

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the shipped `assets/tts` archive to the constants in [TtsModelCatalog]. Nothing else in
 * the JVM suite opens the real asset: [TtsModelRepositoryTest] builds synthetic archives and
 * overrides the metadata with `copy()`. If the archive is repacked and a constant is left stale,
 * `ensureInstalled` rejects the bundled model as CORRUPT on every device, so that must fail here.
 *
 * Reads the asset from the working tree: unit tests run with `user.dir == app/`
 * (same idiom as DictionaryQueryPlanTest).
 */
class TtsModelCatalogAssetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val entry = TtsModelCatalog.libritts
    private val asset = File(System.getProperty("user.dir"), "src/main/assets/${requireNotNull(entry.assetPath)}")

    @Test
    fun libritts_bundledArchive_matchesCatalogMetadata() {
        assertTrue("cannot read ${asset.path}; did the asset move?", asset.isFile)
        // Checked explicitly so a stale constant names the field instead of surfacing as CORRUPT.
        assertEquals("archiveBytes", entry.archiveBytes, asset.length())
        assertEquals("archiveSha256", entry.archiveSha256, sha256(asset))

        val repository = TtsModelRepository(
            temporary.newFolder(), { asset.inputStream() }, OkHttpClient(), Dispatchers.IO, listOf(entry)
        )
        runBlocking {
            repository.withModel(entry.id) { directory ->
                val files = directory.walkTopDown().filter { it.isFile }
                    .filterNot { it.name == ".files" || it.name == ".complete" }.toList()
                assertEquals("fileCount", entry.fileCount, files.size)
                assertEquals("unpackedBytes", entry.unpackedBytes, files.sumOf { it.length() })
                for (required in entry.requiredFiles) {
                    assertTrue("$required missing", File(directory, required).length() > 0)
                }
                // espeak-ng aborts init unless all four of these exist next to the dictionaries.
                for (name in listOf("phondata", "phontab", "phonindex", "intonations")) {
                    assertTrue("espeak-ng-data/$name missing", File(directory, "espeak-ng-data/$name").length() > 0)
                }
            }
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
