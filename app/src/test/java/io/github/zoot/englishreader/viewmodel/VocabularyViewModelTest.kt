package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.audio.WordAudioUrl
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.entity.VocabularyWithSource
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.OfflineLookupResult
import io.github.zoot.englishreader.data.repository.VocabularyRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

    private fun setupWith(rows: List<VocabularyWithSource>) {
        vocabularyRepository = mockk(relaxed = true)
        dictionaryRepository = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        networkChecker = mockk(relaxed = true)
        ttsPlayer = mockk(relaxed = true)
        pronunciationAudioCache = mockk(relaxed = true)
        every { vocabularyRepository.getAllVocabularyWithSource() } returns flowOf(rows)
        every { networkChecker.isOnline() } returns true
        viewModel = VocabularyViewModel(
            vocabularyRepository = vocabularyRepository,
            dictionaryRepository = dictionaryRepository,
            audioPlayer = audioPlayer,
            networkChecker = networkChecker,
            ttsPlayer = ttsPlayer,
            pronunciationAudioCache = pronunciationAudioCache
        )
    }

    /** 只关心分组时用这个重载：来源标题一并置空。 */
    private fun setupWithWords(words: List<VocabularyEntity>) {
        setupWith(words.map { VocabularyWithSource(it, articleTitle = null) })
    }

    @Before
    fun setup() {
        setupWith(emptyList<VocabularyWithSource>())
    }

    @Test
    fun default_groupType_isByTime() = runTest {
        assertEquals(GroupType.ByTime, viewModel.groupType.value)
    }

    @Test
    fun switchToAlphabet_groupsWordsByFirstLetter() = runTest {
        setupWithWords(
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
    fun restoreVocabulary_zeroesIdBeforeInsert() = runTest {
        val entity = vocab("restore-me", 99)

        viewModel.restoreVocabulary(entity)

        // 带原 id 插入会写成显式主键，可能覆盖删除后新建的行。
        coVerify(exactly = 1) { vocabularyRepository.insertVocabulary(entity.copy(id = 0)) }
        coVerify(exactly = 0) { vocabularyRepository.insertVocabulary(entity) }
    }

    @Test
    fun details_resolvesPhoneticGlossAndSourceTitle() = runTest {
        setupWith(listOf(row("Apple", id = 1L, articleTitle = "The Future of AI")))
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
        setupWith(listOf(row("lives", id = 7L)))
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
        setupWith(listOf(row("Zyxwvu", id = 3L, articleTitle = "Obscure Words")))
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
        verify(exactly = 1) { ttsPlayer.speak("noticing", any()) }
    }

    @Test
    fun playWordAudio_looksUpCacheWithLowercaseWord() = runTest {
        // 生词存的是原文大小写，而缓存键与阅读页一致走 lowercase——
        // 两边不一致会让阅读时缓存过的词在生词本里永远命中不了。
        viewModel.playWordAudio(vocab("Noticing", 1L))

        coVerify(exactly = 1) { pronunciationAudioCache.get("noticing") }
    }
}
