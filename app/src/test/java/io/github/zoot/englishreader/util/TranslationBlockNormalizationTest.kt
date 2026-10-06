package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.model.TranslationSegmentationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationBlockNormalizationTest {
    @Test
    fun plan_mandatoryBreaksAndEmoji_preservesSourceAndUtf16Offsets() {
        val content = "A\rB\nC\r\nD\u0085E\u2028F\u2029G😀H."
        var splitterInput: String? = null
        val result = TranslationBlockPlanner.plan(1L, content, TranslationSegmentationMode.AUTO) { normalized ->
            splitterInput = normalized
            listOf(SentenceRange(0, normalized, 0, normalized.length))
        }
        val blocks = (result as TranslationBlockPlanner.Result.Planned).plan.blocks

        assertEquals("A B C  D E F G😀H.", splitterInput)
        assertEquals(content.length, splitterInput!!.length)
        assertEquals(content, blocks.joinToString("") { it.text })
        var cursor = 0
        blocks.forEach { block ->
            assertEquals(0, block.sourceParagraphIndex)
            assertEquals(cursor, block.startOffset)
            assertEquals(content.substring(block.startOffset, block.endOffset), block.text)
            cursor = block.endOffset
        }
        assertEquals(content.length, cursor)
    }
}
