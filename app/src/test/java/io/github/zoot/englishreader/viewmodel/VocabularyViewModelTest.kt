package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.WordAudioUrl
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.OfflineLookupResult
import io.github.zoot.englishreader.data.repository.VocabularyInsertResult
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.ui.screen.vocabulary.GroupType
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyGroupId
import io.github.zoot.englishreader.ui.screen.vocabulary.VocabularyWordDetail
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * VocabularyViewModel 单元测试
 *
 * 覆盖：
 * - 分组逻辑（默认按时间、切字母、重置展开、toggleGroup）
 * - 卡片补充信息：释义、音标、词形还原标注、来源文章标题
 * - 删除与撤销删除（撤销必须把 id 归零，见 restoreVocabulary 的注释）
 */
class VocabularyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var vocabularyRepository: VocabularyRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var networkChecker: NetworkChecker
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var pronunciationAudioCache: PronunciationAudioCache
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var viewModel: VocabularyViewModel

    private fun vocab(word: String, id: Long): VocabularyEntity =
        VocabularyEntity(id = id, word = word, articleId = 1L)

    private fun row(
        word: String,
        id: Long,
        articleTitle: String? = null
    ): VocabularyWithSource = VocabularyWithSource(
        vocabulary = VocabularyEntity(
            id = id,
            word = word,
            articleId = if (articleTitle != null) 1L else null
        ),
        articleTitle = articleTitle
    )

    private fun offlineEntry(word: String, phonetic: String?, chinese: String) =
        OfflineLookupResult(DictionaryEntry(word = word, phonetic = phonetic, chinese = chinese, english = null))

    /**
     * 上游生词流。
     *
     * 用例只改它的值，不重建 mock 与 ViewModel：`stateIn` 在构造时就订阅了这个流实例，
     * 构造后再改 mock 的返回值是无效的（指向的是另一个 flow 对象）。这也让本文件与
     * ArticleListViewModelTest 的「@Before 建一次，用例只改上游」约定一致。
     */
    private val rowsFlow = MutableStateFlow<List<VocabularyWithSource>>(emptyList())

    @Before
    fun setup() {
        vocabularyRepository = mockk(relaxed = true)
        dictionaryRepository = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        networkChecker = mockk(relaxed = true)
        ttsPlayer = mockk(relaxed = true)
        pronunciationAudioCache = mockk(relaxed = true)
        settingsPreferences = mockk(relaxed = true)
        every { vocabularyRepository.getAllVocabularyWithSource() } returns rowsFlow
        every { networkChecker.isOnline() } returns true
        // 默认未授权联网 TTS：与产品默认值一致（SettingsPreferences.allowNetworkTts 缺省 false），
        // 于是既有用例断言的仍是「单词发音只用本地语音」这个改动前的行为。
        every { settingsPreferences.allowNetworkTts } returns MutableStateFlow(false)
        // **必须显式打桩。** relaxed mock 对返回 `Flow` 的属性给的是一个永不发射的 mock Flow，
        // `speakViaTts` 里的 `.first()` 会就此永久挂起——于是 `speakWord` 根本不会被调用，
        // 失败信息显示成「was not called」而不是「挂住了」，很容易被误读成产线代码漏了调用。
        // 与 `ReadingViewModelFixture` 对 `pronunciationAudioCache.get` 的那条注释同一类问题。
        every { settingsPreferences.ttsReadingSettings } returns MutableStateFlow(TtsReadingSettings())
        viewModel = VocabularyViewModel(
            vocabularyRepository = vocabularyRepository,
            dictionaryRepository = dictionaryRepository,
            audioPlayer = audioPlayer,
            networkChecker = networkChecker,
            ttsPlayer = ttsPlayer,
            pronunciationAudioCache = pronunciationAudioCache,
            settingsPreferences = settingsPreferences
        )
    }

    /** 设置上游生词（含来源标题）。 */
    private fun withRows(rows: List<VocabularyWithSource>) {
        rowsFlow.value = rows
    }

    /** 只关心分组时用这个：来源标题一并置空。 */
    private fun withWords(words: List<VocabularyEntity>) {
        withRows(words.map { VocabularyWithSource(it, articleTitle = null) })
    }

    @Test
    fun default_groupType_isByTime() = runTest {
        assertEquals(GroupType.ByTime, viewModel.groupType.value)
    }

    @Test
    fun switchToAlphabet_groupsWordsByFirstLetter() = runTest {
        withWords(
            listOf(
                vocab("apple", 1),
                vocab("banana", 2),
                vocab("avocado", 3)
            )
        )

        viewModel.groups.test {
            // 先跳过时间分组的初始输出
            awaitItem()
            viewModel.switchGroupType(GroupType.ByAlphabet)

            // 等到出现字母分组结果（A、B）
            var groups = awaitItem()
            while (groups.none { it.id == VocabularyGroupId.Alphabet("A") }) {
                groups = awaitItem()
            }

            val a = groups.first { it.id == VocabularyGroupId.Alphabet("A") }
            assertEquals(2, a.words.size) // apple, avocado
            assertEquals(listOf("apple", "avocado"), a.words.map { it.word })
            val b = groups.first { it.id == VocabularyGroupId.Alphabet("B") }
            assertEquals(1, b.words.size) // banana
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun switchGroupType_resetsExpandedState() = runTest {
        assertEquals(setOf(VocabularyGroupId.Today), viewModel.expandedGroups.value)

        viewModel.switchGroupType(GroupType.ByAlphabet)
        // 切到字母分组，展开状态清空
        assertTrue(viewModel.expandedGroups.value.isEmpty())

        viewModel.switchGroupType(GroupType.ByTime)
        assertEquals(setOf(VocabularyGroupId.Today), viewModel.expandedGroups.value)
    }

    @Test
    fun toggleGroup_addsAndRemovesTitle() = runTest {
        viewModel.switchGroupType(GroupType.ByAlphabet) // 展开集合清空

        val groupId = VocabularyGroupId.Alphabet("A")
        viewModel.toggleGroup(groupId)
        assertTrue(groupId in viewModel.expandedGroups.value)

        viewModel.toggleGroup(groupId)
        assertTrue(groupId !in viewModel.expandedGroups.value)
    }

    @Test
    fun deleteVocabulary_delegatesToRepository() = runTest {
        val entity = vocab("delete-me", 99)

        viewModel.deleteVocabulary(entity)

        coVerify(exactly = 1) { vocabularyRepository.deleteVocabulary(entity) }
    }

    @Test
    fun deleteVocabulary_afterPersisting_emitsDeletedWithThatWord() = runTest {
        val entity = vocab("delete-me", 99)

        viewModel.uiEvent.test {
            viewModel.deleteVocabulary(entity)

            // 撤销要用这个实体重新插入，所以事件必须带着它。
            assertEquals(VocabularyUiEvent.Deleted(entity), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteVocabulary_writeFails_reportsFailureInsteadOfSuccess() = runTest {
        val entity = vocab("delete-me", 99)
        coEvery { vocabularyRepository.deleteVocabulary(entity) } throws RuntimeException("db down")

        viewModel.uiEvent.test {
            viewModel.deleteVocabulary(entity)

            // 没落库就绝不能报「已删除」——那条 Snackbar 还带撤销，用户会以为词已经没了。
            assertEquals(VocabularyUiEvent.DeleteFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteVocabulary_whileFirstStillPending_doesNotDeleteTwice() = runTest {
        // 左滑的 confirmValueChange 可能在目标值来回穿越阈值时被反复调用，所以去重必须
        // 真的挡住「前一次还没写完」的第二次调用。
        //
        // 判据必须让第一次删除**可观察地挂起**：`MainDispatcherRule` 用的是
        // UnconfinedTestDispatcher，launch 会 eager 跑到底（连 finally 摘除 id 一起），
        // 直接连调两次时根本不存在在途窗口，那样的测试验不到任何东西。
        val entity = vocab("delete-me", 99)
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var writeCount = 0
        coEvery { vocabularyRepository.deleteVocabulary(entity) } coAnswers {
            if (++writeCount == 1) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            }
        }

        viewModel.deleteVocabulary(entity)
        firstWriteStarted.await()

        // 第一次仍挂在写库里，此时的重复派发必须被丢弃。
        viewModel.deleteVocabulary(entity)
        coVerify(exactly = 1) { vocabularyRepository.deleteVocabulary(entity) }

        // 放行后去重登记必须解除：删掉再撤销、再删同一个 id 还得能删。
        releaseFirstWrite.complete(Unit)
        viewModel.deleteVocabulary(entity)
        coVerify(exactly = 2) { vocabularyRepository.deleteVocabulary(entity) }
    }

    @Test
    fun restoreVocabulary_zeroesIdBeforeInsert() = runTest {
        val entity = vocab("restore-me", 99)

        viewModel.restoreVocabulary(entity)

        // 带原 id 插入会写成显式主键，可能覆盖删除后新建的行。
        coVerify(exactly = 1) { vocabularyRepository.insertVocabulary(entity.copy(id = 0)) }
        coVerify(exactly = 0) { vocabularyRepository.insertVocabulary(entity) }
    }

    @Test
    fun restoreVocabulary_writeFails_reportsFailure() = runTest {
        val entity = vocab("restore-me", 99)
        coEvery { vocabularyRepository.insertVocabulary(any()) } throws RuntimeException("db down")

        viewModel.uiEvent.test {
            viewModel.restoreVocabulary(entity)

            // 撤销失败必须说出来，否则用户以为词回来了，实际没有。
            assertEquals(VocabularyUiEvent.RestoreFailed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restoreVocabulary_alreadyExists_isNotReportedAsFailure() = runTest {
        // UNIQUE(word, articleId) 冲突不是失败：词确实在生词本里，那正是用户要的结果。
        val entity = vocab("restore-me", 99)
        coEvery { vocabularyRepository.insertVocabulary(any()) } returns
            VocabularyInsertResult.AlreadyExists

        viewModel.uiEvent.test {
            viewModel.restoreVocabulary(entity)
            runCurrent()

            expectNoEvents()
        }
    }

    @Test
    fun details_resolvesPhoneticGlossAndSourceTitle() = runTest {
        withRows(listOf(row("Apple", id = 1L, articleTitle = "The Future of AI")))
        // 生词存的是原文大小写，查词典必须走 lowercase。
        coEvery { dictionaryRepository.lookupOffline("apple") } returns
            offlineEntry("apple", "/ˈæpl/", "n. 苹果；苹果树")

        val detail = awaitDetails().getValue(1L)

        assertEquals("/ˈæpl/", detail.phonetic)
        assertEquals("n. 苹果；苹果树", detail.chinese)
        assertEquals("The Future of AI", detail.sourceTitle)
        assertNull("直接命中时不应标注原形", detail.headword)
        assertTrue(detail.hasGloss)
    }

    @Test
    fun details_inflectedForm_reportsHeadword() = runTest {
        withRows(listOf(row("lives", id = 7L)))
        coEvery { dictionaryRepository.lookupOffline("lives") } returns
            OfflineLookupResult(
                entry = DictionaryEntry("live", "/lɪv/", "vi. 活；居住", null),
                inflectedForm = "lives"
            )

        val detail = awaitDetails().getValue(7L)

        assertEquals("/lɪv/", detail.phonetic)
        assertEquals("live", detail.headword)
    }

    @Test
    fun details_wordMissingFromDictionary_hasNoGlossButKeepsSource() = runTest {
        withRows(listOf(row("Zyxwvu", id = 3L, articleTitle = "Obscure Words")))
        coEvery { dictionaryRepository.lookupOffline("zyxwvu") } returns null

        val detail = awaitDetails().getValue(3L)

        assertNull(detail.phonetic)
        assertNull(detail.chinese)
        assertTrue(!detail.hasGloss)
        // 词库查不到不应连累来源标题。
        assertEquals("Obscure Words", detail.sourceTitle)
    }

    /** 取第一份非空的 details：stateIn 的初始值是空 map，解析完成后才发第二份。 */
    private suspend fun awaitDetails(): Map<Long, VocabularyWordDetail> =
        viewModel.details.first { it.isNotEmpty() }

    // ---- 点击单词播放读音 ----

    @Test
    fun playWordAudio_cachedFile_playsLocalPathInsteadOfRemote() = runTest {
        val entity = vocab("noticing", 1L)
        val cached = File("cache/abc.mp3")
        coEvery { pronunciationAudioCache.get("noticing") } returns cached

        viewModel.playWordAudio(entity)

        // 命中缓存必须走本地文件：远端 URL 在离线时根本播不出来，而缓存存在的
        // 全部意义就是让这些词离线可用。
        coVerify(exactly = 1) {
            audioPlayer.play(url = cached.absolutePath, onComplete = any(), onError = any())
        }
        coVerify(exactly = 0) { audioPlayer.play(url = WordAudioUrl.forWord("noticing"), any(), any()) }
    }

    @Test
    fun playWordAudio_notCachedAndOnline_playsRemoteUrl() = runTest {
        val entity = vocab("noticing", 1L)
        coEvery { pronunciationAudioCache.get("noticing") } returns null
        every { networkChecker.isOnline() } returns true

        viewModel.playWordAudio(entity)

        coVerify(exactly = 1) {
            audioPlayer.play(
                url = WordAudioUrl.forWord("noticing"),
                onComplete = any(),
                onError = any()
            )
        }
    }

    @Test
    fun playWordAudio_notCachedAndOffline_fallsBackToTtsWithoutRequestingAudio() = runTest {
        val entity = vocab("noticing", 1L)
        coEvery { pronunciationAudioCache.get("noticing") } returns null
        every { networkChecker.isOnline() } returns false

        viewModel.playWordAudio(entity)

        coVerify(exactly = 0) { audioPlayer.play(any(), any(), any()) }
        verify(exactly = 1) { ttsPlayer.speakWord("noticing", any(), any(), any()) }
    }

    /**
     * 已授权联网 TTS 时，单词兜底必须把 `true` 传下去。
     *
     * 改动前 `TtsPlayer.speak` 把 `allowNetwork` 硬编码成 false，于是单词发音永远只能用本地
     * 语音——那是 Google 的老式拼接音，而网络语音才是神经网络音。用户既然已经为整句朗读开了
     * 这个开关，单词没有理由被排除在外。
     *
     * 断言必须用 `eq(true)` 而不是 `any()`：上面那条 `fallsBackToTts` 用例正是用 `any()`，
     * 所以它在「授权被丢弃、永远传 false」的实现下照样绿。
     */
    @Test
    fun playWordAudio_networkTtsAuthorized_passesTheConsentThrough() = runTest {
        every { settingsPreferences.allowNetworkTts } returns MutableStateFlow(true)
        coEvery { pronunciationAudioCache.get("noticing") } returns null
        every { networkChecker.isOnline() } returns false

        viewModel.playWordAudio(vocab("noticing", 1L))

        verify(exactly = 1) { ttsPlayer.speakWord("noticing", any(), eq(true), any()) }
    }

    /** 未授权时仍然只用本地语音——这一路不得绕过用户的同意。 */
    @Test
    fun playWordAudio_networkTtsNotAuthorized_keepsWordFallbackLocalOnly() = runTest {
        // setup 里默认就是 false（与产品默认一致），这里显式写出来让判据自解释。
        every { settingsPreferences.allowNetworkTts } returns MutableStateFlow(false)
        coEvery { pronunciationAudioCache.get("noticing") } returns null
        every { networkChecker.isOnline() } returns false

        viewModel.playWordAudio(vocab("noticing", 1L))

        verify(exactly = 1) { ttsPlayer.speakWord("noticing", any(), eq(false), any()) }
    }

    @Test
    fun playWordAudio_looksUpCacheWithLowercaseWord() = runTest {
        // 生词存的是原文大小写，而缓存键与阅读页一致走 lowercase——
        // 两边不一致会让阅读时缓存过的词在生词本里永远命中不了。
        viewModel.playWordAudio(vocab("Noticing", 1L))

        coVerify(exactly = 1) { pronunciationAudioCache.get("noticing") }
    }

    /**
     * 用户在设置里挑的语音必须传到单词兜底。
     *
     * 改动前 `speakWord` 收到的是 `TtsReadingSettings()`，即 `voiceId = null`：设置页挑的语音
     * 对单词发音完全无效。与自动选择的「离线优先」叠加后会变成听得见的割裂——好语音基本都是
     * 网络语音，于是已授权且挑了网络神经语音的用户，在生词本里点词仍然是本地拼接音。
     *
     * 断言必须用 `eq("engine/neural")` 而非 `any()`：上面那几条既有用例都用 `any()` 占位，
     * 所以它们在「voiceId 被丢弃、永远传 null」的实现下照样绿。
     */
    @Test
    fun playWordAudio_passesTheChosenReadingVoiceThrough() = runTest {
        every { settingsPreferences.ttsReadingSettings } returns
            MutableStateFlow(TtsReadingSettings(voiceId = "engine/neural"))
        coEvery { pronunciationAudioCache.get("noticing") } returns null
        every { networkChecker.isOnline() } returns false

        viewModel.playWordAudio(vocab("noticing", 1L))

        verify(exactly = 1) { ttsPlayer.speakWord("noticing", eq("engine/neural"), any(), any()) }
    }
}
