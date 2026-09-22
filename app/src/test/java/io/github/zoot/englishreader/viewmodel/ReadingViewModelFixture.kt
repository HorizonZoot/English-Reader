package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import io.github.zoot.englishreader.data.audio.PronunciationAudioCache
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.data.repository.WholeTranslationRepository
import io.github.zoot.englishreader.model.TtsReadingSettings
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.TtsPlayer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.github.zoot.englishreader.model.ReadingArticleState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * [ReadingViewModel] 的共享测试装置。
 *
 * 只承担两件事：**构造 ViewModel** 和**提供两个测试文件确实共享的默认打桩**。
 * 每个 collaborator 都是构造参数，调用方可以传入自己预先打桩的 mock；未传入的部分
 * 用 relaxed mock 加稳定默认值填充。
 *
 * 刻意不做的事：不提供任何带业务语义的便捷方法（`fixtureThatRejects()` 之类），
 * 不封装 AI / TTS / 查词的专属行为。那些属于各测试文件自己的 Given，藏进装置里
 * 会让用例读不出前提。
 *
 * 参数数量与 [ReadingViewModel] 的依赖数量一致——装置无法比它构造的对象更简单。
 * 依赖集中本身是生产端的问题，已记录为 Architecture Follow-up，不在测试层解决。
 */
internal class ReadingViewModelFixture(
    val articleRepository: ArticleRepository = mockk(relaxed = true),
    val vocabularyRepository: VocabularyRepository = mockk(relaxed = true),
    val dictionaryRepository: DictionaryRepository = mockk(relaxed = true),
    val audioPlayer: AudioPlayer = mockk(relaxed = true),
    val networkChecker: NetworkChecker = mockk(relaxed = true),
    val ttsPlayer: TtsPlayer = mockk(relaxed = true),
    val aiExplanationRepository: AiExplanationRepository = mockk(relaxed = true),
    // 协调器在构造期不调用 registry，故 relaxed mock 即可，无需真实应用作用域
    val aiOperationRegistry: AiExplanationOperationRegistry = mockk(relaxed = true),
    val settingsPreferences: SettingsPreferences = mockk(relaxed = true),
    val bookRepository: BookRepository = mockk(relaxed = true),
    val pronunciationAudioCache: PronunciationAudioCache = mockk(relaxed = true),
    val wholeTranslationRepository: WholeTranslationRepository = mockk(relaxed = true),
    // 偏好流按 Flow 传入，而不是在装置内部固定成某种流类型：
    // `flowOf` 发射后即完成，`MutableStateFlow` 保持打开且可在用例中推进。
    // 需要驱动偏好变化的用例传入自己的 MutableStateFlow，其余用冷流默认值。
    fontSizeOption: Flow<FontSizeOption> = flowOf(FontSizeOption.DEFAULT),
    themeOption: Flow<ThemeOption> = flowOf(ThemeOption.DEFAULT),
    readingMode: Flow<ReadingMode> = flowOf(ReadingMode.DEFAULT),
    allowNetworkTts: Flow<Boolean> = flowOf(false),
    ttsReadingSettings: Flow<TtsReadingSettings> = flowOf(TtsReadingSettings())
) {

    init {
        // 默认未命中发音缓存：多数用例验的是首次查词走远端的路径。
        //
        // **必须显式打桩返回 null。** relaxed mock 对返回 `File?` 的 suspend fun
        // 并不给 null —— 它会造一个 relaxed `File` mock，其 `absolutePath` 是空串。
        // 于是生产代码走进「缓存命中」分支、拿空路径去播，自动播放用例会因此变红，
        // 报的是 `argument: ` 对不上期望的 URL。那种红是**桩写错了**，不是生产代码错了。
        //
        // 这条防护原先只写在 ReadingViewModelTest 里，ReadingTtsViewModelTest 有同样的
        // 裸打桩但没有说明。放在这里让两个文件共用同一份解释。
        //
        // 移除这一行会让 4 条用例变红（saveVocabulary_failure_reportsFailed、
        // userPreferenceChanges_persistThroughSharedSettingsPreferences、
        // beginWordSelection_registeredExplanation_detachesObserverAndKeepsPaidOperation、
        // playWordAudio_offlineWithCacheHit_playsLocalFileNotTts），2026-09-12 实测确认。
        coEvery { pronunciationAudioCache.get(any()) } returns null
        // checkIfWordInVocabulary 会收集该 flow
        coEvery { vocabularyRepository.getVocabularyByArticle(any()) } returns flowOf(emptyList())
        // ReadingViewModel 用 stateIn 收集这些偏好，需要确定的初始值
        every { settingsPreferences.fontSizeOption } returns fontSizeOption
        every { settingsPreferences.themeOption } returns themeOption
        every { settingsPreferences.readingMode } returns readingMode
        every { settingsPreferences.allowNetworkTts } returns allowNetworkTts
        every { settingsPreferences.ttsReadingSettings } returns ttsReadingSettings
        coEvery { articleRepository.getReadingPosition(any()) } returns null
        every { articleRepository.observeArticle(any()) } returns emptyFlow()
        coEvery { articleRepository.getReadingArticle(any()) } coAnswers {
            articleRepository.getArticleById(firstArg())?.let(::ReadingArticleState)
        }
        every { articleRepository.observeReadingArticle(any()) } answers {
            articleRepository.observeArticle(firstArg()).map { it?.let(::ReadingArticleState) }
        }
        coEvery { wholeTranslationRepository.conflictFor(any()) } returns null
        // 默认有网：多数用例走真人音路径；无网用例各自覆盖
        every { networkChecker.isOnline() } returns true
        // 默认不是书章节：独立文章用例的 chapterContext 应保持 null
        coEvery { bookRepository.findChapterByArticleId(any()) } returns null
    }

    /** 用当前装置的 collaborator 构造 ViewModel；[handle] 用于验证进程死亡后的状态恢复。 */
    fun create(handle: SavedStateHandle = SavedStateHandle()) = ReadingViewModel(
        articleRepository,
        vocabularyRepository,
        dictionaryRepository,
        audioPlayer,
        networkChecker,
        ttsPlayer,
        aiExplanationRepository,
        aiOperationRegistry,
        settingsPreferences,
        bookRepository,
        pronunciationAudioCache,
        wholeTranslationRepository,
        handle
    )
}
