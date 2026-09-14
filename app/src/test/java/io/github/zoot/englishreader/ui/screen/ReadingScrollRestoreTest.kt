package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingPositionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 验证生产阅读入口的恢复接线和保存内容；真实字形几何另由设备用例覆盖。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingScrollRestoreTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val article = ArticleEntity(
        id = 20L,
        title = "Chapter Two",
        content = (1..12).joinToString("\n\n") { "Paragraph $it text here." }
    )

    @Test
    fun noStoredTarget_consumeIsNotCalled() {
        var consumed = 0
        render(target = null, onConsumed = { consumed++ })
        composeRule.runOnIdle { assertEquals(0, consumed) }
    }

    @Test
    fun restore_currentArticle_consumesTargetOnce() {
        var consumed = 0
        val position = ReadingPosition(20, ReadingAnchor(5, characterOffset = 10))
        render(target = ReadingPositionTarget(position), onConsumed = { consumed++ })

        composeRule.runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun targetForAnotherArticle_isIgnoredAndNotConsumed() {
        var consumed = 0
        render(ReadingPositionTarget(ReadingPosition(999, ReadingAnchor(5))), onConsumed = { consumed++ })
        composeRule.runOnIdle { assertEquals(0, consumed) }
    }

    @Test
    fun outOfRangeParagraphIndex_restoresLastAvailableParagraph() {
        var consumed = 0
        val saves = mutableListOf<ReadingPosition>()
        render(
            ReadingPositionTarget(ReadingPosition(20, ReadingAnchor(9_999))),
            onConsumed = { consumed++ }, onSave = { saves += it }
        )
        composeRule.runOnIdle {
            assertEquals(1, consumed)
            assertTrue(saves.isNotEmpty())
            assertEquals(11, saves.first().anchor.paragraphIndex)
        }
    }

    @Test
    fun restore_firstSaveDoesNotOverwriteStoredPositionWithZero() {
        val saves = mutableListOf<ReadingPosition>()
        val position = ReadingPosition(20, ReadingAnchor(8))
        render(ReadingPositionTarget(position), onSave = { saves += it })

        composeRule.runOnIdle {
            assertTrue(saves.isNotEmpty())
            assertEquals(position, saves.first())
        }
    }

    @Test
    fun fontReflow_keepsCharacterAnchorInsteadOfSavingLineStart() {
        val saves = mutableListOf<ReadingPosition>()
        val font = mutableStateOf(FontSizeOption.MEDIUM)
        val position = ReadingPosition(20, ReadingAnchor(3, characterOffset = 12))
        render(ReadingPositionTarget(position), onSave = { saves += it }, font = font)
        composeRule.runOnIdle { font.value = FontSizeOption.LARGE }

        composeRule.runOnIdle {
            assertTrue(saves.isNotEmpty())
            assertTrue(saves.all { it == position })
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun progress_singleLongParagraph_tracksInnerScrollAndReachesEnd() {
        val body = "A long paragraph keeps going with enough words to require scrolling. ".repeat(80)
        render(target = null, readingArticle = article.copy(content = body))

        assertEquals(0f, progress(), 0.001f)
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, 500f))
        }
        composeRule.waitForIdle()
        assertTrue("scrolling within one paragraph must update progress", progress() > 0f)
        assertTrue("the middle of a long paragraph is not the end", progress() < 1f)

        list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, 100_000f))
        }
        composeRule.waitForIdle()
        assertEquals(1f, progress(), 0.001f)
    }

    @Test
    fun progress_shortArticleFullyVisible_isComplete() {
        render(target = null, readingArticle = article.copy(content = "First.\n\nSecond."))

        assertEquals(1f, progress(), 0.001f)
    }

    @Test
    @Config(qualifiers = "w1000dp-h1000dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun restore_windowAndFontScaleChanges_preserveAnchorAndAllowReadingToEnd() {
        val viewport = mutableStateOf(DpSize(360.dp, 620.dp))
        val fontScale = mutableStateOf(1f)
        val saved = mutableListOf<ReadingPosition>()
        val position = ReadingPosition(20, ReadingAnchor(3, characterOffset = 12))
        render(
            target = ReadingPositionTarget(position),
            readingArticle = article.copy(content = (1..20).joinToString("\n\n") {
                "Paragraph $it. " + "The text remains readable across different window sizes. ".repeat(10)
            }),
            onSave = { saved += it },
            viewport = viewport,
            fontScale = fontScale
        )

        listOf(
            DpSize(360.dp, 620.dp) to 1f,
            DpSize(640.dp, 360.dp) to 1.35f,
            DpSize(320.dp, 480.dp) to 1.8f
        ).forEach { (size, scale) ->
            composeRule.runOnIdle { viewport.value = size; fontScale.value = scale }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals("$size / $scale", position, saved.last()) }
        }

        composeRule.onNode(hasScrollToIndexAction())
            .performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> assertTrue(scroll(0f, 1_000_000f)) }
        composeRule.waitForIdle()
        assertEquals(1f, progress(), 0.001f)
    }

    private fun progress(): Float = composeRule
        .onNodeWithContentDescription("阅读进度")
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun render(
        target: ReadingPositionTarget?,
        onConsumed: () -> Unit = {},
        onSave: (ReadingPosition) -> Unit = {},
        font: MutableState<FontSizeOption> = mutableStateOf(FontSizeOption.DEFAULT),
        readingArticle: ArticleEntity = article,
        viewport: MutableState<DpSize>? = null,
        fontScale: MutableState<Float>? = null
    ) {
        val pending = mutableStateOf(target)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale?.value ?: density.fontScale)) {
                Box(viewport?.value?.let { Modifier.size(it) } ?: Modifier.fillMaxSize()) {
                    ReadingScreenContent(
                        article = readingArticle,
                        selectedSentence = null,
                        selectedWord = null,
                        isLoadingDefinition = false,
                        isLoading = false,
                        fontSizeOption = font.value,
                        showTranslation = false,
                        snackbarHostState = remember { SnackbarHostState() },
                        onToggleTranslation = {},
                        onBack = {},
                        onExplainSentence = {},
                        onSentenceSelected = { _, _, _ -> },
                        onWordLongPress = {},
                        onClearSelection = {},
                        pendingPositionTarget = pending.value,
                        onPositionTargetConsumed = {
                            onConsumed()
                            pending.value = null
                        },
                        onSaveReadingPosition = onSave
                    )
                }
            }
        }
    }
}
