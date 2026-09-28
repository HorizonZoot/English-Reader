package io.github.zoot.englishreader.data.dictionary

import java.io.Reader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 在构造整行字符串前限制大小；保留 BufferedReader 的 LF、CRLF、CR 行边界。 */
internal class DictionaryPackCsvReader(private val reader: Reader) {
    private val buffer = CharArray(4096)
    private var offset = 0
    private var count = 0
    private var skipLf = false
    private var exhausted = false

    suspend fun readLine(): String? {
        currentCoroutineContext().ensureActive()
        if (exhausted) return null
        val line = StringBuilder()
        while (true) {
            if (offset == count) {
                currentCoroutineContext().ensureActive()
                count = reader.read(buffer)
                offset = 0
                if (count < 0) {
                    exhausted = true
                    return line.takeIf { it.isNotEmpty() }?.toString()
                }
                if (count == 0) continue
            }
            val char = buffer[offset++]
            if (skipLf) {
                skipLf = false
                if (char == '\n') continue
            }
            if (char == '\r' || char == '\n') {
                skipLf = char == '\r'
                return line.toString()
            }
            if (line.length == MAX_LINE_CHARS) {
                throw DictionaryPackInstaller.MalformedPackException("CSV line exceeds character budget")
            }
            line.append(char)
        }
    }

    companion object {
        const val MAX_LINE_CHARS = 64 * 1024
    }
}
