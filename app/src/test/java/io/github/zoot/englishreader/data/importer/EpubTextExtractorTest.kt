package io.github.zoot.englishreader.data.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * EPUB 主链测试：ZIP 复制 → container.xml → OPF → manifest/spine → DRM → XHTML。
 *
 * 这条协调链此前**零覆盖**——`Xml.newPullParser()` 是 Android API，单测里返回 null，
 * 于是所有叶子函数（OcfPathNormalizer、XmlBytesDecoder、XhtmlTextExtractor）全绿，
 * 整本书却可能一篇都导不进来。[XmlParsersTestSupport] 换上 kXML2 后这条链才真正可测。
 */
class EpubTextExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var extractor: EpubTextExtractor

    @Before
    fun setUp() {
        XmlParsersTestSupport.install()
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns tempFolder.root
        extractor = EpubTextExtractor(context)
    }

    @After
    fun tearDown() {
        XmlParsersTestSupport.reset()
    }

    /** 把 EPUB 字节挂到一个 mock URI 上。每次 open 都返回新流。 */
    private fun uriFor(bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun failureOf(bytes: ByteArray): ImportFailure {
        val error = runCatching { extractor.extract(uriFor(bytes)) }.exceptionOrNull()
        assertTrue("expected ImportException but was $error", error is ImportException)
        return (error as ImportException).failure
    }

    // ---- 正常路径 ----

    @Test
    fun extract_chapters_preservesSpineOrderAndParagraphBoundaries() {
        val cases = listOf(
            listOf("First chapter body.", "Second chapter body.") to
                "First chapter body.\n\nSecond chapter body.",
            listOf("Alpha.", "Beta.", "Gamma.") to "Alpha.\n\nBeta.\n\nGamma."
        )
        cases.forEach { (chapters, expectedText) ->
            val result = extractor.extract(uriFor(EpubFixtures.minimalBook(chapters = chapters)))
            val label = "chapters=" + chapters.size

            assertEquals(label, "Test Book", result.declaredTitle)
            assertEquals(label, expectedText, result.text)
            assertEquals(label, chapters.size, result.text.split(Regex("\\n\\s*\\n")).size)
        }
    }

    @Test
    fun extract_opfAtContainerRoot_resolvesHrefsCorrectly() {
        // OPF 在根目录时 opfDir 为空串，href 拼接不能多出前导斜杠
        val result = extractor.extract(uriFor(EpubFixtures.minimalBook(opfDir = "")))

        assertEquals("First chapter body.\n\nSecond chapter body.", result.text)
    }

    @Test
    fun extract_titleFromDcTitleNotFromReferenceElement() {
        // dc:title 只在 <metadata> 内有效。<reference title="..."> 之类同名元素不该被取走
        val opf = listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""",
            """  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""",
            """    <dc:title>Real Title</dc:title>""",
            """  </metadata>""",
            """  <manifest>""",
            """    <item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>""",
            """  </manifest>""",
            """  <spine><itemref idref="c0"/></spine>""",
            """  <guide><reference title="Decoy Title" href="c0.xhtml" type="text"/></guide>""",
            """</package>"""
        ).joinToString("\n")

        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to opf,
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Body.")
        )

        val result = extractor.extract(uriFor(EpubFixtures.zip(entries)))

        assertEquals("Real Title", result.declaredTitle)
    }

    @Test
    fun extract_hrefWithPercentEncoding_findsEntry() {
        // href 是 URI 引用，ZIP entry 名是解码后的字面值。不解码就 getEntry() 查不到，
        // 合法书被判 InvalidEpub。这条链此前只在 OcfPathNormalizer 层面被测过。
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Spaced",
                manifestItems = listOf(
                    """<item id="c0" href="ch%20one.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""")
            ),
            "OEBPS/ch one.xhtml" to EpubFixtures.xhtml("Decoded body.")
        )

        val result = extractor.extract(uriFor(EpubFixtures.zip(entries)))

        assertEquals("Decoded body.", result.text)
    }

    @Test
    fun extract_utf16Opf_isDecodedByXmlRules() {
        // OPF 用 UTF-16 是合法的。强行按 UTF-8 转 String 会产出夹 NUL 的垃圾、
        // 解析失败 → 合法书被判损坏。此前只在 XmlBytesDecoder 层面被测过。
        val opf = EpubFixtures.opf(
            title = "UTF16 Book",
            manifestItems = listOf(
                """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
            ),
            spineItems = listOf("""<itemref idref="c0"/>""")
        )
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val entries = mapOf(
            "META-INF/container.xml" to
                EpubFixtures.containerXml("OEBPS/content.opf").toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to bom + opf.toByteArray(Charsets.UTF_16LE),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Body.").toByteArray(Charsets.UTF_8)
        )

        val result = extractor.extract(uriFor(EpubFixtures.zipRaw(entries)))

        assertEquals("UTF16 Book", result.declaredTitle)
        assertEquals("Body.", result.text)
    }

    @Test
    fun extract_encodedXhtmlBytes_preservesUnicodeAcrossSupportedEncodings() {
        val utf8 = """<?xml version="1.0"?><html><body><p>Unicode: 中文 café</p></body></html>"""
        val utf16 = """<?xml version="1.0"?><html><body><p>UTF-16 text: 中文</p></body></html>"""
        val declaredUtf8 =
            """<?xml version="1.0" encoding="UTF-8"?><html><body><p>café 中文</p></body></html>"""
        val declaredUtf16 =
            """<?xml version="1.0" encoding="UTF-16"?><html><body><p>BOM-less UTF-16: 中文</p></body></html>"""
        listOf(
            Triple(
                "UTF-8 BOM",
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8.toByteArray(Charsets.UTF_8),
                "Unicode: 中文 café"
            ),
            Triple(
                "UTF-16LE BOM",
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16.toByteArray(Charsets.UTF_16LE),
                "UTF-16 text: 中文"
            ),
            Triple(
                "UTF-16BE BOM",
                byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16.toByteArray(Charsets.UTF_16BE),
                "UTF-16 text: 中文"
            ),
            Triple("UTF-8 declaration", declaredUtf8.toByteArray(Charsets.UTF_8), "café 中文"),
            Triple(
                "BOM-less UTF-16 declaration",
                declaredUtf16.toByteArray(Charsets.UTF_16LE),
                "BOM-less UTF-16: 中文"
            )
        ).forEach { (case, bytes, expected) ->
            val text = try {
                XhtmlTextExtractor.extract(XmlBytesDecoder.decode(bytes))
            } catch (error: Exception) {
                throw AssertionError(case, error)
            }
            assertEquals(case, expected, text)
        }
    }

    @Test
    fun extract_encodedImageOnlyXhtml_returnsBlankText() {
        val xhtml =
            """<?xml version="1.0" encoding="UTF-8"?><html><body><img src="c.png"/></body></html>"""

        val text = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(xhtml.toByteArray(Charsets.UTF_8)))

        assertTrue(text.isBlank())
    }

    @Test
    fun extract_encodedEntitiesAndBreaks_preservesProseAndParagraphs() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Entities</title></head>
              <body>
                <p>Tom &amp; Jerry &mdash; caf&eacute; na&iuml;ve.</p>
                <p>First line<br/>second line.</p>
                <p>Unicode: 中文 段落。</p>
              </body>
            </html>
        """.trimIndent()

        val text = XhtmlTextExtractor.extract(XmlBytesDecoder.decode(xhtml.toByteArray(Charsets.UTF_8)))

        assertTrue(text.contains("Tom & Jerry"))
        assertTrue(text.contains("\u2014"))
        assertTrue(text.contains("caf&eacute;"))
        assertTrue(text.contains("na&iuml;ve"))
        assertTrue(text.contains("First line\nsecond line."))
        assertTrue(text.contains("\n\n"))
        assertTrue(text.contains("Unicode: 中文 段落。"))
    }

    @Test
    fun extract_namedEntityInChapter_isResolvedNotRejected() {
        // &nbsp; 不是 XML 内建实体。不预处理会抛 unresolved entity → 合法书被拒。
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Entities",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("He&nbsp;paused&mdash;then left.")
        )

        val result = extractor.extract(uriFor(EpubFixtures.zip(entries)))

        assertEquals("He paused—then left.", result.text)
    }

    // ---- XHTML 结构：此前完全依赖 parser、无任何覆盖 ----

    @Test
    fun extract_brIsIntraParagraphNewlineNotParagraphBreak() {
        // <br> 转成空段会虚增段落数，直接破坏 ParagraphAligner 的英文段/译文段 index 配对
        val result = extractSingleChapter("<p>Line one.<br/>Line two.</p>")

        assertEquals("Line one.\nLine two.", result)
        assertEquals(1, result.split(Regex("\\n\\s*\\n")).size)
    }

    @Test
    fun extract_consecutiveParagraphTagsBecomeParagraphBoundaries() {
        val result = extractSingleChapter("<p>Alpha.</p><p>Beta.</p><div>Gamma.</div>")

        assertEquals(3, result.split(Regex("\\n\\s*\\n")).size)
    }

    @Test
    fun extract_scriptAndStyleContentIsSkipped() {
        val result = extractSingleChapter(
            "<p>Keep.</p><script>var x = 1;</script><style>p{color:red}</style><p>Also keep.</p>"
        )

        assertTrue("script 内容不得进入正文，实际: $result", !result.contains("var x"))
        assertTrue("style 内容不得进入正文，实际: $result", !result.contains("color:red"))
        assertEquals("Keep.\n\nAlso keep.", result)
    }

    @Test
    fun extract_nestedDivsDoNotStackBlankLines() {
        // 嵌套 div 会连续触发段落边界，堆叠出的多余空行会让段落计数错乱
        val result = extractSingleChapter("<div><div><p>Deep text.</p></div></div>")

        assertEquals("Deep text.", result)
    }

    /** 构造只有一章的书并返回其正文，用于聚焦 XHTML 结构行为。 */
    private fun extractSingleChapter(bodyInner: String): String {
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "T",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtmlRawBody(bodyInner)
        )
        return extractor.extract(uriFor(EpubFixtures.zip(entries))).text
    }

    // ---- 结构损坏：必须拒绝，不能静默导入残缺文章 ----

    @Test
    fun extract_missingContainerXml_isRejected() {
        val entries = mapOf("OEBPS/content.opf" to "irrelevant")

        assertEquals(ImportFailure.InvalidEpub, failureOf(EpubFixtures.zip(entries)))
    }

    /**
     * OPF 本身 XML 语法坏掉（未闭合标签）必须报 [ImportFailure.InvalidEpub]。
     *
     * 与周围几个用例的区别：那些验的是结构性缺失（缺 container.xml、rootfile
     * media-type 错、spine idref 不在 manifest、spine 条目不在 ZIP）—— 文件都是合法 XML，
     * 只是内容不对，走的是自己的校验分支。这一条验的是 XML parser 层就抛异常的情况，
     * 靠 catch 把 parser 异常转成 typed failure，是另一条路径。
     *
     * 补这一条的直接原因：文档曾声称「损坏 OPF 由本类覆盖」，而本类当时并没有这种
     * 用例 —— 唯一的损坏 OPF fixture 在 src/testDebug 的 spike 里。现在该声称是真的了。
     */
    @Test
    fun extract_corruptOpfXml_isRejected() {
        val container = listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""",
            """  <rootfiles>""",
            """    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>""",
            """  </rootfiles>""",
            """</container>"""
        ).joinToString("\n")
        // 未闭合的 <manifest>：parser 在遇到 </package> 时抛异常。
        val corruptOpf = listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">""",
            """  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""",
            """    <dc:title>Corrupt</dc:title>""",
            """  </metadata>""",
            """  <manifest>""",
            """    <item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>""",
            """</package>"""
        ).joinToString("\n")

        assertEquals(
            ImportFailure.InvalidEpub,
            failureOf(
                EpubFixtures.zip(
                    mapOf(
                        "META-INF/container.xml" to container,
                        "OEBPS/content.opf" to corruptOpf,
                        "OEBPS/c0.xhtml" to EpubFixtures.xhtmlRawBody("<p>body</p>")
                    )
                )
            )
        )
    }

    @Test
    fun extract_notAZip_isRejected() {
        assertEquals(
            ImportFailure.InvalidEpub,
            failureOf("this is plain text, not a zip".toByteArray())
        )
    }

    @Test
    fun extract_rootfileWithWrongMediaType_isRejected() {
        // rootfile 须按 media-type 选，而不是取第一个 .opf
        val container = listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""",
            """  <rootfiles>""",
            """    <rootfile full-path="OEBPS/other.opf" media-type="application/wrong+xml"/>""",
            """  </rootfiles>""",
            """</container>"""
        ).joinToString("\n")

        val entries = mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/other.opf" to EpubFixtures.opf(
                title = "Wrong media type",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Body.")
        )

        assertEquals(ImportFailure.InvalidEpub, failureOf(EpubFixtures.zip(entries)))
    }

    @Test
    fun extract_spineIdrefNotInManifest_isRejectedNotSilentlyTruncated() {
        // 静默跳过会导入一篇残缺文章并报「成功」，用户不知道正文被截断——比直接拒绝更糟
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Broken",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf(
                    """<itemref idref="c0"/>""",
                    """<itemref idref="missing"/>"""
                )
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Only chapter.")
        )

        assertEquals(ImportFailure.InvalidEpub, failureOf(EpubFixtures.zip(entries)))
    }

    @Test
    fun extract_spineEntryMissingFromZip_isRejected() {
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Broken",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>""",
                    """<item id="c1" href="gone.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""", """<itemref idref="c1"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Only chapter.")
        )

        assertEquals(ImportFailure.InvalidEpub, failureOf(EpubFixtures.zip(entries)))
    }

    @Test
    fun extract_itemrefMissingIdref_isRejected() {
        // 缺 idref 是结构损坏，与 linear="no" 的「合法忽略」性质不同
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Broken",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""", """<itemref linear="yes"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Body.")
        )

        assertEquals(ImportFailure.InvalidEpub, failureOf(EpubFixtures.zip(entries)))
    }

    @Test
    fun extract_linearNoItemIsSkippedNotRejected() {
        // 对照：linear="no"（封面/版权页）是合法的主动忽略
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Fine",
                manifestItems = listOf(
                    """<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>""",
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf(
                    """<itemref idref="cover" linear="no"/>""",
                    """<itemref idref="c0"/>"""
                )
            ),
            "OEBPS/cover.xhtml" to EpubFixtures.xhtml("Cover art page."),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Real body.")
        )

        val result = extractor.extract(uriFor(EpubFixtures.zip(entries)))

        assertEquals("Real body.", result.text)
    }

    @Test
    fun extract_pathEscapingContainerRoot_isRejected() {
        val container = listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""",
            """  <rootfiles>""",
            """    <rootfile full-path="../outside.opf" media-type="application/oebps-package+xml"/>""",
            """  </rootfiles>""",
            """</container>"""
        ).joinToString("\n")

        assertEquals(
            ImportFailure.InvalidEpub,
            failureOf(EpubFixtures.zip(mapOf("META-INF/container.xml" to container)))
        )
    }

    @Test
    fun extract_emptyChapters_isRejectedAsEmptyContent() {
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Empty",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtmlRawBody("<p></p>")
        )

        assertEquals(ImportFailure.EmptyContent, failureOf(EpubFixtures.zip(entries)))
    }

    // ---- DRM ----

    @Test
    fun extract_onlyFontObfuscated_stillImports() {
        // encryption.xml 也用于字体混淆，「存在即报错」会误判合法书
        val entries = mapOf(
            "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
            "META-INF/encryption.xml" to EpubFixtures.encryptionXml("OEBPS/fonts/x.otf"),
            "OEBPS/content.opf" to EpubFixtures.opf(
                title = "Obfuscated Font",
                manifestItems = listOf(
                    """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                ),
                spineItems = listOf("""<itemref idref="c0"/>""")
            ),
            "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Readable body.")
        )

        val result = extractor.extract(uriFor(EpubFixtures.zip(entries)))

        assertEquals("Readable body.", result.text)
    }

    @Test
    fun extract_encryptedSpineChapter_directAndNormalizedPaths_areRejectedAsEncrypted() {
        listOf(
            "direct" to "OEBPS/c0.xhtml",
            "normalized" to "OEBPS/./c0.xhtml"
        ).forEach { (case, encryptionPath) ->
            val entries = mapOf(
                "META-INF/container.xml" to EpubFixtures.containerXml("OEBPS/content.opf"),
                "META-INF/encryption.xml" to EpubFixtures.encryptionXml(encryptionPath),
                "OEBPS/content.opf" to EpubFixtures.opf(
                    title = "DRM",
                    manifestItems = listOf(
                        """<item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/>"""
                    ),
                    spineItems = listOf("""<itemref idref="c0"/>""")
                ),
                "OEBPS/c0.xhtml" to EpubFixtures.xhtml("Ciphertext.")
            )

            assertEquals(case, ImportFailure.EncryptedEpub, failureOf(EpubFixtures.zip(entries)))
        }
    }

    // ---- XXE 防线 ----

    @Test
    fun extract_parserThatCannotDisableDocdecl_isRejected() {
        // fail-closed：DOCTYPE 处理关不掉时必须拒绝，不能静默拿未加固的解析器
        // 去处理不可信 EPUB。原先的 runCatching 写法会放行。
        XmlParsersTestSupport.installDocdeclUnclosable()

        assertEquals(ImportFailure.InvalidEpub, failureOf(EpubFixtures.minimalBook()))
    }
}
