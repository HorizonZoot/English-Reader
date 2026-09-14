package io.github.zoot.englishreader.core

import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPaginatorTest {
    @Test
    fun paginate_longParagraphsAndGaps_coverEveryCharacterOnceWithinViewport() {
        val blocks = listOf(
            block(0, ReadingTextKind.TITLE, 2, 20, 0),
            block(0, ReadingTextKind.ORIGINAL, 15, 10, 15),
            block(0, ReadingTextKind.TRANSLATION, 8, 10, 8),
            block(1, ReadingTextKind.ORIGINAL, 1, 10, 24)
        )
        for (height in listOf(40, 55, 100, 400)) {
            val result = paginateReadingBlocks(blocks, height) as ReadingPagination.Ready
            assertTrue(result.pages.all { it.fragments.isNotEmpty() })
            assertTrue(result.pages.all { page -> page.fragments.sumOf { it.height + it.gapBefore } <= height })
            val fragments = result.pages.flatMap { it.fragments }
            blocks.forEachIndexed { index, metrics ->
                var nextOffset = 0
                fragments.filter { it.blockIndex == index }.forEach { fragment ->
                    assertEquals("height=$height block=$index", nextOffset, fragment.anchor.characterOffset)
                    nextOffset = fragment.endOffset
                }
                assertEquals(metrics.lines.last().endOffset, nextOffset)
            }
        }
    }

    @Test
    fun paginate_lineTallerThanViewport_reportsUnavailableInsteadOfEmptyPages() {
        assertEquals(
            ReadingPagination.ViewportTooSmall,
            paginateReadingBlocks(listOf(block(0, ReadingTextKind.ORIGINAL, 2, 30, 0)), 20)
        )
    }

    @Test
    fun pageFor_reflowAndBoundaryOffsets_findPageContainingOriginalCharacter() {
        val blocks = listOf(block(0, ReadingTextKind.ORIGINAL, 12, 10, 0))
        val anchor = ReadingAnchor(characterOffset = 45)
        for (height in listOf(20, 30, 60)) {
            val result = paginateReadingBlocks(blocks, height) as ReadingPagination.Ready
            val fragment = result.pages[result.pageFor(anchor)].fragments.single()
            assertTrue(anchor.characterOffset in fragment.anchor.characterOffset until fragment.endOffset)
        }
        val result = paginateReadingBlocks(blocks, 20) as ReadingPagination.Ready
        assertEquals(1, result.pageFor(ReadingAnchor(characterOffset = 20)))
        assertEquals(result.pages.lastIndex, result.pageFor(ReadingAnchor(characterOffset = 999)))
    }

    @Test
    fun pageFor_hiddenTranslationAndMissingParagraph_fallBackToAvailableOriginal() {
        val blocks = listOf(
            block(0, ReadingTextKind.ORIGINAL, 4, 10, 0),
            block(1, ReadingTextKind.ORIGINAL, 4, 10, 0)
        )
        val result = paginateReadingBlocks(blocks, 20) as ReadingPagination.Ready
        assertEquals(2, result.pageFor(ReadingAnchor(1, ReadingTextKind.TRANSLATION, 30)))
        assertEquals(3, result.pageFor(ReadingAnchor(999)))
    }

    private fun block(index: Int, kind: ReadingTextKind, count: Int, lineHeight: Int, gap: Int) =
        ReadingBlockMetrics(
            paragraphIndex = index,
            textKind = kind,
            lines = List(count) { line -> ReadingLine(line * 10, (line + 1) * 10, line * lineHeight, (line + 1) * lineHeight) },
            gapBefore = gap
        )
}
