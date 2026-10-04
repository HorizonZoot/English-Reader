package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.viewmodel.ContextLibraryState
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
// MockK 对 final ViewModel 的 Android inline mocking 需要 API 28+。
@SdkSuppress(minSdkVersion = 28)
class VocabularyGestureAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val viewModel = mockk<VocabularyViewModel>(relaxed = true)

    @Test
    fun swipeLeft_deletesThatWord() {
        val word = word(1L, "noticing")
        render(word)

        composeRule.onNodeWithTag("vocabulary-word-1").assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        verify(exactly = 1) { viewModel.deleteVocabulary(word) }
    }

    @Test
    fun swipeLeft_rowSlidesBackAndWaitsForTheListToDropIt() {
        // 列表暂不回流时，行必须回到确切原位，不能只剩可见的残边。
        val word = word(1L, "noticing")
        render(word)
        val before = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()

        composeRule.onNodeWithTag("vocabulary-word-1").assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        val after = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()
        composeRule.onNodeWithText("noticing").assertIsDisplayed()
        assertEquals("row must slide back to where it started", before.left, after.left)
        assertEquals(before.right, after.right)
    }

    @Test
    fun swipeLeft_rowFollowsTheFingerMidGesture() {
        // 背景为空时仍须有实际拖动距离；抬手后的结果不能证明中途跟手。
        val word = word(1L, "noticing")
        render(word)
        val before = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()
        val row = composeRule.onNodeWithTag("vocabulary-word-1").assertIsDisplayed()

        row.performTouchInput { down(center) }
        try {
            row.performTouchInput { moveBy(Offset(-120f, 0f)) }
            composeRule.waitForIdle()

            val dragged = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()
            assertTrue("row did not follow the drag: $before -> $dragged", dragged.left < before.left)
        } finally {
            row.performTouchInput { cancel() }
            composeRule.waitForIdle()
        }
    }

    private fun word(id: Long, text: String) = VocabularyEntity(
        id = id,
        word = text,
        createdAt = 0L
    )

    private fun render(word: VocabularyEntity) {
        every { viewModel.groups } returns MutableStateFlow(
            listOf(VocabularyGroup(VocabularyGroupId.Today, listOf(word), isExpanded = true))
        )
        every { viewModel.details } returns MutableStateFlow(emptyMap())
        every { viewModel.groupType } returns MutableStateFlow(GroupType.ByTime)
        every { viewModel.vocabulary } returns MutableStateFlow(listOf(word))
        every { viewModel.loadingAudioWordId } returns MutableStateFlow(null)
        every { viewModel.excerpts } returns MutableStateFlow(emptyList())
        every { viewModel.excerptPlayback } returns MutableStateFlow(null)
        every { viewModel.excerptToOpen } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { viewModel.uiEvent } returns emptyFlow()
        every { viewModel.contextLibrary } returns MutableStateFlow(ContextLibraryState(loading = false))
        every { viewModel.contextToOpen } returns kotlinx.coroutines.flow.MutableSharedFlow()
        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                VocabularyScreen(onOpenArticle = { _, _ -> }, viewModel = viewModel)
            }
        }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.context_legacy_words)).performClick()
        composeRule.waitForIdle()
    }
}
