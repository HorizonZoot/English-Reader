package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedSourceReaderTest {

    @Test
    fun readAtMost_withinBudget_returnsAllBytes() {
        listOf(
            "hello".toByteArray() to 100,
            ByteArray(10) { it.toByte() } to 10,
            ByteArray(0) to 10
        ).forEach { (data, limit) ->
            val result = BoundedSourceReader.readAtMost(ByteArrayInputStream(data), limit)

            assertArrayEquals("size=${data.size}, limit=$limit", data, result)
        }
    }

    @Test
    fun readAtMost_oneByteOverLimit_throwsSourceTooLarge() {
        val data = ByteArray(11)

        val failure = runCatching {
            BoundedSourceReader.readAtMost(ByteArrayInputStream(data), 10)
        }.exceptionOrNull()

        assertTrue(failure is ImportException)
        assertEquals(ImportFailure.SourceTooLarge(10), (failure as ImportException).failure)
    }

    @Test
    fun readAtMost_streamReturningPartialChunks_readsUntilExhausted() {
        // 契约：单次 read() 只返回部分数据时必须继续读，而不是当成流结束。
        // 真实的 ContentResolver 流几乎必然分块返回。
        val data = "abcdefghij".toByteArray()
        val chunked = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                // 每次只给 3 字节，强制调用方循环
                val n = minOf(3, len, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
        }

        val result = BoundedSourceReader.readAtMost(chunked, 100)

        assertArrayEquals(data, result)
    }
}
