package io.github.zoot.englishreader.data.repository

import java.util.concurrent.TimeUnit

internal object ExplanationCachePolicy {
    val CACHE_TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    const val MAX_CACHE_ENTRIES: Int = 500

    fun expireBefore(nowMillis: Long): Long = nowMillis - CACHE_TTL_MILLIS
}
