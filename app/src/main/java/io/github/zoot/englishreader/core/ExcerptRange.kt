package io.github.zoot.englishreader.core

/** Half-open UTF-16 offsets in one original paragraph, never in display chunks. */
data class ExcerptRange(val startOffset: Int, val endOffset: Int) {
    init {
        require(startOffset >= 0 && endOffset > startOffset)
    }

    companion object {
        fun fromDrag(text: String, anchor: Int, focus: Int): ExcerptRange? {
            if (anchor !in text.indices || focus !in text.indices) return null
            var start = minOf(anchor, focus)
            var end = maxOf(anchor, focus) + 1
            if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end++
            return ExcerptRange(start, end)
        }
    }
}
