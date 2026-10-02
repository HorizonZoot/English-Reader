package io.github.zoot.englishreader.core

/** Half-open UTF-16 offsets in one original paragraph, never in display chunks. */
data class ExcerptRange(val startOffset: Int, val endOffset: Int) {
    init {
        require(startOffset >= 0 && endOffset > startOffset)
    }

    /** Moves one caret without crossing the other or splitting a UTF-16 surrogate pair. */
    fun adjustHandle(text: String, offset: Int, startHandle: Boolean, visible: ExcerptRange): ExcerptRange {
        require(endOffset <= text.length && startOffset >= visible.startOffset && endOffset <= visible.endOffset)
        fun floorBoundary(value: Int): Int = if (value in 1 until text.length &&
            text[value].isLowSurrogate() && text[value - 1].isHighSurrogate()) value - 1 else value
        fun ceilBoundary(value: Int): Int = if (value in 1 until text.length &&
            text[value].isLowSurrogate() && text[value - 1].isHighSurrogate()) value + 1 else value
        return if (startHandle) {
            val minimum = ceilBoundary(visible.startOffset)
            val maximum = floorBoundary(endOffset - 1)
            copy(startOffset = floorBoundary(offset.coerceIn(minimum, maximum)).coerceAtLeast(minimum))
        } else {
            val minimum = ceilBoundary(startOffset + 1)
            val maximum = floorBoundary(visible.endOffset)
            copy(endOffset = ceilBoundary(offset.coerceIn(minimum, maximum)).coerceAtMost(maximum))
        }
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
