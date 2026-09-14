package io.github.zoot.englishreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryAfterParserTest {
    @Test
    fun parse_deltaSeconds_acceptsOnlyBoundedNonNegativeValues() {
        val parser = RetryAfterParser { 0L }
        assertEquals(0L, parser.parse("0"))
        assertEquals(86_400L, parser.parse("86400"))
        assertNull(parser.parse("-1"))
        assertNull(parser.parse("-0"))
        assertNull(parser.parse("+1"))
        assertNull(parser.parse("86401"))
        assertNull(parser.parse("not-a-date"))
    }

    @Test
    fun parse_httpDate_usesInjectedClockAndRoundsUp() {
        val target = 1_445_412_480_000L
        assertEquals(
            1L,
            RetryAfterParser { target - 999L }
                .parse("Wed, 21 Oct 2015 07:28:00 GMT")
        )
        assertEquals(
            0L,
            RetryAfterParser { target + 1L }
                .parse("Wed, 21 Oct 2015 07:28:00 GMT")
        )
        assertNull(
            RetryAfterParser { target - 86_400_001L }
                .parse("Wed, 21 Oct 2015 07:28:00 GMT")
        )
    }
}
