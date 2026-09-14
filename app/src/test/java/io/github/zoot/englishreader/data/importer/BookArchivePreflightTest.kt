package io.github.zoot.englishreader.data.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BookArchivePreflight] 的预算与逃逸校验。
 *
 * 用 Robolectric 而非纯 JVM：[BookArchivePreflight] 通过 `android.util.Xml` 取 pull parser，
 * 在裸 JVM 上返回 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookArchivePreflightTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inspect_validArchive_reportsCountsAndPasses() {
        listOf(1, 3).forEach { chapters ->
            val report = BookArchivePreflight.inspect(epub(chapters = chapters))

            assertEquals("chapters=$chapters", chapters, report.linearSpineItems)
            assertTrue("chapters=$chapters: entries", report.archiveEntries >= chapters + 3)
            assertTrue("chapters=$chapters: inflated bytes", report.inflatedTextBytes > 0)
            assertEquals("chapters=$chapters: package version", "3.0", report.packageVersion)
        }
    }

    @Test
    fun inspect_entryCountAboveLimit_rejectsAsTooManyEntriesNotCorrupt() {
        val file = epub(chapters = 1, extraEntries = ImportBudget.MAX_ZIP_ENTRIES + 10)

        // 不是 InvalidEpub：插图密集的合法长篇很容易超过两千个 entry，
        // 报「文件已损坏」会让用户去重新下载一本本来没问题的书。
        assertFailure(
            ImportFailure.BookArchiveTooManyEntries(ImportBudget.MAX_ZIP_ENTRIES)
        ) { BookArchivePreflight.inspect(file) }
    }

    @Test
    fun inspect_singleTextEntryAboveLimit_reportsPerEntryLimit() {
        // 报的必须是**单项**上限：本例只有一章，累计额度远未用尽，
        // 报累计上限会让用户看到与触发原因不符的数字。
        val file = epub(chapters = 1, chapterBodyBytes = ImportBudget.MAX_XML_ENTRY_BYTES + 1)

        assertFailure(
            ImportFailure.BookArchiveTooLarge(ImportBudget.MAX_XML_ENTRY_BYTES)
        ) { BookArchivePreflight.inspect(file) }
    }

    @Test
    fun inspect_cumulativeInflatedAboveLimit_reportsCumulativeLimit() {
        // 每章都远低于单项上限，只有累计才会击穿——确保测的是累计闸而非单项闸。
        val perChapter = 3_600_000
        check(perChapter < ImportBudget.MAX_XML_ENTRY_BYTES)
        val chapters = 7
        check(perChapter.toLong() * chapters > ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES)

        val file = epub(chapters = chapters, chapterBodyBytes = perChapter)

        // 不是 InvalidEpub：文件合法，只是体量超出处理能力。
        assertFailure(
            ImportFailure.BookArchiveTooLarge(ImportBudget.MAX_EPUB_TOTAL_INFLATED_BYTES)
        ) { BookArchivePreflight.inspect(file) }
    }

    @Test
    fun inspect_spineAboveBookChapterLimit_rejectsAsBookTooManyChapters() {
        val file = epub(chapters = ImportBudget.MAX_BOOK_CHAPTERS + 1, chapterBodyBytes = 16)

        // 结构合法、只是超规模：必须是 BookTooManyChapters，不能退化成 InvalidEpub，
        // 否则用户看到的是「文件损坏」而不是「这本书太大」。
        try {
            BookArchivePreflight.inspect(file)
            fail("expected ImportException(BookTooManyChapters)")
        } catch (e: ImportException) {
            val failure = e.failure
            assertTrue(
                "expected BookTooManyChapters but was $failure",
                failure is ImportFailure.BookTooManyChapters
            )
            failure as ImportFailure.BookTooManyChapters
            assertEquals(ImportBudget.MAX_BOOK_CHAPTERS, failure.limitChapters)
            assertEquals(ImportBudget.MAX_BOOK_CHAPTERS + 1, failure.actualChapters)
        }
    }

    @Test
    fun inspect_manifestDeclaredDocumentWithNonTextExtension_stillCountsTowardBudget() {
        // 同一本书的两个版本：首章只改文件名扩展名（.xhtml → .bin），
        // manifest 仍声明 application/xhtml+xml 并在 spine 里。
        // 按扩展名白名单判定时，.bin 不计入预算，inspectedTextEntries 会少一个、
        // inflatedTextBytes 会明显变小——而 Readium 照读它。
        val plain = BookArchivePreflight.inspect(epub(chapters = 2, chapterBodyBytes = 4_096))
        val disguised = BookArchivePreflight.inspect(
            epub(chapters = 2, chapterBodyBytes = 4_096, disguisedChapterExtension = true)
        )

        // 条目数必须相等：按扩展名判定时 .bin 被跳过，这里会少 1。
        assertEquals(plain.inspectedTextEntries, disguised.inspectedTextEntries)
        // 字节数不完全相等是预期的：OPF 里的 href 从 "ch0.xhtml" 变成 "ch0.bin"，
        // OPF 自身小了 2 字节。真正要验的是首章正文仍被计入：
        // 若被跳过，差值会是一整章（数千字节）而不是 2。
        val delta = plain.inflatedTextBytes - disguised.inflatedTextBytes
        assertTrue("unexpected inflated delta: $delta", delta in 0..16)
    }

    @Test
    fun inspect_disguisedExtensionAboveEntryLimit_reportsPerEntryLimit() {
        // 单项上限必须对 manifest 声明的文档生效，不能因文件名不是 .xhtml 就放行：
        // deflate 比可达 ~1000:1，单个 entry 足以撑爆堆。
        val file = epub(
            chapters = 1,
            chapterBodyBytes = ImportBudget.MAX_XML_ENTRY_BYTES + 1,
            disguisedChapterExtension = true
        )

        assertFailure(
            ImportFailure.BookArchiveTooLarge(ImportBudget.MAX_XML_ENTRY_BYTES)
        ) { BookArchivePreflight.inspect(file) }
    }

    @Test
    fun inspect_entryNameEscapingArchiveRoot_rejectsAsInvalidEpub() {
        val file = epub(chapters = 1, escapingEntryName = "../outside.xhtml")

        assertFailure(ImportFailure.InvalidEpub) { BookArchivePreflight.inspect(file) }
    }

    @Test
    fun inspect_literalPercentInEntryName_isAcceptedNotDoubleDecoded() {
        // ZIP entry 名是字面值而非 URI 引用。字面 `a%20b.xhtml` 若被 percent-decode，
        // map key 会变成 `a b.xhtml`，OPF 里查 `a%2520b.xhtml` 解出的 `a%20b.xhtml` 就 miss，
        // 合法书被误判损坏。
        val file = epub(chapters = 1, literalPercentEntry = true)

        val report = BookArchivePreflight.inspect(file)

        assertEquals(1, report.linearSpineItems)
    }

    // ---- fixtures ----

    private fun assertFailure(expected: ImportFailure, block: () -> Unit) {
        try {
            block()
            fail("expected ImportException($expected)")
        } catch (e: ImportException) {
            assertEquals(expected, e.failure)
        }
    }

    /**
     * 构造最小 EPUB。
     *
     * OPF 逐行拼接而非三引号模板：Phase 0 曾因把多行 manifest 插入 `trimIndent()` 模板，
     * 导致 XML declaration 前残留 8 个空格，XML parser 在 `line=1,column=9` 报错，
     * 排查了很久才发现不是解析器的问题。
     */
    private fun epub(
        chapters: Int,
        chapterBodyBytes: Int = 64,
        extraEntries: Int = 0,
        escapingEntryName: String? = null,
        literalPercentEntry: Boolean = false,
        /**
         * true 时首章文件名用 `.bin` 扩展名，但 manifest 仍声明
         * `media-type="application/xhtml+xml"` 并放进 spine。
         *
         * 这是扩展名白名单与 media-type 判定的分水岭：Readium 照 manifest 读它，
         * 只按扩展名扣预算会让它绕过单项与累计解压上限。
         */
        disguisedChapterExtension: Boolean = false
    ): File {
        val chapterNames = (0 until chapters).map { index ->
            when {
                literalPercentEntry && index == 0 -> "OEBPS/a%20b.xhtml"
                disguisedChapterExtension && index == 0 -> "OEBPS/ch0.bin"
                else -> "OEBPS/ch$index.xhtml"
            }
        }

        val manifest = chapterNames.mapIndexed { index, name ->
            val href = name.removePrefix("OEBPS/").replace("%", "%25")
            """    <item id="c$index" href="$href" media-type="application/xhtml+xml"/>"""
        }
        val spine = chapterNames.indices.map { """    <itemref idref="c$it"/>""" }

        val opf = buildList {
            add("""<?xml version="1.0" encoding="UTF-8"?>""")
            add("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">""")
            add("""  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
            add("""    <dc:identifier id="bid">urn:uuid:preflight-fixture</dc:identifier>""")
            add("""    <dc:title>Preflight Fixture</dc:title>""")
            add("""    <dc:language>en</dc:language>""")
            add("""    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>""")
            add("""  </metadata>""")
            add("""  <manifest>""")
            addAll(manifest)
            add("""  </manifest>""")
            add("""  <spine>""")
            addAll(spine)
            add("""  </spine>""")
            add("""</package>""")
        }.joinToString("\n")

        val body = "word ".repeat((chapterBodyBytes / 5).coerceAtLeast(1))
        val chapterXhtml = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><p>$body</p></body></html>"""

        val container = """<?xml version="1.0" encoding="UTF-8"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            chapterNames.forEach { put(it, chapterXhtml) }
            escapingEntryName?.let { put(it, chapterXhtml) }
            repeat(extraEntries) { put("OEBPS/filler$it.txt", "x") }
        }

        val file = temporaryFolder.newFile("fixture-${System.nanoTime()}.epub")
        file.writeBytes(bytes.toByteArray())
        return file
    }
}
