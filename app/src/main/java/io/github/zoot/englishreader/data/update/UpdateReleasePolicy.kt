package io.github.zoot.englishreader.data.update

import okhttp3.HttpUrl.Companion.toHttpUrl

object UpdateReleasePolicy {
    const val REPOSITORY = "HorizonZoot/English-Reader"
    const val API_PATH = "repos/$REPOSITORY/releases?per_page=1"
    const val FEED_PATH = "$REPOSITORY/releases.atom"
    const val LATEST_RELEASE_URL = "https://github.com/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 256L * 1024 * 1024

    fun tagFromReleaseUrl(value: String): String? {
        val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return null
        if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null
        ) return null
        val parts = url.pathSegments
        if (parts.size != 5 || parts.take(4) != listOf("HorizonZoot", "English-Reader", "releases", "tag")) {
            return null
        }
        return parts.last().takeIf(::isValidTag)
    }

    fun isValidTag(tag: String): Boolean =
        tag.length in 1..100 && AppVersion.parse(tag) != null &&
            tag.all { it.isLetterOrDigit() && it.code < 128 || it in ".-+" }

    fun versionName(tag: String): String {
        require(isValidTag(tag))
        return tag.trim().removePrefix("v").removePrefix("V")
    }

    fun fileName(tag: String): String = "EnglishReader-${versionName(tag)}.apk"

    fun downloadUrl(tag: String): String {
        require(isValidTag(tag))
        return "https://github.com/".toHttpUrl().newBuilder()
            .addPathSegments("$REPOSITORY/releases/download")
            .addPathSegment(tag)
            .addPathSegment(fileName(tag))
            .build().toString()
    }

    fun releaseUrl(tag: String): String {
        require(isValidTag(tag))
        return "https://github.com/".toHttpUrl().newBuilder()
            .addPathSegments("$REPOSITORY/releases/tag")
            .addPathSegment(tag)
            .build().toString()
    }
}
