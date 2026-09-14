package io.github.zoot.englishreader.data.importer.spike

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.data.importer.EpubTextExtractor
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import io.github.zoot.englishreader.data.importer.XmlBytesDecoder
import io.github.zoot.englishreader.data.importer.XhtmlTextExtractor
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Error as ReadiumError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * SPIKE-ONLY evidence that Readium 3.0.3 exposes the EPUB structure the whole-book import task
 * needs, and that its resource bytes stay compatible with the production
 * [io.github.zoot.englishreader.data.importer.XhtmlTextExtractor].
 *
 * Assertions run against [ReadiumPublicationSnapshot], never Readium types, so a Readium type
 * change surfaces as a projection failure instead of silently reshaping the expectations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadiumPublicationSpikeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun structuralFixtures_writeParserInputsAtFirstByte() {
        val epub3 = ReadiumEpubFixtures.epub3WithNav()
        val epub2 = ReadiumEpubFixtures.epub2WithNcx()
        val declarationFixture = ReadiumEpubFixtures.epub3NavWithXmlDeclaration()

        // Regression guard: an OPF built by string interpolation once carried leading whitespace
        // before `<?xml`, which Readium's pull parser rejected at line=1,column=9.
        assertEquals('<'.code, firstByte(epub3, "OEBPS/content.opf"))
        assertEquals('<'.code, firstByte(epub2, "OEBPS/content.opf"))
        assertEquals('<'.code, firstByte(epub3, "OEBPS/nav.xhtml"))
        assertEquals('<'.code, firstByte(epub2, "OEBPS/toc.ncx"))

        assertTrue(entryText(epub3, "OEBPS/nav.xhtml").startsWith("<!DOCTYPE html>"))
        assertTrue(entryText(epub2, "OEBPS/toc.ncx").startsWith("<!DOCTYPE ncx"))
        assertTrue(entryText(declarationFixture, "OEBPS/nav.xhtml").startsWith("<?xml"))
    }

    @Test
    fun epub3Nav_projectsReadingOrderTocAndResources() = runBlocking {
        withPublication("epub3-nav.epub", ReadiumEpubFixtures.epub3WithNav()) { probe, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)

            assertEquals(
                listOf("OEBPS/chapter1.xhtml", "OEBPS/chapter2.xhtml"),
                snapshot.readingOrder.map { it.rawHref },
            )
            assertEquals(
                listOf("OEBPS/chapter1.xhtml", "OEBPS/chapter2.xhtml"),
                snapshot.tableOfContents.map { it.rawHref },
            )
            assertTrue(snapshot.tableOfContents.all { it.targetsReadingOrder })
            assertTrue(snapshot.tableOfContents.all { it.fragment == null })
            assertNotNull(snapshot.title)

            val first = publication.readingOrder.first()
            val bytes = probe.read(requireNotNull(probe.resource(publication, first))).successValue()
            assertTrue(
                XmlBytesDecoder.decode(bytes).contains(ReadiumEpubFixtures.CHAPTER_ONE_BODY),
            )
        }
    }

    @Test
    fun epub2Ncx_projectsReadingOrderAndToc() = runBlocking {
        withPublication("epub2-ncx.epub", ReadiumEpubFixtures.epub2WithNcx()) { _, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)

            assertEquals(
                listOf("OEBPS/chapter1.xhtml", "OEBPS/chapter2.xhtml"),
                snapshot.readingOrder.map { it.rawHref },
            )
            assertEquals(
                listOf("OEBPS/chapter1.xhtml", "OEBPS/chapter2.xhtml"),
                snapshot.tableOfContents.map { it.rawHref },
            )
        }
    }

    @Test
    fun noToc_keepsReadingOrderAndEmptyToc() = runBlocking {
        withPublication("no-toc.epub", ReadiumEpubFixtures.epubWithoutToc()) { _, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)

            assertEquals(1, snapshot.readingOrder.size)
            // Contract: a missing TOC yields an empty list, not a synthesized reading-order copy.
            assertTrue(snapshot.tableOfContents.isEmpty())
        }
    }

    @Test
    fun fragmentAndNonLinear_recordObservedHrefContract() = runBlocking {
        val fixture = ReadiumEpubFixtures.epub3WithFragmentsEncodingAndNonLinear()
        withPublication("fragment-nonlinear.epub", fixture) { _, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)

            // Observed contract 1: readingOrder contains only linear="yes" items, and Readium keeps
            // hrefs percent-encoded while exposing a decoded `path`.
            assertEquals(
                listOf("OEBPS/text/chapter%20one.xhtml"),
                snapshot.readingOrder.map { it.rawHref },
            )
            assertEquals(listOf("OEBPS/text/chapter one.xhtml"), snapshot.readingOrder.map { it.path })

            // Observed contract 2: TOC preserves fragments verbatim and may target a non-linear
            // resource, so the production model cannot assume TOC entry == reading-order item.
            val toc = ReadiumSnapshotProjection.flattenToc(publication)
            assertEquals(
                listOf(
                    "OEBPS/text/chapter%20one.xhtml#sec1",
                    "OEBPS/text/chapter%20one.xhtml#sec2",
                    "OEBPS/front%20matter.xhtml",
                ),
                toc.map { it.rawHref },
            )
            assertEquals(listOf("sec1", "sec2", null), toc.map { it.fragment })
            assertEquals(listOf(true, true, false), toc.map { it.targetsReadingOrder })
        }
    }

    @Test
    fun nestedToc_preservesHierarchyAndFlattensDepthFirst() = runBlocking {
        withPublication(
            "nested-fragment.epub",
            ReadiumEpubFixtures.epub3WithFragmentsEncodingAndNonLinear(),
        ) { _, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)
            val direct = snapshot.tableOfContents
            val flattened = ReadiumSnapshotProjection.flattenToc(publication)

            assertEquals(2, direct.size)
            assertEquals(1, direct.first().childCount)
            assertEquals("sec2", direct.first().children.single().fragment)
            assertEquals(
                listOf("sec1", "sec2", null),
                flattened.map { it.fragment },
            )
        }
    }

    @Test
    fun readiumResourceBytes_feedProductionXhtmlExtractor() = runBlocking {
        withPublication("epub3-nav.epub", ReadiumEpubFixtures.epub3WithNav()) { probe, publication ->
            val first = publication.readingOrder.first()
            val bytes = probe.read(requireNotNull(probe.resource(publication, first))).successValue()

            // The production extractor takes a decoded String; Readium hands back bytes. This is the
            // single decode boundary the real adapter would own.
            val text = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(bytes))

            assertTrue(text.contains(ReadiumEpubFixtures.CHAPTER_ONE_BODY))
            // Block-level tags must have become paragraph breaks, and no markup may survive.
            assertFalse(text.contains("<"))
            assertTrue(text.contains("\n\n"))
        }
    }

    @Test
    fun publicDomainEpub2_opensReadingOrderTocAndFirstResource() = runBlocking {
        val bytes = publicFixtureBytes("/readium/public/gutenberg-1342.epub")
        withPublication("gutenberg-1342.epub", bytes) { probe, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)

            assertTrue(snapshot.readingOrder.isNotEmpty())
            assertTrue(snapshot.tableOfContents.isNotEmpty())
            assertNotNull(snapshot.title)

            val first = publication.readingOrder.first()
            val firstBytes =
                probe.read(requireNotNull(probe.resource(publication, first))).successValue()
            assertTrue(firstBytes.isNotEmpty())

            // Observed contract 3: this real book's first reading-order item is an image-only cover
            // wrapper, so the production chapter model must not assume item[0] carries prose.
            val firstText = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(firstBytes))
            assertTrue(firstText.length < PROSE_THRESHOLD_CHARS)

            // Every linear item must cross the byte decoder and production extractor boundary;
            // image-only wrappers may legitimately yield blank prose.
            var proseCount = 0
            var decodedCount = 0
            for (link in publication.readingOrder) {
                val resource = requireNotNull(probe.resource(publication, link))
                val bytesForItem = probe.read(resource).successValue()
                val text = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(bytesForItem))
                decodedCount++
                if (text.length >= PROSE_THRESHOLD_CHARS) proseCount++
            }
            assertEquals(publication.readingOrder.size, decodedCount)
            assertTrue(proseCount >= 1)
        }
    }

    @Test
    fun publicDomainEpub3_opensReadingOrderTocAndFirstResource() = runBlocking {
        val bytes = publicFixtureBytes("/readium/public/gutenberg-78457-epub3.epub")
        withPublication("gutenberg-78457-epub3.epub", bytes) { probe, publication ->
            val snapshot = ReadiumSnapshotProjection.snapshot(publication)

            assertTrue(snapshot.readingOrder.isNotEmpty())
            assertTrue(snapshot.tableOfContents.isNotEmpty())

            var decodedCount = 0
            var nonBlankCount = 0
            for (link in publication.readingOrder) {
                val resource = requireNotNull(probe.resource(publication, link))
                val bytesForItem = probe.read(resource).successValue()
                val text = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(bytesForItem))
                decodedCount++
                if (text.isNotBlank()) nonBlankCount++
            }
            assertEquals(publication.readingOrder.size, decodedCount)
            assertTrue(nonBlankCount >= 1)
        }
    }

    @Test
    fun encryptedSpine_isRejectedByExistingSafetyBoundaryBeforeReadium() = runBlocking {
        val bytes = ReadiumEpubFixtures.epubWithEncryptionDeclaration()
        val uri = Uri.parse("content://com.example.spike/encrypted")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())

        var failure: ImportFailure? = null
        try {
            EpubTextExtractor(context).extract(uri)
        } catch (error: ImportException) {
            failure = error.failure
        }
        assertEquals(ImportFailure.EncryptedEpub, failure)

        // Readium 3.0.3 itself opens and reads this unsupported XML Encryption declaration.
        // A production adapter must therefore preserve the existing typed preflight instead of
        // delegating DRM detection to Readium.
        withPublication("encrypted.epub", bytes) { probe, publication ->
            val resource = requireNotNull(
                probe.resource(publication, publication.readingOrder.first()),
            )
            assertTrue(probe.read(resource) is Try.Success)
        }
    }

    @Test
    fun corruptArchive_failsWithoutLeakingRawDetail() = runBlocking {
        val file = fixtureFile("corrupt.epub", ByteArray(512) { 0x41 })
        val probe = ReadiumApiCompileProbe(context)
        var failure: ImportFailure? = null
        var detail = ""
        try {
            probe.retrieve(file)
        } catch (error: ImportException) {
            failure = error.failure
            detail = error.toString()
        } finally {
            file.delete()
        }

        assertEquals(ImportFailure.InvalidEpub, failure)
        assertFalse(detail.contains("corrupt.epub"))
    }

    @Test
    fun corruptOpf_failsWithoutLeakingRawDetail() = runBlocking {
        val file = fixtureFile("corrupt-opf.epub", ReadiumEpubFixtures.epubWithCorruptOpf())
        val probe = ReadiumApiCompileProbe(context)
        var failure: ImportFailure? = null
        var detail = ""
        try {
            probe.retrieve(file)
        } catch (error: ImportException) {
            failure = error.failure
            detail = error.toString()
        } finally {
            file.delete()
        }

        assertEquals(ImportFailure.InvalidEpub, failure)
        assertFalse(detail.contains("corrupt-opf.epub"))
        assertFalse(detail.contains("content.opf"))
    }

    @Test
    fun missingResource_returnsNullInsteadOfThrowing() = runBlocking {
        withPublication("epub3-nav.epub", ReadiumEpubFixtures.epub3WithNav()) { probe, publication ->
            val absent = publication.readingOrder.first().copy(
                href = org.readium.r2.shared.publication.Href(
                    requireNotNull(org.readium.r2.shared.util.Url("OEBPS/does-not-exist.xhtml")),
                ),
            )
            val resource = probe.resource(publication, absent)
            // Observed contract, asserted as one proposition so neither branch is vacuous:
            // a missing href either yields no handle at all, or yields a handle whose read fails.
            // What must never happen is a handle that reads successfully.
            val absentIsObservable = resource == null || probe.read(resource) is Try.Failure
            assertTrue(absentIsObservable)
        }
    }

    private suspend fun withPublication(
        name: String,
        bytes: ByteArray,
        block: suspend (ReadiumApiCompileProbe, org.readium.r2.shared.publication.Publication) -> Unit,
    ) {
        val file = fixtureFile(name, bytes)
        try {
            val probe = ReadiumApiCompileProbe(context)
            val asset = probe.retrieve(file).successValue()
            val publication = probe.open(asset).successValue()
            try {
                block(probe, publication)
            } finally {
                publication.close()
            }
        } finally {
            file.delete()
        }
    }

    /**
     * Loads a Project Gutenberg fixture and verifies its SHA-256 against the digest recorded in
     * `app/src/testDebug/resources/readium/public/SOURCE.md`. Provenance is asserted here rather
     * than only documented, so swapping or re-downloading a fixture fails the suite instead of
     * silently changing what the compatibility evidence was measured against.
     */
    private fun publicFixtureBytes(resourcePath: String): ByteArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Public EPUB fixture missing: $resourcePath"
        }.use { it.readBytes() }
        val expected = requireNotNull(PUBLIC_FIXTURE_SHA256[resourcePath]) {
            "No recorded SHA-256 for $resourcePath"
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
        return bytes
    }

    private fun firstByte(epub: ByteArray, entryName: String): Int =
        entryBytes(epub, entryName).first().toInt()

    private fun entryText(epub: ByteArray, entryName: String): String =
        entryBytes(epub, entryName).toString(Charsets.UTF_8)

    private fun entryBytes(epub: ByteArray, entryName: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == entryName) return zip.readBytes()
            }
        }
        throw AssertionError("Fixture entry missing: $entryName")
    }

    private fun fixtureFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply { writeBytes(bytes) }

    private companion object {
        /** Above this length an extracted resource is prose rather than a cover or nav wrapper. */
        const val PROSE_THRESHOLD_CHARS = 200

        /**
         * SHA-256 digests recorded in `readium/public/SOURCE.md` at download time (2026-08-28).
         *
         * Project Gutenberg regenerates its EPUB artifacts, so pinning the digest keeps the
         * recorded compatibility evidence tied to the exact bytes that were measured.
         */
        val PUBLIC_FIXTURE_SHA256 = mapOf(
            "/readium/public/gutenberg-1342.epub" to
                "462be7852d84412c6695851395144a97e9762d45bd3c41b9f356dc7ac047b8a9",
            "/readium/public/gutenberg-78457-epub3.epub" to
                "dfaa55888e8dbf16e255bccb4affd7249a11bddd8592a4e9ab0b08fc84d0453b",
        )
    }

    private fun <T, E> Try<T, E>.successValue(): T = when (this) {
        is Try.Success -> value
        is Try.Failure -> throw AssertionError(
            "Readium operation failed: ${failureOrNull().safeCategory()}",
        )
    }

    /**
     * Renders a failure as a chain of type names only.
     *
     * Readium error messages can embed the archive path and entry names, so the spike records
     * categories instead, matching the production logging rule.
     */
    private fun Any?.safeCategory(): String {
        if (this == null) return "Unknown"

        val categories = mutableListOf<String>()
        var current: Any? = this
        repeat(8) {
            val value = current ?: return@repeat
            categories += value::class.java.name
            if (value is ThrowableError<*>) {
                categories += value.throwable::class.java.name
            }
            current = (value as? ReadiumError)?.cause
        }
        return categories.joinToString("/")
    }
}
