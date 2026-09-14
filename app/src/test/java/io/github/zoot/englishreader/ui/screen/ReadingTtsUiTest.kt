package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingTtsPhase
import io.github.zoot.englishreader.model.ReadingTtsState
import io.github.zoot.englishreader.ui.component.ReadingTtsControls
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.ui.theme.EnglishReaderTheme
import io.github.zoot.englishreader.util.ParagraphAligner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingTtsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun controls_compactWidth_exposesPauseResumeBoundariesAndCompletion() {
        val state = mutableStateOf(ReadingTtsState(
            ReadingTtsPhase.PLAYING, sentenceIndex = 0, sentenceCount = 2, continuous = true
        ))
        val commands = mutableListOf<String>()
        composeRule.setContent {
            EnglishReaderTheme {
                Box(Modifier.width(240.dp)) {
                    ReadingTtsControls(state.value,
                        onPause = { commands += "pause" }, onResume = { commands += "resume" },
                        onPrevious = { commands += "previous" }, onNext = { commands += "next" },
                        onStop = { commands += "stop" })
                }
            }
        }
        composeRule.onNodeWithTag("reading-tts-previous").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("暂停朗读").assertIsDisplayed().performClick()
        composeRule.runOnIdle { state.value = state.value.copy(phase = ReadingTtsPhase.PAUSED, sentenceIndex = 1) }
        composeRule.onNodeWithContentDescription("从本句开头继续朗读").performClick()
        composeRule.onNodeWithTag("reading-tts-next").assertIsNotEnabled()
        composeRule.onNodeWithTag("reading-tts-previous").performClick()
        composeRule.runOnIdle { state.value = state.value.copy(phase = ReadingTtsPhase.COMPLETED) }
        composeRule.onNodeWithText("朗读完成 · 第 2 / 2 句").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("从头重新朗读").performClick()
        composeRule.onNodeWithTag("reading-tts-stop").performClick()
        composeRule.runOnIdle { assertEquals(listOf("pause", "resume", "previous", "resume", "stop"), commands) }
    }

    @Test
    fun scrolling_speechTarget_locatesOriginalSentenceAndAcknowledgesRequest() {
        assertPositionWiring(ReadingMode.SCROLL)
    }

    @Test
    fun pagination_speechTarget_changesPageWithoutClearingPlaybackSelection() {
        assertPositionWiring(ReadingMode.PAGED)
    }

    @Test
    fun topBar_voiceSettingsEntry_dispatchesOpenAction() {
        var openCount = 0
        val article = ArticleEntity(42, "Reading", "A short sentence.")
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    Box(Modifier.height(420.dp)) {
                        ReadingScreenContent(
                            article = article,
                            selectedSentence = null,
                            selectedWord = null,
                            isLoadingDefinition = false,
                            isLoading = false,
                            fontSizeOption = FontSizeOption.DEFAULT,
                            showTranslation = false,
                            snackbarHostState = remember { SnackbarHostState() },
                            onToggleTranslation = {},
                            onBack = {},
                            onExplainSentence = {},
                            onSentenceSelected = { _, _, _ -> },
                            onWordLongPress = {},
                            onClearSelection = {},
                            onOpenVoiceSettings = { openCount++ }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("reading-voice-settings").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, openCount) }
    }

    private fun assertPositionWiring(mode: ReadingMode) {
        val article = ArticleEntity(42, "Reading", (1..80).joinToString(" ") { "word$it" } + ".\n\nTarget.")
        val state = mutableStateOf(ReadingTtsState())
        val target = mutableStateOf<ReadingPositionTarget?>(null)
        val expected = ReadingPositionTarget(ReadingPosition(42, ReadingAnchor(1, ReadingTextKind.ORIGINAL, 0)), requestId = 91)
        val acknowledgments = mutableListOf<ReadingPositionTarget>()
        var source = emptyList<ParagraphAligner.AlignedParagraph>()
        var cleared = 0
        composeRule.setContent {
            EnglishReaderTheme {
                ArticleUiTheme {
                    Box(Modifier.height(420.dp)) {
                        ReadingScreenContent(
                            article = article, selectedSentence = null, selectedWord = null,
                            isLoadingDefinition = false, isLoading = false, fontSizeOption = FontSizeOption.DEFAULT,
                            showTranslation = false, snackbarHostState = remember { SnackbarHostState() },
                            onToggleTranslation = {}, onBack = {}, onExplainSentence = {},
                            onSentenceSelected = { _, _, _ -> }, onWordLongPress = {}, onClearSelection = { cleared++ },
                            readingMode = mode, ttsState = state.value, ttsPositionTarget = target.value,
                            onStartContinuousReading = { id, paragraphs, _ ->
                                assertEquals(42L, id)
                                source = paragraphs
                                target.value = expected
                                state.value = ReadingTtsState(ReadingTtsPhase.PREPARING, 91, 1, 2, true)
                            },
                            onTtsPositioned = {
                                acknowledgments += it
                                target.value = null
                                state.value = state.value.copy(phase = ReadingTtsPhase.PLAYING)
                            }
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("reading-tts-start").performClick()
        composeRule.onNodeWithText("Target.").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在朗读第 2 句"))
        composeRule.onNodeWithTag("reading-tts-controls").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(expected), acknowledgments)
            assertEquals(1, source[1].sentenceOffset)
            assertEquals("Target.", source[1].sentences.single().text)
            assertEquals(0, source[1].sentences.single().startOffset)
            assertEquals(0, cleared)
        }
    }
}
