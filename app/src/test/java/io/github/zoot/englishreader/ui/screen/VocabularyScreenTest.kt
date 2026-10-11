package io.github.zoot.englishreader.ui.screen

import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.model.StudyWord
import io.github.zoot.englishreader.ui.screen.vocabulary.StudyWordGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroup
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupingTime
import io.github.zoot.englishreader.viewmodel.ContextLibraryState
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
import org.robolectric.Shadows.shadowOf
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

    private val groupsState = MutableStateFlow<List<StudyWordGroup>>(emptyList())
    private val detailsState = MutableStateFlow<Map<String, VocabularyWordDetail>>(emptyMap())
    private val groupTypeState = MutableStateFlow<GroupType>(GroupType.ByTime)
    private val libraryState = MutableStateFlow(ContextLibraryState(loading = false))
    private val loadingAudioKeyState = MutableStateFlow<String?>(null)
    private val openedArticles = mutableListOf<Pair<Long, String>>()
    private val excerptsState = MutableStateFlow<List<ExcerptEntity>>(emptyList())
    private val excerptPlaybackState = MutableStateFlow<io.github.zoot.englishreader.viewmodel.ExcerptPlaybackState?>(null)
    private val excerptToOpen = MutableSharedFlow<ExcerptEntity>(extraBufferCapacity = 1)
    private val openedExcerpts = mutableListOf<Pair<Long, Long>>()
    private val selectedTabState = MutableStateFlow(0)
    private val expandedExcerptsState = MutableStateFlow<Set<Long>>(emptySet())
    private val sortMenuState = MutableStateFlow(false)
    private val studyInfoState = MutableStateFlow(false)
    private val excerptMenuState = MutableStateFlow<Long?>(null)
    private val wordMenuState = MutableStateFlow<String?>(null)
    private val wordToDeleteState = MutableStateFlow<StudyWord?>(null)
    private val favoritesTimeState = MutableStateFlow(VocabularyGroupingTime.current())

    /** 一次性事件用无重放的 SharedFlow 模拟 Channel：测试自行 tryEmit 触发界面反应。 */
    private val uiEvents = MutableSharedFlow<VocabularyUiEvent>(extraBufferCapacity = 4)

    private val viewModel = mockk<VocabularyViewModel>(relaxed = true).also { model ->
        every { model.studyGroups } returns groupsState
        every { model.studyDetails } returns detailsState
        every { model.groupType } returns groupTypeState
        every { model.loadingAudioKey } returns loadingAudioKeyState
        every { model.uiEvent } returns uiEvents
        every { model.excerpts } returns excerptsState
        every { model.excerptPlayback } returns excerptPlaybackState
        every { model.excerptToOpen } returns excerptToOpen
        every { model.contextLibrary } returns libraryState
        every { model.contextToOpen } returns MutableSharedFlow()
        every { model.selectedTab } returns selectedTabState
        every { model.expandedExcerpts } returns expandedExcerptsState
        every { model.sortMenuExpanded } returns sortMenuState
        every { model.showStudyInfo } returns studyInfoState
        every { model.excerptMenuId } returns excerptMenuState
        every { model.wordMenuKey } returns wordMenuState
        every { model.wordToDelete } returns wordToDeleteState
        every { model.favoritesTime } returns favoritesTimeState
        every { model.selectTab(any()) } answers { selectedTabState.value = firstArg() }
        every { model.setSortMenuExpanded(any()) } answers { sortMenuState.value = firstArg() }
        every { model.setExcerptMenu(any()) } answers { excerptMenuState.value = firstArg() }
        every { model.setWordMenu(any()) } answers { wordMenuState.value = firstArg() }
        every { model.confirmWordDeletion(any()) } answers { wordToDeleteState.value = firstArg() }
        every { model.toggleExcerptExpanded(any()) } answers {
            val id = firstArg<Long>()
            expandedExcerptsState.value = if (id in expandedExcerptsState.value) expandedExcerptsState.value - id
                else expandedExcerptsState.value + id
        }
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

        val header = string(R.string.favorites_group_words, string(R.string.vocabulary_group_today), 1)
        scrollToText(header)
        composeRule.onNodeWithText(header).assertIsDisplayed()
        scrollToText("noticing")
        composeRule.onNodeWithText("noticing").assertIsDisplayed()
        composeRule.onNodeWithText("/ˈnəʊtɪsɪŋ/").assertIsDisplayed()
        composeRule.onNodeWithText("vt. 注意, 注意到").assertIsDisplayed()
    }

    @Test
    fun alphabetGroup_rendersHeaderAndWord() {
        groupTypeState.value = GroupType.ByAlphabet
        render(groups = listOf(group(VocabularyGroupId.Alphabet("A"), listOf(word(1L, "apple")))))

        val header = string(R.string.favorites_group_letters, "A", 1)
        scrollToText(header)
        composeRule.onNodeWithText(header).assertIsDisplayed()
        scrollToText("apple")
        composeRule.onNodeWithText("apple").assertIsDisplayed()
    }

    @Test
    fun emptyVocabulary_showsEmptyStateAndCollapsedSortMenu() {
        render(groups = emptyList())

        scrollToText(string(R.string.study_words_empty))
        composeRule.onNodeWithText(string(R.string.study_words_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.favorites_sort_alphabet)).assertDoesNotExist()
    }

    @Test
    fun sortMenu_choosingAlphabet_dispatchesGroupType() {
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word(1L, "noticing")))))

        scrollToText(string(R.string.favorites_sort_time))
        composeRule.onNodeWithText(string(R.string.favorites_sort_time)).performClick()
        composeRule.onNodeWithText(string(R.string.favorites_sort_alphabet)).performScrollTo().performClick()

        verify(exactly = 1) { viewModel.switchGroupType(GroupType.ByAlphabet) }
    }

    @Test
    fun sourceArticleMenu_opensThatArticleAfterStoppingAudio() {
        val word = word(1L, "noticing", articleId = 42L)
        val events = mutableListOf<String>()
        every { viewModel.stopAudio() } answers { events += "stop" }
        render(
            groups = listOf(group(VocabularyGroupId.Today, listOf(word))),
            details = mapOf(
                word.id to VocabularyWordDetail(sourceTitle = "The Future of AI")
            ),
            onOpenArticle = { articleId, selectedWord ->
                events += "navigate"
                openedArticles += articleId to selectedWord
            }
        )

        openWordMenu("noticing")
        composeRule.onNodeWithText(string(R.string.vocabulary_open_article)).assertIsDisplayed()
        composeRule.runOnIdle { events.clear() }
        composeRule.onNodeWithText(string(R.string.vocabulary_open_article)).performScrollTo().performClick()

        // 词一并带过去：阅读页要靠它滚动到并高亮该词所在的段落。
        composeRule.runOnIdle {
            assertEquals(listOf(42L to "noticing"), openedArticles)
            assertEquals(listOf("stop", "navigate"), events)
        }
    }

    @Test
    fun lifecycle_leavingResumedAndDisposing_stopsWithoutStartingPlaybackOnReturn() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        val visible = mutableStateOf(true)
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) VocabularyScreen(onOpenArticle = { _, _ -> }, viewModel = viewModel)
            }
        }
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            verify(exactly = 1) { viewModel.stopAudio() }
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.runOnIdle {
            verify(exactly = 0) { viewModel.playStudyWordAudio(any()) }
            visible.value = false
        }
        composeRule.runOnIdle { verify(exactly = 2) { viewModel.stopAudio() } }
    }

    @Test
    fun groupingClock_visibleResumeAndBroadcasts_refreshButStopAndDisposalDetach() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        val visible = mutableStateOf(true)
        var updates = 0
        var before = 0
        every { viewModel.updateGroupingTime(any()) } answers { updates++ }
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) VocabularyScreen(onOpenArticle = { _, _ -> }, viewModel = viewModel)
            }
        }
        composeRule.runOnIdle {
            assertTrue(updates > 0)
            before = updates
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.runOnIdle { assertEquals(before + 1, updates) }

        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) {
            composeRule.runOnIdle {
                before = updates
                RuntimeEnvironment.getApplication().sendBroadcast(Intent(action))
                shadowOf(Looper.getMainLooper()).idle()
            }
            composeRule.runOnIdle { assertEquals(before + 1, updates) }
        }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.runOnIdle {
            before = updates
            RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
        }
        composeRule.runOnIdle {
            assertEquals(before + 1, updates)
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.runOnIdle {
            before = updates
            RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
        }
        composeRule.runOnIdle {
            assertEquals(before, updates)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.runOnIdle {
            assertTrue(updates > before)
            visible.value = false
        }
        composeRule.runOnIdle {
            before = updates
            RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.runOnIdle { assertEquals(before, updates) }
    }

    @Test
    fun pronunciationButton_playsItsWord() {
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        composeRule.onNodeWithContentDescription(string(R.string.word_play_pronunciation, "noticing")).performClick()

        verify(exactly = 1) { viewModel.playStudyWordAudio(StudyWord.Legacy(word)) }
    }

    @Test
    fun wordWithoutGloss_displaysHonestMissingAnswerAndNoSourceAction() {
        val word = word(1L, "Zyxwvu", articleId = null)
        render(
            groups = listOf(group(VocabularyGroupId.Today, listOf(word))),
            details = mapOf(word.id to VocabularyWordDetail())
        )

        composeRule.onNodeWithText("Zyxwvu").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.study_answer_missing)).assertIsDisplayed()
        composeRule.onNodeWithText("纠正释义").assertDoesNotExist()
        composeRule.onNodeWithTag("context-meaning-input").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.vocabulary_open_article)).assertDoesNotExist()
    }

    @Test
    fun deleteButton_isAccessibleAndRequiresConfirmation() {
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        openWordMenu("noticing")
        composeRule.onNodeWithText(string(R.string.action_delete)).performScrollTo().performClick()
        verify(exactly = 0) { viewModel.deleteStudyWord(any()) }
        composeRule.onNodeWithText(string(R.string.study_delete_title)).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.action_delete)) and hasAnyAncestor(isDialog())).performClick()
        verify(exactly = 1) { viewModel.deleteStudyWord(StudyWord.Legacy(word)) }
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
                "vocabulary:${word.id}" to VocabularyWordDetail(
                    phonetic = "/ˈnəʊtɪsɪŋ/",
                    chinese = "vt. 注意, 注意到"
                )
            )
        }

        composeRule.onNodeWithText("vt. 注意, 注意到").assertIsDisplayed()
    }

    @Test
    fun deleteFailed_showsErrorInsteadOfTheUndoPrompt() {
        // 删除没落库时绝不能弹「已删除 + 撤销」那条成功提示，否则用户以为词已经没了。
        //
        // 这里**不**断言「行还在」：本测试的 mock 数据流自始至终不变，行在任何实现下
        // 都在，那样的断言恒真、无法因它命名的原因失败。真正覆盖「失败后行仍可再滑一次」
        // 的是 ViewModel 去重的解除（deleteVocabulary_whileFirstStillPending_doesNotDeleteTwice）
        // 与 swipeLeft_rowSlidesBackAndWaitsForTheListToDropIt。
        val word = word(1L, "noticing")
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word))))

        composeRule.runOnIdle { uiEvents.tryEmit(VocabularyUiEvent.DeleteFailed) }

        composeRule.onNodeWithText(string(R.string.vocabulary_delete_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.vocabulary_deleted, "noticing"))
            .assertDoesNotExist()
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
    fun excerptsTab_expandsCopiesAndReturnsToExistingWords() {
        val excerpt = excerpt()
        excerptsState.value = listOf(excerpt)
        render(groups = listOf(group(VocabularyGroupId.Today, listOf(word(1L, "noticing")))))

        composeRule.onNodeWithText("noticing").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.favorites_excerpts)).performClick()
        composeRule.onNodeWithText(excerpt.text).assertIsDisplayed()
        composeRule.onNodeWithText(excerpt.text).performClick()
        composeRule.onNodeWithText(excerpt.text).assert(SemanticsMatcher("collapse action") {
            it.config[SemanticsActions.OnClick].label == string(R.string.excerpt_collapse)
        })
        verify(exactly = 1) { viewModel.toggleExcerptExpanded(excerpt.id) }
        composeRule.runOnIdle { assertTrue(openedExcerpts.isEmpty()) }
        openExcerptMenu()
        composeRule.onNodeWithText(string(R.string.excerpt_copy)).performClick()
        composeRule.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication()
                .getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(excerpt.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        composeRule.onNodeWithText(excerpt.sourceTitle).performClick()
        verify(exactly = 1) { viewModel.openExcerpt(excerpt) }
        composeRule.onNodeWithText(string(R.string.favorites_words)).performClick()
        scrollToText("noticing")
        composeRule.onNodeWithText("noticing").assertIsDisplayed()
    }

    @Test
    fun deletedExcerpt_snackbarDoesNotBlockNavigationAndUndoRestoresSnapshot() {
        val excerpt = excerpt()
        excerptsState.value = listOf(excerpt)
        render(groups = emptyList())
        composeRule.onNodeWithText(string(R.string.favorites_excerpts)).performClick()
        openExcerptMenu()
        composeRule.onNodeWithText(string(R.string.excerpt_delete)).performClick()
        verify(exactly = 1) { viewModel.deleteExcerpt(excerpt) }
        composeRule.runOnIdle { uiEvents.tryEmit(VocabularyUiEvent.ExcerptDeleted(excerpt)) }
        composeRule.onNodeWithText(string(R.string.excerpt_deleted)).assertIsDisplayed()
        composeRule.runOnIdle { excerptToOpen.tryEmit(excerpt) }
        composeRule.runOnIdle { assertEquals(listOf(42L to excerpt.id), openedExcerpts) }
        composeRule.onNodeWithText(string(R.string.vocabulary_undo)).performClick()
        composeRule.runOnIdle { verify(exactly = 1) { viewModel.restoreExcerpt(excerpt) } }
    }

    @Test
    fun excerptSourceDeleted_showsApprovedMessageAndDisablesNavigation() {
        excerptsState.value = listOf(excerpt().copy(articleId = null))
        render(groups = emptyList())
        composeRule.onNodeWithText(string(R.string.favorites_excerpts)).performClick()
        composeRule.onNodeWithText(excerpt().sourceTitle).assertIsNotEnabled()
        openExcerptMenu()
        composeRule.onNodeWithText("无法获取来源，文章可能被删除").assertIsDisplayed()
        verify(exactly = 0) { viewModel.openExcerpt(any()) }
    }

    @Test
    fun excerptPlayback_preparingAndPlaying_haveStopActionEvenWithoutSource() {
        val excerpt = excerpt().copy(articleId = null)
        excerptsState.value = listOf(excerpt)
        render(groups = emptyList())
        composeRule.onNodeWithText(string(R.string.favorites_excerpts)).performClick()
        composeRule.onNodeWithTag("excerpt-play-${excerpt.id}").assertIsDisplayed().performClick()
        verify(exactly = 1) { viewModel.toggleExcerptPlayback(excerpt) }
        composeRule.runOnIdle {
            excerptPlaybackState.value = io.github.zoot.englishreader.viewmodel.ExcerptPlaybackState(excerpt.id, loading = true)
        }
        composeRule.onNodeWithTag("excerpt-play-${excerpt.id}").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.excerpt_audio_loading)))
            .performClick()
        verify(exactly = 2) { viewModel.toggleExcerptPlayback(excerpt) }
        composeRule.runOnIdle {
            excerptPlaybackState.value = io.github.zoot.englishreader.viewmodel.ExcerptPlaybackState(excerpt.id, loading = false)
        }
        composeRule.onNodeWithContentDescription(string(R.string.excerpt_stop)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.favorites_words)).performClick()
        verify(exactly = 1) { viewModel.selectTab(0) }
        composeRule.onNodeWithTag("excerpts-list").assertDoesNotExist()
    }

    private fun excerpt() = ExcerptEntity(
        id = 3, articleId = 42, originArticleId = 42, sourceTitle = "Source title",
        contentFingerprint = "fingerprint", paragraphIndex = 0, startOffset = 0,
        endOffset = 16, text = "A  precise quote", createdAt = 0
    )

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
        details: Map<Long, VocabularyWordDetail> = emptyMap(),
        onOpenArticle: (Long, String) -> Unit = { articleId, word -> openedArticles += articleId to word }
    ) {
        groupsState.value = groups.map { group ->
            StudyWordGroup(group.id, group.words.map { StudyWord.Legacy(it, details[it.id]?.sourceTitle) }, group.isExpanded)
        }
        detailsState.value = details.mapKeys { "vocabulary:${it.key}" }
        libraryState.value = ContextLibraryState(words = groupsState.value.flatMap { it.words }, loading = false)
        composeRule.setContent {
            Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                VocabularyScreen(
                    onOpenArticle = onOpenArticle,
                    viewModel = viewModel,
                    onOpenExcerpt = { articleId, excerptId -> openedExcerpts += articleId to excerptId }
                )
            }
        }
        groups.firstOrNull()?.words?.firstOrNull()?.let { scrollToText(it.word) }
    }

    private fun openWordMenu(word: String) {
        scrollToText(word)
        composeRule.onNodeWithContentDescription(string(R.string.favorites_word_actions, word)).performScrollTo().performClick()
    }

    private fun openExcerptMenu() {
        composeRule.onNodeWithContentDescription(string(R.string.favorites_excerpt_actions)).performScrollTo().performClick()
    }

    private fun scrollToText(text: String) {
        composeRule.onNodeWithTag("context-card-list").performScrollToNode(hasText(text))
    }

    private fun string(resourceId: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resourceId, *args)
}
