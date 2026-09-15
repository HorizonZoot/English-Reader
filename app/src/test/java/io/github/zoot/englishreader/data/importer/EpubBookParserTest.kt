package io.github.zoot.englishreader.data.importer

import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EpubBookParser] 的 JVM 测试。
 *
 * Robolectric 提供 Readium 需要的 `android.util.Xml` 与 ContentResolver。这些用例断言的是
 * **投影语义**（章节编号、标题优先级、纯图片过滤、预算、指纹稳定性），不是 Readium 自身行为——
 * 后者已由 Phase 0 Spike 用真实公版书证明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubBookParserTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val parser by lazy {
        EpubBookParser(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun parse_packageVersion_recordsFormatIndependentlyOfToc() = runTest {
        // EPUB 2 和 EPUB 3 都有非空目录，版本只能由 OPF package/@version 决定。
        listOf("2.0" to BookFormat.EPUB2, "3.0" to BookFormat.EPUB3).forEach { (version, expected) ->
            val book = parser.parse(
                write(epub(chapters = listOf("ch1.xhtml" to "<p>Alpha.</p>"), packageVersion = version))
            )

            assertTrue("version=$version: fixture toc must be non-empty", book.toc.isNotEmpty())
            assertEquals("version=$version", expected, book.metadata.sourceFormat)
        }
    }

    /**
     * EPUB 2 只有 NCX、没有 NAV 时章节标题仍取自目录。
     *
     * 这条此前无判据：`parse_packageVersion_...` 的 fixture 写 `version="2.0"` 却配 NAV
     * 文档，验的是版本判定，NCX 解析路径从未被生产 parser 走过（只有 `src/testDebug` 的
     * spike 直接打 Readium 验过）。Gutenberg 上大量老书只有 NCX，这是真实用户路径。
     *
     * label 刻意不同于回退值 `"Chapter N"`：若 NCX 未被解析，标题会落到回退分支而非碰巧相等。
     */
    @Test
    fun parse_epub2NcxOnly_usesNcxLabelsAsChapterTitles() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "ch1.xhtml" to "<p>Alpha body.</p>",
                        "ch2.xhtml" to "<p>Beta body.</p>"
                    ),
                    navEntries = listOf(
                        "ch1.xhtml" to "Ncx Chapter One",
                        "ch2.xhtml" to "Ncx Chapter Two"
                    ),
                    packageVersion = "2.0",
                    tocStyle = TocStyle.NCX
                )
            )
        )

        assertEquals(BookFormat.EPUB2, book.metadata.sourceFormat)
        assertEquals(
            listOf("Ncx Chapter One", "Ncx Chapter Two"),
            book.chapters.map { it.title }
        )
        assertEquals(
            listOf("Ncx Chapter One", "Ncx Chapter Two"),
            book.chapters.map { it.navigationTitle }
        )
        assertEquals(listOf(0, 1), book.chapters.map { it.chapterIndex })
    }

    /**
     * `linear="no"` 的 spine 项不进章节列表，其后章节重新连续编号。
     *
     * `EpubBookParser` 本身不看 `linear`——它依赖 Readium 已把这类项排除出 `readingOrder`。
     * 该契约此前只被 `src/testDebug` 的 spike 记录过，生产投影侧无判据：Readium 升版若改变
     * 这个行为，封面/版权页会静默变成正式章节，而没有任何测试会红。
     */
    @Test
    fun parse_nonLinearSpineItem_isExcludedAndRemainingRenumbered() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "front.xhtml" to "<p>Front matter body.</p>",
                        "ch1.xhtml" to "<p>Real body one.</p>",
                        "ch2.xhtml" to "<p>Real body two.</p>"
                    ),
                    navEntries = listOf("ch1.xhtml" to "One", "ch2.xhtml" to "Two"),
                    nonLinearHrefs = setOf("front.xhtml")
                )
            )
        )

        assertEquals(2, book.chapters.size)
        assertTrue(
            "linear=\"no\" resource must not become a chapter",
            book.chapters.none { it.sourceHref.contains("front") }
        )
        assertEquals(listOf(0, 1), book.chapters.map { it.chapterIndex })
        assertEquals(listOf("One", "Two"), book.chapters.map { it.title })
    }

    @Test
    fun parse_chapterOrderChange_changesFingerprint() = runTest {
        // 指纹必须绑定章节顺序：否则同一本书的不同编排版会被当成重复书拒掉。
        val forward = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "ch1.xhtml" to "<p>Alpha body.</p>",
                        "ch2.xhtml" to "<p>Beta body.</p>"
                    )
                )
            )
        )
        val reversed = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "ch1.xhtml" to "<p>Beta body.</p>",
                        "ch2.xhtml" to "<p>Alpha body.</p>"
                    )
                )
            )
        )

        assertEquals(
            forward.chapters.map { it.content }.sorted(),
            reversed.chapters.map { it.content }.sorted()
        )
        assertNotEquals(
            forward.metadata.contentFingerprint,
            reversed.metadata.contentFingerprint
        )
    }

    @Test
    fun parse_chapterBoundaryShift_changesFingerprint() = runTest {
        // 无分隔符的拼接会让 ["AB","C"] 与 ["A","BC"] 同摘要。
        val a = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "ch1.xhtml" to "<p>AB</p>",
                        "ch2.xhtml" to "<p>C</p>"
                    )
                )
            )
        )
        val b = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "ch1.xhtml" to "<p>A</p>",
                        "ch2.xhtml" to "<p>BC</p>"
                    )
                )
            )
        )

        assertNotEquals(a.metadata.contentFingerprint, b.metadata.contentFingerprint)
    }

    @Test
    fun parse_navTitles_preferNavigationOverSpineOrder() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        "ch1.xhtml" to "<p>Alpha body.</p>",
                        "ch2.xhtml" to "<p>Beta body.</p>"
                    ),
                    navEntries = listOf(
                        "ch1.xhtml" to "First From Nav",
                        "ch2.xhtml" to "Second From Nav"
                    )
                )
            )
        )

        assertEquals(listOf("First From Nav", "Second From Nav"), book.chapters.map { it.title })
        assertEquals(listOf(0, 1), book.chapters.map { it.chapterIndex })
        assertEquals(listOf("First From Nav", "Second From Nav"), book.chapters.map { it.navigationTitle })
    }

    @Test
    fun parse_imageOnlyCover_isSkippedAndRemainingChaptersRenumbered() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf(
                        // 真实出版物的封面页：只有 <img>，提取后正文为空。
                        "cover.xhtml" to "<div><img src=\"cover.jpg\" alt=\"\"/></div>",
                        "ch1.xhtml" to "<p>Real body one.</p>",
                        "ch2.xhtml" to "<p>Real body two.</p>"
                    ),
                    navEntries = listOf("ch1.xhtml" to "One", "ch2.xhtml" to "Two")
                )
            )
        )

        assertEquals(2, book.chapters.size)
        // 过滤封面后必须重新编号：不能留下 1,2 的空洞。
        assertEquals(listOf(0, 1), book.chapters.map { it.chapterIndex })
        assertTrue(book.chapters.none { it.sourceHref.contains("cover") })
    }

    @Test
    fun parse_noNavTitle_fallsBackToSequentialChapterName() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf("a.xhtml" to "<p>Body a.</p>", "b.xhtml" to "<p>Body b.</p>"),
                    navEntries = emptyList()
                )
            )
        )

        assertEquals(listOf("Chapter 1", "Chapter 2"), book.chapters.map { it.title })
        assertNull(book.chapters.first().navigationTitle)
    }

    @Test
    fun parse_nestedNav_flattensToTocTreeAndKeepsFragment() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf("ch1.xhtml" to "<p>Body.</p>"),
                    navEntries = listOf("ch1.xhtml" to "Parent"),
                    nestedUnderFirst = "ch1.xhtml#sec2" to "Child"
                )
            )
        )

        assertEquals(1, book.toc.size)
        val parent = book.toc.single()
        assertEquals("Parent", parent.title)
        assertEquals(1, parent.children.size)
        assertEquals("Child", parent.children.single().title)
        assertEquals("sec2", parent.children.single().fragment)
    }

    @Test
    fun parse_allChaptersBlank_rejectsAsNoReadableChapters() = runTest {
        try {
            parser.parse(
                write(
                    epub(
                        chapters = listOf("cover.xhtml" to "<div><img src=\"c.jpg\"/></div>"),
                        navEntries = emptyList()
                    )
                )
            )
            fail("expected ImportException")
        } catch (e: ImportException) {
            assertEquals(ImportFailure.NoReadableChapters, e.failure)
        }
    }

    /**
     * 超限章节报 [ImportFailure.ChapterTooLong]，**不是** `ContentTooLong`，且必须带上章节标题。
     *
     * 两者分开的理由是用户能做的事不同：单篇超限时文件是用户自己挑的，换一篇更短的可行；
     * 章节边界由制作方的分章决定，用户唯一可行动作是换一个版本。标题是让提示能定位到
     * 具体哪一章 —— 一本 60 章的书报「有一章太长」等于没说。
     */
    @Test
    fun parse_oversizedChapter_reportsResolvedTitleAndLimit() = runTest {
        val long = "word ".repeat(ImportBudget.MAX_CHAPTER_CHARS / 5 + 100)
        listOf("The Oversized Chapter" to "The Oversized Chapter", null to "Chapter 1")
            .forEach { (navigationTitle, expectedTitle) ->
                val nav = navigationTitle?.let { listOf("big.xhtml" to it) }.orEmpty()
                try {
                    parser.parse(write(epub(chapters = listOf("big.xhtml" to "<p>$long</p>"), navEntries = nav)))
                    fail("expected ChapterTooLong for $expectedTitle")
                } catch (error: ImportException) {
                    val failure = error.failure
                    assertTrue("$expectedTitle: got $failure", failure is ImportFailure.ChapterTooLong)
                    failure as ImportFailure.ChapterTooLong
                    assertEquals("$expectedTitle: limit", ImportBudget.MAX_CHAPTER_CHARS, failure.limitChars)
                    assertEquals(expectedTitle, failure.chapterTitle)
                    assertTrue("$expectedTitle: actual length", failure.actualChars > ImportBudget.MAX_CHAPTER_CHARS)
                }
            }
    }

    /**
     * 超限章节被切成多篇，且**两个**标题字段都带分片后缀。
     *
     * `navigationTitle` 必须一起加后缀，不能只给 `title` 加：`BookTocScreen` 显示的是
     * `navigationTitle`，只改 `title` 会让一章切出的几部分在目录里显示成完全相同的几行，
     * 用户无法区分。这条断言就是那个缺陷的判据。
     *
     * 同时钉住「未被切开的章节标题逐字不变」——后缀只在真的切开时出现，否则今天能导入的书
     * 会凭空多出 `(1/1)` 之类的噪声。
     */
    @Test
    fun parse_splitChapter_suffixesBothTitleFieldsAndLeavesUnsplitOnesVerbatim() = runTest {
        // 13 段 × 3,000 字符 = 39,024（含分隔符）刚好在上限内，第 14 段起进第二篇。
        val paragraph = "word ".repeat(600).trim()
        val oversized = List(20) { "<p>$paragraph</p>" }.joinToString("")
        val small = "<p>Short chapter body.</p>"

        val book = parser.parse(
            write(
                epub(
                    chapters = listOf("big.xhtml" to oversized, "small.xhtml" to small),
                    navEntries = listOf("big.xhtml" to "Chapter LIV", "small.xhtml" to "Chapter LV")
                )
            )
        )

        val bigParts = book.chapters.filter { it.sourceHref.endsWith("big.xhtml") }
        assertTrue("the oversized resource must be split, got ${bigParts.size}", bigParts.size > 1)
        val total = bigParts.size
        assertEquals(
            "title must carry the part suffix",
            (1..total).map { "Chapter LIV ($it/$total)" },
            bigParts.map { it.title }
        )
        assertEquals(
            "navigationTitle must carry it too, or the TOC shows identical rows",
            (1..total).map { "Chapter LIV ($it/$total)" },
            bigParts.map { it.navigationTitle }
        )

        val unsplit = book.chapters.single { it.sourceHref.endsWith("small.xhtml") }
        assertEquals("unsplit title must stay verbatim", "Chapter LV", unsplit.title)
        assertEquals("unsplit navigationTitle must stay verbatim", "Chapter LV", unsplit.navigationTitle)

        assertEquals(
            "chapterIndex must stay dense across the split",
            book.chapters.indices.toList(),
            book.chapters.map { it.chapterIndex }
        )
        book.chapters.forEach {
            assertTrue(
                "part ${it.chapterIndex} is ${it.content.length} chars, over the ceiling",
                it.content.length <= ImportBudget.MAX_CHAPTER_CHARS
            )
        }
    }

    /**
     * 无 NAV 标题的书被切开时，`navigationTitle` 保持 null，不被后缀「填充」成非 null。
     *
     * `BookTocScreen` 依赖 null 回退到「第 N 章 / 共 M 章」，序号本身已能区分各部分。
     * 若这里给 null 补出一个 `" (1/2)"`，目录会显示成孤零零的括号序号，比回退更糟。
     */
    @Test
    fun parse_splitChapterWithoutNavTitle_keepsNavigationTitleNull() = runTest {
        val paragraph = "word ".repeat(600).trim()
        val oversized = List(20) { "<p>$paragraph</p>" }.joinToString("")

        val book = parser.parse(
            write(epub(chapters = listOf("big.xhtml" to oversized), navEntries = emptyList()))
        )

        assertTrue("expected a split", book.chapters.size > 1)
        book.chapters.forEach { assertNull(it.navigationTitle) }
        // 回退标题按源资源编号，所以每一部分的 title 都基于 "Chapter 1"。
        val total = book.chapters.size
        assertEquals(
            (1..total).map { "Chapter 1 ($it/$total)" },
            book.chapters.map { it.title }
        )
    }

    /**
     * 全书总量上限必须独立于单章上限生效。
     *
     * 这是 [ImportBudget.MAX_BOOK_TEXT_CHARS] 唯一的判据。构造的形状是刻意的：
     * 每章都在 [ImportBudget.MAX_CHAPTER_CHARS] 之内（所以单章闸门不会先触发），
     * 但累加起来越过全书上限。若两个上限被合并成一个，本用例会拿到
     * `ContentTooLong` 而不是 `BookTooLong` —— 那意味着用户看到的是
     * 「某一章太长」，而真实原因是「整本太大」。
     */
    @Test
    fun parse_totalCharsAboveBookBudget_rejectsAsBookTooLong() = runTest {
        // 单章约 20,000 字符（< MAX_CHAPTER_CHARS 40,000），
        // 需要 201 章才越过 MAX_BOOK_TEXT_CHARS 4,000,000。
        // 必须切成多段：单个 20,000 字符的 <p> 会先撞上 MAX_PARAGRAPH_CHARS(8,000)，
        // 那样测到的是段落闸门而不是全书闸门。
        val perChapter = ImportBudget.MAX_CHAPTER_CHARS / 2
        val paragraph = "word ".repeat(1_000 / 5)
        val body = List(perChapter / 1_000) { "<p>$paragraph</p>" }.joinToString("")
        val chapterCount = ImportBudget.MAX_BOOK_TEXT_CHARS / perChapter + 1
        val chapters = (0 until chapterCount).map { "c$it.xhtml" to "<p>$body</p>" }

        try {
            parser.parse(write(epub(chapters = chapters, navEntries = emptyList())))
            fail("expected ImportException")
        } catch (e: ImportException) {
            val failure = e.failure
            assertTrue("got $failure", failure is ImportFailure.BookTooLong)
            failure as ImportFailure.BookTooLong
            assertEquals(ImportBudget.MAX_BOOK_TEXT_CHARS, failure.limitChars)
            assertTrue(
                "actualChars ${failure.actualChars} should exceed the limit",
                failure.actualChars > ImportBudget.MAX_BOOK_TEXT_CHARS
            )
        }
    }

    /**
     * 章节必须过段落结构预算，不能只过字符预算。
     *
     * 章节就是一条 `ArticleEntity`，由同一个 `ReadingScreen` 渲染，所以
     * [ImportBudget.MAX_IMPORT_PARAGRAPHS] 对它与对单篇文章同等适用。fixture 刻意构造成
     * 「字符数远在单章上限之内、段落数越过上限」：1201 个极短 `<p>` 不足 1 万字符 < 4 万，
     * 只有字符闸门时会全部放行，用户拿到一个 1201 段的章节 —— 正是段落上限要防的形状。
     */
    @Test
    fun parse_chapterAboveParagraphCountBudget_rejectsAsTooManyParagraphs() = runTest {
        val paragraphs = ImportBudget.MAX_IMPORT_PARAGRAPHS + 1
        val body = (0 until paragraphs).joinToString("") { "<p>word</p>" }

        try {
            parser.parse(write(epub(chapters = listOf("many.xhtml" to body), navEntries = emptyList())))
            fail("expected ImportException")
        } catch (e: ImportException) {
            val failure = e.failure
            assertTrue("got $failure", failure is ImportFailure.TooManyParagraphs)
            failure as ImportFailure.TooManyParagraphs
            assertEquals(ImportBudget.MAX_IMPORT_PARAGRAPHS, failure.limitParagraphs)
            assertTrue(
                "actualParagraphs ${failure.actualParagraphs} should exceed the limit",
                failure.actualParagraphs > ImportBudget.MAX_IMPORT_PARAGRAPHS
            )
        }
    }

    /**
     * 「整章没有段落分隔」的退化输入必须被单段上限拦下。
     *
     * LazyColumn 救不了这个形状：它的最小组合单位就是一个段落，一个 8000+ 字符的单段
     * 会在一次 AnnotatedString 构造与一次文本测量里被整体处理。字符数仍在单章上限之内，
     * 故若无此闸门则整章放行。
     */
    @Test
    fun parse_chapterWithSingleHugeParagraph_rejectsAsParagraphTooLong() = runTest {
        val huge = "word ".repeat(ImportBudget.MAX_PARAGRAPH_CHARS / 5 + 200)
        assertTrue(
            "fixture must stay inside the chapter char budget or it proves nothing",
            huge.length <= ImportBudget.MAX_CHAPTER_CHARS
        )

        try {
            parser.parse(write(epub(chapters = listOf("one.xhtml" to "<p>$huge</p>"), navEntries = emptyList())))
            fail("expected ImportException")
        } catch (e: ImportException) {
            val failure = e.failure
            assertTrue("got $failure", failure is ImportFailure.ParagraphTooLong)
            assertEquals(
                ImportBudget.MAX_PARAGRAPH_CHARS,
                (failure as ImportFailure.ParagraphTooLong).limitChars
            )
        }
    }

    @Test
    fun parse_sameContentDifferentTitle_producesSameFingerprint() = runTest {
        val chapters = listOf("ch1.xhtml" to "<p>Identical body text.</p>")
        val first = parser.parse(write(epub(chapters = chapters, title = "Title A")))
        val second = parser.parse(write(epub(chapters = chapters, title = "Title B")))

        // 指纹只摘要正文：换标题重新导入仍应被识别为同一本书。
        assertEquals(first.metadata.contentFingerprint, second.metadata.contentFingerprint)
        assertEquals("Title A", first.metadata.title)
        assertEquals("Title B", second.metadata.title)
    }

    @Test
    fun parse_metadata_projectsTitleAuthorLanguageIdentifier() = runTest {
        val book = parser.parse(
            write(
                epub(
                    chapters = listOf("ch1.xhtml" to "<p>Body.</p>"),
                    title = "Projected Title",
                    author = "Projected Author",
                    language = "en",
                    identifier = "urn:uuid:projected-id"
                )
            )
        )

        assertEquals("Projected Title", book.metadata.title)
        assertEquals("Projected Author", book.metadata.author)
        assertEquals("en", book.metadata.language)
        assertEquals("urn:uuid:projected-id", book.metadata.identifier)
        assertNotNull(book.metadata.contentFingerprint)
    }

    // ---- fixture ----

    private fun write(bytes: ByteArray): File =
        temporaryFolder.newFile("book-${bytes.size}-${System.nanoTime()}.epub")
            .also { it.writeBytes(bytes) }

    /**
     * 最小合法 EPUB 3。
     *
     * 每份 XML 都必须以 `<?xml` 起头且前面无空白：Phase 0 Spike 花了很久才定位到，
     * trimIndent 拼接多行片段会在 declaration 前留下缩进，Readium 报的却是笼统的
     * Decoding 错误。所以这里逐行拼，不用三引号模板。
     */
    private fun epub(
        chapters: List<Pair<String, String>>,
        navEntries: List<Pair<String, String>> = chapters.map { it.first to it.first },
        nestedUnderFirst: Pair<String, String>? = null,
        title: String = "Fixture Book",
        author: String = "Fixture Author",
        language: String = "en",
        identifier: String = "urn:uuid:fixture",
        /** OPF `package/@version`。EPUB 2 fixture 传 "2.0"。 */
        packageVersion: String = "3.0",
        /**
         * 目录形式。NCX fixture **不写 nav.xhtml**：两者同时存在时 Readium 优先取 NAV，
         * 用例会退化成又一次 NAV 覆盖，而不是 NCX 判据。
         */
        tocStyle: TocStyle = TocStyle.NAV,
        /** spine 里需要标 `linear="no"` 的章节 href。 */
        nonLinearHrefs: Set<String> = emptySet()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entry(zip, "mimetype", "application/epub+zip".toByteArray())

            entry(
                zip, "META-INF/container.xml",
                lines(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">",
                    "  <rootfiles>",
                    "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>",
                    "  </rootfiles>",
                    "</container>"
                )
            )

            val manifest = buildList {
                when (tocStyle) {
                    TocStyle.NAV ->
                        add("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                    TocStyle.NCX ->
                        add("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
                }
                chapters.forEachIndexed { i, (href, _) ->
                    add("    <item id=\"c$i\" href=\"$href\" media-type=\"application/xhtml+xml\"/>")
                }
            }
            val spine = chapters.mapIndexed { i, (href, _) ->
                val linear = if (href in nonLinearHrefs) " linear=\"no\"" else ""
                "    <itemref idref=\"c$i\"$linear/>"
            }
            // EPUB 2 通过 spine/@toc 指向 NCX；EPUB 3 的 NAV 靠 manifest properties="nav"。
            val spineOpen = if (tocStyle == TocStyle.NCX) "  <spine toc=\"ncx\">" else "  <spine>"

            entry(
                zip, "OEBPS/content.opf",
                lines(
                    *buildList {
                        add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        add("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"$packageVersion\" unique-identifier=\"pub-id\">")
                        add("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
                        add("    <dc:identifier id=\"pub-id\">$identifier</dc:identifier>")
                        add("    <dc:title>$title</dc:title>")
                        add("    <dc:creator>$author</dc:creator>")
                        add("    <dc:language>$language</dc:language>")
                        // EPUB 3 强制要求 dcterms:modified，缺失会被 Readium 拒绝。
                        add("    <meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>")
                        add("  </metadata>")
                        add("  <manifest>")
                        addAll(manifest)
                        add("  </manifest>")
                        add(spineOpen)
                        addAll(spine)
                        add("  </spine>")
                        add("</package>")
                    }.toTypedArray()
                )
            )

            when (tocStyle) {
                TocStyle.NAV -> entry(zip, "OEBPS/nav.xhtml", navDocument(navEntries, nestedUnderFirst))
                TocStyle.NCX -> entry(zip, "OEBPS/toc.ncx", ncxDocument(navEntries))
            }

            chapters.forEach { (href, body) ->
                entry(
                    zip, "OEBPS/$href",
                    lines(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                        "<!DOCTYPE html>",
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\">",
                        "<head><title>Chapter</title></head>",
                        "<body>$body</body>",
                        "</html>"
                    )
                )
            }
        }
        return out.toByteArray()
    }

    private fun navDocument(
        entries: List<Pair<String, String>>,
        nestedUnderFirst: Pair<String, String>?
    ): ByteArray {
        val items = buildList {
            entries.forEachIndexed { index, (href, label) ->
                if (index == 0 && nestedUnderFirst != null) {
                    add("      <li><a href=\"$href\">$label</a>")
                    add("        <ol>")
                    add("          <li><a href=\"${nestedUnderFirst.first}\">${nestedUnderFirst.second}</a></li>")
                    add("        </ol>")
                    add("      </li>")
                } else {
                    add("      <li><a href=\"$href\">$label</a></li>")
                }
            }
        }
        return lines(
            *buildList {
                add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                add("<!DOCTYPE html>")
                add("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">")
                add("<head><title>TOC</title></head>")
                add("<body>")
                add("  <nav epub:type=\"toc\">")
                add("    <ol>")
                addAll(items)
                add("    </ol>")
                add("  </nav>")
                add("</body>")
                add("</html>")
            }.toTypedArray()
        )
    }

    /**
     * EPUB 2 的 NCX 目录。
     *
     * 不写 `<!DOCTYPE ncx ...>`：那个 public identifier 带外部 DTD URL，在 Robolectric 下解析器
     * 可能尝试取网络。NCX 的识别靠 xmlns，DOCTYPE 不是必需的。
     */
    private fun ncxDocument(entries: List<Pair<String, String>>): ByteArray = lines(
        *buildList {
            add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            add("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\" xml:lang=\"en\">")
            add("  <head>")
            add("    <meta name=\"dtb:uid\" content=\"urn:uuid:fixture\"/>")
            add("    <meta name=\"dtb:depth\" content=\"1\"/>")
            add("  </head>")
            add("  <docTitle><text>Fixture Book</text></docTitle>")
            add("  <navMap>")
            entries.forEachIndexed { index, (href, label) ->
                add("    <navPoint id=\"np$index\" playOrder=\"${index + 1}\">")
                add("      <navLabel><text>$label</text></navLabel>")
                add("      <content src=\"$href\"/>")
                add("    </navPoint>")
            }
            add("  </navMap>")
            add("</ncx>")
        }.toTypedArray()
    )

    /** [epub] 写哪种目录。 */
    private enum class TocStyle { NAV, NCX }

    private fun lines(vararg l: String): ByteArray =
        l.joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun entry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
