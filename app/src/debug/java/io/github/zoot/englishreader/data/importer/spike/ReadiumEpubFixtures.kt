package io.github.zoot.englishreader.data.importer.spike

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SPIKE-ONLY synthetic EPUB fixtures.
 *
 * Debug source set so both `testDebug` (JVM/Robolectric) and `androidTest` (device) consume the
 * same builder. Delete with the rest of the spike if Readium is rejected.
 */
internal object ReadiumEpubFixtures {

    const val CHAPTER_ONE_BODY: String = "Chapter one first sentence. Chapter one second sentence."
    const val CHAPTER_TWO_BODY: String = "Chapter two first sentence. Chapter two second sentence."
    const val FRONT_MATTER_BODY: String = "Non linear front matter sentence."

    /** EPUB 3 with a `nav.xhtml` table of contents and two linear spine items. */
    fun epub3WithNav(): ByteArray = epub3WithNav(preserveNavXmlDeclaration = false)

    /** Same EPUB 3 NAV fixture, preserving the standards-valid XML declaration. */
    fun epub3NavWithXmlDeclaration(): ByteArray = epub3WithNav(preserveNavXmlDeclaration = true)

    private fun epub3WithNav(preserveNavXmlDeclaration: Boolean): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "OEBPS/content.opf",
            opf(
                version = "3.0",
                manifest = """
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                """.trimIndent(),
                spine = """
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                """.trimIndent(),
            ),
        )
        entry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Contents</title></head>
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="chapter1.xhtml">Nav Chapter One</a></li>
                    <li><a href="chapter2.xhtml">Nav Chapter Two</a></li>
                  </ol>
                </nav>
              </body>
            </html>
            """.trimIndent(),
            preserveXmlDeclaration = preserveNavXmlDeclaration,
        )
        entry("OEBPS/chapter1.xhtml", chapterXhtml("Chapter One", CHAPTER_ONE_BODY))
        entry("OEBPS/chapter2.xhtml", chapterXhtml("Chapter Two", CHAPTER_TWO_BODY))
    }

    /** EPUB 2 with an NCX table of contents. */
    fun epub2WithNcx(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "OEBPS/content.opf",
            opf(
                version = "2.0",
                manifest = """
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                """.trimIndent(),
                spine = """
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                """.trimIndent(),
                spineToc = "ncx",
            ),
        )
        entry(
            "OEBPS/toc.ncx",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="en">
              <head>
                <meta name="dtb:uid" content="urn:uuid:spike-epub2"/>
                <meta name="dtb:depth" content="1"/>
                <meta name="dtb:totalPageCount" content="0"/>
                <meta name="dtb:maxPageNumber" content="0"/>
              </head>
              <docTitle><text>Spike EPUB 2</text></docTitle>
              <navMap>
                <navPoint id="np1" playOrder="1">
                  <navLabel><text>Ncx Chapter One</text></navLabel>
                  <content src="chapter1.xhtml"/>
                </navPoint>
                <navPoint id="np2" playOrder="2">
                  <navLabel><text>Ncx Chapter Two</text></navLabel>
                  <content src="chapter2.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
            """.trimIndent(),
        )
        entry("OEBPS/chapter1.xhtml", chapterXhtml("Chapter One", CHAPTER_ONE_BODY))
        entry("OEBPS/chapter2.xhtml", chapterXhtml("Chapter Two", CHAPTER_TWO_BODY))
    }

    /** Valid spine, but no NAV document and no NCX. */
    fun epubWithoutToc(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "OEBPS/content.opf",
            opf(
                version = "3.0",
                manifest = """<item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<itemref idref="c1"/>""",
            ),
        )
        entry("OEBPS/chapter1.xhtml", chapterXhtml("Chapter One", CHAPTER_ONE_BODY))
    }

    /** TOC entries use fragments and a percent-encoded path; spine has a `linear="no"` item. */
    fun epub3WithFragmentsEncodingAndNonLinear(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "OEBPS/content.opf",
            opf(
                version = "3.0",
                manifest = """
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="front" href="front matter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="text/chapter%20one.xhtml" media-type="application/xhtml+xml"/>
                """.trimIndent(),
                spine = """
                    <itemref idref="front" linear="no"/>
                    <itemref idref="c1"/>
                """.trimIndent(),
            ),
        )
        entry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Contents</title></head>
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li>
                      <a href="text/chapter%20one.xhtml#sec1">Section One</a>
                      <ol>
                        <li><a href="text/chapter%20one.xhtml#sec2">Section Two</a></li>
                      </ol>
                    </li>
                    <li><a href="front matter.xhtml">Front Matter</a></li>
                  </ol>
                </nav>
              </body>
            </html>
            """.trimIndent(),
        )
        entry("OEBPS/front matter.xhtml", chapterXhtml("Front Matter", FRONT_MATTER_BODY))
        entry(
            "OEBPS/text/chapter one.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Chapter One</title></head>
              <body>
                <h1>Chapter One</h1>
                <section id="sec1"><p>$CHAPTER_ONE_BODY</p></section>
                <section id="sec2"><p>$CHAPTER_TWO_BODY</p></section>
              </body>
            </html>
            """.trimIndent(),
        )
    }

    /** XHTML exercising entities, `br`, and UTF-8 non-ASCII text. */
    fun epub3WithEntitiesAndBreaks(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "OEBPS/content.opf",
            opf(
                version = "3.0",
                manifest = """
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                """.trimIndent(),
                spine = """<itemref idref="c1"/>""",
            ),
        )
        entry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Contents</title></head>
              <body><nav epub:type="toc"><ol><li><a href="chapter1.xhtml">Entities</a></li></ol></nav></body>
            </html>
            """.trimIndent(),
        )
        entry(
            "OEBPS/chapter1.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Entities</title></head>
              <body>
                <p>Tom &amp; Jerry &mdash; caf&eacute; na&iuml;ve.</p>
                <p>First line<br/>second line.</p>
                <p>Unicode: 中文 段落。</p>
              </body>
            </html>
            """.trimIndent(),
        )
    }

    /** Container points at an OPF whose XML is malformed. */
    fun epubWithCorruptOpf(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry("OEBPS/content.opf", "<package><metadata><dc:title>broken")
    }

    /** Manifest/spine reference a resource that is absent from the archive. */
    fun epubWithMissingSpineResource(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "OEBPS/content.opf",
            opf(
                version = "3.0",
                manifest = """<item id="c1" href="missing.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<itemref idref="c1"/>""",
            ),
        )
    }

    /** Declares an unsupported XML Encryption algorithm for the first spine resource. */
    fun epubWithEncryptionDeclaration(): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        entry(
            "META-INF/encryption.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/>
                <CipherData><CipherReference URI="OEBPS/chapter1.xhtml"/></CipherData>
              </EncryptedData>
            </encryption>
            """.trimIndent(),
        )
        entry(
            "OEBPS/content.opf",
            opf(
                version = "3.0",
                manifest = """<item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>""",
                spine = """<itemref idref="c1"/>""",
            ),
        )
        entry("OEBPS/chapter1.xhtml", chapterXhtml("Chapter One", CHAPTER_ONE_BODY))
    }

    /** Bytes that are not a ZIP archive at all. */
    fun notAnArchive(): ByteArray = "this is definitely not a zip archive".toByteArray()

    /** Synthetic long book: [chapters] linear spine items, each [sentencesPerChapter] sentences. */
    fun largeEpub(chapters: Int, sentencesPerChapter: Int): ByteArray = zip {
        mimetype()
        container("OEBPS/content.opf")
        val manifest = StringBuilder()
        val spine = StringBuilder()
        manifest.append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        val navItems = StringBuilder()
        for (i in 1..chapters) {
            manifest.append("\n<item id=\"c$i\" href=\"chapter$i.xhtml\" media-type=\"application/xhtml+xml\"/>")
            spine.append("\n<itemref idref=\"c$i\"/>")
            navItems.append("\n<li><a href=\"chapter$i.xhtml\">Chapter $i</a></li>")
        }
        entry(
            "OEBPS/content.opf",
            opf(version = "3.0", manifest = manifest.toString(), spine = spine.toString()),
        )
        entry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Contents</title></head>
              <body><nav epub:type="toc"><ol>$navItems</ol></nav></body>
            </html>
            """.trimIndent(),
        )
        for (i in 1..chapters) {
            val body = buildString {
                for (s in 1..sentencesPerChapter) {
                    append("Chapter ")
                    append(i)
                    append(" sentence ")
                    append(s)
                    append(". ")
                }
            }.trim()
            entry("OEBPS/chapter$i.xhtml", chapterXhtml("Chapter $i", body))
        }
    }

    /** Writes [bytes] into [dir] as a `.epub` file. */
    fun writeTo(dir: File, name: String, bytes: ByteArray): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    fun chapterXhtml(title: String, body: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head>
          <body>
            <h1>$title</h1>
            <p>$body</p>
          </body>
        </html>
        """.trimIndent()

    private fun opf(
        version: String,
        manifest: String,
        spine: String,
        spineToc: String? = null,
    ): String = buildString {
        val tocAttr = if (spineToc != null) " toc=\"$spineToc\"" else ""
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine(
            "<package xmlns=\"http://www.idpf.org/2007/opf\" " +
                "version=\"$version\" unique-identifier=\"pub-id\">",
        )
        appendLine("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        appendLine("    <dc:identifier id=\"pub-id\">urn:uuid:spike-fixture</dc:identifier>")
        appendLine("    <dc:title>Spike Fixture Book</dc:title>")
        appendLine("    <dc:creator>Spike Author</dc:creator>")
        appendLine("    <dc:language>en</dc:language>")
        if (version.startsWith("3")) {
            appendLine("    <meta property=\"dcterms:modified\">2026-08-28T00:00:00Z</meta>")
        }
        appendLine("  </metadata>")
        appendLine("  <manifest>")
        manifest.lineSequence().forEach { appendLine("    $it") }
        appendLine("  </manifest>")
        appendLine("  <spine$tocAttr>")
        spine.lineSequence().forEach { appendLine("    $it") }
        appendLine("  </spine>")
        append("</package>")
    }

    private fun zip(block: ZipBuilder.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos -> ZipBuilder(zos).block() }
        return out.toByteArray()
    }

    internal class ZipBuilder(private val zos: ZipOutputStream) {
        fun mimetype() {
            val bytes = "application/epub+zip".toByteArray()
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
            zos.putNextEntry(entry)
            zos.write(bytes)
            zos.closeEntry()
        }

        fun container(opfPath: String) = entry(
            "META-INF/container.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )

        fun entry(
            name: String,
            text: String,
            preserveXmlDeclaration: Boolean = false,
        ) {
            val content = if (
                !preserveXmlDeclaration &&
                (name.endsWith(".xhtml") || name.endsWith(".ncx"))
            ) {
                text.withoutLeadingXmlDeclaration()
            } else {
                text
            }
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        private fun String.withoutLeadingXmlDeclaration(): String {
            val trimmed = trimStart()
            if (!trimmed.startsWith("<?xml")) return this
            val declarationEnd = trimmed.indexOf("?>")
            return if (declarationEnd >= 0) {
                trimmed.substring(declarationEnd + 2).trimStart()
            } else {
                this
            }
        }
    }
}
