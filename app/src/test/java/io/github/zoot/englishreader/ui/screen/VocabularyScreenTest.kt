package io.github.zoot.englishreader.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
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
import io.github.zoot.englishreader.viewmodel.VocabularyUiEvent
import io.github.zoot.englishreader.viewmodel.VocabularyViewModel
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
    private val loadingAudioWordIdState = MutableStateFlow<Long?>(null)
    private val openedArticles = mutableListOf<Pair<Long, String>>()

    /** 一次性事件用无重放的 SharedFlow 模拟 Channel：测试自行 tryEmit 触发界面反应。 */
    private val uiEvents = MutableSharedFlow<VocabularyUiEvent>(extraBufferCapacity = 4)

    private val viewModel = mockk<VocabularyViewModel>(relaxed = true).also { model ->
        every { model.groups } returns groupsState
        every { model.details } returns detailsState
        every { model.groupType } returns groupTypeState
        every { model.vocabulary } returns vocabularyState
        every { model.loadingAudioWordId } returns loadingAudioWordIdState
        every { model.uiEvent } returns uiEvents
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

        // 词一并带过去：阅读页要靠它滚动到并高亮该词所在的段落。
        composeRule.runOnIdle { assertEquals(listOf(42L to "noticing"), openedArticles) }
    }

    @Test
    fun tappingWord_playsItsPronunciation() {
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        composeRule.onNodeWithText("noticing").performClick()

        verify(exactly = 1) { viewModel.playWordAudio(word) }
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
    fun swipeLeft_rowSlidesBackAndWaitsForTheListToDropIt() {
        // 删除是异步的（Room 写完才回流）。行必须滑回原位等列表移除它，不能停在
        // dismissed 锚点上：删除万一没落库，Flow 不回流、行也滑不回来（StartToEnd 已关、
        // Settled 又被 confirmValueChange 拒），那一行就永久卡在屏幕外。
        //
        // 断言比「还有一个像素可见」严格得多：必须回到滑动前的确切位置。此前那版
        // 只断言 right > 0，而行实际左移了 272dp、只剩 24dp 残边——照样通过。
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))
        val before = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()

        composeRule.onNodeWithTag("vocabulary-word-1").performTouchInput { swipeLeft() }

        val after = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()
        composeRule.onNodeWithText("noticing").assertIsDisplayed()
        assertEquals("row must slide back to where it started", before.left, after.left)
        assertEquals(before.right, after.right)
    }

    @Test
    fun wordNode_exposesDeleteAsACustomAccessibilityAction() {
        // 左滑手势对读屏不可达，自定义操作是唯一的删除入口。它必须挂在读屏真正会
        // 聚焦的节点上——即因 clickable 而合并子节点、带 contentDescription 的单词行。
        // 挂在外层 SwipeToDismissBox（不合并、无文本）上时读屏遍历会跳过它。
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        val actions = composeRule
            .onNodeWithContentDescription(string(R.string.word_play_pronunciation, "noticing"))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()

        val delete = actions.firstOrNull { it.label == string(R.string.delete_vocabulary) }
        assertTrue("delete action missing on the focusable word node", delete != null)

        composeRule.runOnUiThread { delete!!.action() }

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
    fun swipeLeft_rowFollowsTheFingerMidGesture() {
        // backgroundContent 清空之后这条必须验：SwipeToDismissBox 的拖动锚点由内容尺寸
        // 推导，背景为空时若推导出零距离，滑动就会「不跟手」——手势有效但没有任何反馈。
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))
        val before = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()

        composeRule.onNodeWithTag("vocabulary-word-1").performTouchInput {
            down(center)
            moveBy(Offset(-120f, 0f))
        }

        val dragged = composeRule.onNodeWithText("noticing").getUnclippedBoundsInRoot()
        assertTrue("row did not follow the drag: $before -> $dragged", dragged.left < before.left)
    }

    @Test
    fun deleteFailed_showsErrorAndKeepsTheRow() {
        // 删除没落库时不能弹「已删除」。行留在列表里，用户需要知道这次左滑没生效。
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        composeRule.runOnIdle { uiEvents.tryEmit(VocabularyUiEvent.DeleteFailed) }

        composeRule.onNodeWithText(string(R.string.vocabulary_delete_failed)).assertIsDisplayed()
        // 关键区别：失败时那条带「撤销」的成功提示一条都不能出现，否则用户以为词已经没了。
        composeRule.onNodeWithText(string(R.string.vocabulary_deleted, "noticing"))
            .assertDoesNotExist()
        composeRule.onNodeWithText("noticing").assertIsDisplayed()
    }

    @Test
    fun deleted_offersUndoThatRestoresThatWord() {
        // 「已删除 + 撤销」由 ViewModel 在写库返回后发的事件驱动，不是左滑当场弹。
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        composeRule.runOnIdle { uiEvents.tryEmit(VocabularyUiEvent.Deleted(word)) }

        composeRule.onNodeWithText(string(R.string.vocabulary_deleted, "noticing"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.vocabulary_undo)).performClick()

        composeRule.runOnIdle { verify(exactly = 1) { viewModel.restoreVocabulary(word) } }
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
                    onOpenArticle = { articleId, word -> openedArticles += articleId to word },
                    viewModel = viewModel
                )
            }
        }
    }

    private fun string(resourceId: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resourceId, *args)
}
