package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.model.AiExplanationTextNormalizer
import io.github.zoot.englishreader.model.SelectedSentence
import io.github.zoot.englishreader.ui.textLayoutResult
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.floor

class ReadingPaginationAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    private val content = (1..240).joinToString(" ") { if (it % 7 == 0) "naïve$it🙂" else "word$it" } + "."
    private val article = ArticleEntity(42, "", content)

    @Test
    fun horizontalSwipeOnText_turnsOnePageWithoutSentenceOrWordAction() {
        val events = render()
        val text = textNode()
        val pager = composeRule.onNodeWithTag("reading-pages")
        val glyphInRoot = text.fetchSemanticsNode().positionInRoot + visibleGlyph(text)
        val start = glyphInRoot - pager.fetchSemanticsNode().boundsInRoot.topLeft
        val before = currentPage()

        pager.performTouchInput { swipe(start, start.copy(x = 8f), durationMillis = 250) }

        assertEquals(before + 1, currentPage())
        composeRule.runOnIdle {
            assertTrue(events.sentences.isEmpty())
            assertTrue(events.held.isEmpty())
            assertTrue(events.lookups.isEmpty())
        }
    }

    @Test
    fun longPressThenDrag_keepsPageAndLooksUpOnlyAfterRelease() {
        val events = render()
        val text = textNode()
        val glyph = visibleGlyph(text)
        val before = currentPage()
        val holdDuration = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100
        text.performTouchInput {
            down(glyph)
            advanceEventTime(holdDuration)
            moveTo(glyph)
        }
        composeRule.runOnIdle {
            assertEquals(1, events.held.size)
            assertTrue(events.lookups.isEmpty())
        }
        text.performTouchInput {
            moveTo(glyph.copy(x = 12f), delayMillis = 50)
            up()
        }
        assertEquals(before, currentPage())
        composeRule.runOnIdle {
            assertEquals(events.held, events.lookups)
            assertTrue(events.sentences.isEmpty())
        }
    }

    @Test
    fun clippedPagesAndReflow_preserveUnicodeAndCompleteSentenceOffsets() {
        val events = render()
        val first = visibleText(textNode())
        composeRule.onNodeWithContentDescription("下一页").performClick()
        val secondNode = textNode()
        val second = visibleText(secondNode)
        assertTrue(content.startsWith(first + second))
        val pagerBounds = composeRule.onNodeWithTag("reading-pages").fetchSemanticsNode().boundsInRoot
        assertTrue(secondNode.fetchSemanticsNode().boundsInRoot.height <= pagerBounds.height)
        val glyph = visibleGlyph(secondNode)
        secondNode.performTouchInput { click(glyph) }

        composeRule.runOnIdle {
            assertEquals(1, events.sentences.size)
            val selected = events.sentences.single()
            assertEquals(content, selected.text)
            assertEquals(0, selected.startOffset)
            assertEquals(content.length, selected.endOffset)
            events.font.value = FontSizeOption.LARGE
        }
        composeRule.runOnIdle { assertEquals(1, events.sentences.size) }
        assertTrue(textNode().fetchSemanticsNode().boundsInRoot.height <= pagerBounds.height)
    }

    private class Events {
        val sentences = mutableListOf<SentenceRange>()
        val held = mutableListOf<String>()
        val lookups = mutableListOf<String>()
        val font = mutableStateOf(FontSizeOption.MEDIUM)
    }

    private fun render(): Events {
        val events = Events()
        val sentence = mutableStateOf<SelectedSentence?>(null)
        val word = mutableStateOf<String?>(null)
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    ReadingScreenContent(
                        article = article, selectedSentence = sentence.value, selectedWord = word.value,
                        isLoadingDefinition = false, isLoading = false,
                        fontSizeOption = events.font.value, showTranslation = false,
                        snackbarHostState = remember { SnackbarHostState() },
                        onToggleTranslation = {}, onBack = {}, onExplainSentence = {},
                        onSentenceSelected = { id, index, range ->
                            events.sentences += range
                            sentence.value = SelectedSentence(id, index, range.text,
                                AiExplanationTextNormalizer.normalize(range.text), range.startOffset, range.endOffset)
                        },
                        onSentenceTapTarget = { _, _ -> },
                        onWordLongPress = { events.lookups += it },
                        onWordPressStart = { events.held += it; word.value = it },
                        onWordPressRelease = { events.lookups += it },
                        onClearSelection = { sentence.value = null; word.value = null },
                        readingMode = ReadingMode.PAGED
                    )
                }
            }
        }
        return events
    }

    private fun textNode() = composeRule.onNodeWithContentDescription("Interactive reading text")

    private fun visibleText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text

    /** 从实际可见片段与完整布局换算，禁止用设备坐标猜测字形位置。 */
    private fun visibleGlyph(node: SemanticsNodeInteraction): Offset {
        val layout = node.textLayoutResult()
        val original = layout.layoutInput.text.text
        val visible = visibleText(node)
        val start = original.indexOf(visible)
        assertTrue(start >= 0)
        val line = layout.getLineForOffset(start)
        val lineEnd = minOf(layout.getLineEnd(line, visibleEnd = true), start + visible.length)
        val offset = (start until lineEnd).last { original[it].isLetter() }
        val top = if (line == 0) 0f else floor(layout.getLineTop(line))
        val point = layout.getBoundingBox(offset).center - Offset(0f, top)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(point.x in 0f..bounds.width && point.y in 0f..bounds.height)
        return point
    }

    private fun currentPage(): Int {
        val pattern = Regex("第 (\\d+) / (\\d+) 页")
        val node = composeRule.onNode(SemanticsMatcher("page position") {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> pattern.matches(text.text) } == true
        }).fetchSemanticsNode()
        return pattern.matchEntire(node.config[SemanticsProperties.Text].single().text)!!.groupValues[1].toInt()
    }
}
