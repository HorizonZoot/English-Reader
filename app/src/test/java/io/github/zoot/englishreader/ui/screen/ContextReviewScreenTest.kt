package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.core.ContextGrade
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.util.contextEntry
import io.github.zoot.englishreader.util.contextRound
import io.github.zoot.englishreader.viewmodel.ContextReviewEvent
import io.github.zoot.englishreader.viewmodel.ContextReviewSource
import io.github.zoot.englishreader.viewmodel.ContextReviewUiState
import io.github.zoot.englishreader.viewmodel.ContextReviewViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextReviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(ContextReviewUiState(round = contextRound(), busy = false))
    private val revealed = mutableListOf<String>()
    private val grades = mutableListOf<Pair<String, ContextGrade>>()
    private val sources = mutableListOf<String>()
    private var nextRounds = 0

    private fun render(onOpenSource: ((String) -> Unit)? = { sources.add(it) }) {
        compose.setContent {
            ArticleUiTheme {
                ContextReviewContent(state.value, SnackbarHostState(), {}, revealed::add,
                    { token, grade -> grades += token to grade }, { nextRounds++ }, {}, onOpenSource)
            }
        }
    }

    private fun screenViewModel(sourceEvents: MutableSharedFlow<ContextReviewSource>) =
        mockk<ContextReviewViewModel>(relaxed = true).also { model ->
            every { model.state } returns MutableStateFlow(
                ContextReviewUiState(round = contextRound(listOf(contextEntry(revealed = true))), busy = false)
            )
            every { model.uiEvent } returns MutableSharedFlow<ContextReviewEvent>()
            every { model.contextToOpen } returns sourceEvents
            every { model.consumeContextSource(any()) } answers { firstArg<ContextReviewSource>().entry.card }
        }

    @Test
    fun lifecycle_pauseAndDispose_cancelSourceAndNeverReplayBackgroundNavigation() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        val visible = mutableStateOf(true)
        val sourceEvents = MutableSharedFlow<ContextReviewSource>(extraBufferCapacity = 1)
        val viewModel = screenViewModel(sourceEvents)
        val opened = mutableListOf<Pair<Long, Long>>()
        val source = ContextReviewSource(1, contextEntry(revealed = true))
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) ContextReviewScreen(
                    onBack = {}, viewModel = viewModel,
                    onOpenContext = { articleId, cardId -> opened += articleId to cardId }
                )
            }
        }
        compose.runOnIdle { assertTrue(sourceEvents.tryEmit(source)) }
        compose.runOnIdle {
            assertEquals(listOf(7L to 1L), opened)
            owner.registry.currentState = Lifecycle.State.STARTED
            verify(exactly = 1) { viewModel.cancelContextNavigation() }
        }
        compose.runOnIdle { assertTrue(sourceEvents.tryEmit(source)) }
        compose.runOnIdle {
            assertEquals(listOf(7L to 1L), opened)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals(listOf(7L to 1L), opened)
            visible.value = false
        }
        compose.runOnIdle {
            verify(exactly = 2) { viewModel.cancelContextNavigation() }
            assertTrue(sourceEvents.tryEmit(source))
        }
        compose.runOnIdle { assertEquals(listOf(7L to 1L), opened) }
    }

    @Test
    fun sourceEvent_rejectedAtConsumption_doesNotNavigateAndBackCancelsFirst() {
        val sourceEvents = MutableSharedFlow<ContextReviewSource>(extraBufferCapacity = 1)
        val viewModel = screenViewModel(sourceEvents)
        val actions = mutableListOf<String>()
        every { viewModel.consumeContextSource(any()) } returns null
        every { viewModel.cancelContextNavigation() } answers { actions += "cancel" }
        compose.setContent {
            ContextReviewScreen(
                onBack = { actions += "back" }, viewModel = viewModel,
                onOpenContext = { _, _ -> actions += "navigate" }
            )
        }
        compose.runOnIdle { assertTrue(sourceEvents.tryEmit(ContextReviewSource(1, contextEntry(revealed = true)))) }
        compose.runOnIdle {
            verify(exactly = 1) { viewModel.consumeContextSource(any()) }
            assertTrue(actions.isEmpty())
        }
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.nav_back)).performClick()
        compose.runOnIdle { assertEquals(listOf("cancel", "back"), actions) }
    }

    @Test
    fun front_hidesMeaningAndGradesFromSemantics_andHighlightsExactOccurrence() {
        render()
        compose.onNodeWithText("n. 河岸", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("context-review-answer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("context-review-source", useUnmergedTree = true).assertDoesNotExist()
        ContextGrade.entries.forEach { compose.onNodeWithTag("context-grade-${it.name}").assertDoesNotExist() }
        val sentence = compose.onNodeWithTag("context-review-sentence").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertEquals("The bank is quiet.", sentence.text)
        assertTrue(sentence.spanStyles.any { it.start == 4 && it.end == 8 })
        compose.onNodeWithTag("context-review-reveal").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("item-1"), revealed) }
        compose.onNodeWithText("n. 河岸", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("context-review-source", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun revealedStoredTurn_displaysSnapshotAndPassesOpaqueTokenToGrade() {
        state.value = ContextReviewUiState(round = contextRound(listOf(contextEntry(revealed = true))), busy = false)
        render()
        compose.onNodeWithTag("context-review-answer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-review-reveal").assertDoesNotExist()
        compose.onNodeWithTag("context-review-source").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("item-1"), sources) }
        ContextGrade.entries.forEach {
            compose.onNodeWithTag("context-review").performScrollToNode(hasTestTag("context-grade-${it.name}"))
            compose.onNodeWithTag("context-grade-${it.name}").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("context-grade-FUZZY").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("item-1" to ContextGrade.FUZZY), grades) }
    }

    @Test
    fun busyWrite_disablesRevealAndEveryGrade() {
        state.value = state.value.copy(busy = true)
        render()
        compose.onNodeWithTag("context-review-reveal").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            state.value = ContextReviewUiState(round = contextRound(listOf(contextEntry(revealed = true))), busy = true)
        }
        ContextGrade.entries.forEach {
            compose.onNodeWithTag("context-review").performScrollToNode(hasTestTag("context-grade-${it.name}"))
            compose.onNodeWithTag("context-grade-${it.name}").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("context-review-source").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun revealedTurn_withoutSourceCallback_hasNoSourceAction() {
        state.value = ContextReviewUiState(round = contextRound(listOf(contextEntry(revealed = true))), busy = false)
        render(onOpenSource = null)
        compose.onNodeWithTag("context-review-answer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-grade-REMEMBERED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-review-source", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun completedRound_staysOnSummaryUntilNextButtonIsClicked() {
        val entry = contextEntry(revealed = true)
        state.value = ContextReviewUiState(
            round = contextRound(listOf(entry.copy(item = entry.item.copy(grade = ContextGrade.REMEMBERED)))),
            busy = false
        )
        render()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.context_review_complete)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, nextRounds) }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.context_review_next)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, nextRounds) }
    }

    @Test
    fun nextUnrevealedTurn_removesAnswerAndSourceDescendants() {
        state.value = ContextReviewUiState(round = contextRound(listOf(contextEntry(revealed = true))), busy = false)
        render()
        compose.onNodeWithTag("context-review-source").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state.value = ContextReviewUiState(round = contextRound(listOf(contextEntry(id = 2))), busy = false)
        }
        compose.onNodeWithTag("context-review-reveal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-review-answer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("n. 河岸", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("context-review-source", useUnmergedTree = true).assertDoesNotExist()
        ContextGrade.entries.forEach {
            compose.onNodeWithTag("context-grade-${it.name}", useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
