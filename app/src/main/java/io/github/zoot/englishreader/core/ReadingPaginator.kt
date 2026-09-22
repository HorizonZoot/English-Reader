package io.github.zoot.englishreader.core

import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind

data class ReadingLine(
    val startOffset: Int,
    val endOffset: Int,
    val top: Int,
    val bottom: Int
)

data class ReadingBlockMetrics(
    val paragraphIndex: Int,
    val textKind: ReadingTextKind,
    val lines: List<ReadingLine>,
    val gapBefore: Int,
    val textStartOffset: Int = 0
)

/** 原段落布局中的裁切范围；所有行只归属一页。 */
data class ReadingPageFragment(
    val blockIndex: Int,
    val anchor: ReadingAnchor,
    val endOffset: Int,
    val top: Int,
    val height: Int,
    val gapBefore: Int,
    val localStartOffset: Int = anchor.characterOffset,
    val localEndOffset: Int = endOffset
)

data class ReadingPage(val fragments: List<ReadingPageFragment>) {
    val anchor: ReadingAnchor get() = fragments.first().anchor
}

sealed interface ReadingPagination {
    data class Ready(val pages: List<ReadingPage>) : ReadingPagination {
        fun pageFor(anchor: ReadingAnchor): Int {
            val matching = pages.flatMapIndexed { pageIndex, page ->
                page.fragments.filter {
                    it.anchor.paragraphIndex == anchor.paragraphIndex &&
                        it.anchor.textKind == anchor.textKind
                }.map { pageIndex to it }
            }
            if (matching.isNotEmpty()) {
                return matching.firstOrNull { (_, fragment) ->
                    anchor.characterOffset < fragment.endOffset
                }?.first ?: matching.last().first
            }
            if (anchor.textKind == ReadingTextKind.TRANSLATION) {
                return pageFor(anchor.copy(textKind = ReadingTextKind.ORIGINAL, characterOffset = 0))
            }
            return pages.indexOfFirst { page ->
                page.fragments.any {
                    it.anchor.textKind == ReadingTextKind.ORIGINAL &&
                        it.anchor.paragraphIndex >= anchor.paragraphIndex
                }
            }.takeIf { it >= 0 } ?: pages.lastIndex.coerceAtLeast(0)
        }
    }

    data object ViewportTooSmall : ReadingPagination
}

/** Compose 只负责测量；这里的整行装箱、段间距和锚点规则可在 JVM 上验证。 */
fun paginateReadingBlocks(blocks: List<ReadingBlockMetrics>, pageHeight: Int): ReadingPagination {
    if (pageHeight <= 0) return ReadingPagination.ViewportTooSmall
    val pages = mutableListOf<ReadingPage>()
    var fragments = mutableListOf<ReadingPageFragment>()
    var usedHeight = 0

    fun finishPage() {
        if (fragments.isEmpty()) return
        pages += ReadingPage(fragments.toList())
        fragments = mutableListOf()
        usedHeight = 0
    }

    blocks.forEachIndexed { blockIndex, block ->
        var firstLine = 0
        while (firstLine < block.lines.size) {
            val top = block.lines[firstLine].top
            val gap = if (fragments.isEmpty() || firstLine > 0) 0 else block.gapBefore
            var endLine = firstLine
            while (endLine < block.lines.size &&
                usedHeight + gap + block.lines[endLine].bottom - top <= pageHeight
            ) {
                endLine++
            }
            if (endLine == firstLine) {
                if (fragments.isEmpty()) return ReadingPagination.ViewportTooSmall
                finishPage()
                continue
            }
            val last = block.lines[endLine - 1]
            val height = last.bottom - top
            fragments += ReadingPageFragment(
                blockIndex = blockIndex,
                anchor = ReadingAnchor(block.paragraphIndex, block.textKind, block.textStartOffset + block.lines[firstLine].startOffset),
                endOffset = block.textStartOffset + last.endOffset,
                top = top,
                height = height,
                gapBefore = gap,
                localStartOffset = block.lines[firstLine].startOffset,
                localEndOffset = last.endOffset
            )
            usedHeight += gap + height
            firstLine = endLine
            if (firstLine < block.lines.size) finishPage()
        }
    }
    finishPage()
    return ReadingPagination.Ready(pages)
}
