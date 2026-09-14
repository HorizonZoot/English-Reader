package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XML 字节解码测试。
 *
 * 存在的原因：EPUB 的 container.xml / OPF / XHTML 允许用 UTF-16，而原实现直接
 * `String(bytes, UTF_8)`，会把合法的 UTF-16 文档解成夹 NUL 的垃圾、随后
 * XmlPullParser 解析失败，**合法的书被判为结构损坏**。
 */
class XmlBytesDecoderTest {

    private val xml = """<?xml version="1.0"?><root>hi</root>"""

    @Test
    fun decode_supportedEncoding_stripsBomAndPreservesXml() {
        listOf(
            Triple("UTF-8", Charsets.UTF_8, byteArrayOf()),
            Triple("UTF-8 BOM", Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
            Triple("UTF-16LE BOM", Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            Triple("UTF-16BE BOM", Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
            // 无 BOM 时可由 XML 起始字符 '<' 的字节序识别 UTF-16。
            Triple("UTF-16LE", Charsets.UTF_16LE, byteArrayOf()),
            Triple("UTF-16BE", Charsets.UTF_16BE, byteArrayOf())
        ).forEach { (case, charset, bom) ->
            val result = XmlBytesDecoder.decode(bom + xml.toByteArray(charset))

            assertEquals(case, xml, result)
            assertTrue("$case: BOM 残留会让 XmlPullParser 报错", result.startsWith("<?xml"))
        }
    }

    @Test
    fun decode_utf16WithEncodingDeclaration_isDecodedNotGarbled() {
        // 回归用例：原实现强制 UTF-8，这份文档会被解成夹 NUL 的垃圾并报 InvalidEpub
        val declared = """<?xml version="1.0" encoding="UTF-16"?><container/>"""
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

        val result = XmlBytesDecoder.decode(bom + declared.toByteArray(Charsets.UTF_16LE))

        assertEquals(declared, result)
        assertTrue("不得夹带 NUL", result.none { it == '\u0000' })
    }

    @Test
    fun decode_utf8Text_preservesUnicodeAndAscii() {
        // 普通 UTF-8 文档不能被误判成 UTF-16。
        listOf("<t>中文内容</t>", "<?xml version=\"1.0\"?><a b=\"c\"/>").forEach { doc ->
            assertEquals(doc, doc, XmlBytesDecoder.decode(doc.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun decode_invalidUtf8_isRejectedNotReplaced() {
        // 严格解码：非法序列须报错，不能静默替换成 U+FFFD（那是篡改正文）
        val prefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><p>".toByteArray()
        val suffix = "</p></body></html>".toByteArray()
        listOf(
            "invalid three-byte sequence" to
                byteArrayOf(0x3C, 0x74, 0x3E, 0xE4.toByte(), 0x3F, 0x3C, 0x2F, 0x74, 0x3E),
            "invalid two-byte sequence in XHTML" to
                (prefix + byteArrayOf(0xC3.toByte(), 0x28) + suffix)
        ).forEach { (case, bytes) ->
            val error = assertThrows(case, ImportException::class.java) {
                XmlBytesDecoder.decode(bytes)
            }
            assertEquals(case, ImportFailure.InvalidEpub, error.failure)
        }
    }

    @Test
    fun decode_noContent_returnsEmpty() {
        listOf(
            "empty input" to byteArrayOf(),
            "UTF-16 BOM only" to byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        ).forEach { (case, bytes) ->
            assertEquals(case, "", XmlBytesDecoder.decode(bytes))
        }
    }

    @Test
    fun decode_utf16LeadingWhitespace_detectsByteOrderBeyondShortPrefix() {
        // 短样本只测 decoder；长样本为无声明的合法 XML，覆盖旧 64 字节嗅探窗口漏判。
        listOf(
            Triple("LE short", "\n  $xml", Charsets.UTF_16LE),
            Triple("BE short", "\n$xml", Charsets.UTF_16BE),
            Triple("LE long", " ".repeat(32) + "<root>hi</root>", Charsets.UTF_16LE),
            Triple("BE long", "\n".repeat(40) + "<root>hi</root>", Charsets.UTF_16BE)
        ).forEach { (case, doc, charset) ->
            assertEquals(case, doc, XmlBytesDecoder.decode(doc.toByteArray(charset)))
        }
    }


}
