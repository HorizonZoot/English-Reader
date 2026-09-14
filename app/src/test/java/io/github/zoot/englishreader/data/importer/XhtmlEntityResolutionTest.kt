package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XHTML 命名实体预处理的直接单测。
 *
 * 完整的 [XhtmlTextExtractor.extract]（含段落标签/br/script 跳过）由
 * `EpubTextExtractorTest` 经 `XmlParsers.factory` 注入 kXML2 后覆盖。这里单列实体
 * 预处理，是因为它发生在 parser 之前、是纯字符串变换，值得独立于 EPUB 主链验证。
 *
 * 之所以值得单列：实体表外的未知实体原先被静默替换成空串，`caf&eacute;` 会变成 `caf`
 * ——正文被悄悄删字，比留个 `&eacute;` 严重得多。
 */
class XhtmlEntityResolutionTest {

    /** 实体预处理是私有的实现细节，故用反射直接调它，不经 parser。 */
    private fun resolve(input: String): String {
        val method = XhtmlTextExtractor::class.java
            .getDeclaredMethod("resolveEntities", String::class.java)
        method.isAccessible = true
        return method.invoke(XhtmlTextExtractor, input) as String
    }

    @Test
    fun resolve_knownEntity_isReplaced() {
        assertEquals("a b", resolve("a&nbsp;b"))
        assertEquals("He paused—then left.", resolve("He paused&mdash;then left."))
        assertEquals("“Hi,” she said.", resolve("&ldquo;Hi,&rdquo; she said."))
    }

    @Test
    fun resolve_parserOwnedMarkupAndLiteralText_remainsUnchanged() {
        // XML 内建实体和数字引用留给 parser；CDATA / 注释 / 声明 / PI 中的实体是字面文本。
        // 未闭合 CDATA 也必须保留余下内容，否则会静默删字。
        listOf(
            "Tom &amp; Jerry",
            "&lt;tag&gt;",
            "&quot;q&quot; &apos;a&apos;",
            "dash&#8212;here",
            "hex&#x2014;here",
            "no entities here",
            "a & b",
            "<p><![CDATA[caf&eacute;]]></p>",
            "<p><![CDATA[a&nbsp;b]]></p>",
            "<p><!-- caf&eacute; --></p>",
            "<?xml-stylesheet href=\"a&eacute;.css\"?><p>x</p>",
            "<!DOCTYPE html SYSTEM \"a&eacute;.dtd\"><p>x</p>",
            "<p><![CDATA[caf&eacute;"
        ).forEach { input ->
            assertEquals(input, input, resolve(input))
        }
    }

    @Test
    fun resolve_unknownEntity_doesNotSwallowSurroundingText() {
        // 未收录实体转义后完整保留，实体两侧的正文也不能丢失。
        listOf(
            "caf&eacute;" to "caf&amp;eacute;",
            "before&unknownthing;after" to "before&amp;unknownthing;after"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, resolve(input))
        }
    }

    @Test
    fun resolve_manyShortCommentsCompleteQuickly() {
        // 每处理完一段就分别搜索 4 种起始标记，不存在的 CDATA/PI 会被反复扫完整个剩余
        // 字符串 → O(n²)。实测：输入翻倍耗时翻四倍，360 KB 需 3.5 秒，而 entry 上限是
        // 4 MiB（约 11 倍），即数百秒。4 MiB 的字节上限挡不住计算量攻击，
        // 且循环内没有取消检查。
        val doc = buildString {
            append("<html><body>")
            repeat(40_000) { append("x<!--c-->") }
            append("</body></html>")
        }

        val elapsed = kotlin.system.measureTimeMillis { resolve(doc) }

        assertTrue("实体扫描退化为 O(n^2)，耗时 ${elapsed}ms", elapsed < 1_000)
    }

    @Test
    fun resolve_doctypeWithGreaterThanInQuotedLiteral_isNotRewritten() {
        // XML 的 SystemLiteral 只排除匹配的引号，**不排除 '>'**。在第一个 > 就收尾会
        // 让声明区的后半段被当成正文，其中的 &eacute; 被改写。
        val doc = """<!DOCTYPE html SYSTEM "a>b&eacute;.dtd"><p>x</p>"""

        val result = resolve(doc)

        assertTrue("DOCTYPE 内的实体不得被改写，实际: $result", result.contains("a>b&eacute;.dtd"))
    }

    @Test
    fun resolve_entityOutsideCdataStillResolved() {
        // 对照：CDATA 之外的实体照常处理
        assertEquals(
            "<p>a b<![CDATA[&nbsp;]]>c d</p>",
            resolve("<p>a&nbsp;b<![CDATA[&nbsp;]]>c&nbsp;d</p>")
        )
    }


}
