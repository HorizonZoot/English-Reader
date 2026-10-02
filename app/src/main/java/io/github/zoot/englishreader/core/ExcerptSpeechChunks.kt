package io.github.zoot.englishreader.core

/** Keep the saved snapshot intact while respecting the system TTS per-utterance limit. */
internal fun excerptSpeechChunks(text: String, maxLength: Int = 4_000): List<String> {
    require(maxLength >= 2)
    if (text.isBlank()) return emptyList()
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxLength, text.length)
            if (end < text.length) {
                val wordBoundary = (end - 1 downTo start + maxLength / 2).firstOrNull { text[it].isWhitespace() }
                if (wordBoundary != null) end = wordBoundary + 1
                if (text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
            }
            add(text.substring(start, end))
            start = end
        }
    }
}
