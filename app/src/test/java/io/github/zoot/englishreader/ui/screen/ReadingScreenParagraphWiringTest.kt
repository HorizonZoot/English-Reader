package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.model.SelectedSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ReadingScreen 的分段接线契约（9.6）
 *
 * ParagraphAligner 与 InteractiveText 各自都有测试，但没有测试证明**接线本身**正确：
 * 每个 InteractiveText 必须拿到属于**该段**的句子列表，且全局句子身份 = 段落局部 index +
 * 该段 sentenceOffset。接错段会让第 2 段的高亮和 AI/朗读目标指向第 1 段的句子。
 *
 * 断言走语义层（TalkBack 句子动作按段落分组），不依赖文本布局几何。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingScreenParagraphWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 第 1 段 2 句、第 2 段 2 句、第 3 段 1 句 */
    private val article = ArticleEntity(
        id = 1,
        title = "Wiring",
        content = "Alpha one. Alpha two.\n\nBeta one. Beta two.\n\nGamma only.",
        translation = null
    )

    @Test
    fun eachParagraph_getsItsOwnSentencesWithGlobalOffset() {
        renderArticle { _, _ -> }

        // 每段一个 InteractiveText，句子动作按段落分组且全局编号连续不重
        assertEquals(
            listOf(
                listOf("Highlight sentence 1", "Highlight sentence 2"),
                listOf("Highlight sentence 3", "Highlight sentence 4"),
                listOf("Highlight sentence 5")
            ),
            sentenceActionLabelsPerParagraph()
        )
    }

    @Test
    fun laterParagraphs_reportGlobalIndexAndLocalRange() {
        data class Case(
            val name: String,
            val paragraph: Int,
            val actionLabel: String,
            val expectedGlobalIndex: Int,
            val expectedPrefix: String
        )

        val selected = mutableListOf<Pair<Int, SentenceRange>>()
        renderArticle { globalIndex, range -> selected.add(globalIndex to range) }

        listOf(
            Case(
                name = "second paragraph",
                paragraph = 1,
                actionLabel = "Highlight sentence 3",
                expectedGlobalIndex = 2,
                expectedPrefix = "Beta one"
            ),
            Case(
                name = "third paragraph",
                paragraph = 2,
                actionLabel = "Highlight sentence 5",
                expectedGlobalIndex = 4,
                expectedPrefix = "Gamma"
            )
        ).forEach { case ->
            selected.clear()
            performParagraphAction(paragraph = case.paragraph, label = case.actionLabel)

            composeRule.runOnIdle {
                assertEquals("${case.name}: callback count", 1, selected.size)
                val (globalIndex, range) = selected.single()
                assertEquals("${case.name}: global index", case.expectedGlobalIndex, globalIndex)
                assertEquals("${case.name}: local sentence index", 0, range.index)
                assertEquals("${case.name}: local start offset", 0, range.startOffset)
                assertTrue(
                    "${case.name}: unexpected range text ${range.text}",
                    range.text.trim().startsWith(case.expectedPrefix)
                )
            }
        }
    }

    private fun renderArticle(onSentenceSelected: (Int, SentenceRange) -> Unit) {
        composeRule.setContent {
            ReadingScreenContent(
                article = article,
                selectedSentence = null as SelectedSentence?,
                selectedWord = null,
                isLoadingDefinition = false,
                isLoading = false,
                fontSizeOption = FontSizeOption.DEFAULT,
                showTranslation = false,
                snackbarHostState = remember { SnackbarHostState() },
                onToggleTranslation = {},
                onBack = {},
                onExplainSentence = {},
                onSentenceSelected = { _, index, range -> onSentenceSelected(index, range) },
                onWordLongPress = {},
                onClearSelection = {}
            )
        }
    }

    private fun interactiveTextNodes() =
        composeRule.onAllNodesWithContentDescription("Interactive reading text")

    private fun sentenceActionLabelsPerParagraph(): List<List<String>> {
        val nodes = interactiveTextNodes().fetchSemanticsNodes()
        return nodes.map { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
                .map { it.label }
                .filter { it.startsWith("Highlight sentence") }
        }
    }

    private fun performParagraphAction(paragraph: Int, label: String) {
        val node = interactiveTextNodes().fetchSemanticsNodes()[paragraph]
        val action = node.config.getOrNull(SemanticsActions.CustomActions)
            ?.firstOrNull { it.label == label }
            ?: error("action not found in paragraph $paragraph: $label")
        composeRule.runOnUiThread { action.action() }
    }
}
