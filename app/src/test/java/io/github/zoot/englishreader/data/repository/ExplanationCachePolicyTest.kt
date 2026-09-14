package io.github.zoot.englishreader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExplanationCachePolicyTest {

    @Test
    fun expireBefore_usesExactlyThirtyDays() {
        val now = TimeUnit.DAYS.toMillis(100)

        assertEquals(
            TimeUnit.DAYS.toMillis(70),
            ExplanationCachePolicy.expireBefore(now)
        )
        assertEquals(500, ExplanationCachePolicy.MAX_CACHE_ENTRIES)
    }
}
