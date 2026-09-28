package io.github.zoot.englishreader.data.dictionary

import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryPackCsvReaderTest {
    @Test
    fun readLine_mixedTerminatorsAndEof_preservesLineContent() = runBlocking {
        val reader = DictionaryPackCsvReader(StringReader("first\r\n\rsecond\nlast"))
        assertEquals("first", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals("second", reader.readLine())
        assertEquals("last", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun readLine_exactBudget_acceptsFinalLineWithOrWithoutTerminator() = runBlocking {
        val line = "x".repeat(64 * 1024)
        for (terminator in listOf("", "\n", "\r\n")) {
            val reader = DictionaryPackCsvReader(StringReader(line + terminator))
            assertEquals(line, reader.readLine())
            assertNull(reader.readLine())
        }
    }

    @Test
    fun readLine_overBudget_rejectsBeforeConsumingEntireSource() = runBlocking {
        var consumed = 0
        val input = object : StringReader("x".repeat(1024 * 1024)) {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) consumed += it }
        }
        val failure = runCatching { DictionaryPackCsvReader(input).readLine() }.exceptionOrNull()
        assertTrue(failure is DictionaryPackInstaller.MalformedPackException)
        assertTrue("must stop after the limit plus one read buffer", consumed <= 64 * 1024 + 4096)
    }
}
