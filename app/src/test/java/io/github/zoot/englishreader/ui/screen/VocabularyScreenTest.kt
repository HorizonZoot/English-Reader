package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import io.github.zoot.englishreader.ui.screen.vocabulary.listKey
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 生词本界面的渲染与交互测试。
 *
 * 这里必须真的渲染一次列表：分组头会把分组标识交给 LazyColumn 当 item key，
 * 而 LazySaveableStateHolder 要求这个 key 能存进 Bundle——传 [VocabularyGroupId]
 * 实例会在列表首次测量时抛 IllegalArgumentException 崩掉整个界面，只有渲染
 * 才能覆盖到那条约束。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocabularyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val groupsState = MutableStateFlow<List<VocabularyGroup>>(emptyList())
    private val detailsState = MutableStateFlow<Map<Long, VocabularyWordDetail>>(emptyMap())
    private val groupTypeState = MutableStateFlow<GroupType>(GroupType.ByTime)
    private val vocabularyState = MutableStateFlow<List<VocabularyEntity>>(emptyList())
    private val openedArticles = mutableListOf<Long>()

    private val viewModel = mockk<VocabularyViewModel>(relaxed = true).also { model ->
        every { model.groups } returns groupsState
        every { model.details } returns detailsState
        every { model.groupType } returns groupTypeState
        every { model.vocabulary } returns vocabularyState
    }

    @Test
    fun todayGroup_showsTitleCountGlossAndPhonetic() {
        val word = word(1L, "noticing")
        render(
            groups = listOf(group(VocabularyGroupId.Today, listOf(word))),
            details = mapOf(
                word.id to VocabularyWordDetail(
                    phonetic = "/ˈnəʊtɪsɪŋ/",
                    chinese = "vt. 注意, 注意到"
                )
            )
        )

        composeRule.onNodeWithText(string(R.string.vocabulary_group_today)).assertIsDisplayed()
        // 「今天」用「N 个新单词」，其它分组用「N 个单词」。
        composeRule.onNodeWithText(string(R.string.vocabulary_group_words_new, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText("noticing").assertIsDisplayed()
        composeRule.onNodeWithText("/ˈnəʊtɪsɪŋ/").assertIsDisplayed()
        composeRule.onNodeWithText("vt. 注意, 注意到").assertIsDisplayed()
    }

    @Test
    fun alphabetGroup_rendersHeaderAndWord() {
        groupTypeState.value = GroupType.ByAlphabet
        render(groups = listOf(group(VocabularyGroupId.Alphabet("A"), listOf(word(1L, "apple")))))

        composeRule.onNodeWithText("A").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.vocabulary_group_words, 1)).assertIsDisplayed()
        composeRule.onNodeWithText("apple").assertIsDisplayed()
    }

    @Test
    fun emptyVocabulary_showsEmptyStateAndHidesGroupingControl() {
        render(groups = emptyList())

        composeRule.onNodeWithText(string(R.string.vocabulary_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.vocabulary_group_by_time)).assertDoesNotExist()
    }

    @Test
    fun segmentedControl_choosingAlphabet_dispatchesGroupType() {
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word(1L, "noticing")))))

        composeRule.onNodeWithText(string(R.string.vocabulary_group_by_alphabet)).performClick()

        verify(exactly = 1) { viewModel.switchGroupType(GroupType.ByAlphabet) }
    }

    @Test
    fun sourceArticleRow_showsTitleAndOpensThatArticle() {
        val word = word(1L, "noticing", articleId = 42L)
        render(
            groups = listOf(group(VocabularyGroupId.Today, listOf(word))),
            details = mapOf(
                word.id to VocabularyWordDetail(sourceTitle = "The Future of AI")
            )
        )

        composeRule.onNodeWithText(
            string(R.string.vocabulary_source_article, "The Future of AI")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.vocabulary_open_article)).performClick()

        composeRule.runOnIdle { assertEquals(listOf(42L), openedArticles) }
    }

    @Test
    fun vocabularyList_isUnchangedByWordWithoutGloss() {
        // 词库未收录的词只显示单词本身，不显示「未找到释义」这类占位。
        val word = word(1L, "Zyxwvu", articleId = null)
        render(
            groups = listOf(group(VocabularyGroupId.Today, listOf(word))),
            details = mapOf(word.id to VocabularyWordDetail())
        )

        composeRule.onNodeWithText("Zyxwvu").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.no_definitions_found)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.vocabulary_open_article)).assertDoesNotExist()
    }

    @Test
    fun swipeLeft_deletesThatWord() {
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        composeRule.onNodeWithTag("vocabulary-word-1").performTouchInput { swipeLeft() }

        verify(exactly = 1) { viewModel.deleteVocabulary(word) }
    }

    @Test
    fun detailsArrivingAfterFirstFrame_fillInTheRow() {
        // 释义是异步解析的：列表先渲染，词典结果后到。若行内容读的是首帧捕获的
        // map，释义永远不会出现——这条测试盯的就是那个。
        val word = word(1L, "noticing")
        render(
            groups = listOf(group(VocabularyGroupId.Today, listOf(word))),
            details = emptyMap()
        )

        composeRule.onNodeWithText("vt. 注意, 注意到").assertDoesNotExist()

        composeRule.runOnIdle {
            detailsState.value = mapOf(
                word.id to VocabularyWordDetail(
                    phonetic = "/ˈnəʊtɪsɪŋ/",
                    chinese = "vt. 注意, 注意到"
                )
            )
        }

        composeRule.onNodeWithText("vt. 注意, 注意到").assertIsDisplayed()
    }

    @Test
    fun everyGroupId_mapsToItsOwnKey() {
        val ids = listOf(
            VocabularyGroupId.Today,
            VocabularyGroupId.Yesterday,
            VocabularyGroupId.ThisWeek,
            VocabularyGroupId.OlderDate("2024-07-14"),
            VocabularyGroupId.Alphabet("A")
        )

        assertEquals(ids.size, ids.map { it.listKey }.toSet().size)
    }

    private fun group(id: VocabularyGroupId, words: List<VocabularyEntity>) =
        VocabularyGroup(id = id, words = words, isExpanded = true)

    private fun word(id: Long, text: String, articleId: Long? = null) = VocabularyEntity(
        id = id,
        word = text,
        articleId = articleId,
        createdAt = 0L
    )

    private fun render(
        groups: List<VocabularyGroup>,
        details: Map<Long, VocabularyWordDetail> = emptyMap()
    ) {
        groupsState.value = groups
        detailsState.value = details
        vocabularyState.value = groups.flatMap { it.words }
        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                VocabularyScreen(
                    onOpenArticle = { openedArticles += it },
                    viewModel = viewModel
                )
            }
        }
    }

    private fun string(resourceId: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resourceId, *args)
}
