package io.github.zoot.englishreader.data.importer.spike

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.importer.XmlBytesDecoder
import io.github.zoot.englishreader.data.importer.XhtmlTextExtractor
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Try
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPIKE-ONLY parse-cost record for real public-domain publications.
 *
 * These numbers come from Robolectric on a desktop JVM, so they are an order-of-magnitude sanity
 * check, not a device benchmark. The real-device benchmark stays a separate acceptance step for the
 * parent whole-book task; nothing here may be used to calibrate `MAX_IMPORT_CHARS`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadiumParsePerformanceSpikeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun realPublications_recordParseCost() = runBlocking {
        for ((resource, expectedSha) in FIXTURES) {
            val bytes = requireNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
            assertEquals(expectedSha, sha256(bytes))
            val file = File(context.cacheDir, resource.substringAfterLast('/'))
            file.writeBytes(bytes)

            try {
                val probe = ReadiumApiCompileProbe(context)
                var itemCount = 0
                var tocCount = 0
                var totalChars = 0

                val openMs = measureTimeMillis {
                    val asset = probe.retrieve(file)
                    check(asset is Try.Success)
                    val opened = probe.open(asset.value)
                    check(opened is Try.Success)
                    opened.value.close()
                }

                val extractMs = measureTimeMillis {
                    val asset = probe.retrieve(file)
                    check(asset is Try.Success)
                    val opened = probe.open(asset.value)
                    check(opened is Try.Success)
                    val publication = opened.value
                    try {
                        itemCount = publication.readingOrder.size
                        tocCount = ReadiumSnapshotProjection.flattenToc(publication).size
                        for (link in publication.readingOrder) {
                            val handle = probe.resource(publication, link) ?: continue
                            val read = probe.read(handle)
                            if (read is Try.Success) {
                                totalChars += XhtmlTextExtractor
                                    .extract(XmlBytesDecoder.decode(read.value)).length
                            }
                        }
                    } finally {
                        publication.close()
                    }
                }

                println(
                    "SPIKE-PERF ${resource.substringAfterLast('/')} " +
                        "archiveBytes=${bytes.size} openMs=$openMs " +
                        "fullExtractMs=$extractMs items=$itemCount tocEntries=$tocCount " +
                        "extractedChars=$totalChars",
                )

                assertTrue(itemCount > 0)
                assertTrue(totalChars > 0)
            } finally {
                file.delete()
            }
        }
    }

    private companion object {
        /**
         * Pinned to the same SHA-256 values recorded in `readium/public/SOURCE.md` and asserted in
         * [ReadiumPublicationSpikeTest]. Timings are only meaningful if they are tied to known
         * bytes: an unpinned fixture swap would silently change every number in the result report.
         */
        val FIXTURES = mapOf(
            "/readium/public/gutenberg-1342.epub" to
                "462be7852d84412c6695851395144a97e9762d45bd3c41b9f356dc7ac047b8a9",
            "/readium/public/gutenberg-78457-epub3.epub" to
                "dfaa55888e8dbf16e255bccb4affd7249a11bddd8592a4e9ab0b08fc84d0453b",
        )

        fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
