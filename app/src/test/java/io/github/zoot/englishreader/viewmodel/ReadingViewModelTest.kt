package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.BookChapterEntity
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.remote.dictionary.Definition
import io.github.zoot.englishreader.data.remote.dictionary.DictionaryResponse
import io.github.zoot.englishreader.data.remote.dictionary.Meaning
import io.github.zoot.englishreader.data.remote.dictionary.Phonetic
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import io.github.zoot.englishreader.data.repository.AiExplanationStartResult
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.OfflineLookupResult
import io.github.zoot.englishreader.data.repository.VocabularyInsertResult
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiOperationHandle
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiOperationRef
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingPositionTarget
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.SelectedSentence
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import io.github.zoot.englishreader.util.TtsFailureReason
import io.github.zoot.englishreader.util.TtsPlaybackResult
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ReadingViewModel 单元测试
 *
 * 覆盖查词流程：
 * - 离线词典命中 → 使用离线释义、source=OFFLINE、中文按“；”拆分
 * - 词形还原命中 → 显示原形、标注 inflectedForm
 * - 离线未命中 → 降级在线 API，source=ONLINE
 * - 在线返回空 → 发 WORD_NOT_FOUND，清除高亮
 * - IOException → NETWORK_ERROR；其他异常 → UNKNOWN_ERROR
 * - 混合读音：有网真人音、无网自动播静默、无网手动播走 TTS
 * - loading 在成功/失败后都会结束
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var articleRepository: ArticleRepository
    private lateinit var vocabularyRepository: VocabularyRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var settingsPreferences: SettingsPreferences
    private lateinit var bookRepository: BookRepository
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var networkChecker: NetworkChecker
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var aiOperationRegistry: AiExplanationOperationRegistry
    private lateinit var aiExplanationRepository: AiExplanationRepository
    private lateinit var pronunciationAudioCache: PronunciationAudioCache
    private lateinit var viewModel: ReadingViewModel
    private lateinit var savedState: SavedStateHandle

    private lateinit var fixture: ReadingViewModelFixture

    @Before
    fun setup() {
        // 共享装置负责 collaborator 与两个测试文件公用的默认打桩（含发音缓存
        // relaxed-mock 陷阱的说明）。下面的字段是同一批 mock 实例的别名，供既有用例
        // 直接打桩和 verify——clearMocks 依赖拿到的是同一个实例。
        fixture = ReadingViewModelFixture()
        articleRepository = fixture.articleRepository
        vocabularyRepository = fixture.vocabularyRepository
        dictionaryRepository = fixture.dictionaryRepository
        audioPlayer = fixture.audioPlayer
        networkChecker = fixture.networkChecker
        ttsPlayer = fixture.ttsPlayer
        settingsPreferences = fixture.settingsPreferences
        aiOperationRegistry = fixture.aiOperationRegistry
        aiExplanationRepository = fixture.aiExplanationRepository
        pronunciationAudioCache = fixture.pronunciationAudioCache
        bookRepository = fixture.bookRepository
        savedState = SavedStateHandle()
        viewModel = createViewModel(savedState)
    }

    private fun createViewModel(handle: SavedStateHandle) = fixture.create(handle)

    /**
     * 6.17 的所有权边界：ViewModel 只暴露一个聚合状态，不再增设
     * `_isLoadingAi` / `_aiExplanation` / `_aiError` 之类平行字段。
     */
    @Test
    fun aiSheetState_initiallyHidden() {
        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
    }

    @Test
    fun previewReadingVoice_waitsForLatestNetworkConsentWrite() = runTest {
        val allowNetwork = MutableStateFlow(true)
        val readingSettings = MutableStateFlow(TtsReadingSettings())
        val writeGate = CompletableDeferred<Unit>()
        every { settingsPreferences.allowNetworkTts } returns allowNetwork
        every { settingsPreferences.ttsReadingSettings } returns readingSettings
        coEvery { settingsPreferences.setAllowNetworkTts(false) } coAnswers {
            writeGate.await()
            allowNetwork.value = false
        }

        coEvery { articleRepository.getArticleById(90) } returns
            ArticleEntity(90, "title", "A sentence.")
        viewModel.loadArticle(90)
        advanceUntilIdle()
        viewModel.openVoiceSettings()
        runCurrent()

        viewModel.setReadingNetworkVoiceAllowed(false)
        runCurrent()
        viewModel.previewReadingVoice("Preview")
        runCurrent()
        verify(exactly = 0) { ttsPlayer.speakReading(any(), any(), any(), any()) }

        writeGate.complete(Unit)
        advanceUntilIdle()
        verify(exactly = 1) { ttsPlayer.speakReading("Preview", any(), false, any()) }
    }
    @Test
    fun userPreferenceChanges_persistThroughSharedSettingsPreferences() = runTest {
        viewModel.setReadingMode(ReadingMode.PAGED)
        advanceUntilIdle()
        coVerify(exactly = 1) { settingsPreferences.setReadingMode(ReadingMode.PAGED) }

        viewModel.setFontSizeOption(FontSizeOption.LARGE)
        advanceUntilIdle()
        coVerify(exactly = 1) { settingsPreferences.setFontSizeOption(FontSizeOption.LARGE) }

        viewModel.setThemeOption(ThemeOption.DARK)
        advanceUntilIdle()
        coVerify(exactly = 1) { settingsPreferences.setThemeOption(ThemeOption.DARK) }
    }

    @Test
    fun translateSelectedSentence_pendingRepositoryPublishesLoadingAndUsesTranslationSemantic() =
        runTest {
            coEvery { articleRepository.getArticleById(40) } returns
                io.github.zoot.englishreader.data.entity.ArticleEntity(40, "t", "First sentence.")
            viewModel.loadArticle(40)
            advanceUntilIdle()
            viewModel.selectSentence(40, 0, SentenceRange(0, "First sentence.", 0, 15))
            runCurrent()

            val gate = CompletableDeferred<Unit>()
            coEvery {
                aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First sentence."))
            } coAnswers {
                gate.await()
                AiExplanationStartResult.Rejected(AiError.Offline)
            }

            viewModel.translateSelectedSentence()
            runCurrent()

            val loading = viewModel.sentenceTranslationState.value as AiSheetState.Loading
            val target = loading.target as AiExplanationTarget.Sentence
            assertEquals(40L, target.snapshot.articleId)
            assertEquals(0, target.snapshot.sentenceIndex)
            coVerify(exactly = 1) {
                aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First sentence."))
            }

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                AiSheetState.Rejected(
                    error = AiError.Offline,
                    target = AiExplanationTarget.Sentence(
                        requireNotNull(viewModel.selectedSentence.value)
                    )
                ),
                viewModel.sentenceTranslationState.value
            )
        }

    @Test
    fun translateSelectedSentence_newSelectionDetachesOldOutcome() = runTest {
        coEvery { articleRepository.getArticleById(41) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(41, "t", "First. Second.")
        viewModel.loadArticle(41)
        advanceUntilIdle()
        viewModel.selectSentence(41, 0, SentenceRange(0, "First.", 0, 6))
        runCurrent()

        val outcome = CompletableDeferred<AiOperationOutcome>()
        val handle = AiOperationHandle(AiOperationRef("translation-key", "translation-op"), outcome)
        coEvery {
            aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First."))
        } returns AiExplanationStartResult.Started(handle)

        viewModel.translateSelectedSentence()
        runCurrent()
        assertTrue(viewModel.sentenceTranslationState.value is AiSheetState.Visible)

        clearMocks(ttsPlayer, answers = false, recordedCalls = true)
        viewModel.selectSentence(41, 1, SentenceRange(1, "Second.", 7, 14))
        advanceUntilIdle()
        verify(exactly = 1) { ttsPlayer.stopBeforeReading() }
        verify(exactly = 0) { ttsPlayer.stop() }
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)

        outcome.complete(AiOperationOutcome.Success("旧译文"))
        advanceUntilIdle()
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
    }

    @Test
    fun cancelSentenceTranslation_visibleOperation_cancelsExactRefAndHidesPopupState() = runTest {
        coEvery { articleRepository.getArticleById(43) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(43, "t", "First.")
        viewModel.loadArticle(43)
        advanceUntilIdle()
        viewModel.selectSentence(43, 0, SentenceRange(0, "First.", 0, 6))

        val outcome = CompletableDeferred<AiOperationOutcome>()
        val ref = AiOperationRef("translation-key", "translation-op")
        val handle = AiOperationHandle(ref, outcome)
        coEvery {
            aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First."))
        } returns AiExplanationStartResult.Started(handle)
        coEvery { aiOperationRegistry.cancel(ref) } returns true

        viewModel.translateSelectedSentence()
        runCurrent()
        viewModel.cancelSentenceTranslation(ref)
        advanceUntilIdle()

        coVerify(exactly = 1) { aiOperationRegistry.cancel(ref) }
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
    }

    @Test
    fun playSelectedSentence_usesFullRawTextAndDismissStopsAudio() = runTest {
        coEvery { articleRepository.getArticleById(42) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(42, "t", "  First sentence.  ")
        viewModel.loadArticle(42)
        advanceUntilIdle()
        viewModel.selectSentence(42, 0, SentenceRange(0, "  First sentence.  ", 0, 19))
        runCurrent()

        viewModel.playSelectedSentence()
        runCurrent()

        verify(exactly = 1) { ttsPlayer.speakReading(eq("  First sentence.  "), any(), eq(false), any()) }
        clearMocks(ttsPlayer, answers = false, recordedCalls = true)
        viewModel.dismissSentenceActions()
        advanceUntilIdle()
        verify(atLeast = 1) { ttsPlayer.stop() }
        assertNull(viewModel.selectedSentence.value)
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
    }

    @Test
    fun playSelectedSentence_failureDetachesTranslationWithoutCancellingPaidOperation() = runTest {
        coEvery { articleRepository.getArticleById(42) } returns ArticleEntity(42, "t", "First sentence.")
        viewModel.loadArticle(42)
        runCurrent()
        viewModel.selectSentence(42, 0, SentenceRange(0, "First sentence.", 0, 15))
        val outcome = CompletableDeferred<AiOperationOutcome>()
        val ref = AiOperationRef("translation-key", "translation-op")
        coEvery { aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First sentence.")) } returns
            AiExplanationStartResult.Started(AiOperationHandle(ref, outcome))
        viewModel.translateSelectedSentence()
        runCurrent()
        assertTrue(viewModel.sentenceTranslationState.value is AiSheetState.Visible)
        every { ttsPlayer.currentVoiceSnapshot() } returns io.github.zoot.englishreader.util.TtsVoiceSnapshot()
        every { ttsPlayer.speakReading(any(), any(), any(), any()) } answers {
            arg<(TtsPlaybackResult) -> Unit>(3)(TtsPlaybackResult.Failed(TtsFailureReason.LANGUAGE_DATA_MISSING))
        }

        viewModel.playSelectedSentence()
        runCurrent()

        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
        coVerify(exactly = 0) { aiOperationRegistry.cancel(ref) }
        assertFalse(outcome.isCancelled)
        outcome.complete(AiOperationOutcome.Success("Late translation"))
        runCurrent()
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
    }

    @Test
    fun dismissSentencePopup_preservesSelectionAndStopsPopupWork() = runTest {
        coEvery { articleRepository.getArticleById(42) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(42, "t", "First sentence.")
        viewModel.loadArticle(42)
        advanceUntilIdle()
        viewModel.selectSentence(42, 0, SentenceRange(0, "First sentence.", 0, 15))

        clearMocks(ttsPlayer, answers = false, recordedCalls = true)
        viewModel.dismissSentencePopup()
        advanceUntilIdle()

        assertEquals(
            SelectedSentence(42, 0, "First sentence.", "First sentence.", 0, 15),
            viewModel.selectedSentence.value
        )
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
        verify(atLeast = 1) { ttsPlayer.stop() }
    }

    @Test
    fun selectSentence_loadedArticle_publishesImmutableSnapshotAndClearsWord() = runTest {
        coEvery { articleRepository.getArticleById(7) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(7, "t", "First sentence.")
        coEvery { dictionaryRepository.lookupOffline("first") } returns
            offlineHit(word = "first", chinese = "第一")
        viewModel.loadArticle(7)
        advanceUntilIdle()
        viewModel.lookupWord("first")

        viewModel.selectSentence(7, 0, SentenceRange(0, "First sentence.", 0, 15))

        assertEquals(
            SelectedSentence(7, 0, "First sentence.", "First sentence.", 0, 15),
            viewModel.selectedSentence.value
        )
        assertNull(viewModel.selectedWord.value)
    }

    @Test
    fun selectSentence_wrongArticleOrLoadingState_isIgnored() = runTest {
        coEvery { articleRepository.getArticleById(3) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(3, "t", "First.")
        viewModel.loadArticle(3)
        advanceUntilIdle()
        viewModel.selectSentence(4, 0, SentenceRange(0, "Wrong.", 0, 6))
        assertNull(viewModel.selectedSentence.value)

        val loadedArticle = CompletableDeferred<ArticleEntity>()
        coEvery { articleRepository.getArticleById(5) } coAnswers { loadedArticle.await() }
        try {
            viewModel.loadArticle(5)
            runCurrent()
            assertTrue(viewModel.isLoadingArticle.value)
            viewModel.selectSentence(5, 0, SentenceRange(0, "Next.", 0, 5))
            assertNull(viewModel.selectedSentence.value)
        } finally {
            loadedArticle.complete(ArticleEntity(5, "t", "Next."))
        }
        advanceUntilIdle()

        assertFalse(viewModel.isLoadingArticle.value)
        viewModel.selectSentence(5, 0, SentenceRange(0, "Next.", 0, 5))
        assertEquals(
            SelectedSentence(5, 0, "Next.", "Next.", 0, 5),
            viewModel.selectedSentence.value
        )
    }

    @Test
    fun clearSelection_clearsSentenceSnapshot() = runTest {
        coEvery { articleRepository.getArticleById(8) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(8, "t", "First.")
        viewModel.loadArticle(8)
        advanceUntilIdle()
        viewModel.selectSentence(8, 0, SentenceRange(0, "First.", 0, 6))

        viewModel.clearSelection()

        assertNull(viewModel.selectedSentence.value)
        assertNull(viewModel.selectedWord.value)
    }

    @Test
    fun explainSelectedSentence_passesOnlyNormalizedTextAndPublishesTypedRejection() = runTest {
        coEvery { articleRepository.getArticleById(9) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(9, "t", "First.")
        viewModel.loadArticle(9)
        advanceUntilIdle()
        viewModel.selectSentence(9, 0, SentenceRange(0, "  First.  ", 0, 10))
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } returns
            AiExplanationStartResult.Rejected(AiError.Offline)

        viewModel.explainSelectedSentence()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            aiExplanationRepository.start(AiExplanationInput.Sentence("First."))
        }
        assertEquals(
            AiSheetState.Rejected(
                error = AiError.Offline,
                target = AiExplanationTarget.Sentence(
                    requireNotNull(viewModel.selectedSentence.value)
                )
            ),
            viewModel.aiSheetState.value
        )
    }

    @Test
    fun translateSelectedSentence_configurationErrorRoutesToPromptNotSheet() = runTest {
        coEvery { articleRepository.getArticleById(50) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(50, "t", "First.")
        viewModel.loadArticle(50)
        advanceUntilIdle()
        viewModel.selectSentence(50, 0, SentenceRange(0, "First.", 0, 6))
        runCurrent()
        coEvery {
            aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First."))
        } returns AiExplanationStartResult.Rejected(AiError.NoActiveProfile)

        viewModel.translateSelectedSentence()
        advanceUntilIdle()

        // 配置类拒绝不进弹层：弹层收回 Hidden，改由居中引导对话框接管，并清掉选句。
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
        assertTrue(viewModel.aiConfigurationPrompt.value)
        assertNull(viewModel.selectedSentence.value)

        viewModel.dismissAiConfigurationPrompt()
        assertFalse(viewModel.aiConfigurationPrompt.value)
    }

    @Test
    fun explainSelectedSentence_configurationErrorRoutesToPromptNotSheet() = runTest {
        coEvery { articleRepository.getArticleById(51) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(51, "t", "First.")
        viewModel.loadArticle(51)
        advanceUntilIdle()
        viewModel.selectSentence(51, 0, SentenceRange(0, "First.", 0, 6))
        runCurrent()
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } returns
            AiExplanationStartResult.Rejected(AiError.CredentialMissing)

        viewModel.explainSelectedSentence()
        advanceUntilIdle()

        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        assertTrue(viewModel.aiConfigurationPrompt.value)
        assertNull(viewModel.selectedSentence.value)
    }

    @Test
    fun explainArticle_passesCapturedArticleContent() = runTest {
        coEvery { articleRepository.getArticleById(10) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(10, "t", "Article body")
        viewModel.loadArticle(10)
        advanceUntilIdle()
        coEvery { aiExplanationRepository.start(AiExplanationInput.Article("Article body")) } returns
            AiExplanationStartResult.Rejected(AiError.NoActiveProfile)

        viewModel.explainArticle()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            aiExplanationRepository.start(AiExplanationInput.Article("Article body"))
        }
    }

    @Test
    fun explainSelectedSentence_pendingRepository_publishesLoadingWithCapturedTargetImmediately() =
        runTest {
            coEvery { articleRepository.getArticleById(11) } returns
                io.github.zoot.englishreader.data.entity.ArticleEntity(11, "t", "First.")
            viewModel.loadArticle(11)
            advanceUntilIdle()
            viewModel.selectSentence(11, 0, SentenceRange(0, "First.", 0, 6))
            val snapshot = requireNotNull(viewModel.selectedSentence.value)
            val result = CompletableDeferred<AiExplanationStartResult>()
            coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } coAnswers {
                result.await()
            }

            viewModel.explainSelectedSentence()

            val loading = viewModel.aiSheetState.value as AiSheetState.Loading
            assertEquals(AiExplanationTarget.Sentence(snapshot), loading.target)

            result.complete(AiExplanationStartResult.Rejected(AiError.Offline))
            advanceUntilIdle()
            assertEquals(
                AiSheetState.Rejected(
                    error = AiError.Offline,
                    target = AiExplanationTarget.Sentence(snapshot)
                ),
                viewModel.aiSheetState.value
            )
        }

    @Test
    fun explainSelectedSentence_startedOperation_keepsCapturedSnapshotTarget() = runTest {
        coEvery { articleRepository.getArticleById(12) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(12, "t", "First. Second.")
        viewModel.loadArticle(12)
        advanceUntilIdle()
        viewModel.selectSentence(12, 0, SentenceRange(0, "First. ", 0, 7))
        val captured = requireNotNull(viewModel.selectedSentence.value)
        val outcome = CompletableDeferred<AiOperationOutcome>()
        val handle = AiOperationHandle(AiOperationRef("semantic-key", "operation-id"), outcome)
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } returns
            AiExplanationStartResult.Started(handle)

        viewModel.explainSelectedSentence()
        runCurrent()
        val visible = viewModel.aiSheetState.value as AiSheetState.Visible
        assertEquals(AiExplanationTarget.Sentence(captured), visible.target)
        viewModel.selectSentence(12, 1, SentenceRange(1, "Second.", 7, 14))
        runCurrent()

        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        assertEquals("First. ", captured.rawText)
        assertEquals("Second.", viewModel.selectedSentence.value?.rawText)
        assertFalse(outcome.isCancelled)

        outcome.complete(AiOperationOutcome.Success("done"))
        advanceUntilIdle()
        assertEquals(AiOperationOutcome.Success("done"), handle.awaitOutcome())
        assertEquals(AiExplanationTarget.Sentence(captured), visible.target)
        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        coVerify(exactly = 0) { aiOperationRegistry.cancel(any()) }
    }

    @Test
    fun selectSentence_sameSnapshot_retainsPreparingAttachedAndCompletedResults() = runTest {
        coEvery { articleRepository.getArticleById(60) } returns ArticleEntity(60, "t", "First.")
        viewModel.loadArticle(60)
        advanceUntilIdle()
        val range = SentenceRange(0, "First.", 0, 6)
        viewModel.selectSentence(60, 0, range)

        val explanationPreparation = CompletableDeferred<AiExplanationStartResult>()
        val translationPreparation = CompletableDeferred<AiExplanationStartResult>()
        val explanationOutcome = CompletableDeferred<AiOperationOutcome>()
        val translationOutcome = CompletableDeferred<AiOperationOutcome>()
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } coAnswers {
            explanationPreparation.await()
        }
        coEvery {
            aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First."))
        } coAnswers { translationPreparation.await() }
        viewModel.explainSelectedSentence()
        viewModel.translateSelectedSentence()
        runCurrent()

        fun repeatSelectionAndCheckUnchanged() {
            val explanation = viewModel.aiSheetState.value
            val translation = viewModel.sentenceTranslationState.value
            viewModel.selectSentence(60, 0, range.copy())
            runCurrent()
            assertSame(explanation, viewModel.aiSheetState.value)
            assertSame(translation, viewModel.sentenceTranslationState.value)
        }

        assertTrue(viewModel.aiSheetState.value is AiSheetState.Loading)
        assertTrue(viewModel.sentenceTranslationState.value is AiSheetState.Loading)
        repeatSelectionAndCheckUnchanged()
        explanationPreparation.complete(
            AiExplanationStartResult.Started(
                AiOperationHandle(AiOperationRef("explanation-key", "explanation-op"), explanationOutcome)
            )
        )
        translationPreparation.complete(
            AiExplanationStartResult.Started(
                AiOperationHandle(AiOperationRef("translation-key", "translation-op"), translationOutcome)
            )
        )
        runCurrent()

        assertNull((viewModel.aiSheetState.value as AiSheetState.Visible).outcome)
        assertNull((viewModel.sentenceTranslationState.value as AiSheetState.Visible).outcome)
        repeatSelectionAndCheckUnchanged()
        explanationOutcome.complete(AiOperationOutcome.Success("explanation"))
        translationOutcome.complete(AiOperationOutcome.Success("translation"))
        runCurrent()
        assertEquals(
            AiOperationOutcome.Success("explanation"),
            (viewModel.aiSheetState.value as AiSheetState.Visible).outcome
        )
        assertEquals(
            AiOperationOutcome.Success("translation"),
            (viewModel.sentenceTranslationState.value as AiSheetState.Visible).outcome
        )
        repeatSelectionAndCheckUnchanged()
        coVerify(exactly = 2) { aiExplanationRepository.start(any()) }
    }

    @Test
    fun dismissSentencePopup_whilePreparing_cancelsBothPreparationsWithoutCancellingOperations() = runTest {
        coEvery { articleRepository.getArticleById(61) } returns ArticleEntity(61, "t", "First.")
        viewModel.loadArticle(61)
        advanceUntilIdle()
        viewModel.selectSentence(61, 0, SentenceRange(0, "First.", 0, 6))
        var cancelledPreparations = 0
        coEvery { aiExplanationRepository.start(any()) } coAnswers {
            try {
                awaitCancellation()
            } catch (cancellation: CancellationException) {
                cancelledPreparations++
                throw cancellation
            }
        }
        viewModel.explainSelectedSentence()
        viewModel.translateSelectedSentence()
        runCurrent()
        assertTrue(viewModel.aiSheetState.value is AiSheetState.Loading)
        assertTrue(viewModel.sentenceTranslationState.value is AiSheetState.Loading)

        viewModel.dismissSentencePopup()
        runCurrent()

        assertEquals(2, cancelledPreparations)
        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        assertSame(AiSheetState.Hidden, viewModel.sentenceTranslationState.value)
        assertNotNull(viewModel.selectedSentence.value)
        coVerify(exactly = 0) { aiOperationRegistry.cancel(any()) }
    }

    @Test
    fun dismissSentencePopup_registeredExplanation_detachesObserverAndKeepsPaidOperation() =
        assertExplanationDismissal { viewModel.dismissSentencePopup() }

    @Test
    fun clearSelection_registeredExplanation_detachesObserverAndKeepsPaidOperation() =
        assertExplanationDismissal { viewModel.clearSelection() }

    @Test
    fun beginWordSelection_registeredExplanation_detachesObserverAndKeepsPaidOperation() =
        assertExplanationDismissal { viewModel.beginWordSelection("First") }

    private fun assertExplanationDismissal(dismiss: () -> Unit) = runTest {
        coEvery { articleRepository.getArticleById(62) } returns ArticleEntity(62, "t", "First.")
        viewModel.loadArticle(62)
        advanceUntilIdle()
        viewModel.selectSentence(62, 0, SentenceRange(0, "First.", 0, 6))
        val result = CompletableDeferred<AiOperationOutcome>()
        val handle = AiOperationHandle(AiOperationRef("explanation-key", "explanation-op"), result)
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } returns
            AiExplanationStartResult.Started(handle)
        viewModel.explainSelectedSentence()
        runCurrent()
        assertTrue(viewModel.aiSheetState.value is AiSheetState.Visible)

        dismiss()
        runCurrent()
        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        assertFalse(result.isCancelled)
        result.complete(AiOperationOutcome.Success("finished after dismissal"))
        runCurrent()

        assertEquals(AiOperationOutcome.Success("finished after dismissal"), handle.awaitOutcome())
        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        coVerify(exactly = 0) { aiOperationRegistry.cancel(any()) }
    }

    @Test
    fun explainSelectedSentence_oldResultWhileNewerPrepares_keepsNewerLoading() =
        assertLateSentenceResultIgnored(translation = false, completeNewerFirst = false)

    @Test
    fun explainSelectedSentence_oldResultAfterNewerCompletes_keepsNewerResult() =
        assertLateSentenceResultIgnored(translation = false, completeNewerFirst = true)

    @Test
    fun translateSelectedSentence_oldResultWhileNewerPrepares_keepsNewerLoading() =
        assertLateSentenceResultIgnored(translation = true, completeNewerFirst = false)

    @Test
    fun translateSelectedSentence_oldResultAfterNewerCompletes_keepsNewerResult() =
        assertLateSentenceResultIgnored(translation = true, completeNewerFirst = true)

    private fun assertLateSentenceResultIgnored(
        translation: Boolean,
        completeNewerFirst: Boolean
    ) = runTest {
        val state = if (translation) viewModel.sentenceTranslationState else viewModel.aiSheetState
        fun input(text: String): AiExplanationInput = if (translation) {
            AiExplanationInput.SentenceTranslation(text)
        } else {
            AiExplanationInput.Sentence(text)
        }
        fun start() {
            if (translation) viewModel.translateSelectedSentence()
            else viewModel.explainSelectedSentence()
        }
        coEvery { articleRepository.getArticleById(63) } returns ArticleEntity(63, "t", "First. Second.")
        viewModel.loadArticle(63)
        advanceUntilIdle()
        viewModel.selectSentence(63, 0, SentenceRange(0, "First.", 0, 6))
        val captured = requireNotNull(viewModel.selectedSentence.value)
        val olderOutcome = CompletableDeferred<AiOperationOutcome>()
        val olderHandle = AiOperationHandle(AiOperationRef("older-key", "older-op"), olderOutcome)
        val newerPreparation = CompletableDeferred<AiExplanationStartResult>()
        val newerOutcome = CompletableDeferred<AiOperationOutcome>()
        val newerHandle = AiOperationHandle(AiOperationRef("newer-key", "newer-op"), newerOutcome)
        coEvery { aiExplanationRepository.start(input("First.")) } returns
            AiExplanationStartResult.Started(olderHandle)
        coEvery { aiExplanationRepository.start(input("Second.")) } coAnswers {
            newerPreparation.await()
        }
        start()
        runCurrent()
        assertEquals(
            AiExplanationTarget.Sentence(captured),
            (state.value as AiSheetState.Visible).target
        )

        viewModel.dismissSentencePopup()
        runCurrent()
        assertSame(AiSheetState.Hidden, state.value)
        viewModel.selectSentence(63, 1, SentenceRange(1, "Second.", 7, 14))
        val newerTarget = AiExplanationTarget.Sentence(requireNotNull(viewModel.selectedSentence.value))
        start()
        runCurrent()
        assertEquals(newerTarget, (state.value as AiSheetState.Loading).target)

        if (completeNewerFirst) {
            newerPreparation.complete(AiExplanationStartResult.Started(newerHandle))
            runCurrent()
            assertNull((state.value as AiSheetState.Visible).outcome)
            newerOutcome.complete(AiOperationOutcome.Success("newer result"))
            runCurrent()
        }
        val newerState = state.value
        olderOutcome.complete(AiOperationOutcome.Success("older result"))
        runCurrent()

        assertSame(newerState, state.value)
        assertEquals("First.", captured.rawText)
        assertEquals(AiOperationOutcome.Success("older result"), olderHandle.awaitOutcome())
        if (!completeNewerFirst) {
            newerPreparation.complete(AiExplanationStartResult.Started(newerHandle))
            runCurrent()
            assertEquals(newerTarget, (state.value as AiSheetState.Visible).target)
            assertNull((state.value as AiSheetState.Visible).outcome)
            newerOutcome.complete(AiOperationOutcome.Success("newer result"))
            runCurrent()
        }
        assertEquals(newerTarget, (state.value as AiSheetState.Visible).target)
        assertEquals(AiOperationOutcome.Success("newer result"), (state.value as AiSheetState.Visible).outcome)
        coVerify(exactly = 1) { aiExplanationRepository.start(input("First.")) }
        coVerify(exactly = 1) { aiExplanationRepository.start(input("Second.")) }
        coVerify(exactly = 0) { aiOperationRegistry.cancel(any()) }
    }

    @Test
    fun cancelAiOperation_visibleExplanation_cancelsOnlyItsRefAndPreservesTranslation() = runTest {
        coEvery { articleRepository.getArticleById(64) } returns ArticleEntity(64, "t", "First.")
        viewModel.loadArticle(64)
        advanceUntilIdle()
        viewModel.selectSentence(64, 0, SentenceRange(0, "First.", 0, 6))
        val explanationRef = AiOperationRef("explanation-key", "explanation-op")
        val explanationOutcome = CompletableDeferred<AiOperationOutcome>()
        val translationOutcome = CompletableDeferred<AiOperationOutcome>()
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } returns
            AiExplanationStartResult.Started(AiOperationHandle(explanationRef, explanationOutcome))
        coEvery {
            aiExplanationRepository.start(AiExplanationInput.SentenceTranslation("First."))
        } returns AiExplanationStartResult.Started(
            AiOperationHandle(AiOperationRef("translation-key", "translation-op"), translationOutcome)
        )
        coEvery { aiOperationRegistry.cancel(explanationRef) } returns true
        viewModel.explainSelectedSentence()
        viewModel.translateSelectedSentence()
        runCurrent()
        val translationState = viewModel.sentenceTranslationState.value

        viewModel.cancelAiOperation(explanationRef)
        runCurrent()

        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
        assertSame(translationState, viewModel.sentenceTranslationState.value)
        coVerify(exactly = 1) { aiOperationRegistry.cancel(explanationRef) }
        coVerify(exactly = 1) { aiOperationRegistry.cancel(any()) }
        translationOutcome.complete(AiOperationOutcome.Success("translation remains available"))
        runCurrent()
        assertEquals(
            AiOperationOutcome.Success("translation remains available"),
            (viewModel.sentenceTranslationState.value as AiSheetState.Visible).outcome
        )
    }

    @Test
    fun explain_newerIntent_cancelsOlderPreparationAndOwnsSheetState() = runTest {
        coEvery { articleRepository.getArticleById(13) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(13, "t", "First.")
        viewModel.loadArticle(13)
        advanceUntilIdle()
        viewModel.selectSentence(13, 0, SentenceRange(0, "First.", 0, 6))
        val olderCancelled = CompletableDeferred<Unit>()
        coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } coAnswers {
            try {
                CompletableDeferred<AiExplanationStartResult>().await()
            } catch (cancellation: CancellationException) {
                olderCancelled.complete(Unit)
                throw cancellation
            }
        }
        coEvery { aiExplanationRepository.start(AiExplanationInput.Article("First.")) } returns
            AiExplanationStartResult.Rejected(AiError.Offline)

        viewModel.explainSelectedSentence()
        viewModel.explainArticle()
        advanceUntilIdle()

        assertTrue(olderCancelled.isCompleted)
        assertEquals(
            AiSheetState.Rejected(
                error = AiError.Offline,
                target = AiExplanationTarget.Article(13)
            ),
            viewModel.aiSheetState.value
        )
    }

    @Test
    fun dismissAiSheet_whilePreparing_cancelsPreparationWithoutOperationReference() = runTest {
        coEvery { articleRepository.getArticleById(14) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(14, "t", "Body")
        viewModel.loadArticle(14)
        advanceUntilIdle()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { aiExplanationRepository.start(AiExplanationInput.Article("Body")) } coAnswers {
            try {
                CompletableDeferred<AiExplanationStartResult>().await()
            } catch (cancellation: CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
        }

        viewModel.explainArticle()
        assertTrue(viewModel.aiSheetState.value is AiSheetState.Loading)
        viewModel.dismissAiSheet()
        advanceUntilIdle()

        assertTrue(cancelled.isCompleted)
        assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
    }

    @Test
    fun loadArticle_overlappingNonCooperativeLoads_onlyLatestPublishesAndClearsLoading() =
        runTest {
            val older = CompletableDeferred<io.github.zoot.englishreader.data.entity.ArticleEntity?>()
            val latest = CompletableDeferred<io.github.zoot.englishreader.data.entity.ArticleEntity?>()
            coEvery { articleRepository.getArticleById(20) } coAnswers {
                withContext(NonCancellable) { older.await() }
            }
            coEvery { articleRepository.getArticleById(21) } coAnswers {
                withContext(NonCancellable) { latest.await() }
            }

            viewModel.loadArticle(20)
            viewModel.loadArticle(21)
            older.complete(io.github.zoot.englishreader.data.entity.ArticleEntity(20, "old", "old"))
            runCurrent()

            assertNull(viewModel.article.value)
            assertTrue(viewModel.isLoadingArticle.value)

            latest.complete(io.github.zoot.englishreader.data.entity.ArticleEntity(21, "new", "new"))
            advanceUntilIdle()
            assertEquals(21L, viewModel.article.value?.id)
            assertFalse(viewModel.isLoadingArticle.value)
        }

    @Test
    fun loadArticle_afterOperationAttach_detachesOldTargetWithoutCancellingPaidOperation() =
        runTest {
            coEvery { articleRepository.getArticleById(30) } returns
                io.github.zoot.englishreader.data.entity.ArticleEntity(30, "old", "First.")
            val nextArticle = CompletableDeferred<io.github.zoot.englishreader.data.entity.ArticleEntity?>()
            coEvery { articleRepository.getArticleById(31) } coAnswers { nextArticle.await() }
            viewModel.loadArticle(30)
            advanceUntilIdle()
            viewModel.selectSentence(30, 0, SentenceRange(0, "First.", 0, 6))

            val outcome = CompletableDeferred<AiOperationOutcome>()
            val handle = AiOperationHandle(AiOperationRef("old-key", "old-operation"), outcome)
            coEvery { aiExplanationRepository.start(AiExplanationInput.Sentence("First.")) } returns
                AiExplanationStartResult.Started(handle)
            viewModel.explainSelectedSentence()
            runCurrent()
            assertTrue(viewModel.aiSheetState.value is AiSheetState.Visible)

            viewModel.loadArticle(31)
            runCurrent()
            viewModel.explainArticle()
            coVerify(exactly = 0) { aiExplanationRepository.start(AiExplanationInput.Article("First.")) }

            assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
            assertNull(viewModel.selectedSentence.value)
            assertEquals(30L, viewModel.article.value?.id)
            assertTrue(viewModel.isLoadingArticle.value)
            assertFalse(outcome.isCancelled)

            outcome.complete(AiOperationOutcome.Success("old result"))
            nextArticle.complete(
                io.github.zoot.englishreader.data.entity.ArticleEntity(31, "new", "Second.")
            )
            advanceUntilIdle()

            assertSame(AiSheetState.Hidden, viewModel.aiSheetState.value)
            assertEquals(31L, viewModel.article.value?.id)
            assertFalse(viewModel.isLoadingArticle.value)
        }

    @Test
    fun explain_withoutSelectionOrArticle_doesNotCallRepository() = runTest {
        viewModel.explainSelectedSentence()
        viewModel.explainArticle()
        advanceUntilIdle()

        coVerify(exactly = 0) { aiExplanationRepository.start(any()) }
    }

    /** 便捷构造离线命中结果（直接命中，无词形还原）。 */
    private fun offlineHit(
        word: String,
        phonetic: String? = null,
        chinese: String,
        english: String? = null,
        inflectedForm: String? = null
    ) = OfflineLookupResult(
        entry = DictionaryEntry(word = word, phonetic = phonetic, chinese = chinese, english = english),
        inflectedForm = inflectedForm
    )

    @Test
    fun lookupWord_offlineHit_usesOfflineDefinitionAndSplitsChinese() = runTest {
        coEvery { dictionaryRepository.lookupOffline("success") } returns
            offlineHit(
                word = "success",
                phonetic = "/səkˈses/",
                chinese = "n. 成功；成就；胜利",
                english = "achievement of aim"
            )

        viewModel.lookupWord("  Success  ")

        val def = viewModel.wordDefinition.value
        assertEquals("success", def?.word)
        assertEquals(DefinitionSource.OFFLINE, def?.source)
        assertEquals(listOf("n. 成功", "成就", "胜利"), def?.chineseDefinitions)
        assertEquals(listOf("achievement of aim"), def?.englishDefinitions)
        // 离线命中的音频改由有道 dictvoice 接口补上（有网时自动播真人音）
        assertEquals("https://dict.youdao.com/dictvoice?audio=success&type=2", def?.audioUrl)
        assertNull(def?.inflectedForm) // 直接命中，无还原标注
        assertFalse(viewModel.isLoadingDefinition.value) // loading 已结束
    }

    @Test
    fun lookupWord_inflectedHit_showsBaseFormWithInflectedNote() = runTest {
        // 用户长按 "lives"，词形还原命中原形 "live"
        coEvery { dictionaryRepository.lookupOffline("lives") } returns
            offlineHit(
                word = "live",
                phonetic = "/lɪv/",
                chinese = "v. 生活；居住",
                english = "to be alive",
                inflectedForm = "lives"
            )

        viewModel.lookupWord("lives")

        val def = viewModel.wordDefinition.value
        assertEquals("live", def?.word) // 显示原形
        assertEquals("lives", def?.inflectedForm) // 标注来自哪个变形词
        assertEquals(DefinitionSource.OFFLINE, def?.source)
        // 发音 URL 基于用户长按的原词 lives，而非还原后的 live
        assertEquals("https://dict.youdao.com/dictvoice?audio=lives&type=2", def?.audioUrl)
    }

    @Test
    fun lookupWord_offlineHit_online_autoPlaysYoudaoAudio() = runTest {
        coEvery { dictionaryRepository.lookupOffline("success") } returns
            offlineHit(word = "success", chinese = "n. 成功", english = "achievement of aim")

        viewModel.lookupWord("success")

        // 有网 + 离线命中：自动播有道真人音，恰好一次
        coVerify(exactly = 1) {
            audioPlayer.play(
                url = "https://dict.youdao.com/dictvoice?audio=success&type=2",
                onComplete = any(),
                onError = any()
            )
        }
    }

    @Test
    fun lookupWord_offlineHit_offline_autoPlayStaysSilent() = runTest {
        // 无网：自动播放（silent=true）应完全静默——不播真人音、不走 TTS
        every { networkChecker.isOnline() } returns false
        coEvery { dictionaryRepository.lookupOffline("success") } returns
            offlineHit(word = "success", chinese = "n. 成功", english = "achievement of aim")

        viewModel.lookupWord("success")

        coVerify(exactly = 0) { audioPlayer.play(any(), any(), any()) }
        verify(exactly = 0) { ttsPlayer.speakWord(any(), any(), any(), any()) }
    }

    /**
     * 离线 + 缓存命中 → 必须播本地文件，不能退到 TTS。
     *
     * 这条是审计 F4 的判据。原来的实现在查缓存**之前**就因离线返回，于是已经缓存过的词
     * 在地铁里也用不上 —— 而「离线可用」正是这个缓存存在的理由。
     *
     * 既有的 `lookupWord_offlineHit_offline_autoPlayStaysSilent` 覆盖不到：它默认缓存未命中，
     * 所以离线静默是对的；缺的是「离线但**有**缓存」这一格。
     */
    @Test
    fun playWordAudio_offlineWithCacheHit_playsLocalFileNotTts() = runTest {
        every { networkChecker.isOnline() } returns false
        val local = java.io.File("cache/pron.mp3")
        coEvery { pronunciationAudioCache.get("lives") } returns local

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2", silent = false)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            audioPlayer.play(url = local.absolutePath, onComplete = any(), onError = any())
        }
        verify(exactly = 0) { ttsPlayer.speakWord(any(), any(), any(), any()) }
    }

    /**
     * 离线 + 缓存未命中 → 保持原有行为：手动点击走 TTS，且不发网络请求。
     *
     * 与上一条成对。F4 的修复把网络检查挪到了缓存查询之后，这条确认那个挪动没有把
     * 「无网时不空等」这个收益弄丢。
     */
    @Test
    fun playWordAudio_offlineCacheMiss_stillFallsBackToTtsWithoutNetwork() = runTest {
        every { networkChecker.isOnline() } returns false
        coEvery { pronunciationAudioCache.get("lives") } returns null

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2", silent = false)
        advanceUntilIdle()

        verify(exactly = 1) { ttsPlayer.speakWord(eq("lives"), any(), any(), any()) }
        coVerify(exactly = 0) { audioPlayer.play(any(), any(), any()) }
        coVerify(exactly = 0) { pronunciationAudioCache.download(any(), any()) }
    }

    /**
     * 缓存命中时必须播**本地文件**，而不是再走一趟有道。
     *
     * 这是整个缓存功能存在的理由：用户报的「语音慢一截」就是那趟网络往返。若这条红了，
     * 缓存层写得再对也没有被用上。
     */
    @Test
    fun playWordAudio_cacheHit_playsLocalFileInsteadOfRemote() = runTest {
        val local = java.io.File("cache/pron.mp3")
        coEvery { pronunciationAudioCache.get("lives") } returns local

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2")

        coVerify(exactly = 1) {
            audioPlayer.play(url = local.absolutePath, onComplete = any(), onError = any())
        }
        coVerify(exactly = 0) {
            audioPlayer.play(url = match<String> { it.startsWith("https://") }, onComplete = any(), onError = any())
        }
    }

    /**
     * 缓存命中时转圈**从未**置起 —— 不是「最后是 false」。
     *
     * ## 为什么必须用 Turbine 采样
     *
     * 我原来在协程跑完后断言 `isLoadingAudio.value == false`，而成功路径无条件把它清成 false。
     * 复审指出这是空转的，我实测确认了：把命中分支也改成置起转圈（去掉 `if (cached == null)`
     * 这个条件），那条测试**照绿**。
     *
     * 它分不清两种状态历史：
     *
     * ```text
     * 全程 false                （正确）
     * false → true → false      （闪一下，正是要防的）
     * ```
     *
     * 所以断言必须落在**流上**而不是终值上。这里让 `play` 挂住，命中路径若置起转圈就会停在
     * true 上被 `expectNoEvents()` 抓到。
     */
    @Test
    fun playWordAudio_cacheHit_neverRaisesTheSpinnerAtAll() = runTest {
        val local = java.io.File("cache/pron.mp3")
        val playStarted = CompletableDeferred<Unit>()
        val releasePlay = CompletableDeferred<Unit>()
        coEvery { pronunciationAudioCache.get("lives") } returns local
        coEvery { audioPlayer.play(any(), any(), any()) } coAnswers {
            playStarted.complete(Unit)
            releasePlay.await()
        }

        viewModel.isLoadingAudio.test {
            assertFalse("baseline", awaitItem())

            viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2")
            playStarted.await()

            // 播放已开始且仍在进行中。命中路径若置起过转圈，此刻流上会有一个 true。
            expectNoEvents()

            releasePlay.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }

        // 确认走的确实是命中路径 —— 否则上面的断言可能因为根本没播而空过。
        coVerify(exactly = 1) {
            audioPlayer.play(url = local.absolutePath, onComplete = any(), onError = any())
        }
    }

    /**
     * 播放**启动后**转圈必须停，不能等音频播完。
     *
     * `onComplete` 挂的是 `MediaPlayer.setOnCompletionListener`，那是音频播完才触发。
     * 若在那里置 false，转圈时长会变成「网络等待 + 整段音频播放」—— 声音已经响了还在转。
     * 实测确认过初版正是这样：`play` 返回后 `isLoadingAudio` 仍为 true。
     *
     * 挂起 `play` 以观察加载态，再让它返回；两个回调都不触发
     * （`play` 在 `start()` 之后返回，`onComplete` 要等播完）。
     */
    @Test
    fun playWordAudio_clearsLoadingOncePlaybackStarts() = runTest {
        val releasePlay = CompletableDeferred<Unit>()
        coEvery { pronunciationAudioCache.get("success") } returns null
        coEvery { audioPlayer.play(any(), any(), any()) } coAnswers { releasePlay.await() }

        try {
            viewModel.playWordAudio("success", "https://dict.youdao.com/dictvoice?audio=success&type=2")
            runCurrent()
            assertTrue(viewModel.isLoadingAudio.value)
        } finally {
            releasePlay.complete(Unit)
        }
        advanceUntilIdle()

        assertFalse(
            "spinner must stop once playback has started; waiting for onComplete would keep it " +
                "spinning for the whole duration of the audio",
            viewModel.isLoadingAudio.value
        )
    }

    /**
     * A 晚到时不得关掉 B 的转圈，且**只有 B 到达 player**。
     *
     * ## 这条被重写过两次，第二次是因为它空转
     *
     * 最初它叫 `previousCompletionDoesNotClearNewRequestSpinner`，却只发起一个请求 —— 外部
     * 审计指出「保护新请求」的命题从未被检验。我改成创建 A、B 两个请求，但**仍然是空转的**：
     * 复审发现 `callbacks` 列表写了从不读，而且整个测试**没有一个 `isLoadingAudio` 断言**
     * —— KDoc 却写着「断言 B 的加载态仍为 true」。我把断言丢了，留下了描述它的文字。
     *
     * ## 为什么必须让 `play` 也挂起
     *
     * `MainDispatcherRule` 用 `UnconfinedTestDispatcher`，所以放行 B 的缓存查询后它会**内联
     * 跑到底**：置 true → 调 play → 清 false，全在测试的下一行之前完成。而 `StateFlow` 合并
     * 中间值，那个 true 到不了 Turbine。实测探针确认：`gateB.complete()` 之后
     * `isLoadingAudio.value` 已经是 false。
     *
     * 所以把 `play` 也做成挂起的 —— B 停在「转圈已置起、播放未完成」的状态，那一格才可观察。
     */
    @Test
    fun playWordAudio_staleRequestNeitherPlaysNorClearsTheNewRequestSpinner() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val playStarted = CompletableDeferred<Unit>()
        val releasePlay = CompletableDeferred<Unit>()

        coEvery { pronunciationAudioCache.get("first") } coAnswers {
            gateA.await()
            null
        }
        coEvery { pronunciationAudioCache.get("second") } returns null
        // play 挂住：B 会停在「转圈已置起」的状态，中间态因此可观察。
        coEvery { audioPlayer.play(any(), any(), any()) } coAnswers {
            playStarted.complete(Unit)
            releasePlay.await()
        }

        viewModel.isLoadingAudio.test {
            assertFalse("baseline", awaitItem())

            // A 先发起并挂在缓存查询上；B 随后发起，未命中缓存后停在 play 里。
            viewModel.playWordAudio("first", "https://dict.youdao.com/dictvoice?audio=first&type=2")
            viewModel.playWordAudio("second", "https://dict.youdao.com/dictvoice?audio=second&type=2")

            playStarted.await()
            assertTrue("B must raise the spinner while it prepares", awaitItem())

            // 放行早已作废的 A：它既不该播放，也不该把 B 的转圈清掉。
            gateA.complete(Unit)
            advanceUntilIdle()
            expectNoEvents()

            releasePlay.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }

        // 只有 B 到达 player —— A 播放会让用户听到自己已经放弃的那个词。
        val playedUrls = mutableListOf<String>()
        coVerify(exactly = 1) { audioPlayer.play(capture(playedUrls), any(), any()) }
        assertTrue(
            "only the newest request may reach the player; played=$playedUrls",
            playedUrls.single().contains("second")
        )
    }

    /**
     * A 的**播放器回调**晚到时不得清掉 B 的转圈。
     *
     * ## 这条补的是上一条测不到的那一格
     *
     * 相邻的 `staleRequestNeitherPlaysNorClearsTheNewRequestSpinner` 里，A 被代次挡在 `play`
     * **之前** —— 所以 A 根本没有 `onError`/`onComplete` 回调存在，那条测试不可能触发它们。
     * 外部审计指出：原命题「旧播放器回调不得清掉新请求的转圈」因此仍未被检验，而
     * `onError` 里的代次守卫无判据。实测确认：删掉那个守卫，整类测试全绿。
     *
     * 要验到它，A 必须**真的到达 player 并把回调交出来**，然后才让 B 起来：
     *
     * 1. A 未命中缓存 → 置起转圈 → 调 `play`，回调被捕获，`play` 立刻返回
     * 2. A 走完，按自己的代次把转圈清掉（这一步是对的）
     * 3. B 发起 → 置起转圈 → 停在挂起的 `play` 里
     * 4. 此时触发**A 的** `onError` —— 真实场景是有道限流/超时在几百毫秒后才回来
     *
     * 没有守卫的话第 4 步会把 B 的转圈关掉：用户看到转圈消失、以为在放了，而 B 还在准备。
     */
    @Test
    fun playWordAudio_staleOnErrorDoesNotClearTheNewRequestSpinner() = runTest {
        val staleOnError = CompletableDeferred<(Exception) -> Unit>()
        val secondPlayStarted = CompletableDeferred<Unit>()
        val releaseSecondPlay = CompletableDeferred<Unit>()

        coEvery { pronunciationAudioCache.get(any()) } returns null
        coEvery { audioPlayer.play(any(), any(), any()) } coAnswers {
            val url = firstArg<String>()
            if (url.contains("first")) {
                // A：把 onError 交出来后立刻返回，模拟「播放已启动、失败稍后才回来」。
                staleOnError.complete(thirdArg())
            } else {
                // B：停在准备中，转圈保持置起，这样 A 的晚到回调有东西可破坏。
                secondPlayStarted.complete(Unit)
                releaseSecondPlay.await()
            }
        }

        viewModel.playWordAudio("first", "https://dict.youdao.com/dictvoice?audio=first&type=2")
        advanceUntilIdle()
        val aOnError = staleOnError.await()

        viewModel.isLoadingAudio.test {
            assertFalse("A already finished, so the spinner is down", awaitItem())

            viewModel.playWordAudio("second", "https://dict.youdao.com/dictvoice?audio=second&type=2")
            secondPlayStarted.await()
            assertTrue("B must raise the spinner while it prepares", awaitItem())

            // A 的失败此刻才回来（有道限流/超时）。它属于一个用户已经放弃的词。
            aOnError(java.io.IOException("youdao rate limited"))
            advanceUntilIdle()

            expectNoEvents()

            releaseSecondPlay.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun playWordAudio_staleOnComplete_doesNotClearNewRequestSpinner() = runTest {
        val firstUrl = "https://dict.youdao.com/dictvoice?audio=first&type=2"
        val secondUrl = "https://dict.youdao.com/dictvoice?audio=second&type=2"
        val staleOnComplete = CompletableDeferred<() -> Unit>()
        val secondPreparing = CompletableDeferred<Unit>()
        val finishSecondPreparation = CompletableDeferred<Unit>()

        coEvery { audioPlayer.play(firstUrl, any(), any()) } coAnswers {
            staleOnComplete.complete(secondArg())
        }
        coEvery { audioPlayer.play(secondUrl, any(), any()) } coAnswers {
            secondPreparing.complete(Unit)
            finishSecondPreparation.await()
        }

        viewModel.playWordAudio("first", firstUrl)
        advanceUntilIdle()
        val completeFirst = staleOnComplete.await()

        viewModel.isLoadingAudio.test {
            try {
                assertFalse("A has started playback", awaitItem())

                viewModel.playWordAudio("second", secondUrl)
                secondPreparing.await()
                assertTrue("B must raise loading while preparing", awaitItem())
                assertTrue(viewModel.isLoadingAudio.value)

                completeFirst()
                advanceUntilIdle()

                expectNoEvents()
                assertTrue("A's completion must leave B's loading state intact", viewModel.isLoadingAudio.value)

                finishSecondPreparation.complete(Unit)
                advanceUntilIdle()

                assertFalse("B clears loading when its preparation returns", awaitItem())
                assertFalse(viewModel.isLoadingAudio.value)
                expectNoEvents()
            } finally {
                finishSecondPreparation.complete(Unit)
            }
        }

        coVerify(exactly = 1) { audioPlayer.play(firstUrl, any(), any()) }
        coVerify(exactly = 1) { audioPlayer.play(secondUrl, any(), any()) }
    }

    /**
     * 请求**已越过缓存查询**、还没调 `play()` 时被作废 → 不得播放。
     *
     * ## 这条推翻了我自己写在生产代码里的一句话
     *
     * 我在 `playWordAudio` 的代次检查旁写了「这一行没有测试判据…那个窗口在单测里无法确定性
     * 复现」。复审指出那句是错的，并给出了构造：让 `cache.get` 的桩**不挂起**，而是在返回前
     * 内联调 `stopAudio()`。
     *
     * 关键在于取消只在挂起点投递：协程此刻正在执行 `answers` 块，`cancel()` 只是把 Job 标记
     * 成已取消，不会立即中断它。于是协程带着**过期的代次**继续往下走到检查点 —— 那正是
     * `job.cancel()` 挡不住、只有代次能挡的那一格。
     *
     * 去掉代次检查这条会红（实测），所以那个守卫现在有判据了。
     */
    @Test
    fun playWordAudio_invalidatedAfterCacheLookupButBeforePlay_doesNotPlay() = runTest {
        coEvery { pronunciationAudioCache.get("lives") } answers {
            // 不挂起：协程正在执行中，此时作废它。cancel 只做标记，不中断当前执行。
            viewModel.stopAudio()
            null
        }

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2")
        advanceUntilIdle()

        coVerify(exactly = 0) {
            audioPlayer.play(any(), any(), any())
        }
        assertFalse("an invalidated request must not leave the spinner on", viewModel.isLoadingAudio.value)
    }

    /**
     * 关闭词义弹窗后，仍挂在缓存查询里的请求恢复时**不得**开始播放。
     *
     * 审计 F2 的核心场景：`playWordAudio` 起的协程原来不被任何字段持有，`stopAudio()`
     * 停的是 player 而不是这个请求。于是慢 I/O + 关闭弹窗的组合会让用户离开后突然听到声音。
     */
    @Test
    fun clearWordDefinition_preventsASuspendedRequestFromStartingPlayback() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { pronunciationAudioCache.get("lives") } coAnswers {
            gate.await()
            null
        }

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2")
        // 请求挂在缓存查询上时用户关掉弹窗。
        viewModel.clearWordDefinition()
        advanceUntilIdle()

        // 放行那个已经作废的查询。
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { audioPlayer.play(any(), any(), any()) }
        assertFalse("spinner must not survive a dismiss", viewModel.isLoadingAudio.value)
    }

    /**
     * 未命中时播远端**并且**后台下载入缓存。
     *
     * 两个断言成对：只验「播了远端」不足以说明缓存会被填充，而不填充的话第二次查词
     * 仍然慢 —— 那就等于这个功能没做。
     */
    @Test
    fun playWordAudio_cacheMiss_playsRemoteAndCachesInBackground() = runTest {
        coEvery { pronunciationAudioCache.get("success") } returns null
        val url = "https://dict.youdao.com/dictvoice?audio=success&type=2"

        viewModel.playWordAudio("success", url)
        advanceUntilIdle()

        coVerify(exactly = 1) { audioPlayer.play(url = url, onComplete = any(), onError = any()) }
        coVerify(exactly = 1) { pronunciationAudioCache.download("success", url) }
    }


    /**
     * 本地缓存播放失败 → 必须失效那个条目，让下次查词重走远端。
     *
     * 审计 F3：`download` 只校验 HTTP 成功与体积区间，不验证内容可播，所以 200 的错误页或
     * 损坏文件能成为「有效」缓存项。不失效的话那个词永久静默失败，而用户只会觉得
     * 「这个词没声音」，不会想到去清空整个缓存。
     */
    @Test
    fun playWordAudio_localFileFailsToPlay_invalidatesThatCacheEntry() = runTest {
        val local = java.io.File("cache/pron.mp3")
        coEvery { pronunciationAudioCache.get("lives") } returns local
        coEvery { audioPlayer.play(any(), any(), any()) } answers {
            thirdArg<(Exception) -> Unit>().invoke(java.io.IOException("unplayable"))
        }

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2")
        advanceUntilIdle()

        coVerify(exactly = 1) { pronunciationAudioCache.invalidate("lives") }
    }

    /**
     * 播**远端**失败不失效缓存 —— 那是网络问题，与缓存无关。
     *
     * 与上一条成对：只验「失败会失效」不足以说明失效是精准的，无条件失效会把
     * 有道限流这种暂时故障当成文件损坏，白白丢掉一个好缓存。
     */
    @Test
    fun playWordAudio_remoteFailure_doesNotInvalidateCache() = runTest {
        coEvery { pronunciationAudioCache.get("lives") } returns null
        coEvery { audioPlayer.play(any(), any(), any()) } answers {
            thirdArg<(Exception) -> Unit>().invoke(java.io.IOException("rate limited"))
        }

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2")
        advanceUntilIdle()

        coVerify(exactly = 0) { pronunciationAudioCache.invalidate(any()) }
    }

    /**
     * 缓存下载失败不得影响发音。
     *
     * 缓存是「顺手做的优化」，它的失败只意味着下次查这个词还得联网。若这条红了，
     * 说明一个磁盘满或有道限流会让发音整体不可用。
     */
    @Test
    fun playWordAudio_cacheDownloadFails_playbackStillHappens() = runTest {
        coEvery { pronunciationAudioCache.get("success") } returns null
        coEvery { pronunciationAudioCache.download(any(), any()) } throws java.io.IOException("disk full")
        val url = "https://dict.youdao.com/dictvoice?audio=success&type=2"

        viewModel.playWordAudio("success", url)
        advanceUntilIdle()

        coVerify(exactly = 1) { audioPlayer.play(url = url, onComplete = any(), onError = any()) }
    }

    /**
     * 播放失败时加载态必须结束，且仍降级 TTS。
     *
     * 不置回 false 的话转圈会一直转，用户以为还在加载而实际已经失败 —— 而这个状态
     * 只有用户关掉弹窗才会被重置。
     */
    @Test
    fun playWordAudio_playbackError_clearsLoadingAndFallsBackToTts() = runTest {
        coEvery { pronunciationAudioCache.get("lives") } returns null
        coEvery { audioPlayer.play(any(), any(), any()) } answers {
            thirdArg<(Exception) -> Unit>().invoke(java.io.IOException("boom"))
        }

        viewModel.playWordAudio("lives", "https://dict.youdao.com/dictvoice?audio=lives&type=2", silent = false)
        advanceUntilIdle()

        assertFalse("loading state must clear on error", viewModel.isLoadingAudio.value)
        verify(exactly = 1) { ttsPlayer.speakWord(eq("lives"), any(), any(), any()) }
    }

    @Test
    fun clearWordDefinition_stopsTtsNotOnlyMediaPlayer() = runTest {
        // 关闭 BottomSheet 时必须同时停 TTS：混合读音引入 TtsPlayer 后，若只停 audioPlayer，
        // 离线手动播放的机器音会在弹窗关闭后继续朗读。
        viewModel.clearWordDefinition()

        verify(exactly = 1) { ttsPlayer.stop() }
        verify(atLeast = 1) { audioPlayer.stop() }
    }

    @Test
    fun lookupWord_onlineHit_autoPlaysPronunciation() = runTest {
        coEvery { dictionaryRepository.lookupOffline("hello") } returns null
        coEvery { dictionaryRepository.lookupOnline("hello") } returns listOf(
            DictionaryResponse(
                word = "hello",
                phonetic = "/həˈloʊ/",
                phonetics = listOf(Phonetic(text = "/həˈloʊ/", audio = "//audio.mp3")),
                meanings = listOf(
                    Meaning(
                        partOfSpeech = "noun",
                        definitions = listOf(Definition(definition = "a greeting"))
                    )
                )
            )
        )

        viewModel.lookupWord("hello")

        // 在线命中且有音频时同样自动播放（协议相对 URL 已补全为 https）
        coVerify(exactly = 1) {
            audioPlayer.play(
                url = "https://audio.mp3",
                onComplete = any(),
                onError = any()
            )
        }
    }

    @Test
    fun lookupWord_offlineMiss_fallsBackToOnline() = runTest {
        coEvery { dictionaryRepository.lookupOffline("hello") } returns null
        coEvery { dictionaryRepository.lookupOnline("hello") } returns listOf(
            DictionaryResponse(
                word = "hello",
                phonetic = "/həˈloʊ/",
                phonetics = listOf(Phonetic(text = "/həˈloʊ/", audio = "//audio.mp3")),
                meanings = listOf(
                    Meaning(
                        partOfSpeech = "noun",
                        definitions = listOf(Definition(definition = "a greeting"))
                    )
                )
            )
        )

        viewModel.lookupWord("hello")

        val def = viewModel.wordDefinition.value
        assertEquals("hello", def?.word)
        assertEquals(DefinitionSource.ONLINE, def?.source)
        assertTrue(def?.chineseDefinitions?.isEmpty() == true)
        assertEquals(listOf("noun. a greeting"), def?.englishDefinitions)
        assertEquals("https://audio.mp3", def?.audioUrl) // 协议相对 URL 被补全
        assertFalse(viewModel.isLoadingDefinition.value)
    }

    @Test
    fun lookupWord_onlineEmpty_emitsWordNotFoundAndClearsSelection() = runTest {
        coEvery { dictionaryRepository.lookupOffline("xyzzy") } returns null
        coEvery { dictionaryRepository.lookupOnline("xyzzy") } returns emptyList()

        viewModel.definitionError.test {
            viewModel.lookupWord("xyzzy")
            assertEquals(DictionaryErrorType.WORD_NOT_FOUND, awaitItem())
        }
        assertNull(viewModel.selectedWord.value)
        assertFalse(viewModel.isLoadingDefinition.value)
    }

    @Test
    fun lookupWord_ioException_emitsNetworkError() = runTest {
        coEvery { dictionaryRepository.lookupOffline("net") } returns null
        coEvery { dictionaryRepository.lookupOnline("net") } throws IOException("timeout")

        viewModel.definitionError.test {
            viewModel.lookupWord("net")
            assertEquals(DictionaryErrorType.NETWORK_ERROR, awaitItem())
        }
        assertFalse(viewModel.isLoadingDefinition.value)
    }

    @Test
    fun lookupWord_genericException_emitsUnknownError() = runTest {
        coEvery { dictionaryRepository.lookupOffline("boom") } returns null
        coEvery { dictionaryRepository.lookupOnline("boom") } throws RuntimeException("boom")

        viewModel.definitionError.test {
            viewModel.lookupWord("boom")
            assertEquals(DictionaryErrorType.UNKNOWN_ERROR, awaitItem())
        }
        assertFalse(viewModel.isLoadingDefinition.value)
    }

    @Test
    fun beginWordSelection_holdsWithoutLookupUntilRelease() = runTest {
        viewModel.beginWordSelection("Alpha")

        assertEquals("alpha", viewModel.selectedWord.value)
        assertNull(viewModel.wordDefinition.value)
        coVerify(exactly = 0) { dictionaryRepository.lookupOffline(any()) }
        verify(exactly = 0) { ttsPlayer.speakWord(any(), any(), any(), any()) }
    }

    @Test
    fun lookupWord_afterHold_queriesAndPublishesDefinition() = runTest {
        coEvery { dictionaryRepository.lookupOffline("alpha") } returns
            offlineHit(word = "alpha", chinese = "n. 字母")

        viewModel.beginWordSelection("Alpha")
        viewModel.lookupWord("Alpha")
        advanceUntilIdle()

        coVerify(exactly = 1) { dictionaryRepository.lookupOffline("alpha") }
        assertEquals("alpha", viewModel.selectedWord.value)
        assertEquals("alpha", viewModel.wordDefinition.value?.word)
    }

    @Test
    fun beginWordSelection_secondWordInvalidatesLateFirstLookup() = runTest {
        val firstLookup = CompletableDeferred<OfflineLookupResult?>()
        coEvery { dictionaryRepository.lookupOffline("first") } coAnswers { firstLookup.await() }
        coEvery { dictionaryRepository.lookupOffline("second") } returns
            offlineHit(word = "second", chinese = "n. 第二")

        viewModel.beginWordSelection("first")
        viewModel.lookupWord("first")
        runCurrent()
        viewModel.beginWordSelection("second")
        viewModel.lookupWord("second")
        advanceUntilIdle()

        firstLookup.complete(offlineHit(word = "first", chinese = "n. 第一"))
        advanceUntilIdle()

        assertEquals("second", viewModel.selectedWord.value)
        assertEquals("second", viewModel.wordDefinition.value?.word)
    }

    @Test
    fun lookupWord_errorBeforeCollectorStarts_isDeliveredToLaterCollector() = runTest {
        coEvery { dictionaryRepository.lookupOffline("xyzzy") } returns null
        coEvery { dictionaryRepository.lookupOnline("xyzzy") } returns emptyList()

        viewModel.lookupWord("xyzzy")
        advanceUntilIdle()

        val event = withTimeoutOrNull(1_000) { viewModel.definitionError.first() }
        assertEquals(DictionaryErrorType.WORD_NOT_FOUND, event)
    }

    @Test
    fun lookupWord_loadsVocabularyWordsAsLowercaseSet() = runTest {
        // 歧义词卡片需按原形逐个判定收藏态，故查词后应加载该文章的已收藏词集合（统一小写）。
        coEvery { articleRepository.getArticleById(any()) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(id = 7, title = "t", content = "c")
        coEvery { vocabularyRepository.getVocabularyByArticle(7) } returns flowOf(
            listOf(
                io.github.zoot.englishreader.data.entity.VocabularyEntity(word = "Life", articleId = 7),
                io.github.zoot.englishreader.data.entity.VocabularyEntity(word = "run", articleId = 7)
            )
        )
        coEvery { dictionaryRepository.lookupOffline("live") } returns
            offlineHit(word = "live", chinese = "v. 生活")

        viewModel.loadArticle(7)
        viewModel.lookupWord("live")

        // 大小写归一：Life → life，供卡片 contains 判定各原形收藏态
        assertEquals(setOf("life", "run"), viewModel.vocabularyWords.value)
    }

    @Test
    fun saveVocabulary_refreshesVocabularyWordsAfterInsert() = runTest {
        coEvery { articleRepository.getArticleById(any()) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(id = 3, title = "t", content = "c")
        // 保存成功后 saveVocabulary 会 refreshVocabularyWords()，此时查库应含刚存入的 life
        coEvery { vocabularyRepository.getVocabularyByArticle(3) } returns
            flowOf(listOf(io.github.zoot.englishreader.data.entity.VocabularyEntity(word = "life", articleId = 3)))
        coEvery { vocabularyRepository.insertVocabulary(any()) } returns VocabularyInsertResult.Inserted(1L)

        viewModel.loadArticle(3)

        viewModel.vocabularySaved.test {
            viewModel.saveVocabulary("life")

            assertEquals(VocabularySaveResult.SAVED, awaitItem())
            // 保存后集合刷新，卡片据此把 life 的"添加"按钮切为"已收藏"
            assertTrue(viewModel.vocabularyWords.value.contains("life"))
            coVerify { vocabularyRepository.insertVocabulary(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveVocabulary_duplicate_reportsAlreadyExistsWithoutRefreshing() = runTest {
        coEvery { articleRepository.getArticleById(any()) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(id = 4, title = "t", content = "c")
        coEvery { vocabularyRepository.insertVocabulary(any()) } returns VocabularyInsertResult.AlreadyExists

        viewModel.loadArticle(4)

        viewModel.vocabularySaved.test {
            viewModel.saveVocabulary("life")

            assertEquals(VocabularySaveResult.ALREADY_EXISTS, awaitItem())
            coVerify(exactly = 0) { vocabularyRepository.getVocabularyByArticle(4) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveVocabulary_failure_reportsFailed() = runTest {
        coEvery { articleRepository.getArticleById(any()) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(id = 5, title = "t", content = "c")
        coEvery { vocabularyRepository.insertVocabulary(any()) } throws IllegalStateException("db")

        viewModel.loadArticle(5)

        viewModel.vocabularySaved.test {
            viewModel.saveVocabulary("life")

            assertEquals(VocabularySaveResult.FAILED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveVocabulary_resultBeforeCollectorStarts_isDeliveredToLaterCollector() = runTest {
        coEvery { articleRepository.getArticleById(any()) } returns
            io.github.zoot.englishreader.data.entity.ArticleEntity(id = 6, title = "t", content = "c")
        coEvery { vocabularyRepository.getVocabularyByArticle(6) } returns flowOf(emptyList())
        coEvery { vocabularyRepository.insertVocabulary(any()) } returns
            VocabularyInsertResult.Inserted(1L)

        viewModel.loadArticle(6)
        advanceUntilIdle()
        viewModel.saveVocabulary("life")
        advanceUntilIdle()

        val event = withTimeoutOrNull(1_000) { viewModel.vocabularySaved.first() }
        assertEquals(VocabularySaveResult.SAVED, event)
    }


    // ---- 章节内阅读位置恢复（整本书导入 Phase 2）----

    @Test
    fun vocabularyRefresh_previousArticleCompletesLate_doesNotReplaceCurrentWords() = runTest {
        val oldWords = CompletableDeferred<Unit>()
        coEvery { articleRepository.getArticleById(1) } returns ArticleEntity(1, "old", "Old.")
        coEvery { articleRepository.getArticleById(2) } returns ArticleEntity(2, "new", "New.")
        coEvery { dictionaryRepository.lookupOffline("oldword") } returns offlineHit(word = "oldword", chinese = "old")
        coEvery { dictionaryRepository.lookupOffline("newword") } returns offlineHit(word = "newword", chinese = "new")
        coEvery { vocabularyRepository.getVocabularyByArticle(1) } returns flow {
            oldWords.await()
            emit(listOf(io.github.zoot.englishreader.data.entity.VocabularyEntity(word = "oldword", articleId = 1)))
        }
        coEvery { vocabularyRepository.getVocabularyByArticle(2) } returns flowOf(
            listOf(io.github.zoot.englishreader.data.entity.VocabularyEntity(word = "newword", articleId = 2))
        )
        viewModel.loadArticle(1)
        viewModel.lookupWord("oldword")
        runCurrent()
        viewModel.loadArticle(2)
        viewModel.lookupWord("newword")
        runCurrent()
        assertEquals(setOf("newword"), viewModel.vocabularyWords.value)
        oldWords.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("newword"), viewModel.vocabularyWords.value)
    }

    @Test
    fun openReadingSession_afterChapterSwitch_doesNotReloadOriginalRoute() = runTest {
        coEvery { articleRepository.getArticleById(10) } returns ArticleEntity(10, "one", "Chapter one.")
        coEvery { articleRepository.getArticleById(11) } returns ArticleEntity(11, "two", "Chapter two.")
        viewModel.openReadingSession(10)
        advanceUntilIdle()
        viewModel.loadArticle(11, ReadingEntry.START)
        advanceUntilIdle()
        val target = viewModel.pendingPositionTarget.value

        viewModel.openReadingSession(10)
        advanceUntilIdle()

        assertEquals(11L, viewModel.article.value?.id)
        assertEquals(target, viewModel.pendingPositionTarget.value)
    }

    @Test
    fun openReadingSession_newViewModel_restoresCurrentChapterFromSavedState() = runTest {
        coEvery { articleRepository.getArticleById(10) } returns ArticleEntity(10, "one", "Chapter one.")
        coEvery { articleRepository.getArticleById(11) } returns ArticleEntity(11, "two", "Chapter two.")
        val position = ReadingPosition(11, ReadingAnchor(characterOffset = 2))
        coEvery { articleRepository.getReadingPosition(11) } returns position
        viewModel.openReadingSession(10)
        viewModel.loadArticle(11)
        advanceUntilIdle()
        val restoredHandle = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
        val recreated = createViewModel(restoredHandle)

        recreated.openReadingSession(10)
        advanceUntilIdle()

        assertEquals(11L, recreated.article.value?.id)
        assertEquals(position, recreated.pendingPositionTarget.value?.position)
    }

    private fun stubChapter(
        articleId: Long,
        bookId: Long = 7L,
        chapterIndex: Int = 2
    ) {
        coEvery { articleRepository.getArticleById(articleId) } returns
            ArticleEntity(articleId, "ch", "Body text.")
        coEvery { bookRepository.findChapterByArticleId(articleId) } returns
            BookChapterEntity(
                id = 1L,
                bookId = bookId,
                articleId = articleId,
                chapterIndex = chapterIndex,
                sourceHref = "c.xhtml",
                navigationTitle = "Chapter"
            )
        // chapters 必须包含本次要加载的 articleId。ReadingViewModel 会校验
        // `chapters.indexOfFirst { it.articleId == articleId } >= 0`，找不到就直接放弃——
        // 这是真正的护卫（重新导入会重发 articleId，旧 id 可能仍存在于 articles），
        // 所以 stub 要把 articleId 放到 chapterIndex 对应的位置上，而不是固定 100..104。
        coEvery { bookRepository.getChaptersOnce(bookId) } returns (0..4).map { i ->
            BookChapterEntity(
                id = i + 1L,
                bookId = bookId,
                articleId = if (i == chapterIndex) articleId else 900L + i,
                chapterIndex = i,
                sourceHref = "c$i.xhtml",
                navigationTitle = "C$i"
            )
        }
    }

    @Test
    fun loadArticle_articleAndChapter_restoreOwnCharacterAnchors() = runTest {
        for ((id, chapter) in listOf(60L to false, 102L to true)) {
            if (chapter) stubChapter(id)
            else coEvery { articleRepository.getArticleById(id) } returns ArticleEntity(id, "t", "Body.")
            val position = ReadingPosition(id, ReadingAnchor(paragraphIndex = 5, characterOffset = 4))
            coEvery { articleRepository.getReadingPosition(id) } returns position

            viewModel.loadArticle(id)
            advanceUntilIdle()

            assertEquals(position, viewModel.pendingPositionTarget.value?.position)
            assertEquals(ReadingEntry.RESUME, viewModel.pendingPositionTarget.value?.entry)
        }
    }

    @Test
    fun loadArticle_pendingRestore_rejectsZeroAndWrongArticleUntilConsumed() = runTest {
        stubChapter(107)
        val stored = ReadingPosition(107, ReadingAnchor(paragraphIndex = 9, characterOffset = 15))
        val gate = CompletableDeferred<ReadingPosition?>()
        val saves = mutableListOf<ReadingPosition>()
        coEvery { articleRepository.getReadingPosition(107) } coAnswers { gate.await() }
        coEvery { articleRepository.saveReadingPosition(any(), "Body text.", any()) } coAnswers { saves += firstArg<ReadingPosition>() }
        var targetAtContext: ReadingPositionTarget? = null
        val watcher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.chapterContext.collect { if (it != null) targetAtContext = viewModel.pendingPositionTarget.value }
        }

        viewModel.loadArticle(107)
        viewModel.saveReadingPosition(ReadingPosition(107, ReadingAnchor()))
        assertTrue(saves.isEmpty())
        gate.complete(stored)
        advanceUntilIdle()
        assertEquals(stored, targetAtContext?.position)
        viewModel.saveReadingPosition(ReadingPosition(107, ReadingAnchor()))
        assertTrue(saves.isEmpty())

        viewModel.consumePositionTarget(requireNotNull(viewModel.pendingPositionTarget.value))
        viewModel.saveReadingPosition(ReadingPosition(999, ReadingAnchor()))
        viewModel.saveReadingPosition(stored)
        advanceUntilIdle()
        assertEquals(listOf(stored), saves)
        watcher.cancel()
    }

    @Test
    fun loadArticle_newArticle_startsAtBeginningWithExplicitRestoreGate() = runTest {
        coEvery { articleRepository.getArticleById(61) } returns ArticleEntity(61, "t", "Body.")
        viewModel.loadArticle(61)
        advanceUntilIdle()

        val target = requireNotNull(viewModel.pendingPositionTarget.value)
        assertEquals(61L, target.position.articleId)
        assertEquals(ReadingEntry.START, target.entry)
    }

    @Test
    fun loadArticle_directionalEntry_overridesStoredPosition() = runTest {
        stubChapter(103)
        coEvery { articleRepository.getReadingPosition(103) } returns ReadingPosition(103, ReadingAnchor(5))
        for (entry in listOf(ReadingEntry.START, ReadingEntry.END)) {
            viewModel.loadArticle(103, entry)
            advanceUntilIdle()
            assertEquals(entry, viewModel.pendingPositionTarget.value?.entry)
        }
    }

    @Test
    fun consumePositionTarget_oldGeneration_doesNotClearNewTargetForSameArticle() = runTest {
        stubChapter(104)
        viewModel.loadArticle(104, ReadingEntry.START)
        advanceUntilIdle()
        val oldTarget = requireNotNull(viewModel.pendingPositionTarget.value)
        viewModel.loadArticle(104, ReadingEntry.END)
        advanceUntilIdle()
        val latest = requireNotNull(viewModel.pendingPositionTarget.value)

        viewModel.consumePositionTarget(oldTarget)
        assertEquals(latest, viewModel.pendingPositionTarget.value)
        viewModel.consumePositionTarget(latest)
        assertNull(viewModel.pendingPositionTarget.value)
    }

    @Test
    fun loadArticle_stalePositionCompletion_doesNotReplaceNewArticleOrTarget() = runTest {
        coEvery { articleRepository.getArticleById(70) } returns ArticleEntity(70, "old", "Old.")
        coEvery { articleRepository.getArticleById(71) } returns ArticleEntity(71, "new", "New.")
        val gate = CompletableDeferred<ReadingPosition?>()
        coEvery { articleRepository.getReadingPosition(70) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val latestPosition = ReadingPosition(71, ReadingAnchor(characterOffset = 2))
        coEvery { articleRepository.getReadingPosition(71) } returns latestPosition

        viewModel.loadArticle(70)
        viewModel.loadArticle(71)
        runCurrent()
        gate.complete(ReadingPosition(70, ReadingAnchor(8)))
        advanceUntilIdle()

        assertEquals(71L, viewModel.article.value?.id)
        assertEquals(latestPosition, viewModel.pendingPositionTarget.value?.position)
        assertFalse(viewModel.isLoadingArticle.value)
    }

    @Test
    fun loadArticle_failure_keepsCurrentChapterAndAllowsItsSelection() = runTest {
        stubChapter(105)
        viewModel.loadArticle(105)
        advanceUntilIdle()
        viewModel.consumePositionTarget(requireNotNull(viewModel.pendingPositionTarget.value))
        val oldContext = viewModel.chapterContext.value
        coEvery { articleRepository.getArticleById(106) } throws IOException("unavailable")

        viewModel.readingErrors.test {
            viewModel.loadArticle(106, ReadingEntry.START)
            assertEquals(ReadingError.LOAD, awaitItem())
            assertEquals(105L, viewModel.article.value?.id)
            assertEquals(oldContext, viewModel.chapterContext.value)
            assertFalse(viewModel.isLoadingArticle.value)
            viewModel.selectSentence(105, 0, SentenceRange(0, "Body text.", 0, 10))
            assertEquals(105L, viewModel.selectedSentence.value?.articleId)
        }
    }

    // ---- 生词本「查看原文」：跳到该词所在段落并点亮 ----

    @Test
    fun openReadingSession_withWord_scrollsToAndHighlightsItsParagraph() = runTest {
        val fixture = ReadingViewModelFixture()
        coEvery { fixture.articleRepository.getArticleById(7L) } returns ArticleEntity(
            id = 7L,
            title = "The Art of Noticing",
            content = "Opening line.\n\nA second paragraph.\n\nSmall details reward noticing."
        )
        val viewModel = fixture.create(SavedStateHandle(mapOf("word" to "noticing")))

        viewModel.openReadingSession(7L)

        val anchor = requireNotNull(viewModel.pendingPositionTarget.value).position.anchor
        assertEquals("noticing 在第三段", 2, anchor.paragraphIndex)
        assertEquals(ReadingTextKind.ORIGINAL, anchor.textKind)
        assertEquals(
            "Start of the match within the paragraph",
            "Small details reward noticing.".indexOf("noticing"),
            anchor.characterOffset
        )
        assertEquals(2, viewModel.highlightedParagraph.value)
    }

    @Test
    fun openReadingSession_wordAppearsInMultipleParagraphs_usesTheFirst() = runTest {
        val fixture = ReadingViewModelFixture()
        coEvery { fixture.articleRepository.getArticleById(7L) } returns ArticleEntity(
            id = 7L,
            title = "Repeat",
            content = "noticing here.\n\nAnd noticing again."
        )
        val viewModel = fixture.create(SavedStateHandle(mapOf("word" to "noticing")))

        viewModel.openReadingSession(7L)

        assertEquals(0, viewModel.pendingPositionTarget.value?.position?.anchor?.paragraphIndex)
    }

    @Test
    fun openReadingSession_wordNotFound_keepsSavedPositionAndHighlightsNothing() = runTest {
        val fixture = ReadingViewModelFixture()
        val saved = ReadingPosition(
            articleId = 7L,
            anchor = ReadingAnchor(paragraphIndex = 1, characterOffset = 4)
        )
        coEvery { fixture.articleRepository.getArticleById(7L) } returns ArticleEntity(
            id = 7L,
            title = "Unrelated",
            content = "First paragraph.\n\nSecond paragraph."
        )
        coEvery { fixture.articleRepository.getReadingPosition(7L) } returns saved
        val viewModel = fixture.create(SavedStateHandle(mapOf("word" to "absent")))

        viewModel.openReadingSession(7L)

        // 词找不到不该让跳转失败，退回正常的阅读位置恢复，且什么都不点亮。
        assertEquals(saved, viewModel.pendingPositionTarget.value?.position)
        assertNull(viewModel.highlightedParagraph.value)
    }

    @Test
    fun openReadingSession_withoutWord_highlightsNothing() = runTest {
        val fixture = ReadingViewModelFixture()
        coEvery { fixture.articleRepository.getArticleById(7L) } returns ArticleEntity(
            id = 7L,
            title = "Plain",
            content = "First paragraph.\n\nSecond paragraph."
        )
        val viewModel = fixture.create()

        viewModel.openReadingSession(7L)

        assertNull(viewModel.highlightedParagraph.value)
    }
}
