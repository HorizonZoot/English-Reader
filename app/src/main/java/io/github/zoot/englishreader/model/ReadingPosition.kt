package io.github.zoot.englishreader.model

enum class ReadingTextKind {
    SOURCE, TITLE, ORIGINAL, TRANSLATION;

    companion object {
        fun fromName(name: String): ReadingTextKind =
            entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** 坐标属于原文本块，既不是页号，也不是滚动像素。 */
data class ReadingAnchor(
    val paragraphIndex: Int = 0,
    val textKind: ReadingTextKind = ReadingTextKind.ORIGINAL,
    val characterOffset: Int = 0
) {
    init {
        require(paragraphIndex >= 0 && characterOffset >= 0)
    }
}

data class ReadingPosition(val articleId: Long, val anchor: ReadingAnchor)

enum class ReadingEntry { RESUME, START, END }

data class ReadingPositionTarget(
    val position: ReadingPosition,
    val entry: ReadingEntry = ReadingEntry.RESUME,
    val requestId: Long = 0
)
