package io.github.zoot.englishreader.data.update

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GitHubReleaseFeedParserTest {
    @Test
    fun parse_publishedEntries_retainsVersionLinkAndHtmlNotes() {
        val result = GitHubReleaseFeedParser.parse(atomFeed("v0.1.4-beta", "v0.1.3-beta"))
        assertEquals(listOf("v0.1.4-beta", "v0.1.3-beta"), result.map { it.tag })
        assertEquals("English Reader v0.1.4-beta", result.first().title)
        assertEquals("<p>Better <strong>updates</strong></p>", result.first().htmlContent)
        assertEquals("https://github.com/HorizonZoot/English-Reader/releases/tag/v0.1.4-beta", result.first().url)
    }

    @Test
    fun parse_foreignRepositoryAndInvalidTags_cannotBecomeUpdates() {
        assertTrue(GitHubReleaseFeedParser.parse(atomFeed("latest", "../../other")).isEmpty())
        assertTrue(GitHubReleaseFeedParser.parse(atomFeed("v1.0").replace("HorizonZoot/English-Reader", "someone/else")).isEmpty())
    }

    @Test
    fun parse_emptyFeed_isDifferentFromHtmlOrWrongNamespace() {
        assertTrue(GitHubReleaseFeedParser.parse(atomFeed()).isEmpty())
        for (value in listOf("", "<html>Not a feed</html>", "<feed xmlns=\"https://invalid.test\"/>")) {
            assertThrows(Exception::class.java) { GitHubReleaseFeedParser.parse(value) }
        }
    }

    @Test
    fun parse_doctypeAndOversizedInput_areRejected() {
        for (value in listOf(
            "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///private'>]>" + atomFeed("v1.0"),
            "x".repeat(GitHubReleaseFeedParser.MAX_FEED_BYTES.toInt() + 1)
        )) {
            assertThrows(IOException::class.java) { GitHubReleaseFeedParser.parse(value) }
        }
    }

    @Test
    fun parse_excessiveNesting_isRejected() {
        val value = "<feed xmlns=\"http://www.w3.org/2005/Atom\">" + "<x>".repeat(40) + "</x>".repeat(40) + "</feed>"
        assertThrows(IOException::class.java) { GitHubReleaseFeedParser.parse(value) }
    }
}

internal fun atomFeed(vararg tags: String): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>Release history</title>
      ${tags.joinToString("\n") { tag -> """
      <entry>
        <id>tag:github.com,2008:Repository/123/$tag</id>
        <link rel="alternate" href="https://github.com/HorizonZoot/English-Reader/releases/tag/$tag"/>
        <title>English Reader $tag</title>
        <content type="html">&lt;p&gt;Better &lt;strong&gt;updates&lt;/strong&gt;&lt;/p&gt;</content>
      </entry>
      """.trimIndent() }}
    </feed>
""".trimIndent().trimStart()
