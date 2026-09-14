package io.github.zoot.englishreader.data.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * EPUB/XHTML 解析用的 XmlPullParser 工厂。
 *
 * 抽出来解决两件事：
 *
 * 1. **XXE 防线不再 fail-open**。原先两处各自 `runCatching { setFeature(...) }`，
 *    某个 parser 实现不支持 `FEATURE_PROCESS_DOCDECL` 时会被静默吞掉，然后继续解析
 *    不可信的 EPUB——安全保证与注释不符。现在统一校验 feature 的**最终状态**，
 *    不满足即拒绝导入（为何不是「设置失败即拒绝」见 [requireFeature]）。
 * 2. **让 EPUB 主链可被纯 JVM 单测覆盖**。`Xml.newPullParser()` 是 Android API，
 *    单元测试里返回 null，导致 ZIP → container → OPF → spine → DRM → XHTML 这条
 *    协调链从未被任何测试真正执行过。[factory] 可在测试中替换为 kXML 实现。
 */
object XmlParsers {

    /**
     * 创建 parser 的底层工厂。
     *
     * 生产环境用 Android 的 [Xml.newPullParser]；测试可替换（见
     * `XmlParsersTestSupport`）。除测试外不应改写。
     */
    @Volatile
    var factory: () -> XmlPullParser = { Xml.newPullParser() }

    /**
     * 创建一个已配置好的 parser 并绑定 [xml]。
     *
     * @throws ImportException [ImportFailure.InvalidEpub] parser 不可用，或安全相关的
     *   feature 最终状态不符要求——后者绝不静默降级：宁可拒绝这本书，也不拿未加固的
     *   解析器去处理不可信输入。
     */
    fun forXml(xml: String): XmlPullParser {
        val parser = runCatching { factory() }.getOrNull()
            ?: throw ImportException(ImportFailure.InvalidEpub)

        // EPUB 的 XML 普遍带前缀（dc:title、opf:role），必须开 namespace 并按 local name 匹配
        requireFeature(parser, XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // XXE / 实体炸弹防线：EPUB 正文不需要 DOCTYPE 与外部实体
        requireFeature(parser, XmlPullParser.FEATURE_PROCESS_DOCDECL, false)

        runCatching { parser.setInput(StringReader(xml)) }
            .getOrElse { throw ImportException(ImportFailure.InvalidEpub) }
        return parser
    }

    /**
     * 确保 feature 处于 [value]，否则拒绝导入。
     *
     * ## 为什么先查再设
     *
     * KXmlParser（Android `Xml.newPullParser()` 的实际实现）**根本不支持 DOCDECL**，
     * 它的 `getFeature` 恒返回 false，而任何 `setFeature(DOCDECL, ...)` 调用一律抛
     * `XmlPullParserException`——包括设成 false。若把「set 抛异常」等同于「防线失效」，
     * 就会在真机上拒收**每一本** EPUB。
     *
     * 真正要保证的是**最终状态**：DOCDECL 必须为 false。已经是 false 就无需设置，
     * 设置失败也无所谓；只有最终状态不符才拒绝。
     *
     * 这比原先的 `runCatching { setFeature(...) }` 严格：那种写法从不检查最终状态，
     * 遇到「默认开启且拒绝关闭」的 parser 会静默放行不可信输入。
     */
    private fun requireFeature(parser: XmlPullParser, name: String, value: Boolean) {
        if (currentFeature(parser, name) == value) return

        runCatching { parser.setFeature(name, value) }

        if (currentFeature(parser, name) != value) {
            throw ImportException(ImportFailure.InvalidEpub)
        }
    }

    /** 读 feature 当前值；读取本身失败时按「不满足要求」处理。 */
    private fun currentFeature(parser: XmlPullParser, name: String): Boolean? =
        runCatching { parser.getFeature(name) }.getOrNull()
}
