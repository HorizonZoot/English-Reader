package io.github.zoot.englishreader.data.update

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

internal data class ReleaseFeedEntry(
    val tag: String,
    val title: String?,
    val htmlContent: String?,
    val url: String
)

internal object GitHubReleaseFeedParser {
    const val MAX_FEED_BYTES = 512L * 1024
    private const val ATOM = "http://www.w3.org/2005/Atom"

    fun parse(xml: String): List<ReleaseFeedEntry> {
        if (xml.length > MAX_FEED_BYTES || xml.contains("<!DOCTYPE", ignoreCase = true) ||
            xml.contains("<!ENTITY", ignoreCase = true)
        ) throw IOException("Invalid release feed")
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        }
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) throw IOException("Unsafe XML parser")
        parser.setInput(StringReader(xml))
        val result = mutableListOf<ReleaseFeedEntry>()
        var rootSeen = false
        var title: String? = null
        var content: String? = null
        var url: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (parser.depth > 32) throw IOException("Release feed nesting limit exceeded")
            if (event == XmlPullParser.START_TAG) {
                if (parser.depth == 1) {
                    if (parser.namespace != ATOM || parser.name != "feed") throw IOException("Invalid feed root")
                    rootSeen = true
                } else if (parser.namespace == ATOM && parser.depth == 2 && parser.name == "entry") {
                    title = null
                    content = null
                    url = null
                } else if (parser.namespace == ATOM && parser.depth == 3) {
                    when (parser.name) {
                        "title" -> title = parser.nextText()
                        "content" -> content = parser.nextText()
                        "link" -> if (parser.getAttributeValue(null, "rel") == "alternate") {
                            url = parser.getAttributeValue(null, "href")
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == ATOM &&
                parser.depth == 2 && parser.name == "entry"
            ) {
                val link = url
                val tag = link?.let(UpdateReleasePolicy::tagFromReleaseUrl)
                if (tag != null) result += ReleaseFeedEntry(tag, title, content, link)
            }
            event = parser.next()
        }
        if (!rootSeen) throw IOException("Missing release feed")
        return result
    }
}
