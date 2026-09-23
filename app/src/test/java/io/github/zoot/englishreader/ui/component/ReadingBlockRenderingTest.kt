package io.github.zoot.englishreader.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.model.AppliedTranslationBlock
import io.github.zoot.englishreader.model.AppliedTranslationLayout
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.ui.screen.ReadingScreenContent
import io.github.zoot.englishreader.util.ParagraphAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingBlockRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val source = "First half\nsecond half."
    private val article = ArticleEntity(7, "Title", source, translation = "上半句\n下半句")
    private val layout = AppliedTranslationLayout(
        AppliedTranslationLayout.VERSION_V1, "line", "test",
        listOf(
            AppliedTranslationBlock(0, 0, 0, 11, 0, 4),
            AppliedTranslationBlock(1, 0, 11, source.length, 4, 7)
        )
    )

    @Test
    fun sharedBlocks_alternateAndKeepTranslationOffsets_thenHideAsOneOriginalParagraph() {
        val visible = mutableStateOf(true)
        var blocks = emptyList<ReadingTextBlock>()
        val paragraphs = ParagraphAligner.align(source, article.translation) { listOf(SentenceRange(0, it, 0, it.length)) }
        compose.setContent {
            MaterialTheme {
                val projected = rememberReadingTextBlocks(article, paragraphs, FontSizeOption.MEDIUM, visible.value, layout)
                SideEffect { blocks = projected }
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("Title", "First half\n", "上半句", "second half.", "下半句"), blocks.map { it.text })
            assertEquals(4, blocks.last().textStartOffset)
            assertEquals(blocks.size, blocks.map { it.key }.distinct().size)
            visible.value = false
        }
        compose.runOnIdle { assertEquals(listOf("Title", source), blocks.map { it.text }) }
    }

    @Test
    fun slicedInteractiveText_accessibilitySelectsCompleteOriginalSentenceWithStableIdentity() {
        val sentence = SentenceRange(0, source, 0, source.length)
        var selected: Pair<Int, SentenceRange>? = null
        compose.setContent {
            MaterialTheme {
                InteractiveText(
                    text = source.substring(11), sourceStartOffset = 11,
                    fontSize = 18.sp, modifier = Modifier.testTag("slice"),
                    precomputedSentences = listOf(sentence), sentenceIndexOffset = 8,
                    highlightedSentenceIndex = 8,
                    onSentenceClick = { index, range -> selected = index to range }, onWordLongPress = {}
                )
            }
        }
        compose.onNodeWithTag("slice").assertTextEquals("second half.")
        val action = compose.onNodeWithTag("slice")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label.startsWith("Highlight sentence") }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.runOnIdle { assertEquals(8 to sentence, selected) }
    }

    @Test fun scroll_secondTranslationBlock_restoresParagraphLocalOffset() = verifyRestore(ReadingMode.SCROLL)
    @Test fun paged_secondTranslationBlock_restoresParagraphLocalOffset() = verifyRestore(ReadingMode.PAGED)

    private fun verifyRestore(mode: ReadingMode) {
        val saves = mutableListOf<ReadingPosition>()
        val position = ReadingPosition(7, ReadingAnchor(0, ReadingTextKind.TRANSLATION, 5))
        val target = mutableStateOf<ReadingPositionTarget?>(ReadingPositionTarget(position))
        compose.setContent {
            MaterialTheme {
                ReadingScreenContent(
                    article = article, appliedLayout = layout,
                    selectedSentence = null, selectedWord = null,
                    isLoadingDefinition = false, isLoading = false,
                    fontSizeOption = FontSizeOption.MEDIUM, showTranslation = true,
                    readingMode = mode, snackbarHostState = remember { SnackbarHostState() },
                    onToggleTranslation = {}, onBack = {}, onExplainSentence = {},
                    onSentenceSelected = { _, _, _ -> }, onWordLongPress = {}, onClearSelection = {},
                    pendingPositionTarget = target.value,
                    onPositionTargetConsumed = { target.value = null },
                    onSaveReadingPosition = { saves += it }
                )
            }
        }
        compose.runOnIdle {
            assertTrue(saves.isNotEmpty())
            assertEquals(position, saves.first())
        }
    }
}
