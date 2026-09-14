package io.github.zoot.englishreader.data.importer.spike

import io.github.zoot.englishreader.data.importer.ImportBudget
import io.github.zoot.englishreader.data.importer.ImportException
import io.github.zoot.englishreader.data.importer.ImportFailure
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadiumArchivePreflightTest {

    @Test
    fun validEpub3_reportsBoundedTextResourcesAndSpine() {
        withFixture(ReadiumEpubFixtures.epub3WithNav()) { file ->
            val report = ReadiumArchivePreflight.inspect(file)

            assertEquals(2, report.linearSpineItems)
            assertTrue(report.archiveEntries >= 5)
            assertTrue(report.inspectedTextEntries >= 4)
            assertTrue(report.inflatedTextBytes > 0)
            assertTrue(report.inflatedTextBytes <= ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES)
        }
    }

    @Test
    fun archiveWithTooManyEntries_isRejectedBeforeReadium() {
        withFixture(epub(extraEntries = ImportBudget.MAX_ZIP_ENTRIES)) { file ->
            assertInvalidEpub { ReadiumArchivePreflight.inspect(file) }
        }
    }

    @Test
    fun oversizedXhtmlEntry_isRejectedBeforeReadium() {
        withFixture(
            epub(
                chapterCount = 1,
                chapterBodyBytes = ImportBudget.MAX_XML_ENTRY_BYTES + 1,
            ),
        ) { file ->
            assertInvalidEpub { ReadiumArchivePreflight.inspect(file) }
        }
    }

    @Test
    fun cumulativeInflatedTextBudget_isRejectedBeforeReadium() {
        val perChapter = 3_600_000
        check(perChapter < ImportBudget.MAX_XML_ENTRY_BYTES)
        check(perChapter * 7 > ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES)

        withFixture(epub(chapterCount = 7, chapterBodyBytes = perChapter)) { file ->
            assertInvalidEpub { ReadiumArchivePreflight.inspect(file) }
        }
    }

    @Test
    fun tooManyLinearSpineItems_isRejectedBeforeReadium() {
        withFixture(epub(chapterCount = ImportBudget.MAX_SPINE_ITEMS + 1)) { file ->
            assertInvalidEpub { ReadiumArchivePreflight.inspect(file) }
        }
    }

    /**
     * A ZIP entry name that escapes the container root must be rejected before any Readium open.
     *
     * This is the branch that makes the preflight a security gate rather than a size gate: the
     * archive is otherwise well-formed, so nothing else in the pipeline would stop it.
     */
    @Test
    fun inspect_entryEscapingContainerRoot_isRejected() {
        val bytes = epub(chapterCount = 1, extraEntryNames = listOf("../outside.xhtml"))

        withFixture(bytes) { file ->
            assertInvalidEpub { ReadiumArchivePreflight.inspect(file) }
        }
    }

    /**
     * A literal `%20` and a literal space must stay DISTINCT entries.
     *
     * ZIP entry names are literal values; only container/OPF hrefs are URI references. If the
     * preflight percent-decoded entry names, `a%20b.xhtml` and `a b.xhtml` would both normalize to
     * `a b.xhtml`, collide in the entry map, and a legal book would be rejected as corrupt. This
     * fixture contains both names at once, so the assertion fails if decoding is ever reintroduced.
     */
    @Test
    fun inspect_literalPercentAndLiteralSpace_remainDistinctEntries() {
        val bytes = epub(
            chapterCount = 1,
            extraEntryNames = listOf("OEBPS/a%20b.xhtml", "OEBPS/a b.xhtml"),
        )

        withFixture(bytes) { file ->
            val report = ReadiumArchivePreflight.inspect(file)

            // container.xml + content.opf + c0.xhtml + both literal-named XHTML files.
            assertEquals(5, report.inspectedTextEntries)
            assertEquals(1, report.linearSpineItems)
        }
    }

    private fun epub(
        chapterCount: Int = 1,
        chapterBodyBytes: Int = 32,
        extraEntries: Int = 0,
        extraEntryNames: List<String> = emptyList(),
    ): ByteArray {
        val manifest = buildString {
            repeat(chapterCount) { index ->
                append("<item id=\"c$index\" href=\"c$index.xhtml\" media-type=\"application/xhtml+xml\"/>")
            }
        }
        val spine = buildString {
            repeat(chapterCount) { index -> append("<itemref idref=\"c$index\"/>") }
        }
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">preflight</dc:identifier>
                <dc:title>Preflight</dc:title>
                <dc:language>en</dc:language>
                <meta property="dcterms:modified">2026-08-27T00:00:00Z</meta>
              </metadata>
              <manifest>$manifest</manifest>
              <spine>$spine</spine>
            </package>""".trimIndent()
        val container = """<?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent()
        val repeated = ByteArray(chapterBodyBytes) { 'a'.code.toByte() }

        return java.io.ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.writeEntry("mimetype", "application/epub+zip".toByteArray())
                zip.writeEntry("META-INF/container.xml", container.toByteArray())
                zip.writeEntry("OEBPS/content.opf", opf.toByteArray())
                repeat(chapterCount) { index ->
                    zip.putNextEntry(ZipEntry("OEBPS/c$index.xhtml"))
                    zip.write("<html><body><p>".toByteArray())
                    zip.write(repeated)
                    zip.write("</p></body></html>".toByteArray())
                    zip.closeEntry()
                }
                repeat(extraEntries) { index ->
                    zip.writeEntry("extra/$index.bin", byteArrayOf(0))
                }
                extraEntryNames.forEach { name ->
                    zip.writeEntry(name, "<html><body><p>x</p></body></html>".toByteArray())
                }
            }
            output.toByteArray()
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private inline fun withFixture(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("readium-preflight-", ".epub")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }

    private inline fun assertInvalidEpub(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is ImportException)
        assertEquals(ImportFailure.InvalidEpub, (failure as ImportException).failure)
    }
}
