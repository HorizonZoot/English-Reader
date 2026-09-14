package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.ui.boundsOnScreen
import io.github.zoot.englishreader.ui.component.WordDetailsBottomSheet
import io.github.zoot.englishreader.ui.positionOnScreen
import io.github.zoot.englishreader.ui.textLayoutResult
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ReadingWordSelectionAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()
    private var underlineColor = Color.Unspecified

    @Test
    fun longPress_repeatedWord_highlightsOnlyTouchedOccurrenceAndParagraph() {
        val first = "Alpha appears here. Alpha appears twice."
        val second = "Alpha also appears in another paragraph."
        renderReading(listOf(first, second), showSheetOnRelease = false)

        composeRule.onNodeWithText(first).longClickFirstGlyph()

        val firstText = composeRule.onNodeWithText(first).textLayoutResult().layoutInput.text
        val secondText = composeRule.onNodeWithText(second).textLayoutResult().layoutInput.text
        assertEquals(
            listOf(0 to 5),
            firstText.spanStyles.filter { it.item.background != Color.Unspecified }
                .map { it.start to it.end }
        )
        assertTrue(
            "The other paragraph must not highlight a different occurrence of the same word",
            secondText.spanStyles.none { it.item.background != Color.Unspecified }
        )
    }

    @Test
    fun partiallyExpandedWordSheet_visibleWord_keepsReadingPosition() {
        val first = "Alpha is already above the word details."
        renderReading(listOf(first, ("More reading continues below. ").repeat(30).trim()))
        val before = composeRule.onNodeWithText(first).firstGlyphOnScreen()

        composeRule.onNodeWithText(first).longClickFirstGlyph()

        val sheet = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Expand),
            useUnmergedTree = true
        ).fetchSemanticsNode()
            .boundsOnScreen()
        assertTrue("The fixture must place the word above the actual sheet", before.bottom < sheet.top)
        val after = composeRule.onNodeWithText(first).firstGlyphOnScreen()
        val viewport = composeRule.onNodeWithTag("reading-content").fetchSemanticsNode().boundsOnScreen()
        assertTrue("The selected glyph must remain in the reading viewport", after.top >= viewport.top)
        assertEquals("An unobstructed word must not trigger scrolling", before.top, after.top, 2f)
    }

    @Test
    fun expandedWordSheet_noVisibleReadingSpace_doesNotScrollArticle() {
        val first = "Alpha is already above the word details."
        renderReading(listOf(first, ("More reading continues below. ").repeat(30).trim()))
        composeRule.onNodeWithText(first).longClickFirstGlyph()
        val before = composeRule.onNodeWithText(first).firstGlyphOnScreen()
        val viewport = composeRule.onNodeWithTag("reading-content").fetchSemanticsNode().boundsOnScreen()

        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Expand),
            useUnmergedTree = true
        ).performSemanticsAction(SemanticsActions.Expand) { expand -> assertTrue(expand()) }
        composeRule.waitForIdle()

        val expandedTop = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse),
            useUnmergedTree = true
        ).fetchSemanticsNode().boundsOnScreen().top
        assertTrue("The expanded sheet must cover the whole reading viewport", expandedTop <= viewport.top)
        val after = composeRule.onNodeWithText(first).firstGlyphOnScreen()
        assertEquals("No scrolling can reveal the word above a full-height sheet", before.top, after.top, 2f)
    }

    @Test
    fun partiallyExpandedWordSheet_obstructedWord_scrollsAboveSurfaceWithinViewport() {
        val introduction = ("An introductory paragraph fills the reading viewport before the target. ")
            .repeat(12).trim()
        val target = "Omega must remain visible after opening its definition window."
        renderReading(listOf(introduction, target, ("Further reading follows. ").repeat(30).trim()))
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(2)
        val viewport = composeRule.onNodeWithTag("reading-content").fetchSemanticsNode().boundsOnScreen()
        val initial = composeRule.onNodeWithText(target).firstGlyphOnScreen()
        val scrollDelta = initial.bottom + 24f - viewport.bottom
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, scrollDelta))
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithText(target).firstGlyphOnScreen()

        composeRule.onNodeWithText(target).longClickFirstGlyph()

        val sheet = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Expand),
            useUnmergedTree = true
        ).fetchSemanticsNode()
            .boundsOnScreen()
        assertTrue("The fixture must place the word behind the actual sheet", before.top > sheet.top)
        val after = composeRule.onNodeWithText(target).firstGlyphOnScreen()
        assertTrue("The obstructed word must move upward", after.top < before.top - 2f)
        assertTrue("The selected glyph must remain in the reading viewport", after.top >= viewport.top)
        assertTrue("The full glyph must be above the sheet surface", after.bottom <= sheet.top)
        val expectedBottom = sheet.top - with(composeRule.density) { 8.dp.toPx() }
        assertEquals("Only the necessary scroll distance is allowed", expectedBottom, after.bottom, 2f)
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun selectedWord_drawsVisibleDashesOverHighlightInBothReadingModes() {
        val paragraph = "Alpha appears here. Alpha appears twice."
        val mode = mutableStateOf(ReadingMode.SCROLL)
        renderReading(listOf(paragraph), showSheetOnRelease = false, readingMode = mode)

        ReadingMode.entries.forEach { readingMode ->
            composeRule.runOnIdle { mode.value = readingMode }
            composeRule.waitForIdle()
            val node = composeRule.onNodeWithText(paragraph)
            node.longClickFirstGlyph()
            val layout = node.textLayoutResult()
            val highlight = layout.layoutInput.text.spanStyles.first {
                it.start == 0 && it.end == 5 && it.item.background != Color.Unspecified
            }.item.background
            val pixels = node.captureToImage().toPixelMap()
            val y = (layout.getLineBottom(0) - with(composeRule.density) { 2.dp.toPx() })
                .roundToInt().coerceIn(0, pixels.height - 1)
            val left = layout.getBoundingBox(0).left.roundToInt().coerceAtLeast(0)
            val right = layout.getBoundingBox(4).right.roundToInt().coerceAtMost(pixels.width)
            fun distance(a: Color, b: Color): Float =
                (a.red - b.red) * (a.red - b.red) +
                    (a.green - b.green) * (a.green - b.green) +
                    (a.blue - b.blue) * (a.blue - b.blue)

            val ink = (left until right).map { x ->
                distance(pixels[x, y], underlineColor) < distance(pixels[x, y], highlight)
            }
            val runs = ink.indices.count { ink[it] && (it == 0 || !ink[it - 1]) }
            assertTrue("$readingMode: underline needs visible dashes and gaps above the highlight",
                runs >= 2 && ink.any { !it })
        }
    }

    private fun renderReading(
        paragraphs: List<String>,
        showSheetOnRelease: Boolean = true,
        readingMode: MutableState<ReadingMode> = mutableStateOf(ReadingMode.SCROLL)
    ) {
        val article = ArticleEntity(42, "Words", paragraphs.joinToString("\n\n"))
        val selectedWord = mutableStateOf<String?>(null)
        val showDetails = mutableStateOf(false)
        val sheetTop = mutableStateOf<Float?>(null)
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    val color = MaterialTheme.colorScheme.primary
                    SideEffect { underlineColor = color }
                    if (showDetails.value) {
                        WordDetailsBottomSheet(
                            word = requireNotNull(selectedWord.value),
                            phonetic = null,
                            chineseDefinitions = listOf("用于检验半展开窗口的释义。"),
                            englishDefinitions = List(20) { index ->
                                "Meaning ${index + 1} keeps the definition longer than the visible sheet."
                            },
                            onDismiss = { showDetails.value = false },
                            onAddToVocabulary = {},
                            onPlayAudio = {},
                            onVisibleTopChanged = { sheetTop.value = it }
                        )
                    }
                    ReadingScreenContent(
                        article = article,
                        selectedSentence = null,
                        selectedWord = selectedWord.value,
                        wordDetailsVisible = showDetails.value,
                        wordSheetTopOnScreen = sheetTop.value,
                        isLoadingDefinition = false,
                        isLoading = false,
                        fontSizeOption = FontSizeOption.MEDIUM,
                        readingMode = readingMode.value,
                        showTranslation = false,
                        snackbarHostState = remember { SnackbarHostState() },
                        onToggleTranslation = {},
                        onBack = {},
                        onExplainSentence = {},
                        onSentenceSelected = { _, _, _ -> },
                        onWordLongPress = {},
                        onWordPressStart = { selectedWord.value = it.lowercase() },
                        onWordPressRelease = { showDetails.value = showSheetOnRelease },
                        onClearSelection = { selectedWord.value = null }
                    )
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.firstGlyphOnScreen(): Rect {
        val glyph = textLayoutResult().getBoundingBox(0)
        return glyph.translate(fetchSemanticsNode().positionOnScreen())
    }

    private fun SemanticsNodeInteraction.longClickFirstGlyph() {
        val glyph = textLayoutResult().getBoundingBox(0)
        val node = fetchSemanticsNode()
        val glyphInRoot = node.positionInRoot + glyph.center
        assertTrue("The long press must hit a visible glyph", node.boundsInRoot.contains(glyphInRoot))
        val touchPosition = glyphInRoot - node.boundsInRoot.topLeft
        performTouchInput { longClick(touchPosition) }
        composeRule.waitForIdle()
    }
}
