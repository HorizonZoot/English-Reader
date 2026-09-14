package io.github.zoot.englishreader.data.importer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/**
 * 让 [XmlParsers] 在纯 JVM 单测里可用。
 *
 * `Xml.newPullParser()` 是 Android API，单测中返回 null，故 EPUB 主链
 * （ZIP → container → OPF → spine → DRM → XHTML）此前**从未被任何测试真正执行**——
 * 叶子函数全绿仍可能整本书无法导入。这里换成 kXML2（正是 Android 底层用的实现），
 * 使那条链能被覆盖。
 */
object XmlParsersTestSupport {

    private val factory: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
    }

    /** 装上 kXML2 工厂。在 `@Before` 里调用。 */
    fun install() {
        XmlParsers.factory = { factory.newPullParser() }
    }

    /**
     * 装上一个「DOCDECL 默认开启且无法关闭」的工厂，用于验证 XXE 防线是 fail-closed。
     *
     * 注意这与 KXmlParser 的真实行为不同：后者 DOCDECL 恒为 false（已满足要求）、
     * 只是拒绝 setFeature 调用，那是安全的。真正危险的是「默认开启且关不掉」——
     * 原先的 runCatching 写法会静默放行这种 parser。
     */
    fun installDocdeclUnclosable() {
        XmlParsers.factory = { DocdeclStuckOnParser(factory.newPullParser()) }
    }

    /** 还原为生产实现。在 `@After` 里调用，避免污染其他测试。 */
    fun reset() {
        XmlParsers.factory = { android.util.Xml.newPullParser() }
    }

    private class DocdeclStuckOnParser(
        private val delegate: XmlPullParser
    ) : XmlPullParser by delegate {

        override fun setFeature(name: String, state: Boolean) {
            if (name == XmlPullParser.FEATURE_PROCESS_DOCDECL) {
                throw XmlPullParserException("cannot change: $name")
            }
            delegate.setFeature(name, state)
        }

        // 恒为 true = DOCTYPE 处理一直开着，且上面拒绝关闭 → 必须被拒绝导入
        override fun getFeature(name: String): Boolean =
            if (name == XmlPullParser.FEATURE_PROCESS_DOCDECL) true
            else delegate.getFeature(name)
    }
}
