package io.github.zoot.englishreader.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleasePolicyTest {
    @Test
    fun packageName_andUrl_useTheSelectedReleaseNotLatestRedirect() {
        assertEquals("EnglishReader-0.1.4-beta.apk", UpdateReleasePolicy.fileName("v0.1.4-beta"))
        assertEquals(
            "https://github.com/HorizonZoot/English-Reader/releases/download/v0.1.4-beta/EnglishReader-0.1.4-beta.apk",
            UpdateReleasePolicy.downloadUrl("v0.1.4-beta")
        )
        assertEquals("0.1.4-beta", UpdateReleasePolicy.versionName("V0.1.4-beta"))
    }

    @Test
    fun releaseUrl_onlyAcceptsThisRepositoryAndSafeVersions() {
        assertEquals("v0.1.4-beta", UpdateReleasePolicy.tagFromReleaseUrl(
            "https://github.com/HorizonZoot/English-Reader/releases/tag/v0.1.4-beta"
        ))
        for (url in listOf(
            "http://github.com/HorizonZoot/English-Reader/releases/tag/v1.0",
            "https://github.com.evil.test/HorizonZoot/English-Reader/releases/tag/v1.0",
            "https://user@github.com/HorizonZoot/English-Reader/releases/tag/v1.0",
            "https://github.com:444/HorizonZoot/English-Reader/releases/tag/v1.0",
            "https://github.com/HorizonZoot/English-Reader/releases/tag/v1.0?asset=other",
            "https://github.com/HorizonZoot/English-Reader/releases/tag/v1.0#other",
            "https://github.com/another/repo/releases/tag/v1.0",
            "https://github.com/HorizonZoot/English-Reader/releases/tag/v1.0%2Ffile",
            "https://github.com/HorizonZoot/English-Reader/releases/tag/latest"
        )) assertNull(url, UpdateReleasePolicy.tagFromReleaseUrl(url))
    }

    @Test
    fun unsafeTags_cannotCreateDownloadPaths() {
        for (tag in listOf("", "../1.0", "1.0/file", "1.0?x=1", "1.0 beta", "１.0", "1".repeat(101))) {
            assertFalse(tag, UpdateReleasePolicy.isValidTag(tag))
            assertThrows(IllegalArgumentException::class.java) { UpdateReleasePolicy.downloadUrl(tag) }
        }
    }
}
