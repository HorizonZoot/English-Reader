package io.github.zoot.englishreader.data.importer

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 程序化构造 EPUB 字节，供 [EpubTextExtractorTest] 使用。
 *
 * 不放二进制 fixture 文件进仓库：真实 EPUB 的哪一处细节触发了测试无从判断，
 * 而这里每个字段都显式可见、可逐项篡改，坏在哪一目了然。
 *
 * ## 为什么全部用 joinToString 而不是 trimIndent
 *
 * `trimIndent()` 在**字符串插值之后**才计算最小缩进。插进来的 manifest/spine 行若自带
 * 缩进，会把最小值拉低，于是 `<?xml ...?>` 声明前留下空格——XML 禁止声明前有任何字符，
 * 解析直接失败。整批 fixture 曾因此全红。故这里一律逐行拼接，不依赖缩进推断。
 */
object EpubFixtures {

    const val MIMETYPE = "application/epub+zip"

    /** 一本结构完整、两章正文的最小 EPUB。 */
    fun minimalBook(
        title: String = "Test Book",
        chapters: List<String> = listOf("First chapter body.", "Second chapter body."),
        opfDir: String = "OEBPS"
    ): ByteArray {
        val prefix = if (opfDir.isEmpty()) "" else "$opfDir/"
        val manifest = chapters.indices.map { i ->
            """<item id="ch$i" href="ch$i.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = chapters.indices.map { i -> """<itemref idref="ch$i"/>""" }

        val entries = mutableMapOf(
            "META-INF/container.xml" to containerXml("${prefix}content.opf"),
            "${prefix}content.opf" to opf(title, manifest, spine)
        )
        chapters.forEachIndexed { i, body ->
            entries["${prefix}ch$i.xhtml"] = xhtml(body)
        }
        return zip(entries)
    }

    fun containerXml(fullPath: String): String = lines(
        """<?xml version="1.0" encoding="UTF-8"?>""",
        """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""",
        """  <rootfiles>""",
        """    <rootfile full-path="$fullPath" media-type="application/oebps-package+xml"/>""",
        """  </rootfiles>""",
        """</container>"""
    )

    fun opf(
        title: String,
        manifestItems: List<String>,
        spineItems: List<String>
    ): String = lines(
        """<?xml version="1.0" encoding="UTF-8"?>""",
        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">""",
        """  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""",
        """    <dc:title>$title</dc:title>""",
        """  </metadata>""",
        """  <manifest>""",
        *manifestItems.map { "    $it" }.toTypedArray(),
        """  </manifest>""",
        """  <spine>""",
        *spineItems.map { "    $it" }.toTypedArray(),
        """  </spine>""",
        """</package>"""
    )

    fun xhtml(body: String): String = lines(
        """<?xml version="1.0" encoding="UTF-8"?>""",
        """<html xmlns="http://www.w3.org/1999/xhtml">""",
        """  <head><title>chapter</title></head>""",
        """  <body><p>$body</p></body>""",
        """</html>"""
    )

    /** [bodyInner] 直接作为 `<body>` 的内容，用于测试段落标签/br/script 等结构。 */
    fun xhtmlRawBody(bodyInner: String): String = lines(
        """<?xml version="1.0" encoding="UTF-8"?>""",
        """<html xmlns="http://www.w3.org/1999/xhtml">""",
        """  <head><title>chapter</title></head>""",
        """  <body>$bodyInner</body>""",
        """</html>"""
    )

    fun encryptionXml(vararg encryptedPaths: String): String = lines(
        """<?xml version="1.0" encoding="UTF-8"?>""",
        """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""",
        *encryptedPaths.flatMap { path ->
            listOf(
                """  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">""",
                """    <CipherData><CipherReference URI="$path"/></CipherData>""",
                """  </EncryptedData>"""
            )
        }.toTypedArray(),
        """</encryption>"""
    )

    private fun lines(vararg parts: String): String = parts.joinToString("\n")

    /** 按给定 entry 表打包为 ZIP。mimetype 按 OCF 约定放在最前且不压缩。 */
    fun zip(entries: Map<String, String>): ByteArray =
        zipRaw(entries.mapValues { it.value.toByteArray(Charsets.UTF_8) })

    /** 打包为 ZIP，允许指定 entry 的原始字节（用于非 UTF-8 编码等场景）。 */
    fun zipRaw(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            writeStored(zos, "mimetype", MIMETYPE.toByteArray())
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 写一个 STORED（不压缩）entry。
     *
     * STORED 必须由调用方自行填 size 与 crc——ZipOutputStream 不会替你算，
     * 缺了会抛「STORED entry missing size, compressed size, or crc-32」。
     */
    private fun writeStored(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }
}
