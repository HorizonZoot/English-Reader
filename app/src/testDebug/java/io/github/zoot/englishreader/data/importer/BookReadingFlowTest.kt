package io.github.zoot.englishreader.data.importer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.zoot.englishreader.core.SentenceRange
import androidx.room.Room
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.importer.spike.ReadiumEpubFixtures
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.data.ai.AiExplanationInput
import io.github.zoot.englishreader.data.database.EnglishReaderDatabase
import io.github.zoot.englishreader.data.entity.DictionaryEntry
import io.github.zoot.englishreader.data.entity.VocabularyEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.ReadingMode
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.data.repository.AiExplanationRepository
import io.github.zoot.englishreader.data.repository.ArticleRepository
import io.github.zoot.englishreader.data.repository.BookRepository
import io.github.zoot.englishreader.data.repository.DictionaryRepository
import io.github.zoot.englishreader.data.repository.OfflineLookupResult
import io.github.zoot.englishreader.data.repository.VocabularyInsertResult
import io.github.zoot.englishreader.data.repository.VocabularyRepository
import io.github.zoot.englishreader.util.AudioPlayer
import io.github.zoot.englishreader.util.NetworkChecker
import io.github.zoot.englishreader.util.ParagraphAligner
import io.github.zoot.englishreader.util.SentenceSplitter
import io.github.zoot.englishreader.util.TtsPlayer
import io.github.zoot.englishreader.viewmodel.ReadingViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real chapters exercise persistence, reading, vocabulary and both AI request types.
 * The corpus books cover full EPUB2/EPUB3 import; the bundled chapter runs without the corpus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookReadingFlowTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser by lazy { EpubBookParser(context) }

    private lateinit var db: EnglishReaderDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var articleRepository: ArticleRepository
    private lateinit var vocabularyRepository: VocabularyRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var aiExplanationRepository: AiExplanationRepository
    private lateinit var viewModel: ReadingViewModel
    private val applicationScope by lazy { TestScope(mainDispatcherRule.testDispatcher) }

    @Before
    fun setUp() {
        // Room owns its executors; synchronize them so test-scheduler advancement observes DAO reads.
        db = Room.inMemoryDatabaseBuilder(context, EnglishReaderDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        bookRepository = BookRepository(db.bookDao(), db.articleDao())
        articleRepository = ArticleRepository(db.articleDao(), applicationScope.backgroundScope)

        vocabularyRepository = mockk(relaxed = true)
        dictionaryRepository = mockk(relaxed = true)
        aiExplanationRepository = mockk(relaxed = true)
        val settingsPreferences: SettingsPreferences = mockk(relaxed = true)
        every { settingsPreferences.fontSizeOption } returns flowOf(FontSizeOption.DEFAULT)
        every { settingsPreferences.themeOption } returns flowOf(ThemeOption.DEFAULT)
        every { settingsPreferences.readingMode } returns flowOf(ReadingMode.DEFAULT)
        val networkChecker: NetworkChecker = mockk(relaxed = true)
        every { networkChecker.isOnline() } returns true
        coEvery { vocabularyRepository.getVocabularyByArticle(any()) } returns flowOf(emptyList())

        viewModel = ReadingViewModel(
            articleRepository,
            vocabularyRepository,
            dictionaryRepository,
            mockk<AudioPlayer>(relaxed = true),
            networkChecker,
            mockk<TtsPlayer>(relaxed = true),
            aiExplanationRepository,
            mockk<AiExplanationOperationRegistry>(relaxed = true),
            settingsPreferences,
            mockk(relaxed = true),
            // pronunciationAudioCache。这里刻意保持裸 relaxed mock，因为本测试只走
            // 导入 / 阅读 / 查词 / AI 路径，从不触发发音播放。
            //
            // 若将来给本测试加发音或音频覆盖，**必须显式打桩 `get(any()) returns null`**：
            // relaxed mock 对返回 `File?` 的 suspend fun 不给 null，而是造一个 relaxed
            // `File` mock，其 `absolutePath` 是空串。生产代码会据此走进「缓存命中」分支、
            // 拿空路径去播，症状是断言里的 URL 对不上而非明显的桩错误。
            // 同样的坑已在 ReadingViewModelFixture 付过一次学费。
            mockk(relaxed = true),
            // wholeTranslationRepository。本测试不走全文翻译路径；relaxed mock 即可，
            // 其 findResumable 返回 null，openWholeTranslation 也不会被调用。
            mockk(relaxed = true),
            SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        applicationScope.backgroundScope.cancel()
        db.close()
    }

    @Test
    fun bundledChapter_supportsReadingWithoutOptionalCorpus() = runTest {
        val bytes = requireNotNull(
            javaClass.getResourceAsStream("/readium/public/gutenberg-78457-epub3.epub")
        ) { "real book fixture missing" }.use { it.readBytes() }
        val file = ReadiumEpubFixtures.writeTo(temporaryFolder.newFolder(), "book.epub", bytes)
        val chapter = chaptersUnderBudget(file).first()
        assertTrue("chapter content is blank", chapter.content.isNotBlank())
        assertTrue("chapter title is blank", chapter.title.isNotBlank())

        val articleId = db.articleDao().insertArticle(
            ArticleEntity(
                id = 4242L,
                title = chapter.title,
                content = chapter.content,
                source = "book_chapter",
                createdAt = 1L
            )
        )
        exerciseReading("bundled chapter", articleId, minSentences = 1, minSentenceChars = 12, minWordChars = 4)
    }

    /** 爱丽丝梦游仙境：EPUB 2，Gutenberg 打包，13 章。 */
    @Test
    fun aliceInWonderland_importsAndSupportsFullReadingFlow() = runTest {
        runFlow(book = "gutenberg-11", expectedFormat = BookFormat.EPUB2)
    }

    /** 双城记：EPUB 3，Standard Ebooks 打包，54 章 —— 一章一文件。 */
    @Test
    fun aTaleOfTwoCities_importsAndSupportsFullReadingFlow() = runTest {
        runFlow(book = "se-a-tale-of-two-cities", expectedFormat = BookFormat.EPUB3)
    }

    /**
     * 一本书的完整路径。
     *
     * 逐步断言而不是只看最终结果：每一步的失败都指向一个具体的跨层断点，而合并成一条断言就只能
     * 知道「某处坏了」。
     */
    private suspend fun TestScope.runFlow(book: String, expectedFormat: BookFormat) {
        val file = corpusBook(book)
        assumeTrue(
            "$book absent; run: python tools/epub-corpus/fetch_corpus.py",
            file != null
        )
        requireNotNull(file)

        // --- 1. 导入：真实 EpubBookParser，全闸门 ---
        val imported = parser.parse(file)

        assertEquals("$book: sourceFormat", expectedFormat, imported.metadata.sourceFormat)
        assertTrue("$book: no chapters", imported.chapters.isNotEmpty())
        assertEquals(
            "$book: chapterIndex must be dense and ordered",
            imported.chapters.indices.toList(),
            imported.chapters.map { it.chapterIndex }
        )
        assertTrue("$book: blank fingerprint", imported.metadata.contentFingerprint.isNotBlank())
        imported.chapters.forEach { chapter ->
            assertTrue(
                "$book ch${chapter.chapterIndex}: exceeds MAX_CHAPTER_CHARS",
                chapter.content.length <= ImportBudget.MAX_CHAPTER_CHARS
            )
        }

        // 取正文最长的章节：短资源多是封面、目录、许可证页，分词在那些上的表现
        // 不代表小说正文。
        val chapter = imported.chapters.maxBy { it.content.length }
        assertTrue(
            "$book: longest chapter is only ${chapter.content.length} chars; " +
                "that is front matter, not prose",
            chapter.content.length > MIN_PROSE_CHARS
        )

        // --- 2. 真落库：BookRepository.persist 走真 Room 单事务 ---
        val bookId = bookRepository.persist(imported)
        assertTrue("$book: persist returned no bookId", bookId > 0)

        // 从库里读回章节关系。articleId 是 DAO 在事务内回填的，不是我们编的。
        val relations = bookRepository.getChaptersOnce(bookId)
        assertEquals(
            "$book: persisted chapter count must match the parse result",
            imported.chapters.size,
            relations.size
        )
        assertEquals(
            "$book: chapterIndex must survive persistence dense and ordered",
            imported.chapters.indices.toList(),
            relations.map { it.chapterIndex }
        )

        // 取正文最长那一章对应的关系行。
        val targetIndex = imported.chapters.indexOf(chapter)
        val relation = relations.first { it.chapterIndex == targetIndex }
        val articleId = relation.articleId
        assertTrue("$book: articleId was not back-filled by the DAO", articleId > 0)

        // --- 3. 真读回：ArticleRepository 从同一个库取，不是 mock ---
        val persisted = requireNotNull(articleRepository.getArticleById(articleId)) {
            "$book: chapter article $articleId missing after persist"
        }
        assertEquals(
            "$book: content changed across persist/read-back",
            chapter.content,
            persisted.content
        )

        exerciseReading(book, articleId, MIN_SENTENCES, MIN_TARGET_SENTENCE_CHARS, minWordChars = 5)
    }

    private suspend fun TestScope.exerciseReading(
        book: String,
        articleId: Long,
        minSentences: Int,
        minSentenceChars: Int,
        minWordChars: Int
    ) {
        viewModel.loadArticle(articleId)
        advanceUntilIdle()
        val loaded = requireNotNull(viewModel.article.value) { "$book: chapter did not load" }
        assertEquals("$book: wrong article loaded", articleId, loaded.id)

        // --- 4. 分词：生产 ParagraphAligner + 真实 ICU ---
        val paragraphs = ParagraphAligner.align(
            content = loaded.content,
            translation = loaded.translation,
            sentenceSplitter = SentenceSplitter::split
        )
        assertTrue("$book: no paragraphs", paragraphs.isNotEmpty())
        val sentences = paragraphs.flatMap { it.sentences }
        assertTrue("$book: fewer than $minSentences sentences", sentences.size >= minSentences)

        // 偏移不变量，两个层级都要查。
        //
        // 段内 startOffset 错 → 点第 N 句高亮到第 N+1 句。
        // 段落 sentenceOffset 错 → `InteractiveText` 用 `index + sentenceOffset` 作跨段落的
        // 句子身份，错 1 会让不同段落的全局索引撞车或空缺，高亮跳到另一段去。
        // 后者是实测补上的：只查段内偏移时，把 `sentenceOffset` 改成 `offset + 1` 本类照绿。
        var cumulativeSentences = 0
        paragraphs.forEach { paragraph ->
            assertEquals(
                "$book: paragraph sentenceOffset must equal the number of sentences before it",
                cumulativeSentences,
                paragraph.sentenceOffset
            )
            cumulativeSentences += paragraph.sentences.size

            paragraph.sentences.forEachIndexed { position, sentence ->
                assertEquals("$book: sentence index not sequential", position, sentence.index)
                assertTrue(
                    "$book: sentence text not at declared offset ${sentence.startOffset}",
                    paragraph.english.startsWith(sentence.text, sentence.startOffset)
                )
            }
        }

        // 双语对导入的书不可用：persist 从不写 translation。钉住这个已知状态。
        assertTrue(
            "$book: translation unexpectedly populated; if book import now writes it, " +
                "update ADR-012 and the bilingual claims in context.md",
            paragraphs.all { it.chinese == null }
        )

        // --- 5. 查词 ---
        val word = firstProseWord(sentences, minWordChars)
        coEvery { dictionaryRepository.lookupOffline(word) } returns OfflineLookupResult(
            entry = DictionaryEntry(
                word = word,
                phonetic = "/stub/",
                chinese = "桩释义一；桩释义二",
                english = "stub gloss"
            )
        )

        viewModel.lookupWord(word)
        advanceUntilIdle()

        val definition = requireNotNull(viewModel.wordDefinition.value) {
            "$book: lookup of '$word' produced no definition"
        }
        assertEquals("$book: definition word", word, definition.word)
        assertEquals(
            "$book: Chinese gloss must be split into two entries",
            2,
            definition.chineseDefinitions.size
        )

        // --- 6. 存生词：绑到库里回填的 articleId ---
        coEvery { vocabularyRepository.insertVocabulary(any()) } returns VocabularyInsertResult.Inserted(1L)
        val saved = slot<VocabularyEntity>()

        viewModel.saveVocabulary(word)
        advanceUntilIdle()

        coVerify { vocabularyRepository.insertVocabulary(capture(saved)) }
        assertEquals("$book: vocabulary must bind to the chapter", articleId, saved.captured.articleId)
        assertEquals("$book: wrong word saved", word, saved.captured.word)

        // --- 7. 选句 ---
        val target = sentences.first { it.text.trim().length > minSentenceChars }
        val owningParagraph = paragraphs.first { target in it.sentences }
        val globalIndex = owningParagraph.sentenceOffset + target.index

        viewModel.selectSentence(articleId, globalIndex, target)
        advanceUntilIdle()

        val selected = requireNotNull(viewModel.selectedSentence.value) {
            "$book: selectSentence rejected; its guard requires loadArticle to have " +
                "completed for the same id"
        }
        assertEquals("$book: selection bound to wrong article", articleId, selected.articleId)
        assertTrue("$book: normalized text blank", selected.normalizedText.isNotBlank())

        // --- 8. 逐句解释与逐句翻译：同一快照，必须走不同的输入类型 ---
        val aiInputs = mutableListOf<AiExplanationInput>()

        viewModel.explainSelectedSentence()
        advanceUntilIdle()
        viewModel.translateSelectedSentence()
        advanceUntilIdle()

        coVerify(exactly = 2) { aiExplanationRepository.start(capture(aiInputs)) }
        assertEquals("$book: expected one explain and one translate", 2, aiInputs.size)
        assertTrue(
            "$book: explanation must use AiExplanationInput.Sentence, got ${aiInputs[0]::class.simpleName}",
            aiInputs[0] is AiExplanationInput.Sentence
        )
        assertTrue(
            "$book: translation must use SentenceTranslation, got ${aiInputs[1]::class.simpleName}; " +
                "sharing a type would make the two share cache entries",
            aiInputs[1] is AiExplanationInput.SentenceTranslation
        )
        assertEquals(
            "$book: both requests must carry the selected sentence's normalized text",
            selected.normalizedText,
            aiInputs[1].text
        )

        assertEquals("$book: explanation and translation source text", aiInputs[0].text, aiInputs[1].text)
    }

    // --- helpers ---

    private fun corpusBook(name: String): File? =
        File(System.getProperty("user.dir"), "../build/epub-corpus/$name.epub").takeIf { it.isFile }

    // Keep contractions intact, then reject them instead of querying fragments such as couldn.
    private fun firstProseWord(sentences: List<SentenceRange>, minLength: Int): String =
        sentences.asSequence()
            .flatMap { it.text.split(Regex("[^A-Za-z']+")).asSequence() }
            .map { it.trim('\'').lowercase() }
            .first { it.length >= minLength && it.all { ch -> ch in 'a'..'z' } }

    // This bundled book is rejected as a whole; its existing chapter sample remains independently readable.
    private fun chaptersUnderBudget(file: File): List<ImportedChapter> {
        val texts = mutableListOf<ImportedChapter>()
        java.util.zip.ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
                .filter { it.name.endsWith(".xhtml") || it.name.endsWith(".html") }
                .sortedBy { it.name }
            entries.forEach { entry ->
                val xhtml = zip.getInputStream(entry).use { XmlBytesDecoder.decode(it.readBytes()) }
                val content = runCatching { XhtmlTextExtractor.extract(xhtml) }.getOrNull() ?: return@forEach
                if (content.isBlank() || content.length > ImportBudget.MAX_CHAPTER_CHARS) return@forEach
                texts += ImportedChapter(
                    chapterIndex = texts.size,
                    title = "Chapter ${texts.size + 1}",
                    sourceHref = entry.name,
                    navigationTitle = null,
                    content = content
                )
            }
        }
        require(texts.isNotEmpty()) {
            "no resource in the real book fits MAX_CHAPTER_CHARS; this test needs a readable chapter"
        }
        return texts
    }


    private companion object {
        /** 低于此长度的「最长章节」说明取到的是前置内容而非正文。 */
        const val MIN_PROSE_CHARS = 5_000

        /** 一章真实小说正文至少应有这么多句；低于此说明分词或提取出了问题。 */
        const val MIN_SENTENCES = 50

        /** 选作 AI 目标的句子最小长度 —— 太短的句子规范化后可能为空。 */
        const val MIN_TARGET_SENTENCE_CHARS = 20
    }
}
